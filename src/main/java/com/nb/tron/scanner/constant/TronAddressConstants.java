package com.nb.tron.scanner.constant;

import java.math.BigInteger;

/**
 * TRON 地址格式常量。
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
public final class TronAddressConstants {

    /**
     * TRON 地址网络前缀。
     */
    public static final byte ADDRESS_PREFIX = 0x41;

    /**
     * 不包含网络前缀的账户地址字节数。
     */
    public static final int ACCOUNT_BYTES = 20;

    /**
     * 包含网络前缀的地址载荷字节数。
     */
    public static final int ADDRESS_PAYLOAD_BYTES = 21;

    /**
     * Base58Check 校验和字节数。
     */
    public static final int CHECKSUM_BYTES = 4;

    /**
     * TRC20 事件 Topic 的字节数。
     */
    public static final int TOPIC_BYTES = 32;

    /**
     * Base58 编码字符表。
     */
    public static final String BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * Base58 进制基数。
     */
    public static final BigInteger BASE58_RADIX = BigInteger.valueOf(58);

    /**
     * Base58Check 校验和使用的摘要算法。
     */
    public static final String SHA_256_ALGORITHM = "SHA-256";

    private TronAddressConstants() {
    }
}
