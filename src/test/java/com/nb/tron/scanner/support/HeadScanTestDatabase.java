package com.nb.tron.scanner.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.biz.HeadScanProgressService;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.mapper.TronScannedBlockMapper;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.impl.TronScanCheckpointServiceImpl;
import com.nb.tron.scanner.service.impl.TronScannedBlockServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 扫块验收使用的独立内存数据库，执行真实 MyBatis SQL 和手动事务。
 *
 * <p>每个测试创建自己的 H2 MySQL 模式数据库，不连接本地或部署环境的业务库。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
public class HeadScanTestDatabase implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final TronScanCheckpointServiceImpl checkpoints;
    private final TronScannedBlockServiceImpl blocks;
    private final TransactionSupport transactions;

    public HeadScanTestDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource(
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
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TronScanCheckpointMapper.class);
        configuration.addMapper(TronScannedBlockMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        checkpoints = new TronScanCheckpointServiceImpl();
        blocks = new TronScannedBlockServiceImpl();
        ReflectionTestUtils.setField(checkpoints, "baseMapper", session.getMapper(TronScanCheckpointMapper.class));
        ReflectionTestUtils.setField(blocks, "baseMapper", session.getMapper(TronScannedBlockMapper.class));
        var template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions = new TransactionSupport(template, template);
    }

    public TronScanCheckpointServiceImpl checkpoints() {
        return checkpoints;
    }

    public TronScannedBlockServiceImpl blocks() {
        return blocks;
    }

    public HeadScanProgressService progress(TronScannerProperties properties, TronNodeManager nodeManager) {
        return new HeadScanProgressService(properties, nodeManager, checkpoints, blocks, transactions);
    }

    @Override
    public void close() {
        jdbc.execute("SHUTDOWN");
    }
}
