package io.agentmanager.framework.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ThreadController {

    @GetMapping("/threads")
    public List<Map<String, Object>> listThreads() {
        return Collections.emptyList();
    }
}
