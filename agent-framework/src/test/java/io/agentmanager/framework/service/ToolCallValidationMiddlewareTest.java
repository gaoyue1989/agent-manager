package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolUseBlock;

class ToolCallValidationMiddlewareTest {

    @Nested
    class IsMalformed {

        @Test
        void nullName() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_123").name(null).input(Map.of()).content("{}").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void emptyName() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_123").name("").input(Map.of()).content("{}").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void fragmentPlaceholder() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_123").name("__fragment__").input(Map.of()).content("").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void pendingPlaceholder() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_123").name("__pending__").input(Map.of()).content("").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void nullId() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id(null).name("write_file").input(Map.of()).content("{}").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void emptyId() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("").name("write_file").input(Map.of()).content("{}").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void validToolUse() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_abc").name("write_file")
                    .input(Map.of("path", "/tmp/x")).content("{\"path\":\"/tmp/x\"}").build();
            assertFalse(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void nullBlock() {
            assertTrue(ToolCallValidationMiddleware.isMalformed(null));
        }

        @Test
        void bothNameAndIdNull() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id(null).name(null).input(new HashMap<>()).content("{\"partial").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }

        @Test
        void doubleUnderscoreName() {
            ToolUseBlock tub = ToolUseBlock.builder()
                    .id("call_123").name("__anything__").input(Map.of()).content("").build();
            assertTrue(ToolCallValidationMiddleware.isMalformed(tub));
        }
    }

    @Nested
    class NormalizeToolCall {

        private static ToolUseBlock tub(String name, Map<String, Object> input, String content) {
            return ToolUseBlock.builder()
                    .id("call_1").name(name).input(input).content(content).build();
        }

        @Test
        void noChangeReturnsSameInstance() {
            ToolUseBlock original = tub("write_file",
                    Map.of("path", "a.txt", "content", "hi"), "{\"path\":\"a.txt\",\"content\":\"hi\"}");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertSame(original, nc.block());
        }

        @Test
        void pathAliasNormalized() {
            ToolUseBlock original = tub("write_file",
                    Map.of("file_path", "a.txt", "content", "hi"),
                    "{\"file_path\":\"a.txt\",\"content\":\"hi\"}");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertNotSame(original, nc.block());
            assertEquals("a.txt", nc.block().getInput().get("path"));
            assertFalse(nc.block().getInput().containsKey("file_path"));
            assertEquals("hi", nc.block().getInput().get("content"));
            // content 同步重建，含规范键
            assertEquals("a.txt", readJsonString(nc.block().getContent(), "path"));
            assertEquals("hi", readJsonString(nc.block().getContent(), "content"));
        }

        @Test
        void contentAliasNormalized() {
            ToolUseBlock original = tub("write_file",
                    Map.of("path", "a.txt", "text", "hello"),
                    "{\"path\":\"a.txt\",\"text\":\"hello\"}");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertEquals("hello", nc.block().getInput().get("content"));
            assertFalse(nc.block().getInput().containsKey("text"));
            assertEquals("hello", readJsonString(nc.block().getContent(), "content"));
        }

        @Test
        void canonicalWinsOverAlias() {
            ToolUseBlock original = tub("write_file",
                    Map.of("path", "real.txt", "file_path", "other.txt", "content", "hi"),
                    "{\"path\":\"real.txt\",\"file_path\":\"other.txt\",\"content\":\"hi\"}");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertEquals("real.txt", nc.block().getInput().get("path"));
        }

        @Test
        void contentRebuiltFromInputWhenContentEmpty() {
            // SDK accumulator 在原始 JSON 非法时把 content 回退为 "{}"，但 input 仍完整
            ToolUseBlock original = tub("write_file",
                    Map.of("path", "a.txt", "content", "body"), "{}");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertNotSame(original, nc.block());
            assertEquals("a.txt", readJsonString(nc.block().getContent(), "path"));
            assertEquals("body", readJsonString(nc.block().getContent(), "content"));
        }

        @Test
        void contentRebuiltWhenRawContentMalformed() {
            ToolUseBlock original = tub("write_file",
                    Map.of("path", "a.txt", "content", "x"),
                    "{\"path\":\"a.txt\",\"content\":\"x\""); // 截断的非法 JSON
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertEquals("a.txt", readJsonString(nc.block().getContent(), "path"));
        }

        @Test
        void zeroArgToolPassesThrough() {
            ToolUseBlock original = tub("list_files", Map.of(), "");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNull(nc.rejectionMessage());
            assertSame(original, nc.block());
        }

        @Test
        void unusableGarbageArgsRejected() {
            ToolUseBlock original = tub("write_file", Map.of(), "not-json-at-all");
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(original);
            assertNotNull(nc.rejectionMessage());
            assertNull(nc.block());
            assertTrue(nc.rejectionMessage().contains("write_file"));
        }

        @Test
        void nullToolUseRejected() {
            ToolCallValidationMiddleware.NormalizedCall nc =
                    ToolCallValidationMiddleware.normalizeToolCall(null);
            assertNotNull(nc.rejectionMessage());
        }

        @Test
        void isJsonObjectChecks() {
            assertTrue(ToolCallValidationMiddleware.isJsonObject("{\"a\":1}"));
            assertTrue(ToolCallValidationMiddleware.isJsonObject("{}"));
            assertFalse(ToolCallValidationMiddleware.isJsonObject("{"));
            assertFalse(ToolCallValidationMiddleware.isJsonObject("[1,2]"));
            assertFalse(ToolCallValidationMiddleware.isJsonObject(""));
            assertFalse(ToolCallValidationMiddleware.isJsonObject(null));
        }

        private static String readJsonString(String json, String key) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(json).get(key).asText();
            } catch (Exception e) {
                throw new AssertionError("bad json: " + json, e);
            }
        }
    }
}
