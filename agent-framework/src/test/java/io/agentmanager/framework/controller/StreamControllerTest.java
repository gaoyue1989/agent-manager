package io.agentmanager.framework.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.service.AgentRuntimeService;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @Test
    void chatStreamShouldReturnSseEvents() throws Exception {
        when(agentRuntime.invokeStream(any(), any()))
            .thenReturn(Flux.just(
                Map.of("type", "token", "token", "Hello "),
                Map.of("type", "token", "token", "World!"),
                Map.of("type", "done")
            ));

        mockMvc.perform(post("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\",\"metadata\":{}}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    void chatStreamShouldHandleEmptyMessage() throws Exception {
        when(agentRuntime.invokeStream(any(), any()))
            .thenReturn(Flux.just(Map.of("type", "done")));

        mockMvc.perform(post("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
            .andExpect(status().isOk());
    }
}
