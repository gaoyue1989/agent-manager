package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;


/**
 * tryTranslateExec 输出契约回归（P0-12）：SDK BaseSandboxFilesystem 的探测/读取类 shell
 * 命令模板被确定性翻译为 execd files API 直读。输出是手工对齐的哨兵/格式协议——
 * 错一处即 workspace 模板重复注入/文件误判缺失且全部静默失败，故逐字节钉死：
 * <ul>
 *   <li>read：尾随 \n 剥离 + sed '1,Np' 行截断语义 + __NOT_FOUND__/__EMPTY__ 哨兵（exitCode 0；
 *       仅 files API 404 判缺失，其余异常上抛回落——issue #296 收窄）</li>
 *   <li>ls：DIR:/FILE: 行格式、顺序=listDirectory 返回顺序、__NOT_EXISTS__ fail-soft（exitCode 0）</li>
 *   <li>test -e / test -d（仅工作区根精确等值，issue #290）/ mkdir -p 与非模板命令回落真 exec</li>
 * </ul>
 * 入口统一走 {@link OpenSandbox#translateForProbe}（生产不调用的公开探针），绕开 exec() 的
 * hydrateForCall 副作用，verify 计数干净。EXEC_TRANSLATE_ENABLED 默认 true，无需额外开关。
 */
class OpenSandboxExecTranslateTest {

    private static OpenSandboxClientOptions options() {
        return new OpenSandboxClientOptions()
            .serverUrl("s").apiKey("k")
            .image("opensandbox/code-interpreter:v1.1.0")
            .resource(Map.of("cpu", "1", "memory", "1024Mi"));
    }

    /** AbstractBaseSandbox 构造要求 state.workspaceSpec 非空（OpenSandboxTest 同款） */
    private static OpenSandboxState state() {
        var s = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        s.setWorkspaceSpec(ws);
        return s;
    }

    /** 带 commands+files 的 osb mock；files 的具体 stub 由各用例自行接（返回 files mock 便于打桩） */
    private static Filesystem mockOsb(Sandbox osb) {
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        return files;
    }

    private static Execution mockExecution(int exitCode, String stdout) {
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(exitCode);
        var logs = mock(ExecutionLogs.class);
        when(logs.getStdout()).thenReturn(
            stdout == null || stdout.isEmpty() ? List.of()
                : List.of(new OutputMessage(stdout, 0L, false)));
        when(logs.getStderr()).thenReturn(List.of());
        when(exec.getLogs()).thenReturn(logs);
        return exec;
    }

    /** SDK read 探测模板（OpenSandbox.P_READ 注释原文：P_READ 含 \1 反向引用，缺一即静默回落） */
    private static String readTemplate(String path, int n) {
        return "if [ ! -f '" + path + "' ]; then echo '__NOT_FOUND__'; elif [ ! -s '" + path
            + "' ]; then echo '__EMPTY__'; else sed -n '1," + n + "p' '" + path + "'; fi";
    }

    /** SDK ls 目录模板（含 stat 管道片段——P_LS 对 \1 反向引用与 \s+ 分隔的契约） */
    private static String lsTemplate(String dir) {
        return "if [ ! -e '" + dir + "' ]; then echo '__NOT_EXISTS__'; elif [ ! -d '" + dir
            + "' ]; then echo '__NOT_A_DIR__'; else for f in '" + dir + "'/*; do "
            + "if [ -d \"$f\" ]; then "
            + "mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
            + "printf 'DIR:%s\\t%s\\n' \"$f\" \"$mtime\"; "
            + "elif [ -f \"$f\" ]; then "
            + "size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
            + "mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
            + "printf 'FILE:%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; "
            + "fi; done; fi";
    }

    @Test
    void readShouldMatchExecdLineSemantics() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.readByteArray("AGENTS.md")).thenReturn("a\nb\n".getBytes(StandardCharsets.UTF_8));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        // 全量读（N=100）：文件自身尾随 \n 剥离 + 全量行重组
        var full = sandbox.translateForProbe(readTemplate("AGENTS.md", 100));
        assertNotNull(full, "SDK read 模板必须命中翻译（不匹配即静默回落真 exec，翻译失效无告警）");
        assertEquals(0, full.exitCode());
        assertEquals("a\nb", full.stdout(), "尾随 \\n 必须剥离（对齐 execd 按行切分重组语义）");

        // 截断读（N=1）：被打印行的行尾换行保留（sed '1,Np' 语义）
        var head = sandbox.translateForProbe(readTemplate("AGENTS.md", 1));
        assertNotNull(head);
        assertEquals(0, head.exitCode());
        assertEquals("a\n", head.stdout(), "行截断保留行尾换行——错成 \"a\" 即下游逐行解析错位");

        // 读 404（files API 缺失形态）→ __NOT_FOUND__ 哨兵，exitCode 0（SDK 靠哨兵判断缺失）；
        // 非 404 异常已由 issue #296 收窄为上抛回落真 exec（OpenSandboxTest X 组覆盖）
        when(files.readByteArray("missing.md")).thenThrow(new SandboxApiException("HTTP 404 Not Found",
            null, 404, new SandboxError("UNEXPECTED_RESPONSE", "")));
        var notFound = sandbox.translateForProbe(readTemplate("missing.md", 100));
        assertNotNull(notFound);
        assertEquals(0, notFound.exitCode());
        assertEquals("__NOT_FOUND__", notFound.stdout());

        // 空文件（byte[0]）→ __EMPTY__ 哨兵（SDK 有「文件存在但为空」专用提示分支）
        when(files.readByteArray("blank.md")).thenReturn(new byte[0]);
        var empty = sandbox.translateForProbe(readTemplate("blank.md", 100));
        assertNotNull(empty);
        assertEquals(0, empty.exitCode());
        assertEquals("__EMPTY__", empty.stdout());

        // test -e 命中文件（readByteArray 成功即存在）→ "yes"，exitCode 0
        var exists = sandbox.translateForProbe("test -e 'AGENTS.md' && echo yes || echo no");
        assertNotNull(exists);
        assertEquals(0, exists.exitCode());
        assertEquals("yes", exists.stdout());
    }

    @Test
    void lsShouldRenderDirAndFileLinesInListOrder() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        var t = OffsetDateTime.ofInstant(Instant.ofEpochSecond(1730000000), ZoneOffset.UTC);
        // EntryInfo 8 参构造：path, mode, owner, group, size, modifiedAt, createdAt, type
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            new EntryInfo("/workspace/notes", 493, "root", "root", 0, t, t, "directory"),
            new EntryInfo("/workspace/a.txt", 420, "root", "root", 7, t, t, "file")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe(lsTemplate("/workspace"));
        assertNotNull(r, "SDK ls 模板必须命中翻译");
        assertEquals(0, r.exitCode());
        // 输出顺序 = listDirectory 返回顺序（翻译器不排序；排序仅 glob 走 TreeSet）
        // /workspace/ 前缀按工作区相对路径归一
        assertEquals("DIR:notes\t1730000000\nFILE:a.txt\t7\t1730000000", r.stdout());

        // listDirectory 404 → __NOT_EXISTS__ fail-soft（exitCode 0，SDK 靠哨兵判断目录缺失）；
        // 非 404 异常上抛回落真 exec（issue #296 收窄，OpenSandboxTest X 组覆盖）
        when(files.listDirectory("/gone")).thenThrow(new SandboxApiException("HTTP 404 Not Found",
            null, 404, new SandboxError("UNEXPECTED_RESPONSE", "")));
        var missing = sandbox.translateForProbe(lsTemplate("/gone"));
        assertNotNull(missing);
        assertEquals(0, missing.exitCode());
        assertEquals("__NOT_EXISTS__", missing.stdout());

        // listDirectory 返回 null（mock/防御形态）→ 同哨兵
        when(files.listDirectory("/null")).thenReturn(null);
        var nullEntries = sandbox.translateForProbe(lsTemplate("/null"));
        assertNotNull(nullEntries);
        assertEquals("__NOT_EXISTS__", nullEntries.stdout());

        // test -e 命中目录（readByteArray 失败后 listDirectory 成功）→ "yes"
        var dirExists = sandbox.translateForProbe("test -e '/workspace' && echo yes || echo no");
        assertNotNull(dirExists);
        assertEquals("yes", dirExists.stdout());

        // test -d 工作区根探测（无引号形态）→ 存在 exitCode 0
        var testD = sandbox.translateForProbe("test -d /workspace");
        assertNotNull(testD);
        assertEquals(0, testD.exitCode());
        assertEquals("", testD.stdout());
    }

    @Test
    void nonTemplateCommandShouldFallBackToRealExec() throws Exception {
        var osb = mock(Sandbox.class);
        mockOsb(osb);
        // 真实回落命令走 commands().run（覆盖 mockOsb 的通用 stub，便于断言具体命令）
        // 先建好返回对象再 stub，避免嵌套 when()（UnfinishedStubbing）
        var realExec = mockExecution(0, "REAL_EXEC");
        var commands = mock(Commands.class);
        when(commands.run(anyString())).thenReturn(realExec);
        when(osb.commands()).thenReturn(commands);
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        // 非模板命令：不命中任何翻译正则 → 原样真 exec
        var result = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(),
            "ls /workspace", 30);

        assertEquals(0, result.exitCode());
        assertEquals("REAL_EXEC", result.stdout(), "非模板命令必须原样回落真 exec，不得被翻译拦截");
        verify(osb.commands()).run("ls /workspace");
    }

    @Test
    void mkdirCommandShouldFallBackToRealExec() throws Exception {
        // issue #290：P_MKDIR 宽匹配（^mkdir -p (.+)$）已删除——"mkdir -p a b" 多目录语义
        // 与 harness move 模板（mkdir -p $(dirname 'x') && mv 'x' 'y'）会被误翻译成
        // createDirectories 且虚报 exit 0，故 mkdir 一律回落真 exec
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertNull(sandbox.translateForProbe("mkdir -p /workspace"), "mkdir -p 必须回落真 exec（返回 null）");
        assertNull(sandbox.translateForProbe("mkdir -p a b"));
        verify(files, never()).createDirectories(anyList());
        verify(osb.commands(), never()).run(anyString());
    }
}
