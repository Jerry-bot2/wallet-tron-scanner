package com.nb.tron.scanner.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nb.tron.scanner.biz.HeadScanProgressService;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.impl.TronScanCheckpointServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

/**
 * 扫块验收使用的独立内存数据库，执行真实MyBatis检查点SQL。
 *
 * <p>每个测试创建自己的 H2 MySQL 模式数据库，不连接本地或部署环境的业务库。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
public class HeadScanTestDatabase implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final TronScanCheckpointServiceImpl checkpoints;

    public HeadScanTestDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE tron_scan_checkpoint (
              chain_network VARCHAR(32) PRIMARY KEY, last_block_number BIGINT NOT NULL,
              last_block_hash VARCHAR(128) NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TronScanCheckpointMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        checkpoints = new TronScanCheckpointServiceImpl();
        ReflectionTestUtils.setField(checkpoints, "baseMapper", session.getMapper(TronScanCheckpointMapper.class));
    }

    public TronScanCheckpointServiceImpl checkpoints() {
        return checkpoints;
    }

    public HeadScanProgressService progress(TronScannerProperties properties, TronNodeManager nodeManager) {
        return new HeadScanProgressService(properties, nodeManager, checkpoints);
    }

    @Override
    public void close() {
        jdbc.execute("SHUTDOWN");
    }
}
