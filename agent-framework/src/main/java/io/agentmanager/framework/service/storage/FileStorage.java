package io.agentmanager.framework.service.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储后端抽象（对齐 Dify ext_storage，见 file-upload-download-plan §4.2）。
 *
 * <p>DB 只存元数据（storage_type + storage_key），文件字节由后端承载：
 * <ul>
 *   <li>LocalFileStorage —— 本地路径 {root}/{key}（原子写 + 路径穿越防护）</li>
 *   <li>S3FileStorage —— S3 兼容 bucket（MinIO SDK，MinIO/Ceph RGW/OSS）</li>
 * </ul>
 */
public interface FileStorage {

    /**
     * 写入（实现层负责原子性：local=tmp+move；s3=putObject 天然原子）。
     *
     * @param key         存储 key（形如 upload/202509/{uuid}-{name}）
     * @param in          内容流（调用方负责关闭）
     * @param size        字节数（s3 需要 Content-Length）
     * @param contentType MIME 类型
     */
    void write(String key, InputStream in, long size, String contentType) throws IOException;

    /** 流式读取（s3=getObject 流；local=FileInputStream） */
    InputStream read(String key) throws IOException;

    /** 对象是否存在（下载/回滚前判断） */
    boolean exists(String key) throws IOException;

    /** 删除对象（清理/回滚；不存在时幂等成功） */
    void delete(String key) throws IOException;
}