package io.agentmanager.framework.controller;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThreadControllerTest {

    private final ThreadController controller = new ThreadController();

    @Test
    void listThreadsShouldReturnEmptyList() {
        assertEquals(List.of(), controller.listThreads());
    }
}