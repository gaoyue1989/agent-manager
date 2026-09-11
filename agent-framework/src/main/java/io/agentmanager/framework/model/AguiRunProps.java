package io.agentmanager.framework.model;

import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI forwardedProps 平台扩展解析（agui-migration-plan §5.1）。
 *
 * <p>forwardedProps 为非可信身份来源、仅传输用途：userId 缺省 "webui"，
 * fileIds 为 /files/upload 返回的 fileId 列表（上限对齐 FILE_UPLOAD pending 上限 20）。
 * 解析失败抛 {@link IllegalArgumentException}，由 controller 映射 HTTP 400。
 */
public record AguiRunProps(String userId, List<String> fileIds) {

    private static final int MAX_USER_ID_LENGTH = 128;
    private static final int MAX_FILE_IDS = 20;

    public static AguiRunProps from(RunAgentInput input) {
        var rawUserId = asString(input.getForwardedProp("userId"));
        var userId = rawUserId != null && !rawUserId.isBlank() ? rawUserId.trim() : "webui";
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException(
                "forwardedProps.userId too long (max " + MAX_USER_ID_LENGTH + ")");
        }

        var rawFileIds = input.getForwardedProp("fileIds");
        if (rawFileIds == null) {
            return new AguiRunProps(userId, List.of());
        }
        if (!(rawFileIds instanceof List<?> rawList)) {
            throw new IllegalArgumentException("forwardedProps.fileIds must be an array of strings");
        }
        if (rawList.size() > MAX_FILE_IDS) {
            throw new IllegalArgumentException(
                "forwardedProps.fileIds too many (max " + MAX_FILE_IDS + ")");
        }
        var fileIds = new ArrayList<String>(rawList.size());
        for (var raw : rawList) {
            var fileId = raw == null ? null : String.valueOf(raw).trim();
            if (fileId == null || fileId.isEmpty()) {
                throw new IllegalArgumentException("forwardedProps.fileIds contains blank id");
            }
            fileIds.add(fileId);
        }
        return new AguiRunProps(userId, List.copyOf(fileIds));
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
