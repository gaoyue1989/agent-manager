package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;

/**
 * OpenSandbox API 全流程集成测试（默认禁用）。
 *
 * 运行方式：设置环境变量 OPENSANDBOX_IT=1 后执行
 *   SANDBOX_IT=1 mvn test -Dtest=OpenSandboxApiIntegrationTest
 *
 * 依赖：OpenSandbox Server 已部署（192.168.31.155:8090）。
 * 全流程：创建 → 查询状态 → 命令执行 → 文件写/读 → 删除。
 */
@EnabledIfEnvironmentVariable(named = "SANDBOX_IT", matches = "1")
class OpenSandboxApiIntegrationTest {

    private static final String SERVER_URL = "192.168.31.155:8090";
    private static final String API_KEY = System.getenv().getOrDefault(
        "OPENSANDBOX_API_KEY", "change-me");

    private static Sandbox sandbox;

    @BeforeAll
    static void createSandbox() {
        var config = ConnectionConfig.builder()
            .domain(SERVER_URL)
            .apiKey(API_KEY)
            .protocol("http")
            .build();
        sandbox = Sandbox.builder()
            .connectionConfig(config)
            .image("opensandbox/code-interpreter:v1.1.0")
            .timeout(Duration.ofMinutes(10))
            .resource(Map.of("cpu", "1", "memory", "1024Mi"))
            .build();
        assertNotNull(sandbox.getId(), "sandbox id should be assigned");
        System.out.println("Sandbox created: " + sandbox.getId());
    }

    @Test
    void flowQueryStatus() {
        var info = sandbox.getInfo();
        assertNotNull(info);
        assertEquals(sandbox.getId(), info.getId());
        System.out.println("Sandbox state: " + info.getStatus().getState());
    }

    @Test
    void flowExecuteCommand() {
        var exec = sandbox.commands().run("echo hello-from-sandbox && python3 --version");
        assertEquals(0, exec.getExitCode());
        var stdout = exec.getLogs().getStdout().stream()
            .map(m -> m.getText()).toList();
        assertTrue(stdout.stream().anyMatch(s -> s.contains("hello-from-sandbox")));
        System.out.println("stdout: " + stdout);
    }

    @Test
    void flowFileWriteRead() {
        // 写文件
        sandbox.files().write(List.of(
            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path("/workspace/hello.txt")
                .data("Hello from integration test")
                .mode(644)
                .build()
        ));
        // 读文件
        var content = sandbox.files().readFile("/workspace/hello.txt");
        assertEquals("Hello from integration test", content);

        // 列目录（EntryInfo.path 为沙箱内完整路径，如 /workspace/hello.txt）
        var entries = sandbox.files().listDirectory("/workspace");
        assertTrue(entries.stream().anyMatch(e -> e.getPath().endsWith("hello.txt")));
        System.out.println("workspace entries: " + entries.stream().map(e -> e.getPath()).toList());
    }

    @Test
    void flowWorkspaceToolsContract() {
        // AgentScope 运行时镜像约束自检（在真实沙箱内执行）
        var exec = sandbox.commands().run(
            "sh -c 'echo ok' && tar --version && printf x | base64 | base64 -d"
                + " && stat -c %Y /tmp && mkdir -p /workspace/.probe && test -d /workspace/.probe");
        assertEquals(0, exec.getExitCode(), () -> exec.getLogs().getStderr().toString());
        System.out.println("Runtime contract check passed");
    }

    @AfterAll
    static void deleteSandbox() {
        if (sandbox != null) {
            sandbox.kill();
            sandbox.close();
            System.out.println("Sandbox deleted");
        }
    }
}
