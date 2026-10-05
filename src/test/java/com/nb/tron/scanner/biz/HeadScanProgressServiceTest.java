package com.nb.tron.scanner.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nb.core.exception.BizException;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.mapper.TronScannedBlockMapper;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronBlockHeaderReader;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.parser.TronBlockParser;
import com.nb.tron.scanner.service.impl.TronScanCheckpointServiceImpl;
import com.nb.tron.scanner.service.impl.TronScannedBlockServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 MyBatis Mapper、数据库事务和行锁，验证摘要与检查点的原子提交。
 * H2 使用 MySQL 模式，不代替上线前的 MySQL 和真实节点联调。
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadScanProgressServiceTest {

    private JdbcTemplate jdbc;
    private TronScannerProperties properties;
    private TronNodeManager nodeManager;
    private TronScanCheckpointServiceImpl checkpointService;
    private TronScannedBlockServiceImpl scannedBlockService;
    private TransactionSupport transactions;
    private HeadScanProgressService progressService;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE tron_scan_checkpoint (
              chain_network VARCHAR(32) PRIMARY KEY, last_block_number BIGINT NOT NULL,
              last_block_hash VARCHAR(128) NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE tron_scanned_block (
              chain_network VARCHAR(32) NOT NULL, block_number BIGINT NOT NULL,
              block_hash VARCHAR(128) NOT NULL, PRIMARY KEY (chain_network, block_number))
            """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TronScanCheckpointMapper.class);
        configuration.addMapper(TronScannedBlockMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        checkpointService = spy(new TronScanCheckpointServiceImpl());
        ReflectionTestUtils.setField(checkpointService, "baseMapper", session.getMapper(TronScanCheckpointMapper.class));
        scannedBlockService = spy(new TronScannedBlockServiceImpl());
        ReflectionTestUtils.setField(scannedBlockService, "baseMapper", session.getMapper(TronScannedBlockMapper.class));
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions = new TransactionSupport(template, template);
        properties = new TronScannerProperties();
        properties.setStartBlockHeight(100L);
        nodeManager = mock(TronNodeManager.class);
        progressService = createProgressService();
        when(nodeManager.getBlockHeaderByHeight(99)).thenReturn(height(99, "h99"));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void shouldInitializeFullNodeBoundaryAndResumeAfterRestart() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        assertThat(initial.getLastBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockHash()).isEqualTo("h99");
        progressService.advance(initial, block(100, "h100"));

        HeadScanProgressService restarted = createProgressService();
        assertThat(restarted.loadCheckpoint().getLastBlockNumber()).isEqualTo(100);
        verify(nodeManager, times(1)).getBlockHeaderByHeight(99);
    }

    @Test
    void shouldInitializeGenesisSentinelWithoutNegativeRpc() {
        properties.setStartBlockHeight(0L);
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        assertThat(initial.getLastBlockNumber()).isEqualTo(-1);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockHash()).isEmpty();
        verifyNoInteractions(nodeManager);
    }

    @Test
    void shouldLeaveDatabaseEmptyWhenInitialBlockCannotBeRead() {
        when(nodeManager.getBlockHeaderByHeight(99))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_BLOCK_NOT_FOUND));
        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(BizException.class);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET")).isNull();
    }

    @Test
    void shouldRollbackInitialCheckpointWhenSummaryInsertFails() {
        doThrow(new IllegalStateException("insert failed")).when(scannedBlockService).saveBlock(any());
        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(IllegalStateException.class);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
    }

    @Test
    void shouldNotSaveSummaryWhenInitialCheckpointIsNotInserted() {
        doReturn(false).when(checkpointService).save(any(TronScanCheckpoint.class));

        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET")).isNull();
    }

    @Test
    void shouldResumeExistingProgressOnNextRoundWhenAnotherTaskInitializesFirst() {
        // 当前任务查到没有进度后，另一个任务先初始化并扫描到 100。
        when(nodeManager.getBlockHeaderByHeight(99)).thenAnswer(invocation -> {
            TronScanCheckpoint initial = new TronScanCheckpoint()
                .setChainNetwork("MAINNET").setLastBlockNumber(99L).setLastBlockHash("h99");
            transactions.execute(() -> {
                checkpointService.save(initial);
                scannedBlockService.saveBlock(summary("MAINNET", 99, "h99"));
            });
            progressService.advance(initial, block(100, "h100"));
            return height(99, "h99");
        });

        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(DuplicateKeyException.class);
        assertThat(progressService.loadCheckpoint().getLastBlockNumber()).isEqualTo(100);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100).getBlockHash()).isEqualTo("h100");
        verify(nodeManager, times(1)).getBlockHeaderByHeight(99);
    }

    @Test
    void shouldRejectLegacyCheckpointWithoutFabricatingHistory() {
        jdbc.update("INSERT INTO tron_scan_checkpoint (chain_network,last_block_number,last_block_hash) VALUES (?,?,?)",
            "MAINNET", 10000L, "old10000");
        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
        verifyNoInteractions(nodeManager);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(10000);
    }

    @Test
    void shouldRollbackSummaryWhenCheckpointUpdateFailsThenAllowReplay() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        doReturn(false).when(checkpointService).advance(anyString(), anyLong(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> progressService.advance(initial, block(100, "h100"))).isInstanceOf(BizException.class);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNull();
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(99);

        doCallRealMethod().when(checkpointService).advance(anyString(), anyLong(), anyString(), anyLong(), anyString());
        progressService.advance(initial, block(100, "h100"));
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(100);
    }

    @Test
    void shouldNotAdvanceOnDuplicateSummaryOrOverwriteItsHash() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        scannedBlockService.saveBlock(summary("MAINNET", 100, "existing100"));

        assertThatThrownBy(() -> progressService.advance(initial, block(100, "h100")))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100).getBlockHash()).isEqualTo("existing100");
    }

    @Test
    void shouldBoundHistoryWindowAndKeepOtherNetworks() {
        properties.setBlockHistorySize(2);
        scannedBlockService.saveBlock(summary("NILE", 1, "nile1"));
        scanThrough102();

        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(101);
        assertThat(scannedBlockService.findByHeight("MAINNET", 102)).isNotNull();
        assertThat(scannedBlockService.findByHeight("NILE", 1)).isNotNull();
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(102);
    }

    @Test
    void shouldKeepCheckpointWhenAlreadyAtCommonBlock() {
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();

        progressService.rewind(checkpoint, summary("MAINNET", 99, "h99"));

        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 99).getBlockHash()).isEqualTo("h99");
        verify(checkpointService, never()).rewind(anyString(), anyLong(), anyString(), anyLong(), anyString());
        verify(scannedBlockService).removeAfter("MAINNET", 99);
    }

    @Test
    void shouldPreserveHistoryWhenCheckpointRewindFails() {
        TronScanCheckpoint current = scanThrough102();
        doReturn(false).when(checkpointService).rewind(anyString(), anyLong(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> progressService.rewind(current, summary("MAINNET", 99, "h99")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(102);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 102)).isNotNull();
        verify(scannedBlockService, never()).removeAfter(anyString(), anyLong());
    }

    @Test
    void shouldRollbackBothRewindAndDeletionOnFailure() {
        TronScanCheckpoint current = scanThrough102();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("process failed before commit");
        }).when(scannedBlockService).removeAfter("MAINNET", 99);

        assertThatThrownBy(() -> progressService.rewind(current, summary("MAINNET", 99, "h99")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(102);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 102)).isNotNull();
    }

    @Test
    void shouldRewindAndReplayReplacementBranchAfterRestart() {
        TronScanCheckpoint current = scanThrough102();
        progressService.rewind(current, summary("MAINNET", 100, "h100"));

        HeadScanProgressService restarted = createProgressService();
        TronScanCheckpoint recovered = restarted.loadCheckpoint();
        assertThat(recovered.getLastBlockNumber()).isEqualTo(100);
        assertThat(scannedBlockService.findByHeight("MAINNET", 101)).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        restarted.advance(recovered, block(101, "replacement101"));
        assertThat(scannedBlockService.findByHeight("MAINNET", 101).getBlockHash()).isEqualTo("replacement101");
    }

    @Test
    void shouldRejectRewindOutsideRetainedHistory() {
        properties.setBlockHistorySize(2);
        TronScanCheckpoint current = scanThrough102();
        assertThatThrownBy(() -> progressService.rewind(current, summary("MAINNET", 99, "h99")))
            .isInstanceOf(BizException.class);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(102);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(101);
    }

    @Test
    void shouldAllowOnlyOneConcurrentCommitFromSameCheckpoint() throws Exception {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (Callable<Boolean>) () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    progressService.advance(initial, block(100, "h100"));
                    return true;
                } catch (BizException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
                    return false;
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tron_scanned_block", Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldDiscoverReplacementDepositAfterForkAndRestart() {
        var addressIndex = mock(TronAddressIndex.class);
        var currencyIndex = mock(TronCurrencyIndex.class);
        var parser = mock(TronBlockParser.class);
        var publisher = mock(DepositDiscoveryPublisher.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);
        TronBlockHeaderReader reader = mock(TronBlockHeaderReader.class);
        when(nodeManager.openBlockHeaderReader(anyLong())).thenReturn(reader);
        when(reader.getBlockHeaderByHeight(anyLong())).thenAnswer(invocation -> {
            long height = invocation.getArgument(0);
            return height(height, "h" + height);
        });
        when(nodeManager.getHeadHeight()).thenReturn(height(102, "h102"));
        when(parser.parse(any())).thenReturn(List.of());
        for (long h = 100; h <= 102; h++) {
            when(nodeManager.getBlockDataByHeight(h)).thenReturn(block(h, "h" + h));
        }
        var oldDeposit = deposit("old-transaction", "h101");
        when(parser.parse(block(101, "h101"))).thenReturn(List.of(oldDeposit));
        var continuity = new HeadBlockContinuityService(new HeadBlockAncestorFinder(nodeManager, scannedBlockService), progressService, nodeManager);
        var scanner = new HeadBlockScanService(properties, addressIndex, currencyIndex, nodeManager,
            parser, continuity, publisher, progressService);
        assertThat(scanner.scanBlocks()).isEqualTo(3);
        verify(publisher).publishAndWait(block(101, "h101"), List.of(oldDeposit));

        // 101、102 被替换，二分查到最近共同区块 100，先回退而不发送新事件。
        when(reader.getBlockHeaderByHeight(101)).thenReturn(height(101, "new101"));
        when(reader.getBlockHeaderByHeight(102)).thenReturn(height(102, "new102"));
        assertThatThrownBy(scanner::scanBlocks).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(100);
        assertThat(scannedBlockService.findByHeight("MAINNET", 101)).isNull();

        // 重新创建业务对象模拟重启，完全依靠数据库边界重扫，发现替换分支中的充值。
        HeadScanProgressService restartedProgress = createProgressService();
        var restartedContinuity = new HeadBlockContinuityService(new HeadBlockAncestorFinder(nodeManager, scannedBlockService), restartedProgress, nodeManager);
        var restartedScanner = new HeadBlockScanService(properties, addressIndex, currencyIndex, nodeManager,
            parser, restartedContinuity, publisher, restartedProgress);
        TronBlockData new101 = block(101, "new101");
        TronBlockData new102 = new TronBlockData("full", 102, "new102", "new101", Instant.EPOCH, List.of(), Map.of());
        when(nodeManager.getBlockDataByHeight(101)).thenReturn(new101);
        when(nodeManager.getBlockDataByHeight(102)).thenReturn(new102);
        var newDeposit = deposit("replacement-transaction", "new101");
        when(parser.parse(new101)).thenReturn(List.of(newDeposit));

        assertThat(restartedScanner.scanBlocks()).isEqualTo(2);
        verify(publisher).publishAndWait(new101, List.of(newDeposit));
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockHash()).isEqualTo("new102");
    }

    @Test
    void shouldRollbackProgressSummaryAndCleanupTogether() {
        properties.setBlockHistorySize(2);
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();
        checkpoint = progressService.advance(checkpoint, block(100, "h100"));
        TronScanCheckpoint current = checkpoint;
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("cleanup failed before commit");
        }).when(scannedBlockService).removeBefore("MAINNET", 100);

        assertThatThrownBy(() -> progressService.advance(current, block(101, "h101")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 101)).isNull();
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(100);
    }

    private TronDepositEvent deposit(String txId, String hash) {
        return new TronDepositEvent("TRON", "MAINNET", "USDT", "contract", txId,
            0, 101L, hash, Instant.EPOCH, "sender", "receiver", BigInteger.ONE);
    }

    private HeadScanProgressService createProgressService() {
        return new HeadScanProgressService(properties, nodeManager, checkpointService, scannedBlockService, transactions);
    }

    private TronScanCheckpoint scanThrough102() {
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();
        for (long height = 100; height <= 102; height++) {
            checkpoint = progressService.advance(checkpoint, block(height, "h" + height));
        }
        return checkpoint;
    }

    private TronScannedBlock summary(String network, long height, String hash) {
        return new TronScannedBlock().setChainNetwork(network).setBlockNumber(height).setBlockHash(hash);
    }

    private TronNodeHeight height(long height, String hash) {
        return new TronNodeHeight("full", height, hash, Instant.EPOCH);
    }

    private TronBlockData block(long height, String hash) {
        return new TronBlockData("full", height, hash, "h" + (height - 1), Instant.EPOCH, List.of(), Map.of());
    }
}
