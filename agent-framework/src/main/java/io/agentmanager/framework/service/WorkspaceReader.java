package io.agentmanager.framework.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;

/**
 * 运行时工作区文件读写：MEMORY.md / memory/ 的 KV 读取与沙箱注入。
 *
 * KV 命名空间与 RemoteFilesystemSpec(IsolationScope.USER) 一致：
 * 直接复用框架 RemoteFilesystem(baseStore, List.of(userId))，避免手工拼接 key。
 */
@Service
public class WorkspaceReader {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceReader.class);

    /** 运行时文件路径（与回写范围对称） */
    public static final String MEMORY_FILE = "MEMORY.md";
    public static final String MEMORY_DIR = "memory";

    private final BaseStore baseStore;

    public WorkspaceReader(DistributedStore distributedStore) {
        this.baseStore = distributedStore.baseStore();
    }

    /**
     * 从 KV 读取某用户的运行时文件（MEMORY.md + memory/*.md）。
     * 返回相对路径 → 内容字节。
     */
    public Map<String, byte[]> readRuntimeFiles(String userId) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        var ctx = RuntimeContext.builder().userId(userId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, List.of(userId));

            // 直接 read 判断存在（exists() 对相对路径返回 false，不可靠）
            var memoryRead = fs.read(ctx, MEMORY_FILE, 0, -1);
            if (memoryRead.isSuccess() && memoryRead.fileData() != null && memoryRead.fileData().content() != null) {
                files.put(MEMORY_FILE, memoryRead.fileData().content().getBytes(StandardCharsets.UTF_8));
            }

            var ls = fs.ls(ctx, "/" + MEMORY_DIR);
            if (ls.isSuccess()) {
                for (var info : ls.entries()) {
                    // RemoteFilesystem.ls 返回前导 "/" 的完整相对路径，read 需去掉
                    var rel = info.path().startsWith("/") ? info.path().substring(1) : info.path();
                    if (rel.endsWith(".md")) {
                        var read = fs.read(ctx, rel, 0, -1);
                        if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
                            files.put(rel, read.fileData().content().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read runtime files for user {}: {}", userId, e.getMessage());
        }
        return files;
    }

    /**
     * 将运行时文件注入沙箱 /workspace（SDK 文件 API）。
     * 静态模板（AGENTS.md/skills/ 等）由框架投影注入，不在此处理。
     */
    public void injectToSandbox(com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                                Map<String, byte[]> files) {
        if (files.isEmpty()) {
            return;
        }
        var entries = files.entrySet().stream()
            .map(e -> com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path("/workspace/" + e.getKey())
                .data(new String(e.getValue(), StandardCharsets.UTF_8))
                .mode(644)
                .build())
            .toList();
        osbSandbox.files().write(entries);
        log.info("Injected {} runtime files into sandbox", entries.size());
    }
}
