package io.agentmanager.framework.util;

import java.nio.charset.StandardCharsets;

/**
 * 文件名字节预算（issue #425/#499 单一真相源）：存储落盘末段形如
 * {@code {uuid36}-{name}.{uuid8}.tmp}，ext4 单段上限 255 字节 →
 * name ≤ 255 - 36 - 1 - 1 - 8 - 4 = 205 字节。
 *
 * <p>上传侧（FileController.sanitizeFileName）与产出登记侧（FileTools.presentFile 的
 * storageKey/file_name）共用同一常量与截断函数，保证两侧交付名同预算同语义：
 * 截 stem 保扩展名、UTF-8 字符边界回退（残缺序列不得产出 U+FFFD 反超预算）。
 */
public final class FileNameBudget {

    /** 文件名字节预算：预留 {uuid36}- 前缀与 .{uuid8}.tmp 原子写后缀共 50 字节 */
    public static final int MAX_FILE_NAME_BYTES = 255 - 36 - 1 - 1 - 8 - 4;

    private FileNameBudget() {}

    /** UTF-8 字节预算截断：回退到最后一个完整字符边界（残缺序列不得产出替换字符） */
    public static String truncateUtf8Bytes(String s, int maxBytes) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    /**
     * 按预算截断文件名，超预算时截 stem 保扩展名（下游扩展名/MIME 关联依赖）。
     * 预算内原名返回。
     */
    public static String truncatePreservingExtension(String name) {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_FILE_NAME_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        var ext = dot > 0 ? truncateUtf8Bytes(name.substring(dot), MAX_FILE_NAME_BYTES) : "";
        return truncateUtf8Bytes(dot > 0 ? name.substring(0, dot) : name,
            MAX_FILE_NAME_BYTES - ext.getBytes(StandardCharsets.UTF_8).length) + ext;
    }
}
