package io.agentmanager.framework.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.agentmanager.framework.config.AgentManagerProperties;

/**
 * S3FileStorage 集成测试（真实 S3 兼容端点，如七牛云 S3 网关）。
 *
 * <p>默认跳过（不依赖外网）；设置 S3_IT=1 并提供以下环境变量后启用：
 * <pre>
 *   S3_IT=1
 *   S3_IT_ENDPOINT=https://s3.cn-south-1.qiniucs.com
 *   S3_IT_ACCESS_KEY=...
 *   S3_IT_SECRET_KEY=...
 *   S3_IT_BUCKET=gao-qiniu
 * </pre>
 * 运行：S3_IT=1 ... mvn test -Dtest=S3FileStorageIT
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "S3_IT", matches = "true|1")
class S3FileStorageIT {

    private static final String FILE_SENTINEL = "S3-IT-SENTINEL-" + UUID.randomUUID();

    private S3FileStorage storage;

    @BeforeAll
    void setUp() {
        var file = new AgentManagerProperties.FileConfig(
            true, 20, 20, "image/*,text/plain", 5, 15, 50, true, 7,
            "s3", "/data/files",
            requireEnv("S3_IT_ENDPOINT"), requireEnv("S3_IT_ACCESS_KEY"),
            requireEnv("S3_IT_SECRET_KEY"), requireEnv("S3_IT_BUCKET"));
        var props = new AgentManagerProperties(
            emptyLlm(), emptyServer(), emptyCheckpoint(), "/tmp/s3it", "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7), file);
        storage = new S3FileStorage(props);
    }

    @AfterAll
    void tearDown() {
        // 清理测试对象（key 前缀 s3-it- 均为本测试写入）
    }

    private static String requireEnv(String name) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing env " + name);
        }
        return v;
    }

    private static AgentManagerProperties.LLMConfig emptyLlm() {
        return new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120);
    }

    private static AgentManagerProperties.ServerConfig emptyServer() {
        return new AgentManagerProperties.ServerConfig("0.0.0.0", 8100);
    }

    private static AgentManagerProperties.CheckpointConfig emptyCheckpoint() {
        return new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "u", "p", "test");
    }

    @Test
    void writeReadDeleteRoundtrip() throws Exception {
        var key = "s3-it/roundtrip-" + UUID.randomUUID() + ".txt";
        var content = ("hello s3\n" + FILE_SENTINEL + "\n").getBytes(StandardCharsets.UTF_8);
        // write（含 contentType）
        storage.write(key, new ByteArrayInputStream(content), content.length, "text/plain");
        // exists
        assertTrue(storage.exists(key), "写入后 exists 应为 true");
        // read → 内容一致
        byte[] read;
        try (var in = storage.read(key)) {
            read = in.readAllBytes();
        }
        assertEquals(FILE_SENTINEL, new String(read, StandardCharsets.UTF_8).lines()
            .filter(l -> l.startsWith("S3-IT-SENTINEL")).findFirst().orElse(""), "读回内容应含哨兵值");
        // delete → exists false（幂等）
        storage.delete(key);
        assertFalse(storage.exists(key), "删除后 exists 应为 false");
        // 重复 delete 幂等（七牛网关 removeObject 不存在不报错或转 IOException 幂等语义）
        storage.delete(key);
    }

    @Test
    void existsFalseForMissingKey() throws Exception {
        assertFalse(storage.exists("s3-it/no-such-" + UUID.randomUUID()), "不存在对象应返回 false");
    }

    @Test
    void readMissingKeyThrows() {
        try (var in = storage.read("s3-it/no-such-" + UUID.randomUUID())) {
            // 部分实现惰性请求（read 时抛出）；此处消费触发
            in.readAllBytes();
            throw new AssertionError("读不存在对象应抛 IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("getObject"), "应为 getObject 失败: " + expected.getMessage());
        }
    }
}
