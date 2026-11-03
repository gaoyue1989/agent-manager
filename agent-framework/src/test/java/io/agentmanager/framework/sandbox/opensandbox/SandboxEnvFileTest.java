package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

/**
 * {@link SandboxEnvFile} 单元测试：EXECD_ENVS 路径发现、合并式 upsert、跨 execd 版本兼容的
 * 裸值格式（不包引号）、值约束（禁 $ / 换行 / 首尾空白 / 首字符引号 / 保留键 / 64 键 / 32KiB）、
 * 原子发布与「保留镜像写入的 PATH 等行」。
 */
class SandboxEnvFileTest {

    private static final String ENV_PATH = "/opt/opensandbox/.env";

    private Sandbox sandbox;
    private Commands commands;
    private Filesystem files;
    private final SandboxEnvFile envFile = new SandboxEnvFile();

    @BeforeEach
    void setUp() {
        sandbox = mock(Sandbox.class);
        commands = mock(Commands.class);
        files = mock(Filesystem.class);
        when(sandbox.commands()).thenReturn(commands);
        when(sandbox.files()).thenReturn(files);
    }

    // ---------- 桩 ----------

    /** 打桩 commands().run(...)：返回 exit 0 + 指定 stdout */
    private void commandReturns(String stdout) {
        ExecutionLogs logs = logsWith(stdout);
        Execution execution = mock(Execution.class);
        when(execution.getExitCode()).thenReturn(0);
        when(execution.getLogs()).thenReturn(logs);
        when(commands.run(anyString())).thenReturn(execution);
    }

    private ExecutionLogs logsWith(String text) {
        OutputMessage message = mock(OutputMessage.class);
        when(message.getText()).thenReturn(text);
        ExecutionLogs logs = mock(ExecutionLogs.class);
        when(logs.getStdout()).thenReturn(List.of(message));
        return logs;
    }

    private void jsonSidecarReturns(String json) throws Exception {
        when(files.readFile(SandboxEnvFile.MANAGED_JSON_PATH)).thenReturn(json);
    }

    private void originalEnvReturns(String content) throws Exception {
        when(files.readFile(ENV_PATH)).thenReturn(content);
    }

    private void jsonSidecarMissing() {
        SandboxApiException notFound = api404();
        when(files.readFile(SandboxEnvFile.MANAGED_JSON_PATH)).thenThrow(notFound);
    }

    private void originalEnvMissing() {
        SandboxApiException notFound = api404();
        when(files.readFile(ENV_PATH)).thenThrow(notFound);
    }

    /** 捕获 files().write 的全部批次（.json 与临时文件） */
    @SuppressWarnings("unchecked")
    private List<List<WriteEntry>> writtenBatches() {
        ArgumentCaptor<List<WriteEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(files, times(2)).write(captor.capture());
        return captor.getAllValues();
    }

    private static WriteEntry writeOf(List<WriteEntry> batch) {
        return batch.get(0);
    }

    /** SDK 异常类构造器参数非空，用 mock 构造指定状态码 */
    private static SandboxApiException api404() {
        return apiException(404);
    }

    private static SandboxApiException apiException(int status) {
        SandboxApiException e = mock(SandboxApiException.class);
        when(e.getStatusCode()).thenReturn(status);
        return e;
    }

    private static String textOf(WriteEntry entry) {
        return new String((byte[]) entry.getData(), StandardCharsets.UTF_8);
    }

    // ---------- 路径发现 ----------

    @Test
    void discoverShouldReturnPathFromSandboxEnv() {
        commandReturns("/opt/opensandbox/.env");
        assertEquals("/opt/opensandbox/.env", envFile.discoverEnvPath(sandbox));
        verify(commands).run(SandboxEnvFile.DISCOVER_CMD);
    }

    @Test
    void discoverShouldRejectBlankPath() {
        commandReturns("");
        SandboxEnvException e = assertThrows(SandboxEnvException.class, () -> envFile.discoverEnvPath(sandbox));
        assertEquals("execd_env_file_unsupported", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void discoverShouldRejectIllegalPath() {
        commandReturns("relative/path with space");
        SandboxEnvException e = assertThrows(SandboxEnvException.class, () -> envFile.discoverEnvPath(sandbox));
        assertEquals("execd_env_file_unsupported", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void discoverShouldMapCommandFailureTo502() {
        when(commands.run(anyString())).thenThrow(new RuntimeException("conn reset"));
        SandboxEnvException e = assertThrows(SandboxEnvException.class, () -> envFile.discoverEnvPath(sandbox));
        assertEquals("sandbox_unavailable", e.getErrorCode());
        assertEquals(502, e.getHttpStatus());
    }

    // ---------- 合并式写入：保留原文 + 追加 ----------

    @Test
    void mergeShouldAppendAndPreserveImageLines() throws Exception {
        commandReturns("");
        jsonSidecarReturns("{\"A\":\"1\"}");
        originalEnvReturns("PATH=/usr/local/bin:/usr/bin:/bin\nJAVA_HOME=/usr/lib/jvm\n");

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("B", "2"));

        // 返回值：管理键合并、updatedKeys 只含本次变化的键
        assertEquals(Map.of("A", "1", "B", "2"), result.managed());
        assertEquals(List.of("B"), result.updatedKeys());

        List<List<WriteEntry>> batches = writtenBatches();
        // 第一批：.json 管理键（含合并结果）
        assertEquals(SandboxEnvFile.MANAGED_JSON_PATH, writeOf(batches.get(0)).getPath());
        assertEquals("{\"A\":\"1\",\"B\":\"2\"}", textOf(writeOf(batches.get(0))));
        // 第二批：原子发布（原文 + 追加行）
        WriteEntry publish = writeOf(batches.get(1));
        assertTrue(publish.getPath().startsWith(ENV_PATH + ".tmp."));
        assertEquals(600, publish.getMode());
        String content = textOf(publish);
        assertTrue(content.contains("PATH=/usr/local/bin:/usr/bin:/bin\n"), "镜像写入的 PATH 行必须保留");
        assertTrue(content.contains("JAVA_HOME=/usr/lib/jvm\n"), "镜像写入的 JAVA_HOME 行必须保留");
        assertTrue(content.endsWith("B=2\n"), "裸 KEY=value（不包引号）");
        // mv 命令
        verify(commands).run("mv -f '" + publish.getPath() + "' '" + ENV_PATH + "'");
    }

    @Test
    void mergeShouldUpdateExistingKeyByAppend() throws Exception {
        commandReturns("");
        jsonSidecarReturns("{\"A\":\"old\",\"B\":\"keep\"}");
        originalEnvReturns("A=old\nB=keep\n");

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("A", "new"));

        assertEquals(List.of("A"), result.updatedKeys());
        String content = textOf(writeOf(writtenBatches().get(1)));
        // 原有行保留，新行追加在末尾（execd 顺序解析、后面的行覆盖前面的行）
        assertTrue(content.contains("A=old\n"));
        assertTrue(content.contains("B=keep\n"));
        assertTrue(content.endsWith("A=new\n"));
    }

    @Test
    void mergeShouldStartFromEmptyWhenFilesMissing() throws Exception {
        commandReturns("");
        jsonSidecarMissing();
        originalEnvMissing();

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("FOO", "bar"));

        assertEquals(Map.of("FOO", "bar"), result.managed());
        String content = textOf(writeOf(writtenBatches().get(1)));
        assertEquals("FOO=bar\n", content);
    }

    @Test
    void mergeShouldFailSoftOnCorruptManagedJson() throws Exception {
        commandReturns("");
        when(files.readFile(SandboxEnvFile.MANAGED_JSON_PATH)).thenReturn("not-json{");
        originalEnvReturns("PATH=/bin\n");

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("FOO", "bar"));

        // 损坏 JSON fail-soft 从空开始；原文件行仍保留，不丢 env
        assertEquals(Map.of("FOO", "bar"), result.managed());
        String content = textOf(writeOf(writtenBatches().get(1)));
        assertTrue(content.contains("PATH=/bin\n"));
        assertTrue(content.endsWith("FOO=bar\n"));
    }

    // ---------- 校验 ----------

    @Test
    void mergeShouldAllowEmbeddedSingleQuote() throws Exception {
        // 裸值格式下，值内单引号（非首字符）旧/新 execd 都按字面保留
        commandReturns("");
        jsonSidecarMissing();
        originalEnvMissing();
        envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("PASS", "it's"));
        String content = textOf(writeOf(writtenBatches().get(1)));
        assertEquals("PASS=it's\n", content);
    }

    @Test
    void mergeShouldRejectDollarValue() {
        // 旧/新 execd 都对裸值做 os.ExpandEnv，且无转义语法 → 无法无损表达
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("PASS", "sk-$HOME")));
        assertEquals("unsupported_env_value", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldRejectNewlineAndCarriageReturn() {
        for (String value : List.of("a\nb", "a\rb")) {
            SandboxEnvException e = assertThrows(SandboxEnvException.class,
                () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("OK", value)));
            assertEquals("invalid_env_value", e.getErrorCode());
        }
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldRejectLeadingOrTrailingWhitespace() {
        for (String value : List.of(" v", "v ")) {
            SandboxEnvException e = assertThrows(SandboxEnvException.class,
                () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("OK", value)));
            assertEquals("invalid_env_value", e.getErrorCode());
        }
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldRejectQuoteLeadingValue() {
        // 新版 execd 以首个字符判定引号值（剥引号），旧版保留字面 → 语义分叉
        for (String value : List.of("'abc'", "\"abc\"")) {
            SandboxEnvException e = assertThrows(SandboxEnvException.class,
                () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("OK", value)));
            assertEquals("unsupported_env_value", e.getErrorCode());
        }
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldRejectReservedKey() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("EXECD_ENVS", "/x")));
        assertEquals("reserved_env_key", e.getErrorCode());
    }

    @Test
    void mergeShouldRejectInvalidKey() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("1BAD", "v")));
        assertEquals("invalid_env_key", e.getErrorCode());
    }

    @Test
    void mergeShouldRejectNulValue() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("OK", "a\0b")));
        assertEquals("invalid_env_value", e.getErrorCode());
    }

    @Test
    void mergeShouldRejectOversizeValue() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("OK", "x".repeat(9000))));
        assertEquals("invalid_env_value", e.getErrorCode());
    }

    @Test
    void mergeShouldRejectTooManyKeys() {
        Map<String, String> incoming = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) {
            incoming.put("K" + i, "v");
        }
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, incoming));
        assertEquals("env_limit_exceeded", e.getErrorCode());
    }

    @Test
    void mergeShouldRejectMergedLimitExceeded() throws Exception {
        commandReturns("");
        // 已有 63 个管理键，再放 2 个 → 合并 65 超限
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 63; i++) {
            json.append(i == 0 ? "" : ",").append("\"K").append(i).append("\":\"v\"");
        }
        json.append("}");
        jsonSidecarReturns(json.toString());
        originalEnvMissing();

        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("N1", "v", "N2", "v")));
        assertEquals("env_limit_exceeded", e.getErrorCode());
        // 校验失败不产生部分写入
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldSkipPublishWhenNothingChanged() throws Exception {
        commandReturns("");
        jsonSidecarReturns("{\"A\":\"1\"}");
        originalEnvReturns("A=1\n");

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("A", "1"));

        // 无变化：updatedKeys 空、只写 .json、不重复追加行、不发布
        assertTrue(result.updatedKeys().isEmpty());
        assertEquals(Map.of("A", "1"), result.managed());
        ArgumentCaptor<List<WriteEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(files, times(1)).write(captor.capture());
        assertEquals(SandboxEnvFile.MANAGED_JSON_PATH, writeOf(captor.getAllValues().get(0)).getPath());
        verify(commands, never()).run(anyString());
    }

    // ---------- 发布失败 ----------

    @Test
    void mergeShouldMapMvFailureTo502() throws Exception {
        jsonSidecarMissing();
        originalEnvMissing();
        // mergeAndWrite 内唯一一次 commands.run 是发布用的 mv；返回 exit 1
        Execution mvFailed = mock(Execution.class);
        when(mvFailed.getExitCode()).thenReturn(1);
        when(commands.run(anyString())).thenReturn(mvFailed);

        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("FOO", "bar")));
        assertEquals("sandbox_unavailable", e.getErrorCode());
        assertEquals(502, e.getHttpStatus());
    }

    @Test
    void mergeShouldMapOriginalReadFailureTo502() {
        commandReturns("");
        jsonSidecarMissing();
        SandboxApiException serverError = apiException(500);
        when(files.readFile(ENV_PATH)).thenThrow(serverError);

        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("FOO", "bar")));
        assertEquals("sandbox_unavailable", e.getErrorCode());
        assertEquals(502, e.getHttpStatus());
    }
}
