package io.agentmanager.framework.service;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;

/**
 * 工具结果数据帧的媒体载荷统一提取（issue #492）：DataBlock（SDK 原生二进制结果，
 * file-upload-download-plan §10）与 ImageBlock（MCP ImageContent 的转换产物）共用
 * media_type/data/url 词表；各协议面序列化点从本提取结果按自身帧格式填充。
 */
public final class ToolResultMedia {

    /** 提取结果：值与 tool_result_data_delta 帧字段一一对应；mediaType 为 null 即非媒体块 */
    public record Payload(String mediaType, String data, String url) {
    }

    private static final Payload EMPTY = new Payload(null, null, null);

    private ToolResultMedia() {
    }

    public static Payload extract(ContentBlock content) {
        if (content instanceof DataBlock block) {
            return fromSource(block.getSource());
        }
        if (content instanceof ImageBlock image) {
            return fromSource(image.getSource());
        }
        return EMPTY;
    }

    private static Payload fromSource(io.agentscope.core.message.Source source) {
        if (source instanceof Base64Source base64) {
            return new Payload(base64.getMediaType(), base64.getData(), null);
        }
        if (source instanceof URLSource urlSource) {
            return new Payload(urlSource.getMimeType(), null, urlSource.getUrl());
        }
        return EMPTY;
    }
}
