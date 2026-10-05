-- 阶段6.5增量脚本：只新增区块摘要表，不修改检查点字段，不自动删除扫描进度。
-- 已有检查点缺少摘要时，需要按阶段6文档的迁移步骤从原扫描起点回放。

CREATE TABLE `tron_scanned_block` (
    `chain_network` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TRON网络',
    `block_number` BIGINT NOT NULL COMMENT '已扫描区块高度；初始创世边界允许为-1',
    `block_hash` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当时实际处理的区块Hash；高度-1时为空字符串',
    PRIMARY KEY (`chain_network`, `block_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TRON最近已扫描区块摘要：用于分叉时查找共同区块，不代表固化状态';
