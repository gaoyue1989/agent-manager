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
}
