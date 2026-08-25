package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebugController.class)
class DebugControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void debugPageShouldReturnHtml() throws Exception {
        mockMvc.perform(get("/debug/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Agent Debug Console")));
    }

    @Test
    void noTrailingSlashShouldRedirectRelative() throws Exception {
        mockMvc.perform(get("/debug"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("debug/"));
    }

    @Test
    void forwardedPrefixShouldBePreservedInRedirect() throws Exception {
        mockMvc.perform(get("/debug").header("X-Forwarded-Prefix", "/agent/approval-demo"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/agent/approval-demo/debug/"));
    }
}
