package com.nb.tron.scanner.biz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.impl.TronScanCheckpointServiceImpl;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 使用真实MyBatis Mapper验证固化扫描检查点的初始化、推进和故障重试。
 * H2使用MySQL模式，不代替上线前的MySQL和真实节点联调。
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadScanPersistenceTest {

    private JdbcTemplate jdbc;

    private TronScannerProperties properties;

    private TronNodeManager nodeManager;

    private TronScanCheckpointServiceImpl checkpointService;

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

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TronScanCheckpointMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());

        checkpointService = spy(new TronScanCheckpointServiceImpl());
        ReflectionTestUtils.setField(
            checkpointService, "baseMapper", session.getMapper(TronScanCheckpointMapper.class));
        properties = new TronScannerProperties();
        properties.setStartBlockHeight(100L);
        nodeManager = mock(TronNodeManager.class);
        progressService = createProgressService();
        when(nodeManager.getBlockHeaderByHeight(99L)).thenReturn(height(99L, "h99"));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void shouldInitializeCheckpointAndResumeAfterRestart() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        assertCheckpoint(initial, 99L, "h99");

        progressService.advance(initial, block(100L, "h100"));

        HeadScanProgressService restarted = createProgressService();
        assertCheckpoint(restarted.loadCheckpoint(), 100L, "h100");
        verify(nodeManager, times(1)).getBlockHeaderByHeight(99L);
    }

    @Test
    void shouldInitializeGenesisSentinelWithoutNegativeRpc() {
        properties.setStartBlockHeight(0L);

        TronScanCheckpoint initial = progressService.loadCheckpoint();

        assertCheckpoint(initial, -1L, "");
        verifyNoInteractions(nodeManager);
    }

    @Test
    void shouldLeaveDatabaseEmptyWhenInitialBlockCannotBeRead() {
        when(nodeManager.getBlockHeaderByHeight(99L))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_SDK_CALL_FAILED));

        assertThatThrownBy(progressService::loadCheckpoint).isInstanceOf(BizException.class);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
    }

    @Test
    void shouldRejectFailedInitialCheckpointInsert() {
        doReturn(false).when(checkpointService).save(any(TronScanCheckpoint.class));

        assertThatThrownBy(progressService::loadCheckpoint)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
        assertThat(checkpointService.findByNetwork("MAINNET")).isNull();
    }

    @Test
    void shouldLoadExistingCheckpointWithoutReadingNodeAgain() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        TronScanCheckpoint expected = progressService.advance(initial, block(100L, "h100"));
        clearInvocations(nodeManager, checkpointService);

        TronScanCheckpoint actual = progressService.loadCheckpoint();

        assertCheckpoint(actual, expected.getLastBlockNumber(), expected.getLastBlockHash());
        verify(checkpointService).findByNetwork("MAINNET");
        verifyNoInteractions(nodeManager);
    }

    @Test
    void shouldKeepCheckpointAndAllowReplayWhenAdvanceFails() {
        TronScanCheckpoint initial = progressService.loadCheckpoint();
        doReturn(false).when(checkpointService).updatePosition(anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> progressService.advance(initial, block(100L, "h100")))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
        assertCheckpoint(checkpointService.findByNetwork("MAINNET"), 99L, "h99");

        doCallRealMethod().when(checkpointService).updatePosition(anyString(), anyLong(), anyString());
        progressService.advance(initial, block(100L, "h100"));
        assertCheckpoint(createProgressService().loadCheckpoint(), 100L, "h100");
    }

    private HeadScanProgressService createProgressService() {
        return new HeadScanProgressService(properties, nodeManager, checkpointService);
    }

    private void assertCheckpoint(TronScanCheckpoint checkpoint, long blockNumber, String blockHash) {
        assertThat(checkpoint.getLastBlockNumber()).isEqualTo(blockNumber);
        assertThat(checkpoint.getLastBlockHash()).isEqualTo(blockHash);
    }

    private TronNodeHeight height(long blockNumber, String blockHash) {
        return new TronNodeHeight("full", blockNumber, blockHash, Instant.EPOCH);
    }

    private TronBlockData block(long blockNumber, String blockHash) {
        return new TronBlockData(
            "full", blockNumber, blockHash, "h" + (blockNumber - 1), Instant.EPOCH, List.of(), Map.of());
    }
}
