package com.nb.tron.scanner.model;

/**
 * 上次扫描末块的分叉检查结果。
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 *
 * @param fork true：本地与节点同高度的 Hash 不同；false：Hash 相同，或尚未扫描创世块
 */
public record HeadBlockCheckResult(boolean fork) {
}
