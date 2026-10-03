package com.nb.tron.scanner.support;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronAddressCodecTest {

    private static final String USDT_CONTRACT_BASE58 =
        "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private static final String USDT_CONTRACT_HEX =
        "41a614f803b6fd780986a42c78ec9c7f77e6ded13c";

    private static final String USDT_CONTRACT_TOPIC =
        "000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c";

    private final TronAddressCodec addressCodec = new TronAddressCodec();

    @Test
    void shouldConvertTronHexAddressToBase58Check() {
        assertThat(addressCodec.fromHex(USDT_CONTRACT_HEX))
            .isEqualTo(USDT_CONTRACT_BASE58);
        assertThat(addressCodec.fromHex(USDT_CONTRACT_HEX.substring(2)))
            .isEqualTo(USDT_CONTRACT_BASE58);
    }

    @Test
    void shouldConvertAddressTopicToBase58Check() {
        assertThat(addressCodec.fromTopic(USDT_CONTRACT_TOPIC))
            .isEqualTo(USDT_CONTRACT_BASE58);
    }

    @Test
    void shouldKeepValidBase58CheckAddress() {
        assertThat(addressCodec.isValidBase58Check(USDT_CONTRACT_BASE58)).isTrue();
        assertThat(addressCodec.normalize(USDT_CONTRACT_BASE58))
            .isEqualTo(USDT_CONTRACT_BASE58);
    }

    @Test
    void shouldRejectInvalidAddress() {
        assertInvalid(() -> addressCodec.normalize(
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6x"));
        assertInvalid(() -> addressCodec.fromHex("42a614f803b6fd780986a42c78ec9c7f77e6ded13c"));
        assertInvalid(() -> addressCodec.fromTopic(
            "010000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c"));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_ADDRESS_INVALID);
    }
}
