package io.agentmanager.framework.util;

/**
 * 路径安全工具类：确保字符串可作为跨平台（含 Windows）的文件路径段使用。
 *
 * <p>Windows 不允许 {@code : * ? " < > |} 及控制字符出现在路径段中；
 * 本工具统一替换为下划线，保证传给 SDK（LocalFilesystem / WorkspaceManager）的值
 * 不会触发 {@link java.nio.file.InvalidPathException}。
 */
public final class PathSafe {

    private PathSafe() {}

    /** Windows 非法字符 + 反斜杠 + 控制字符的正则，编译一次复用 */
    private static final java.util.regex.Pattern UNSAFE =
        java.util.regex.Pattern.compile("[:*?\"<>|\\\\\\p{Cntrl}]");

    /**
     * 将字符串安全化为路径段。
     *
     * <ul>
     *   <li>null / blank → "unknown"</li>
     *   <li>Windows 非法字符（: * ? " &lt; &gt; |）及控制字符 → 下划线</li>
     *   <li>路径遍历 {@code ..} → 下划线</li>
     *   <li>首尾空白去除；连续空白折叠为单空格</li>
     *   <li>超 64 字符截断</li>
     *   <li>全部被清理后 → "unknown"</li>
     * </ul>
     */
    public static String sanitize(String key) {
        if (key == null || key.isBlank()) return "unknown";
        var s = UNSAFE.matcher(key).replaceAll("_");
        s = s.replace("..", "_");
        s = s.trim();
        s = s.replaceAll("\\s+", " ");
        if (s.isEmpty() || s.matches("^_+$")) return "unknown";
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }
}
