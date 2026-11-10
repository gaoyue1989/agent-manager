package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 捕获 files().write 的全部批次（写序 issue #230：第一批=生效文件临时发布、第二批=.json sidecar） */
    @SuppressWarnings("unchecked")
    private List<List<WriteEntry>> writtenBatches() {
        ArgumentCaptor<List<WriteEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(files, times(2)).write(captor.capture());
        return captor.getAllValues();
    }

    private static WriteEntry writeOf(List<WriteEntry> batch) {
        return batch.get(0);
    }

    /** 重试收敛用例：按累计次数捕获全部写批次（与 writtenBatches 的定长 2 次区分） */
    @SuppressWarnings("unchecked")
    private List<List<WriteEntry>> writesSoFar(int expectedCount) {
        ArgumentCaptor<List<WriteEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(files, times(expectedCount)).write(captor.capture());
        return captor.getAllValues();
    }

    private static Execution executionWithExit(int code) {
        Execution execution = mock(Execution.class);
        when(execution.getExitCode()).thenReturn(code);
        return execution;
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
        // 第一批：原子发布（原文 + 追加行）——写序 issue #230：先发布生效文件、后写 sidecar
        WriteEntry publish = writeOf(batches.get(0));
        assertTrue(publish.getPath().startsWith(ENV_PATH + ".tmp."));
        assertEquals(600, publish.getMode());
        String content = textOf(publish);
        assertTrue(content.contains("PATH=/usr/local/bin:/usr/bin:/bin\n"), "镜像写入的 PATH 行必须保留");
        assertTrue(content.contains("JAVA_HOME=/usr/lib/jvm\n"), "镜像写入的 JAVA_HOME 行必须保留");
        assertTrue(content.endsWith("B=2\n"), "裸 KEY=value（不包引号）");
        // 第二批：.json 管理键（含合并结果）
        assertEquals(SandboxEnvFile.MANAGED_JSON_PATH, writeOf(batches.get(1)).getPath());
        assertEquals("{\"A\":\"1\",\"B\":\"2\"}", textOf(writeOf(batches.get(1))));
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
        String content = textOf(writeOf(writtenBatches().get(0)));
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
        String content = textOf(writeOf(writtenBatches().get(0)));
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
        String content = textOf(writeOf(writtenBatches().get(0)));
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
        String content = textOf(writeOf(writtenBatches().get(0)));
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
    void mergeShouldTolerateLegacySidecarValues() throws Exception {
        // issue #288：sidecar 存量键（7a43d44 之前旧规则允许 $/换行/首尾空白/前导引号）不按
        // 新规则回放——升级后改无关键不得被历史值永久 400 阻断；存量值仅存 sidecar、不进
        // .env 渲染，放行不破坏生效文件语义
        commandReturns("");
        jsonSidecarReturns("{\"LEGACY_DOLLAR\":\"sk-$HOME\",\"LEGACY_QUOTE\":\"'quoted'\","
            + "\"LEGACY_WS\":\" padded \",\"GOOD\":\"ok\"}");
        originalEnvReturns("PATH=/usr/bin\n");

        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("NEW", "v"));
        assertEquals(List.of("NEW"), result.updatedKeys());
        List<List<WriteEntry>> batches = writtenBatches();
        // 发布批仅追加本次变更行（存量值不得经 .env 发布行重新写入）
        String env = textOf(writeOf(batches.get(0)));
        assertTrue(env.endsWith("NEW=v\n"), env);
        assertFalse(env.contains("$HOME") && env.contains("padded"), env);
        // sidecar 批原样保留全部历史键
        String sidecar = textOf(writeOf(batches.get(1)));
        assertTrue(sidecar.contains("\"LEGACY_DOLLAR\":\"sk-$HOME\""), sidecar);
        assertTrue(sidecar.contains("\"LEGACY_QUOTE\":\"'quoted'\""), sidecar);
        assertTrue(sidecar.contains("\"LEGACY_WS\":\" padded \""), sidecar);
    }

    @Test
    void mergeShouldStillRejectNewValueViolatingRulesOnLegacyKey() throws Exception {
        // 更新存量键：新值必须满足新规则（覆盖即自然收敛清洗），legacy 放行不豁免本次写入
        commandReturns("");
        jsonSidecarReturns("{\"LEGACY\":\"old\"}");
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("LEGACY", "sk-$HOME")));
        assertEquals("unsupported_env_value", e.getErrorCode());
        verify(files, never()).write(any());
    }

    @Test
    void mergeShouldIncludeKeyNameInValueErrors() {
        // issue #288：错误信息带键名定位违规值（历史错误信息无键名，多键场景无法定位）
        commandReturns("");
        jsonSidecarMissing();
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("MY_TOKEN", "sk-$HOME")));
        assertTrue(e.getMessage() != null && e.getMessage().contains("MY_TOKEN"),
            "错误信息应含违规键名: " + e.getMessage());
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

    // ---------- 失败收敛（写序 issue #230：先 publish 后 writeManaged）----------

    @Test
    void mergeShouldRetryAfterPublishFailureBecauseSidecarNotAdvanced() throws Exception {
        jsonSidecarMissing();
        originalEnvMissing();
        // 第一次：mv 失败（exit 1）→ 502，且仅 1 次写（发布批）——sidecar 未推进
        // （Execution mock 须在 when() 链外构造，链内 stub 另一 mock 会触发 UnfinishedStubbing）
        Execution mvFailed = executionWithExit(1);
        when(commands.run(anyString())).thenReturn(mvFailed);
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("K", "v")));
        assertEquals("sandbox_unavailable", e.getErrorCode());
        assertEquals(502, e.getHttpStatus());
        List<List<WriteEntry>> afterFirst = writesSoFar(1);
        assertTrue(writeOf(afterFirst.get(0)).getPath().startsWith(ENV_PATH + ".tmp."),
            "失败时只应有发布临时文件写，sidecar 未推进");

        // 桩修复后重试：变更判定重新命中（sidecar 仍缺 K）→ 重新发布收敛
        Execution mvOk = executionWithExit(0);
        when(commands.run(anyString())).thenReturn(mvOk);
        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("K", "v"));
        assertEquals(List.of("K"), result.updatedKeys(), "sidecar 未推进 → 重试必须重新检出变化（非静默 no-op）");
        List<List<WriteEntry>> all = writesSoFar(3);
        assertTrue(textOf(writeOf(all.get(1))).endsWith("K=v\n"), "重试重新发布含 K=V");
        assertEquals(SandboxEnvFile.MANAGED_JSON_PATH, writeOf(all.get(2)).getPath());
    }

    @Test
    void mergeShouldRetryAfterSidecarWriteFailureIdempotently() throws Exception {
        // 模拟沙箱文件系统：files().write 落盘、mv 真改名、readFile 回读——
        // 才能断言「重试读到已含 K=v 的原文 → 追加第二行 → execd 取最后行幂等」
        Map<String, byte[]> fs = new java.util.HashMap<>();
        java.util.regex.Pattern mv = java.util.regex.Pattern.compile("^mv -f '(.+)' '(.+)'$");
        AtomicBoolean failSidecarWrite = new AtomicBoolean(true);
        // Filesystem.write 返回 void：打桩用 doAnswer（files().write 落盘模拟）
        doAnswer(invocation -> {
            WriteEntry entry = writeOf(invocation.getArgument(0));
            if (failSidecarWrite.get() && SandboxEnvFile.MANAGED_JSON_PATH.equals(entry.getPath())) {
                throw apiException(500);
            }
            fs.put(entry.getPath(), (byte[]) entry.getData());
            return null;
        }).when(files).write(any());
        when(files.readFile(ENV_PATH)).thenAnswer(
            invocation -> new String(fs.getOrDefault(ENV_PATH, new byte[0]), StandardCharsets.UTF_8));
        when(commands.run(anyString())).thenAnswer(invocation -> {
            java.util.regex.Matcher m = mv.matcher((String) invocation.getArgument(0));
            assertTrue(m.matches(), "unexpected command: " + invocation.getArgument(0));
            fs.put(m.group(2), fs.remove(m.group(1)));
            return executionWithExit(0);
        });

        // 第一次：发布成功（mv 改名生效）但 sidecar 写失败 → 502
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("K", "v")));
        assertEquals("sandbox_unavailable", e.getErrorCode());
        assertEquals(502, e.getHttpStatus());
        assertEquals("K=v\n", new String(fs.get(ENV_PATH), StandardCharsets.UTF_8), "生效文件已含 K=V");

        // 重试：sidecar 仍缺 K → 变更重新检出 → 追加第二行 K=V（execd 顺序解析取最后行，幂等收敛）
        failSidecarWrite.set(false);
        SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(sandbox, ENV_PATH, Map.of("K", "v"));
        assertEquals(List.of("K"), result.updatedKeys(), "sidecar 未推进 → 重试重新检出变化");
        assertEquals("K=v\nK=v\n", new String(fs.get(ENV_PATH), StandardCharsets.UTF_8),
            "生效文件出现两行 K=V（最后一行生效，重复追加无害）");
    }
}
