package io.agentmanager.framework.service.storage;

import java.io.IOException;

/**
 * 有界写越限（{@link FileStorage#writeBounded}）：流内容超过 maxBytes，写入已中止且
 * 不会留下任何目标对象（local 未 move、s3 未 PUT）。调用方据此向模型/用户返回明确
 * 的「超限拒绝」语义（present_file 大文件快速拒绝路径），与一般 IO 故障区分。
 */
public class StorageOverflowException extends IOException {

    private final long maxBytes;
    private final long actualBytes;

    public StorageOverflowException(long maxBytes, long actualBytes) {
        super("content exceeds " + maxBytes + " bytes limit (at least " + actualBytes + ")");
        this.maxBytes = maxBytes;
        this.actualBytes = actualBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }
}
