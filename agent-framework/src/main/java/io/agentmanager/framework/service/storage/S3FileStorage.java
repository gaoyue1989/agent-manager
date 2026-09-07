package io.agentmanager.framework.service.storage;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

/**
 * S3 兼容存储后端（MinIO SDK，适配 MinIO/Ceph RGW/阿里 OSS S3 网关）。
 *
 * <p>putObject 天然原子（对象级），getObject 流式读取，removeObject 不存在时报
 * NoSuchKey —— 统一转为"不存在"语义（exists=false / delete 幂等）。
 */
@Service
@ConditionalOnProperty(prefix = "agent.file", name = "storage-type", havingValue = "s3")
public class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final MinioClient client;
    private final String bucket;

    public S3FileStorage(io.agentmanager.framework.config.AgentManagerProperties props) {
        var c = props.file();
        this.bucket = c.storageS3Bucket();
        this.client = MinioClient.builder()
            .endpoint(c.storageS3Endpoint())
            .credentials(c.storageS3AccessKey(), c.storageS3SecretKey())
            .build();
        // 启动时验证 bucket 可达（配置错误快速暴露；S3 不可达时 fail-fast）
        try {
            if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build())) {
                throw new IllegalStateException("S3 bucket does not exist: " + bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("S3 storage init failed: " + e.getMessage(), e);
        }
        log.info("S3FileStorage bucket: {}", bucket);
    }

    @Override
    public void write(String key, InputStream in, long size, String contentType) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(in, size, -1)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build());
        } catch (Exception e) {
            throw new IOException("S3 putObject failed: " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String key) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IOException("S3 getObject failed: " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) throws IOException {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            // NoSuchKey / NotFound → 不存在
            return false;
        } catch (Exception e) {
            throw new IOException("S3 statObject failed: " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IOException("S3 removeObject failed: " + key + ": " + e.getMessage(), e);
        }
    }
}