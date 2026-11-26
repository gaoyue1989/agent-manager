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
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxInternalException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import io.agentmanager.framework.service.WorkspaceReader;


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

    /** SDK glob 模板（P_FIND 同形，root/pattern 可参数化） */
    private static String findTemplate(String root, String pattern) {
        return "find '" + root + "' -type f -name '" + pattern + "' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";
    }

    /** glob 模板的 -L 归一化形态（doExec normalizeSymlinkHostileFind 契约：回落真 exec 必带 -L） */
    private static String findLTemplate(String root, String pattern) {
        return "find -L '" + root + "' -type f -name '" + pattern + "' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";
    }

    private static OffsetDateTime epoch() {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(1730000000), ZoneOffset.UTC);
    }

    /** 覆盖 mockOsb 的通用 commands stub（run 返回真实执行对象），供回落断言 */
    private static void stubRealExec(Sandbox osb, Execution realExec) {
        var commands = mock(Commands.class);
        when(commands.run(anyString())).thenReturn(realExec);
        when(osb.commands()).thenReturn(commands);
    }

    /** symlink 条目（EntryInfo 8 参构造；type 对齐 execd Lstat 语义 file/directory/symlink/other） */
    private static EntryInfo symlinkEntry(String path) {
        var t = epoch();
        return new EntryInfo(path, 493, "root", "root", 0, t, t, "symlink");
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

    // ---------- 软链农场透明性（issue #394，skills-syncd §6.3 落实 §13 A-1）----------
    // farm 模式下翻译语义必须与真实 shell 一致：ls/glob/test -e 遇 type=symlink 条目
    // 不可裁决（EntryInfo 无 link target、execd 对软链根拒绝穿透），整目录回落真 exec。

    @Test
    void lsSymlinkEntryShouldFallBackToRealExecWithOriginalCommand() throws Exception {
        // ① ls 父目录含 type=symlink 条目 → 翻译 null 回落；doExec 收到原始 P_LS 模板命令
        // （shell [ -d ]/[ -f ] 解引用语义兜底——软链目录显示 DIR:、软链文件显示 FILE:）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            symlinkEntry("/workspace/.skills-cache/sess/oaf-package/demo"),
            new EntryInfo("/workspace/a.txt", 420, "root", "root", 7, epoch(), epoch(), "file")));
        stubRealExec(osb, mockExecution(0, "DIR:.skills-cache/sess/oaf-package/demo\t0"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertNull(sandbox.translateForProbe(lsTemplate("/workspace")), "含 symlink 条目必须整目录回落（null）");

        var r = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(),
            lsTemplate("/workspace"), 30);
        assertEquals(0, r.exitCode());
        assertEquals("DIR:.skills-cache/sess/oaf-package/demo\t0", r.stdout(), "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(lsTemplate("/workspace"));
    }

    @Test
    void globSymlinkSubtreeShouldFallBackToFindL() throws Exception {
        // ② glob 父目录（真实目录）含 symlink 子目录 → 回落真 exec，且命令补 -L
        // （GNU find 默认不跟随目录软链，无 -L 恒空；-L 对混合树语义=跟随链接枚举文件）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("tree")).thenReturn(List.of(
            symlinkEntry("/workspace/tree/link")));
        stubRealExec(osb, mockExecution(0, "tree/link/script.py\t3\t0"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertNull(sandbox.translateForProbe(findTemplate("tree", "*.py")), "软链子树必须回落（null）");

        var r = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(),
            findTemplate("tree", "*.py"), 30);
        assertEquals(0, r.exitCode());
        assertEquals("tree/link/script.py\t3\t0", r.stdout(), "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(findLTemplate("tree", "*.py"));
    }

    @Test
    void globSymlinkRootRejectedByExecdShouldFallBackToFindL() throws Exception {
        // ③ glob ROOT 本身为软链：execd listDirectory 对软链根拒绝穿透 400（设计 §6.3 契约，
        // 非 404 → 上抛交 tryTranslateExec 回落）→ doExec 收到 -L 归一化后的 find 命令
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("link")).thenThrow(new SandboxApiException("HTTP 400 Bad Request",
            null, 400, new SandboxError("UNEXPECTED_RESPONSE", "symlink not a directory")));
        stubRealExec(osb, mockExecution(0, "link/script.py\t3\t0"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(),
            findTemplate("link", "*.py"), 30);
        assertEquals(0, r.exitCode());
        assertEquals("link/script.py\t3\t0", r.stdout());
        verify(osb.commands()).run(findLTemplate("link", "*.py"));
    }

    @Test
    void testExistsSymlinkEntryShouldFallBackToRealTestE() throws Exception {
        // ④ test -e 父目录列举命中 type=symlink 条目 → 翻译 null 回落，doExec 收到原始
        // test -e 命令（悬空链 shell 判 no、有效链判 yes/no 由 shell 裁决）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace/tree")).thenReturn(List.of(
            symlinkEntry("/workspace/tree/dangling")));
        stubRealExec(osb, mockExecution(0, "no"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var probe = "test -e 'tree/dangling' && echo yes || echo no";
        assertNull(sandbox.translateForProbe(probe), "父列举命中 symlink 条目必须回落（null）");

        var r = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(), probe, 30);
        assertEquals("no", r.stdout(), "悬空链由 shell 裁决（真 test -e 答 no）");
        verify(osb.commands()).run(probe);
    }

    @Test
    void symlinkFreeEntriesShouldKeepFastPathByteIdentical() throws Exception {
        // ⑤ 快路径回归：仅 file/directory 条目时翻译直答逐字节不变（不回落、不触 commands）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("tree")).thenReturn(List.of(
            new EntryInfo("/workspace/tree/sub", 493, "root", "root", 0, epoch(), epoch(), "directory"),
            new EntryInfo("/workspace/tree/f.md", 420, "root", "root", 7, epoch(), epoch(), "file")));
        when(files.listDirectory("tree/sub")).thenReturn(List.of(
            new EntryInfo("/workspace/tree/sub/x.py", 420, "root", "root", 3, epoch(), epoch(), "file")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe(findTemplate("tree", "*.py"));
        assertNotNull(r, "无软链时 glob 快路径必须保持翻译直答");
        assertEquals(0, r.exitCode());
        assertEquals("tree/sub/x.py\t3\t1730000000", r.stdout());
        verify(osb.commands(), never()).run(anyString());
    }

    // ---------- 受控缓存子树不进 glob 视野（issue #500，农场软链信任模型细化）----------

    @Test
    void rootGlobOverFarmSymlinkCacheShouldKeepFastPath() throws Exception {
        // ⑥ issue #500：.skills-cache 是平台受控技能缓存投影（软链农场，skills-syncd §6.2），
        // 根 glob 遍历不进入受控子树——农场软链不得触发整树回落（回落即快路径失效 +
        // 冗余 listDirectory RTT + 读穿漏 KV），农场 /skills-store 内部文件也不得混入用户视野
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            new EntryInfo("/workspace/.skills-cache", 493, "root", "root", 0, epoch(), epoch(), "directory"),
            new EntryInfo("/workspace/notes.md", 420, "root", "root", 7, epoch(), epoch(), "file")));
        when(files.listDirectory(".skills-cache")).thenReturn(List.of(
            symlinkEntry("/workspace/.skills-cache/sess/oaf-package/demo")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe(findTemplate("/workspace", "*.md"));
        assertNotNull(r, "受控缓存子树必须跳过：农场软链不得触发整树回落（issue #500 主缺陷）");
        assertEquals(0, r.exitCode());
        assertEquals("notes.md\t7\t1730000000", r.stdout(),
            "根 glob 视野不含受控缓存内部文件（农场软链解引用文件不得混入）");
        verify(osb.commands(), never()).run(anyString());
        verify(files, never()).listDirectory(".skills-cache");
    }

    @Test
    void globWithRootInsideSkillsCacheShouldStillFallBackToFindL() throws Exception {
        // ⑦ issue #500 回归边界：root 本身在受控缓存子树内（显式枚举技能文件）时，
        // 农场软链照旧整树回落 find -L（#394 技能枚举语义不动）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace/.skills-cache/sess")).thenReturn(List.of(
            symlinkEntry("/workspace/.skills-cache/sess/oaf-package/demo")));
        stubRealExec(osb, mockExecution(0, ".skills-cache/sess/oaf-package/demo/SKILL.md\t12\t0"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var probe = findTemplate("/workspace/.skills-cache/sess", "*.md");
        assertNull(sandbox.translateForProbe(probe), "root 在受控缓存内的软链必须照旧回落（#394 语义）");

        var r = sandbox.exec(io.agentscope.core.agent.RuntimeContext.builder().userId("u1").build(),
            probe, 30);
        assertEquals(0, r.exitCode());
        assertEquals(".skills-cache/sess/oaf-package/demo/SKILL.md\t12\t0", r.stdout(),
            "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(findLTemplate("/workspace/.skills-cache/sess", "*.md"));
    }

    // ---------- test -e 尾斜杠归一（issue #502）：LLM/SDK 生成的探测路径常带尾斜杠，
    // normalizeRel 不剥尾斜杠令 parent/leafPath 切分错位（parent=目标自身、leaf=空串），
    // 已有目录被误判 no；真实 bash test -e dir/ 答 yes ----------

    @Test
    void testExistsTrailingSlashDirectoryShouldAnswerYes() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(
            new EntryInfo("/workspace/skills/my-skill", 493, "root", "root", 0, epoch(), epoch(), "directory")));
        when(files.listDirectory("/workspace/skills/my-skill")).thenReturn(List.of(
            new EntryInfo("/workspace/skills/my-skill/SKILL.md", 420, "root", "root", 7, epoch(), epoch(), "file")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe("test -e '/workspace/skills/my-skill/' && echo yes || echo no");
        assertNotNull(r);
        assertEquals("yes", r.stdout(), "带尾斜杠的已有目录必须与不带斜杠同判 yes（真实 bash 语义）");
        verify(files, never()).listDirectory("/workspace/skills/my-skill");
    }

    @Test
    void testExistsTrailingSlashOnRegularFileShouldAnswerNo() throws Exception {
        // POSIX：尾斜杠要求最后组件为目录——test -e file/ 对普通文件答 no，
        // 不得因 readByteArray 命中普通文件而误答 yes
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.readByteArray("AGENTS.md")).thenReturn("body".getBytes(StandardCharsets.UTF_8));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe("test -e 'AGENTS.md/' && echo yes || echo no");
        assertNotNull(r);
        assertEquals("no", r.stdout(), "普通文件带尾斜杠必须按 POSIX 语义答 no");
        verify(files).readByteArray("AGENTS.md");
        verify(files, never()).readByteArray("AGENTS.md/");
    }

    @Test
    void testExistsTrailingSlashSymlinkEntryShouldFallBackToRealTestE() throws Exception {
        // 带尾斜杠的目录软链：父列举命中 symlink 条目照旧回落真 exec（#394 闸门语义
        // 不因尾斜杠归一改变，悬空链 no / 有效链 yes/no 由 shell 裁决）
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace/tree")).thenReturn(List.of(
            symlinkEntry("/workspace/tree/link")));
        stubRealExec(osb, mockExecution(0, "yes"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var probe = "test -e 'tree/link/' && echo yes || echo no";
        assertNull(sandbox.translateForProbe(probe), "尾斜杠命中 symlink 条目必须回落（null，#394 语义保持）");
    }

    @Test
    void testExistsTopLevelWorkspaceDirectoryUsesRootListingWithoutReadByteArray() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            new EntryInfo("/workspace/skills", 493, "root", "root", 0, epoch(), epoch(), "directory"),
            new EntryInfo("/workspace/memory", 493, "root", "root", 0, epoch(), epoch(), "directory")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r1 = sandbox.translateForProbe("test -e '/workspace/skills' && echo yes || echo no");
        var r2 = sandbox.translateForProbe("test -e 'memory' && echo yes || echo no");
        assertNotNull(r1);
        assertEquals("yes", r1.stdout());
        assertNotNull(r2);
        assertEquals("yes", r2.stdout());
        verify(files, never()).readByteArray(anyString());
    }

    @Test
    void testExistsDirectoryDownloadInternalExceptionFallsThroughToListDirectory() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.readByteArray("etc/conf")).thenThrow(
            new SandboxInternalException("Cannot buffer entire body for content length: 9223372036854775807", null));
        when(files.listDirectory("etc/conf")).thenReturn(List.of());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r = sandbox.translateForProbe("test -e '/etc/conf' && echo yes || echo no");
        assertNotNull(r);
        assertEquals("yes", r.stdout());
    }

    @Test
    void readThroughLsAndGlobMemoryTrailingSlashShouldWork() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        var reader = mock(WorkspaceReader.class);
        when(files.listDirectory("memory/")).thenThrow(
            new SandboxApiException("HTTP 404 Not Found", null, 404, new SandboxError(SandboxError.FILE_NOT_FOUND, "not found")));
        when(reader.listRuntimeFiles("u1")).thenReturn(Map.of(
            "MEMORY.md", 12,
            "memory/2026-11-24.md", 20));
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null, null, null, null, false, false, true, false);
        sandbox.setUserKey("u1");

        var lsRes = sandbox.translateForProbe(lsTemplate("memory/"));
        assertNotNull(lsRes);
        assertEquals("FILE:memory/2026-11-24.md\t20\t0", lsRes.stdout());

        var globRes = sandbox.translateForProbe(findTemplate("memory/", "*.md"));
        assertNotNull(globRes);
        assertEquals("memory/2026-11-24.md\t20\t0", globRes.stdout());
    }

    @Test
    void testExistsMultiTrailingSlashDirAndKvFileTrailingSlash() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        var reader = mock(WorkspaceReader.class);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(
            new EntryInfo("/workspace/skills/my-skill", 493, "root", "root", 0, epoch(), epoch(), "directory")));
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            new EntryInfo("/workspace/skills", 493, "root", "root", 0, epoch(), epoch(), "directory")));
        when(files.listDirectory("/workspace/memory")).thenReturn(List.of());
        when(reader.readMemoryFile("u1", "MEMORY.md")).thenReturn("# mem");
        when(reader.readMemoryFile("u1", "memory/2026-11-24.md")).thenReturn("daily");
        when(reader.listRuntimeFiles("u1")).thenReturn(Map.of(
            "MEMORY.md", 5,
            "memory/2026-11-24.md", 5));
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null, null, null, null, false, false, true, false);
        sandbox.setUserKey("u1");

        var multiDir = sandbox.translateForProbe("test -e '/workspace/skills/my-skill//' && echo yes || echo no");
        assertNotNull(multiDir);
        assertEquals("yes", multiDir.stdout());

        var kvRootFileSlash = sandbox.translateForProbe("test -e 'MEMORY.md/' && echo yes || echo no");
        assertNotNull(kvRootFileSlash);
        assertEquals("no", kvRootFileSlash.stdout());

        var kvDailyFileSlash = sandbox.translateForProbe("test -e 'memory/2026-11-24.md/' && echo yes || echo no");
        assertNotNull(kvDailyFileSlash);
        assertEquals("no", kvDailyFileSlash.stdout());

        var kvMemDirSlash = sandbox.translateForProbe("test -e 'memory//' && echo yes || echo no");
        assertNotNull(kvMemDirSlash);
        assertEquals("yes", kvMemDirSlash.stdout());
    }

    @Test
    void readThroughRootGlobShouldIncludeKvMemorySubdirFiles() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        var reader = mock(WorkspaceReader.class);
        when(files.listDirectory("/workspace")).thenReturn(List.of(
            new EntryInfo("/workspace/README.md", 420, "root", "root", 17, epoch(), epoch(), "file")));
        when(reader.listRuntimeFiles("u1")).thenReturn(Map.of(
            "MEMORY.md", 42,
            "memory/2026-11-24.md", 11));
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null, null, null, null, false, false, true, false);
        sandbox.setUserKey("u1");

        var globRes = sandbox.translateForProbe(findTemplate("/workspace", "*.md"));
        assertNotNull(globRes);
        assertTrue(globRes.stdout().contains("MEMORY.md\t42\t0"), globRes.stdout());
        assertTrue(globRes.stdout().contains("memory/2026-11-24.md\t11\t0"), globRes.stdout());
        assertTrue(globRes.stdout().contains("README.md\t17\t"), globRes.stdout());
    }

    @Test
    void translateExistsShouldPreserveLeadingSlashForNonWorkspaceAbsolutePathsAndWorkspaceRoot() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mockOsb(osb);
        when(files.readByteArray("/etc/hosts")).thenReturn("127.0.0.1 localhost".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(files.readByteArray("/workspace")).thenThrow(new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxInternalException("Cannot buffer entire body", null));
        when(files.listDirectory("/workspace")).thenReturn(List.of());

        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var r1 = sandbox.translateForProbe("test -e '/etc/hosts' && echo yes || echo no");
        assertNotNull(r1);
        assertEquals("yes", r1.stdout().trim());
        verify(files).readByteArray("/etc/hosts");

        var r2 = sandbox.translateForProbe("test -e '/workspace' && echo yes || echo no");
        assertNotNull(r2);
        assertEquals("yes", r2.stdout().trim());
        verify(files).listDirectory("/workspace");
    }

    @Test
    void translateExistsShouldFallBackToRealExecForDotAndDotDotSegments643() {
        var osb = mock(Sandbox.class);
        mockOsb(osb);
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        for (String p : List.of(".", "/workspace/.", "/workspace/skills/.", "/workspace/skills/..")) {
            assertNull(sandbox.translateForProbe("test -e '" + p + "' && echo yes || echo no"),
                "dot segment path " + p + " must fall back to real exec");
        }
    }
}
