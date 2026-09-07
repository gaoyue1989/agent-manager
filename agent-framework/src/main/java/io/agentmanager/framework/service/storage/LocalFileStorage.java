package io.agentmanager.framework.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 本地路径存储后端：文件落在 {FILE_STORAGE_LOCAL_DIR}/{key}。
 *
 * <p>安全与一致性（对齐 DeerFlow uploads/manager.py）：
 * <ul>
 *   <li><b>路径穿越防护</b>：key 规范化（toAbsolutePath().normalize()）后校验
 *       前缀仍是根目录，拒绝 ../ 逃逸</li>
 *   <li><b>原子写</b>：先写 {key}.tmp 再 Files.move(ATOMIC_MOVE)，读方永不看到半成品</li>
 *   <li><b>子目录惰性创建</b>：按 key 前缀（origin/yyyyMM/）自动建目录</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "agent.file", name = "storage-type",
    havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalFileStorage(io.agentmanager.framework.config.AgentManagerProperties props) {
        this(Path.of(props.file().storageLocalDir()));
    }

    /** 直接指定根目录（测试用） */
    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage root " + this.root + ": " + e.getMessage(), e);
        }
        log.info("LocalFileStorage root: {}", this.root);
    }

    @Override
    public void write(String key, InputStream in, long size, String contentType) throws IOException {
        var target = resolve(key);
        Files.createDirectories(target.getParent());
        // tmp 名带随机后缀：并发写同 key 时各线程 tmp 互不冲突（原子 move 保证最终一致性）
        var tmp = target.resolveSibling(target.getFileName() + "."
            + java.util.UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try (in) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 跨设备/文件系统不支持原子移动时降级为普通 move（语义等价，仅非原子）
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public InputStream read(String key) throws IOException {
        var target = resolve(key);
        if (!Files.exists(target)) {
            throw new IOException("storage object not found: " + key);
        }
        return Files.newInputStream(target);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        var target = resolve(key);
        Files.deleteIfExists(target);
        // 顺带清理残留的 .tmp（上次原子写中断）
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
    }

    /** 规范化 key 并校验仍在根目录内（防 ../ 穿越） */
    private Path resolve(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IOException("storage key is empty");
        }
        var p = root.resolve(key).toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IOException("storage key escapes root: " + key);
        }
        return p;
    }

    /** 暴露根目录（测试断言用） */
    public Path root() {
        return root;
    }
}