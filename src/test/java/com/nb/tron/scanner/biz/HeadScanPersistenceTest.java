package com.nb.tron.scanner.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nb.core.exception.BizException;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.biz.DepositDiscoveryService;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.mapper.TronScannedBlockMapper;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronBlockHeaderReader;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.impl.TronScanCheckpointServiceImpl;
import com.nb.tron.scanner.service.impl.TronScannedBlockServiceImpl;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 MyBatis Mapper 和手动事务，验证单线程扫描的提交与回退原子性。
 * H2 使用 MySQL 模式，不代替上线前的 MySQL 和真实节点联调。
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadScanPersistenceTest {

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
        when(nodeManager.getBlockHeaderByHeight(99)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return height(99, "h99");
        });
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
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_SDK_CALL_FAILED));
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
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET")).isNull();
    }

    @Test
    void shouldLoadExistingCheckpointWithoutRepeatingHistoryQueries() {
        TronScanCheckpoint checkpoint = scanThrough102();
        clearInvocations(checkpointService, scannedBlockService, nodeManager);

        assertThat(progressService.loadCheckpoint()).usingRecursiveComparison()
            .ignoringFields("updatedAt").isEqualTo(checkpoint);
        verify(checkpointService).findByNetwork("MAINNET");
        verifyNoInteractions(scannedBlockService, nodeManager);
    }

    @Test
    void shouldRollbackSummaryWhenCheckpointUpdateFailsThenAllowReplay() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        doReturn(false).when(checkpointService).updatePosition(anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> progressService.advance(initial, block(100, "h100"))).isInstanceOf(BizException.class);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNull();
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(99);

        doCallRealMethod().when(checkpointService).updatePosition(anyString(), anyLong(), anyString());
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
    void shouldKeepRecentWindowWithoutTouchingOtherNetworks() {
        properties.setBlockHistorySize(2);
        scannedBlockService.saveBlock(summary("NILE", 1, "nile1"));
        progressService.advance(prepareHistoryThrough(199), block(200, "h200"));

        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNull();
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(199);
        assertThat(scannedBlockService.findByHeight("MAINNET", 199)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 200)).isNotNull();
        assertThat(scannedBlockService.findByHeight("NILE", 1)).isNotNull();
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(200);
    }

    @Test
    void shouldSkipHistoryQueriesBetweenCleanupHeightsAndResumeCleanupAfterRestart() {
        properties.setBlockHistorySize(4);
        TronScanCheckpoint checkpoint = progressService.advance(prepareHistoryThrough(199), block(200, "h200"));
        clearInvocations(checkpointService, scannedBlockService);

        // 201～299 不查询、不清理旧历史，每块只插入摘要、更新进度。
        for (long h = 201; h <= 299; h++) {
            checkpoint = progressService.advance(checkpoint, block(h, "h" + h));
        }
        verify(checkpointService, times(99)).updatePosition(eq("MAINNET"), anyLong(), anyString());
        verify(scannedBlockService, times(99)).saveBlock(any());
        verify(scannedBlockService, never()).findOldestBlock(anyString());
        verify(scannedBlockService, never()).removeBefore(anyString(), anyLong());
        // 连续摘要 197～299，共 4 + 99 条。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tron_scanned_block WHERE chain_network = 'MAINNET'", Integer.class))
            .isEqualTo(103);

        HeadScanProgressService restarted = createProgressService();
        restarted.advance(restarted.loadCheckpoint(), block(300, "h300"));
        verify(scannedBlockService, never()).findOldestBlock("MAINNET");
        verify(scannedBlockService, times(1)).removeBefore("MAINNET", 297);
        assertThat(jdbc.queryForList("SELECT block_number FROM tron_scanned_block WHERE chain_network = 'MAINNET' ORDER BY block_number", Long.class))
            .containsExactly(297L, 298L, 299L, 300L);
    }

    @Test
    void shouldCleanGenesisSentinelWhenItFallsOutsideRecentWindow() {
        properties.setStartBlockHeight(0L);
        properties.setBlockHistorySize(2);
        TronScanCheckpoint current = progressService.loadCheckpoint();
        for (long h = 0; h <= 100; h++) {
            current = progressService.advance(current, block(h, "h" + h));
        }

        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", -1)).isNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 0)).isNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 1)).isNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 99)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNotNull();
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(100);
    }

    @Test
    void shouldKeepCheckpointWhenAlreadyAtCommonBlock() {
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();

        progressService.rewind(checkpoint, summary("MAINNET", 99, "h99"));

        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 99).getBlockHash()).isEqualTo("h99");
        verify(checkpointService).updatePosition("MAINNET", 99, "h99");
        verify(scannedBlockService).removeAfter("MAINNET", 99);
    }

    @Test
    void shouldPreserveHistoryWhenCheckpointRewindFails() {
        TronScanCheckpoint current = scanThrough102();
        doReturn(false).when(checkpointService).updatePosition(anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> progressService.rewind(current, summary("MAINNET", 99, "h99")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
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
        assertThat(current.getLastBlockNumber()).isEqualTo(102);
        assertThat(current.getLastBlockHash()).isEqualTo("h102");
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
    void shouldKeepDatabaseUnchangedWhenForkExceedsRetainedHistory() {
        properties.setBlockHistorySize(2);
        TronScanCheckpoint current = progressService.advance(prepareHistoryThrough(199), block(200, "h200"));
        TronBlockHeaderReader reader = mock(TronBlockHeaderReader.class);
        when(nodeManager.openBlockHeaderReader(200)).thenReturn(reader);
        when(reader.getBlockHeaderByHeight(anyLong())).thenAnswer(invocation -> {
            long h = invocation.getArgument(0);
            return height(h, h <= 100 ? "h" + h : "new" + h);
        });
        var finder = new HeadBlockAncestorFinder(nodeManager, scannedBlockService);
        var continuity = new HeadBlockContinuityService(nodeManager, finder, progressService);
        clearInvocations(checkpointService, scannedBlockService);

        // 只保留 199、200，共同区块 100 已被清理：报错并保持原进度，不能猜一个回退位置。
        assertThatThrownBy(() -> continuity.handleFork(current)).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND);
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(200);
        assertThat(scannedBlockService.findByHeight("MAINNET", 199).getBlockHash()).isEqualTo("h199");
        assertThat(scannedBlockService.findByHeight("MAINNET", 200).getBlockHash()).isEqualTo("h200");
        verify(checkpointService, never()).updatePosition(anyString(), anyLong(), anyString());
        verify(scannedBlockService, never()).removeAfter(anyString(), anyLong());
    }

    @Test
    void shouldDiscoverReplacementDepositAfterForkAndRestart() {
        var addressIndex = mock(TronAddressIndex.class);
        var currencyIndex = mock(TronCurrencyIndex.class);
        var parser = mock(DepositDiscoveryService.class);
        var publisher = mock(DepositDiscoveryPublisher.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);
        TronBlockHeaderReader reader = mock(TronBlockHeaderReader.class);
        when(nodeManager.openBlockHeaderReader(anyLong())).thenReturn(reader);
        when(nodeManager.getBlockHeaderByHeight(anyLong())).thenAnswer(i -> reader.getBlockHeaderByHeight(i.getArgument(0)));
        when(reader.getBlockHeaderByHeight(anyLong())).thenAnswer(invocation -> {
            long height = invocation.getArgument(0);
            return height(height, "h" + height);
        });
        when(nodeManager.getHeadHeight()).thenReturn(height(102, "h102"));
        when(parser.discover(any())).thenReturn(List.of());
        for (long h = 100; h <= 102; h++) {
            when(nodeManager.getBlockDataByHeight(h)).thenReturn(block(h, "h" + h));
        }
        var oldDeposit = deposit("old-transaction", "h101");
        when(parser.discover(block(101, "h101"))).thenReturn(List.of(oldDeposit));
        var scanner = scanner(progressService, addressIndex, currencyIndex, parser, publisher);
        assertThat(scanner.scanBlocks()).isEqualTo(3);
        verify(publisher).publishAndWait(block(101, "h101"), List.of(oldDeposit));

        // 101、102 被替换，二分查到最近共同区块 100，先回退而不发送新事件。
        when(reader.getBlockHeaderByHeight(101)).thenReturn(height(101, "new101"));
        when(reader.getBlockHeaderByHeight(102)).thenReturn(height(102, "new102"));
        assertThat(scanner.scanBlocks()).isZero();
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockNumber()).isEqualTo(100);
        assertThat(scannedBlockService.findByHeight("MAINNET", 101)).isNull();

        // 重新创建业务对象模拟重启，完全依靠数据库边界重扫，发现替换分支中的充值。
        HeadScanProgressService restartedProgress = createProgressService();
        var restartedScanner = scanner(restartedProgress, addressIndex, currencyIndex, parser, publisher);
        TronBlockData new101 = block(101, "new101");
        TronBlockData new102 = new TronBlockData("full", 102, "new102", "new101", Instant.EPOCH, List.of(), Map.of());
        when(nodeManager.getBlockDataByHeight(101)).thenReturn(new101);
        when(nodeManager.getBlockDataByHeight(102)).thenReturn(new102);
        var newDeposit = deposit("replacement-transaction", "new101");
        when(parser.discover(new101)).thenReturn(List.of(newDeposit));

        assertThat(restartedScanner.scanBlocks()).isEqualTo(2);
        verify(publisher).publishAndWait(new101, List.of(newDeposit));
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(checkpointService.findByNetwork("MAINNET").getLastBlockHash()).isEqualTo("new102");
    }

    @Test
    void shouldRollbackProgressSummaryAndCleanupTogether() {
        properties.setBlockHistorySize(2);
        TronScanCheckpoint current = prepareHistoryThrough(199);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("cleanup failed before commit");
        }).when(scannedBlockService).removeBefore("MAINNET", 199);

        assertThatThrownBy(() -> progressService.advance(current, block(200, "h200")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(scannedBlockService.findOldestBlock("MAINNET").getBlockNumber()).isEqualTo(99);
        assertThat(scannedBlockService.findByHeight("MAINNET", 100)).isNotNull();
        assertThat(scannedBlockService.findByHeight("MAINNET", 200)).isNull();
        assertThat(createProgressService().loadCheckpoint().getLastBlockNumber()).isEqualTo(199);

        // 清理失败没有推进 200；重启后仍在同一高度重试，不会跳过本次清理。
        doCallRealMethod().when(scannedBlockService).removeBefore("MAINNET", 199);
        HeadScanProgressService restarted = createProgressService();
        restarted.advance(restarted.loadCheckpoint(), block(200, "h200"));
        assertThat(jdbc.queryForList("SELECT block_number FROM tron_scanned_block WHERE chain_network = 'MAINNET' ORDER BY block_number", Long.class))
            .containsExactly(199L, 200L);
    }

    /**
     * 批量准备已经扫过的历史，再由真实 advance 验证整百高度的清理与事务行为。
     */
    private TronScanCheckpoint prepareHistoryThrough(long lastBlockNumber) {
        progressService.loadCheckpoint();
        List<Object[]> rows = LongStream.rangeClosed(100, lastBlockNumber)
            .mapToObj(h -> new Object[] {"MAINNET", h, "h" + h}).toList();
        jdbc.batchUpdate("INSERT INTO tron_scanned_block VALUES (?, ?, ?)", rows);
        checkpointService.updatePosition("MAINNET", lastBlockNumber, "h" + lastBlockNumber);
        return progressService.loadCheckpoint();
    }

    private TronDepositEvent deposit(String txId, String hash) {
        return new TronDepositEvent("USDT", "contract", txId,
            0, 101L, hash, Instant.EPOCH, "sender", "receiver", BigInteger.ONE);
    }

    private HeadBlockScanService scanner(HeadScanProgressService progress, TronAddressIndex addresses,
                                        TronCurrencyIndex currencies, DepositDiscoveryService parser,
                                        DepositDiscoveryPublisher publisher) {
        HeadBlockAncestorFinder finder = new HeadBlockAncestorFinder(nodeManager, scannedBlockService);
        HeadBlockContinuityService continuity = new HeadBlockContinuityService(nodeManager, finder, progress);
        return new HeadBlockScanService(properties, addresses, currencies, nodeManager, parser, publisher, continuity, progress);
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
