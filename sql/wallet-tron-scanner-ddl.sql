-- wallet-tron-scanner 首版建表脚本
-- 数据库基线：MySQL 8.0 / InnoDB / UTC
-- scanner 数据库保存监控地址、Head 扫块检查点和分叉恢复所需的区块摘要。

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `tron_monitor_address` (
    `source_address_id` BIGINT NOT NULL COMMENT 'wallet-chain-server 中 chain_address.id，也是地址增量同步游标',
    `chain_network` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TRON网络，例如MAINNET、NILE',
    `address` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Base58Check格式TRON地址',
    `address_purpose` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '地址用途：DEPOSIT、HOT、RESOURCE',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步到扫描器的时间，UTC',
    PRIMARY KEY (`source_address_id`),
    UNIQUE KEY `uk_tron_monitor_address` (`chain_network`, `address`),
    KEY `idx_tron_monitor_address_sync` (`chain_network`, `source_address_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TRON扫描器监控地址本地索引';

CREATE TABLE `tron_scan_checkpoint` (
    `chain_network` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TRON网络，例如MAINNET、NILE',
    `last_block_number` BIGINT NOT NULL COMMENT '最后完整解析并成功上报的Head区块高度',
    `last_block_hash` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最后完整处理的Head区块Hash',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近扫描进度变更时间，UTC',
    PRIMARY KEY (`chain_network`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TRON Head扫块检查点';

CREATE TABLE `tron_scanned_block` (
    `chain_network` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TRON网络',
    `block_number` BIGINT NOT NULL COMMENT '已扫描区块高度；初始创世边界允许为-1',
    `block_hash` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当时实际处理的区块Hash；高度-1时为空字符串',
    PRIMARY KEY (`chain_network`, `block_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TRON最近已扫描区块摘要：用于分叉时查找共同区块，不代表固化状态';
