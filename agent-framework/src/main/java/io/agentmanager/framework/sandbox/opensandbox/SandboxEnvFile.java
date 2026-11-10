package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 沙箱 {@code EXECD_ENVS} 文件读写（docs/sandbox-env-update-design.md §6）。
 *
 * <p>生效机制：execd bootstrap 默认设置 {@code EXECD_ENVS=/opt/opensandbox/.env} 并 touch
 * （{@code components/execd/bootstrap.sh:492-503}）；每条命令执行前 {@code UserEnvOverlay}
 * 重新读取该文件。接口只做「读取原文 → 追加变更行 → 原子替换」：
 * <ul>
 *   <li>保留镜像脚本启动时追加的 {@code PATH}/{@code JAVA_HOME}/{@code GOROOT} 行
 *       （code-interpreter.sh:154-156 / code-interpreter-env.sh:37-42）；</li>
 *   <li>保留之前未修改的键；同名键追加新行，execd 顺序解析、后面的行覆盖前面的行；</li>
 *   <li>接口管理的键记录在 {@code /tmp/.agent-env.json}（仅用于合并与响应，不参与 execd 读取）。</li>
 * </ul>
 *
 * <p><b>值格式（跨 execd 版本兼容）</b>：按裸 {@code KEY=value} 渲染，<b>不包裹引号</b>。
 * 已发布的 execd（server/v0.2.x 线、2026-09-17 前构建）<b>不解析引号</b>，{@code KEY='v'}
 * 会被原样读成 {@code 'v'}（实测：值含引号且 {@code $} 仍被展开）；新版 execd 才支持
 * dotenv 引号语义。裸渲染在旧/新版本下语义一致，因此值内禁止：
 * <ul>
 *   <li>{@code $}：两版裸值分支都走 {@code os.ExpandEnv}，且无转义语法（{@code $$} 也不是转义），
 *       无法无损表达；</li>
 *   <li>换行/回车：按行解析会截断条目；</li>
 *   <li>首尾空白：旧版逐行 {@code TrimSpace}、新版 {@code TrimSpace(值)}，口径不一；</li>
 *   <li>首字符 {@code '} / {@code "}：新版按引号值解析（剥引号）、旧版按字面保留，语义分叉。</li>
 * </ul>
 * 值内的单引号（非首字符）两版均按字面保留，允许。新规则仅作用于本次请求写入的值：
 * sidecar 存量键（7a43d44 之前旧规则写入）按 legacy 口径放行，不阻断升级后的 PUT
 * （issue #288，见 {@link #validateMerged}）。
 */
@Component
public class SandboxEnvFile {

    private static final Logger log = LoggerFactory.getLogger(SandboxEnvFile.class);

    /** 接口管理键集合（execd 不读该文件，仅用于合并与响应） */
    public static final String MANAGED_JSON_PATH = "/tmp/.agent-env.json";

    /** 路径发现命令：{@code printf %s} 避免 echo 的尾部换行 */
    public static final String DISCOVER_CMD = "printf %s \"$EXECD_ENVS\"";

    /** 生效文件路径合法性：绝对路径、常规字符；防拼进 {@code mv} 命令注入 */
    private static final Pattern ENV_PATH = Pattern.compile("^/[A-Za-z0-9._/-]+$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    /** execd 自身管理、不允许经接口覆盖的平台键 */
    private static final Set<String> RESERVED_KEYS = Set.of("EXECD_API_GRACE_SHUTDOWN", "EXECD_ENVS");

    private static final int MAX_KEYS = 64;
    private static final int MAX_TOTAL_BYTES = 32 * 1024;
    private static final int MAX_VALUE_BYTES = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 合并结果：managed = 全部接口管理键（含历史），updatedKeys = 本次实际变化的键 */
    public record MergeResult(Map<String, String> managed, List<String> updatedKeys) {
    }

    /**
     * 读取沙箱内 {@code $EXECD_ENVS} 实际路径（bootstrap 默认 {@code /opt/opensandbox/.env}）。
     * 空/非法 → 409 {@code execd_env_file_unsupported}；命令失败 → 502。
     */
    public String discoverEnvPath(Sandbox sandbox) {
        String out;
        try {
            out = stdoutOf(sandbox.commands().run(DISCOVER_CMD));
        } catch (Exception e) {
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to discover EXECD_ENVS: " + e.getMessage());
        }
        if (out == null || out.isBlank()) {
            throw new SandboxEnvException("execd_env_file_unsupported", 409,
                "EXECD_ENVS is not set in sandbox (bootstrap too old or sandbox image unsupported)");
        }
        String path = out.trim();
        if (!ENV_PATH.matcher(path).matches() || path.contains("..")) {
            throw new SandboxEnvException("execd_env_file_unsupported", 409,
                "illegal EXECD_ENVS path in sandbox");
        }
        return path;
    }

    /**
     * 合并式写入：读 `.json` 管理键 → 合并 → 校验 → 读 `<envPath>` 原文 → 追加变更行 →
     * 临时文件 + {@code mv -f} 原子发布 → 写 `.json`。返回合并结果供响应组装。
     *
     * <p><b>写序（issue #230）</b>：先发布生效文件、后写管理键 sidecar——变更判定只比对
     * sidecar，若 sidecar 先写而发布失败，调用方按设计文档重试时判定为「无变化」静默
     * no-op，生效文件永久缺键。调序后失败矩阵收敛：
     * <ul>
     *   <li>发布失败 → sidecar 未推进 → 重试重新检出变化、重新发布；</li>
     *   <li>发布成功而 sidecar 写失败 → 重试重复追加一行 {@code K=V}
     *       （execd 顺序解析取最后出现的行，幂等收敛）。</li>
     * </ul>
     */
    public MergeResult mergeAndWrite(Sandbox sandbox, String envPath, Map<String, String> incoming) {
        validateIncoming(incoming);
        String original = readOriginal(sandbox, envPath);
        Map<String, String> managed = new LinkedHashMap<>(readManaged(sandbox));
        List<String> updatedKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String oldValue = managed.get(entry.getKey());
            if (oldValue == null || !oldValue.equals(entry.getValue())) {
                updatedKeys.add(entry.getKey());
            }
            managed.put(entry.getKey(), entry.getValue());
        }
        validateMerged(managed, incoming.keySet());
        if (!updatedKeys.isEmpty()) {
            // 只追加实际变化的键：无变化的 PUT 不重复追加行（未修改键原样保留、execd 取最后出现的行）
            Map<String, String> changed = new LinkedHashMap<>();
            for (String key : updatedKeys) {
                changed.put(key, incoming.get(key));
            }
            // 先发布生效文件再写 sidecar（issue #230）：发布失败时 sidecar 未推进，重试可重新检出变化
            publish(sandbox, envPath, appendLines(original, renderLines(changed)));
        }
        writeManaged(sandbox, managed);
        return new MergeResult(managed, List.copyOf(updatedKeys));
    }

    // ==================== 校验 ====================

    /** 入参加校验（合并前，新规则全量）；合并结果上限校验见 {@link #validateMerged} */
    private void validateIncoming(Map<String, String> incoming) {
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            validateKey(entry.getKey());
            validateValue(entry.getKey(), entry.getValue(), true);
            totalBytes += utf8Length(entry.getKey()) + utf8Length(entry.getValue());
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw limitExceeded("env payload exceeds " + MAX_TOTAL_BYTES + " bytes");
        }
    }

    /**
     * 合并结果校验（键数/总字节/每键），失败不产生部分写入。
     *
     * <p><b>存量键 legacy 口径（issue #288）</b>：本次请求未触及的 sidecar 历史键按宽松规则
     * 回放——7a43d44 之前的旧规则允许 {@code $}/换行/首尾空白/前导引号，这些值已合法持久化；
     * 若按新规则整体回放，升级后任何一次 PUT（哪怕只改无关键）都会被永久 400/409 阻断，且
     * mergeAndWrite 无删除键通路、错误信息不带键名无法定位。存量值仅存 sidecar（不参与
     * {@code .env} 渲染，见 {@link #mergeAndWrite} 只追加 {@code changed} 行），放行不破坏
     * 生效文件语义；用户更新该键时新值必须满足新规则（自然收敛清洗）。
     *
     * @param incomingKeys 本次请求触及的键（其值已按新规则全量校验，替换存量值后仍按新规校验）
     */
    private void validateMerged(Map<String, String> merged, Set<String> incomingKeys) {
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            validateKey(entry.getKey());
            validateValue(entry.getKey(), entry.getValue(), incomingKeys.contains(entry.getKey()));
            totalBytes += utf8Length(entry.getKey()) + utf8Length(entry.getValue());
        }
        if (merged.size() > MAX_KEYS) {
            throw limitExceeded("merged env has " + merged.size() + " keys (max " + MAX_KEYS + ")");
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw limitExceeded("merged env exceeds " + MAX_TOTAL_BYTES + " bytes");
        }
    }

    private void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw invalid("invalid_env_key", "env key '" + key + "' must match ^[A-Za-z_][A-Za-z0-9_]{0,127}$");
        }
        if (RESERVED_KEYS.contains(key)) {
            throw invalid("reserved_env_key", "env key '" + key + "' is reserved by execd/platform");
        }
    }

    /**
     * 单值校验（错误信息带键名定位违规值，issue #288）。
     *
     * @param strictFormat false 仅用于 sidecar 存量键回放：跳过 7a43d44 新增的四条跨版本
     *                     格式规则（$/换行/首尾空白/前导引号），null/超长/NUL 不变量仍保留
     *                     （旧规则同款，存量值恒满足）
     */
    private void validateValue(String key, String value, boolean strictFormat) {
        if (value == null) {
            throw invalid("invalid_env_value", "env value of '" + key + "' must not be null");
        }
        if (utf8Length(value) > MAX_VALUE_BYTES) {
            throw invalid("invalid_env_value", "env value of '" + key + "' exceeds " + MAX_VALUE_BYTES + " bytes");
        }
        if (value.indexOf('\0') >= 0) {
            throw invalid("invalid_env_value", "env value of '" + key + "' must not contain NUL");
        }
        if (!strictFormat) {
            return;
        }
        // 行式格式：值内换行会截断条目并注入后续行
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw invalid("invalid_env_value", "env value of '" + key + "' must not contain newline");
        }
        // 旧/新 execd 的裸值分支都做 os.ExpandEnv，且无转义语法（$$ 会被解析为空）
        if (value.indexOf('$') >= 0) {
            throw new SandboxEnvException("unsupported_env_value", 400,
                "env value of '" + key + "' must not contain '$' (EXECD_ENVS expands $VARS with no escape syntax)");
        }
        // 首尾空白在旧版（逐行 TrimSpace）与新版（TrimSpace(值)）口径不一致
        if (!value.equals(value.strip())) {
            throw invalid("invalid_env_value", "env value of '" + key + "' must not have leading/trailing whitespace");
        }
        // 新版 execd 以首个非空白字符判定引号值并剥引号；旧版按字面保留 → 语义分叉
        if (!value.isEmpty() && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            throw new SandboxEnvException("unsupported_env_value", 400,
                "env value of '" + key + "' must not start with a quote (EXECD_ENVS quote semantics differ across versions)");
        }
    }

    private SandboxEnvException invalid(String code, String message) {
        return new SandboxEnvException(code, 400, message);
    }

    private SandboxEnvException limitExceeded(String message) {
        return new SandboxEnvException("env_limit_exceeded", 400, message);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    // ==================== 渲染与发布 ====================

    /**
     * 渲染变更行：每行裸 {@code KEY=value}（不包裹引号）——旧版 execd 不剥引号、新版支持
     * dotenv 引号，裸值在两者下语义一致（见类注释「值格式」）。
     */
    static String renderLines(Map<String, String> incoming) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 追加到原文末尾（保证原文以换行结尾）；原文件所有行原样保留 */
    static String appendLines(String original, String lines) {
        StringBuilder sb = new StringBuilder(original == null ? "" : original);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(lines);
        return sb.toString();
    }

    private void publish(Sandbox sandbox, String envPath, String content) {
        String tmpPath = envPath + ".tmp." + UUID.randomUUID();
        try {
            sandbox.files().write(List.of(WriteEntry.builder()
                .path(tmpPath)
                .data(content.getBytes(StandardCharsets.UTF_8))
                .mode(600)
                .build()));
            Execution mv = sandbox.commands().run("mv -f '" + tmpPath + "' '" + envPath + "'");
            Integer exitCode = mv.getExitCode();
            if (exitCode == null || exitCode != 0) {
                throw new SandboxEnvException("sandbox_unavailable", 502,
                    "failed to publish env file (mv exit " + exitCode + ")");
            }
        } catch (SandboxEnvException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to publish env file: " + e.getMessage());
        }
    }

    // ==================== 读取 ====================

    /** 读取生效文件原文；404/空 → 空串；其他失败 → 502（不静默清空） */
    private String readOriginal(Sandbox sandbox, String envPath) {
        try {
            String text = sandbox.files().readFile(envPath);
            return text == null ? "" : text;
        } catch (SandboxApiException e) {
            if (notFound(e)) {
                return "";
            }
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to read env file: " + e.getMessage());
        } catch (SandboxEnvException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to read env file: " + e.getMessage());
        }
    }

    /** 读取接口管理键；404 → 空；JSON 损坏 → fail-soft 从空开始（旧文件行仍保留，不丢 env） */
    private Map<String, String> readManaged(Sandbox sandbox) {
        try {
            String json = sandbox.files().readFile(MANAGED_JSON_PATH);
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (SandboxApiException e) {
            if (notFound(e)) {
                return Map.of();
            }
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to read managed env json: " + e.getMessage());
        } catch (SandboxEnvException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[sandbox-env] managed json unreadable, starting from empty: {}", e.getMessage());
            return Map.of();
        }
    }

    private void writeManaged(Sandbox sandbox, Map<String, String> managed) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(managed);
            sandbox.files().write(List.of(WriteEntry.builder()
                .path(MANAGED_JSON_PATH)
                .data(bytes)
                .mode(600)
                .build()));
        } catch (Exception e) {
            throw new SandboxEnvException("sandbox_unavailable", 502,
                "failed to write managed env json: " + e.getMessage());
        }
    }

    private static boolean notFound(SandboxApiException e) {
        Integer status = e.getStatusCode();
        return status != null && status == 404;
    }

    private static String stdoutOf(Execution execution) {
        if (execution.getLogs() == null || execution.getLogs().getStdout() == null) {
            return "";
        }
        return execution.getLogs().getStdout().stream()
            .map(OutputMessage::getText)
            .collect(Collectors.joining());
    }
}
