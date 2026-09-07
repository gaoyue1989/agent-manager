package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 文件存储 env → FileConfig 绑定回归测试。
 *
 * <p>历史坑：application.yml 曾写成嵌套 <code>storage: {s3-endpoint: ...}</code>，
 * 与 record 字段 storageS3Endpoint（canonical: storage-s3-endpoint）不匹配，
 * binder 静默回退默认值（S3 字段默认空 → 启动失败）；而 @ConditionalOnProperty
 * 走 Environment 层恰好能取到，造成"type 生效、S3 参数全空"的错位。
 * 本测试模拟该绑定链路，锁住平铺键名结构。
 */
class S3EnvBindingTest {

    private AgentManagerProperties.FileConfig bind(Map<String, Object> envVars) {
        var spring = new StandardEnvironment();
        spring.getPropertySources().addFirst(new SystemEnvironmentPropertySource("sysenv", envVars));
        // 模拟 application.yml（平铺键名，与主配置一致）
        Map<String, Object> yml = new HashMap<>();
        yml.put("agent.file.storage-type", "${FILE_STORAGE_TYPE:local}");
        yml.put("agent.file.storage-local-dir", "${FILE_STORAGE_LOCAL_DIR:/data/files}");
        yml.put("agent.file.storage-s3-endpoint", "${FILE_STORAGE_S3_ENDPOINT:}");
        yml.put("agent.file.storage-s3-access-key", "${FILE_STORAGE_S3_ACCESS_KEY:}");
        yml.put("agent.file.storage-s3-secret-key", "${FILE_STORAGE_S3_SECRET_KEY:}");
        yml.put("agent.file.storage-s3-bucket", "${FILE_STORAGE_S3_BUCKET:agent-files}");
        var sources = ConfigurationPropertySources.get(spring);
        // 手动解析 yml placeholder（等价 Spring Boot 启动时的占位符解析）
        Map<String, Object> resolved = new HashMap<>();
        yml.forEach((k, v) -> resolved.put(k,
            v instanceof String s ? spring.resolvePlaceholders(s) : v));
        spring.getPropertySources().addLast(new MapPropertySource("yml", resolved));
        return Binder.get(spring)
            .bind("agent.file", Bindable.of(AgentManagerProperties.FileConfig.class))
            .get();
    }

    @Test
    void s3EnvVarsBindToRecordFields() {
        var file = bind(new HashMap<>(Map.of(
            "FILE_STORAGE_TYPE", "s3",
            "FILE_STORAGE_S3_ENDPOINT", "https://s3.cn-south-1.qiniucs.com",
            "FILE_STORAGE_S3_ACCESS_KEY", "ak-test",
            "FILE_STORAGE_S3_SECRET_KEY", "sk-test",
            "FILE_STORAGE_S3_BUCKET", "gao-qiniu")));
        assertEquals("s3", file.storageType());
        assertEquals("https://s3.cn-south-1.qiniucs.com", file.storageS3Endpoint());
        assertEquals("ak-test", file.storageS3AccessKey());
        assertEquals("sk-test", file.storageS3SecretKey());
        assertEquals("gao-qiniu", file.storageS3Bucket());
    }

    @Test
    void localFallbackWhenEnvAbsent() {
        var file = bind(new HashMap<>());
        assertEquals("local", file.storageType());
        assertEquals("/data/files", file.storageLocalDir());
        assertEquals("agent-files", file.storageS3Bucket());
    }
}
