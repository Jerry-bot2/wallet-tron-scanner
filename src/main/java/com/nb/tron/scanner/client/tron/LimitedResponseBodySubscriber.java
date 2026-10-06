package com.nb.tron.scanner.client.tron;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import lombok.RequiredArgsConstructor;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 接收完整响应体，同时限制累计大小。
 *
 * <p>
 * 每次接收前先检查大小，超限就取消订阅；不能收完后才检查，否则大响应可能耗尽内存。
 * 字节收集和完成通知交给 JDK 自带订阅器处理。
 * </p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@RequiredArgsConstructor
final class LimitedResponseBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final long maxResponseBytes;

    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();

    private Flow.Subscription subscription;

    private long receivedBytes;

    private boolean terminated;

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (terminated) {
            return;
        }
        receivedBytes += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (receivedBytes > maxResponseBytes) {
            onError(BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID));
            subscription.cancel();
            return;
        }
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable exception) {
        if (!terminated) {
            terminated = true;
            delegate.onError(exception);
        }
    }

    @Override
    public void onComplete() {
        if (!terminated) {
            terminated = true;
            delegate.onComplete();
        }
    }
}
