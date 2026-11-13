package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageSourceFingerprintTest {

    @TempDir
    Path dir;

    @Test
    void shouldStayStableAcrossCallsOnUntouchedSource() throws Exception {
        write(dir.resolve("demo-a/SKILL.md"), "# demo a\n");
        write(dir.resolve("demo-a/scripts/run.py"), "print(1)\n");
        write(dir.resolve("demo-b/SKILL.md"), "# demo b\n");

        var first = PackageSourceFingerprint.of(dir);
        var second = PackageSourceFingerprint.of(dir);

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertTrue(first.matches("[0-9a-f]{16}"));
    }

    @Test
    void shouldMatchLegacyAlgorithmOutput() throws Exception {
        write(dir.resolve("demo-a/SKILL.md"), "# demo a\n");
        write(dir.resolve("demo-a/scripts/run.py"), "print(1)\n");
        write(dir.resolve("demo-b/SKILL.md"), "# demo b\n");

        assertEquals(legacyPackageSourceFingerprint(dir), PackageSourceFingerprint.of(dir));
    }

    @Test
    void shouldChangeWhenContentChanges() throws Exception {
        write(dir.resolve("demo-a/SKILL.md"), "# demo a\n");
        var before = PackageSourceFingerprint.of(dir);

        write(dir.resolve("demo-a/SKILL.md"), "# demo a changed\n");

        assertNotEquals(before, PackageSourceFingerprint.of(dir));
    }

    @Test
    void shouldChangeWhenFileAddedOrRemoved() throws Exception {
        write(dir.resolve("demo-a/SKILL.md"), "# demo a\n");
        var before = PackageSourceFingerprint.of(dir);

        write(dir.resolve("demo-b/SKILL.md"), "# demo b\n");
        var afterAdd = PackageSourceFingerprint.of(dir);

        assertNotEquals(before, afterAdd);

        Files.delete(dir.resolve("demo-b/SKILL.md"));
        assertEquals(before, PackageSourceFingerprint.of(dir));
    }

    @Test
    void digestShouldChangeWhenDisabledOverlayChanges() throws Exception {
        write(dir.resolve("demo-a/SKILL.md"), "# demo a\n");

        var base = PackageSourceFingerprint.digest(dir, List.of());
        var withDisabled = PackageSourceFingerprint.digest(dir, List.of("disabled|demo-a"));
        var withTwo = PackageSourceFingerprint.digest(dir, List.of("disabled|demo-a", "disabled|demo-c"));

        assertNotEquals(base, withDisabled);
        assertNotEquals(withDisabled, withTwo);
        assertEquals(withDisabled, PackageSourceFingerprint.digest(dir, List.of("disabled|demo-a")));
    }

    @Test
    void digestShouldTreatMissingDirAsStableEmptySource() {
        var missing = dir.resolve("nope");

        assertEquals(PackageSourceFingerprint.digest(missing, List.of()),
            PackageSourceFingerprint.digest(missing, List.of()));
        assertEquals(16, PackageSourceFingerprint.digest(missing, List.of()).length());
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String legacyPackageSourceFingerprint(Path pkgDir) throws Exception {
        var lines = new ArrayList<String>();
        try (var walk = Files.walk(pkgDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(p ->
                lines.add(pkgDir.relativize(p) + "|" + mtimeOf(p) + "|" + sizeOf(p)));
        }
        lines.sort(String::compareTo);
        var md = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        var digest = md.digest();
        var sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.substring(0, 16);
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
