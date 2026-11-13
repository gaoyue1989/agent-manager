package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillBundleCacheTest {

    private static final byte[] GZ_A = "gz-a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GZ_B = "gz-b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GZ_C = "gz-c".getBytes(StandardCharsets.UTF_8);
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_A1 = "a1".repeat(32);
    private static final String SHA_A2 = "a2".repeat(32);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @TempDir
    Path dir;

    @Test
    void putThenGetShouldRoundTripShaAndSize() throws IOException {
        var cache = new SkillBundleCache(dir);

        cache.put("aaaa", GZ_A, SHA_A);
        var entry = cache.get("aaaa").orElseThrow();

        assertEquals(SHA_A, entry.sha256());
        assertEquals(GZ_A.length, entry.size());
        assertArrayEquals(GZ_A, Files.readAllBytes(entry.file()));
    }

    @Test
    void getShouldReturnEmptyForUnknownVersion() throws IOException {
        var cache = new SkillBundleCache(dir);

        assertTrue(cache.get("nope").isEmpty());
    }

    @Test
    void getShouldReturnEmptyWhenSidecarMissing() throws IOException {
        var cache = new SkillBundleCache(dir);
        cache.put("aaaa", GZ_A, SHA_A);
        Files.delete(dir.resolve("aaaa.sha256"));

        assertTrue(cache.get("aaaa").isEmpty());
    }

    @Test
    void putShouldKeepOnlyTwoMostRecentVersions() throws IOException {
        var cache = new SkillBundleCache(dir);

        cache.put("aaaa", GZ_A, SHA_A);
        Files.setLastModifiedTime(dir.resolve("aaaa.tar.gz"), FileTime.fromMillis(1000));
        cache.put("bbbb", GZ_B, SHA_B);
        Files.setLastModifiedTime(dir.resolve("bbbb.tar.gz"), FileTime.fromMillis(2000));
        cache.put("cccc", GZ_C, SHA_C);

        assertEquals(Set.of("bbbb.tar.gz", "cccc.tar.gz", "bbbb.sha256", "cccc.sha256"),
            bundleCacheFiles());
        assertTrue(cache.get("aaaa").isEmpty());
        assertTrue(cache.get("bbbb").isPresent());
        assertTrue(cache.get("cccc").isPresent());
    }

    @Test
    void putShouldOverwriteSameVersionAtomically() throws IOException {
        var cache = new SkillBundleCache(dir);
        cache.put("aaaa", GZ_A, SHA_A1);

        cache.put("aaaa", GZ_B, SHA_A2);

        var entry = cache.get("aaaa").orElseThrow();
        assertEquals(SHA_A2, entry.sha256());
        assertArrayEquals(GZ_B, Files.readAllBytes(entry.file()));
        assertEquals(1, bundleCacheFiles().stream().filter(f -> f.endsWith(".tar.gz")).count());
    }

    private Set<String> bundleCacheFiles() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
