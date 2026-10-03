package com.nb.tron.scanner.support;

import com.nb.core.exception.BizException;
import com.nb.core.exception.SystemException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import static com.nb.tron.scanner.constant.TronAddressConstants.ACCOUNT_BYTES;
import static com.nb.tron.scanner.constant.TronAddressConstants.ADDRESS_PAYLOAD_BYTES;
import static com.nb.tron.scanner.constant.TronAddressConstants.ADDRESS_PREFIX;
import static com.nb.tron.scanner.constant.TronAddressConstants.BASE58_ALPHABET;
import static com.nb.tron.scanner.constant.TronAddressConstants.BASE58_RADIX;
import static com.nb.tron.scanner.constant.TronAddressConstants.CHECKSUM_BYTES;
import static com.nb.tron.scanner.constant.TronAddressConstants.SHA_256_ALGORITHM;
import static com.nb.tron.scanner.constant.TronAddressConstants.TOPIC_BYTES;

/**
 * TRON 地址转换器
 *
 * <p>将节点交易参数和事件 Topic 中的 Hex 地址统一转换为 Base58Check，
 * 后续解析器只使用 Base58Check 地址匹配平台地址索引。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
public class TronAddressCodec {

    /**
     * 统一地址格式。
     *
     * <p>合法 Base58Check 地址直接返回；Hex 地址转换成 Base58Check。</p>
     */
    public String normalize(String address) {
        if (isValidBase58Check(address)) {
            return address;
        }
        return fromHex(address);
    }

    /**
     * 将 Hex 地址转换为 Base58Check。
     *
     * <p>支持 20 字节账户地址，以及带 {@code 41} 网络前缀的 21 字节地址。</p>
     */
    public String fromHex(String hexAddress) {
        byte[] addressBytes = parseHex(hexAddress);
        byte[] payload = switch (addressBytes.length) {
            case ACCOUNT_BYTES -> addTronPrefix(addressBytes);
            case ADDRESS_PAYLOAD_BYTES -> requireTronPrefix(addressBytes);
            default -> throw invalidAddress();
        };
        return encodeBase58Check(payload);
    }

    /**
     * 将 TRC20 事件 Topic 中的地址转换为 Base58Check。
     *
     * <p>地址 Topic 必须是 32 字节，前 12 字节为零，后 20 字节为账户地址。</p>
     *
     * <p>例如 USDT 合约地址：</p>
     * <pre>
     * Topic       = 000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c
     * 账户地址     = a614f803b6fd780986a42c78ec9c7f77e6ded13c
     * TRON Hex    = 41a614f803b6fd780986a42c78ec9c7f77e6ded13c
     * Base58Check = TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t
     * </pre>
     *
     * <p>返回结果可以直接用于查询平台地址索引或币种合约索引。</p>
     */
    public String fromTopic(String topic) {
        byte[] topicBytes = parseHex(topic);
        if (topicBytes.length != TOPIC_BYTES || !hasZeroAddressPadding(topicBytes)) {
            throw invalidAddress();
        }
        return encodeBase58Check(addTronPrefix(
            Arrays.copyOfRange(topicBytes, TOPIC_BYTES - ACCOUNT_BYTES, TOPIC_BYTES)));
    }

    /**
     * 校验 TRON Base58Check 地址的长度、网络前缀和校验和。
     */
    public boolean isValidBase58Check(String address) {
        if (address == null || address.length() != 34 || address.charAt(0) != 'T') {
            return false;
        }

        byte[] decoded = decodeBase58(address);
        if (decoded == null
            || decoded.length != ADDRESS_PAYLOAD_BYTES + CHECKSUM_BYTES
            || decoded[0] != ADDRESS_PREFIX) {
            return false;
        }

        byte[] payload = Arrays.copyOf(decoded, ADDRESS_PAYLOAD_BYTES);
        byte[] expectedChecksum = checksum(payload);
        byte[] actualChecksum = Arrays.copyOfRange(
            decoded,
            ADDRESS_PAYLOAD_BYTES,
            decoded.length);
        return MessageDigest.isEqual(expectedChecksum, actualChecksum);
    }

    private byte[] parseHex(String value) {
        if (value == null || value.isBlank()) {
            throw invalidAddress();
        }

        String normalized = value.startsWith("0x") || value.startsWith("0X")
            ? value.substring(2)
            : value;
        try {
            return HexFormat.of().parseHex(normalized);
        } catch (IllegalArgumentException exception) {
            throw invalidAddress();
        }
    }

    private byte[] addTronPrefix(byte[] accountBytes) {
        byte[] payload = new byte[ADDRESS_PAYLOAD_BYTES];
        payload[0] = ADDRESS_PREFIX;
        System.arraycopy(accountBytes, 0, payload, 1, ACCOUNT_BYTES);
        return payload;
    }

    private byte[] requireTronPrefix(byte[] payload) {
        if (payload[0] != ADDRESS_PREFIX) {
            throw invalidAddress();
        }
        return payload;
    }

    private boolean hasZeroAddressPadding(byte[] topicBytes) {
        for (int index = 0; index < TOPIC_BYTES - ACCOUNT_BYTES; index++) {
            if (topicBytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private String encodeBase58Check(byte[] payload) {
        byte[] addressBytes = Arrays.copyOf(payload, ADDRESS_PAYLOAD_BYTES + CHECKSUM_BYTES);
        byte[] checksum = checksum(payload);
        System.arraycopy(checksum, 0, addressBytes, ADDRESS_PAYLOAD_BYTES, CHECKSUM_BYTES);
        return encodeBase58(addressBytes);
    }

    private byte[] checksum(byte[] payload) {
        return Arrays.copyOf(sha256(sha256(payload)), CHECKSUM_BYTES);
    }

    private String encodeBase58(byte[] value) {
        BigInteger number = new BigInteger(1, value);
        StringBuilder encoded = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] quotientAndRemainder = number.divideAndRemainder(BASE58_RADIX);
            encoded.append(BASE58_ALPHABET.charAt(quotientAndRemainder[1].intValue()));
            number = quotientAndRemainder[0];
        }
        for (byte current : value) {
            if (current != 0) {
                break;
            }
            encoded.append(BASE58_ALPHABET.charAt(0));
        }
        return encoded.reverse().toString();
    }

    private byte[] decodeBase58(String value) {
        BigInteger number = BigInteger.ZERO;
        for (int index = 0; index < value.length(); index++) {
            int digit = BASE58_ALPHABET.indexOf(value.charAt(index));
            if (digit < 0) {
                return null;
            }
            number = number.multiply(BASE58_RADIX).add(BigInteger.valueOf(digit));
        }

        byte[] body = number.signum() == 0 ? new byte[0] : number.toByteArray();
        if (body.length > 0 && body[0] == 0) {
            body = Arrays.copyOfRange(body, 1, body.length);
        }

        int leadingZeros = 0;
        while (leadingZeros < value.length()
            && value.charAt(leadingZeros) == BASE58_ALPHABET.charAt(0)) {
            leadingZeros++;
        }

        byte[] decoded = new byte[leadingZeros + body.length];
        System.arraycopy(body, 0, decoded, leadingZeros, body.length);
        return decoded;
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance(SHA_256_ALGORITHM).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new SystemException(ScannerBizErrCode.CRYPTO_ALGORITHM_UNAVAILABLE, exception);
        }
    }

    private BizException invalidAddress() {
        return BizException.of(ScannerBizErrCode.TRON_ADDRESS_INVALID);
    }
}
