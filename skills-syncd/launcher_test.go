package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

const (
	testLauncherSource = "docker/skillsync-launcher.sh"
	testLogCapBytes    = 4096
	testPrefillLine    = "PREFILL padded-to-sixty-bytes xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n"
	testTailMarker     = "TAIL-KEEP-MARKER-last-prefill-line-must-survive-truncation\n"
)

type testLauncher struct {
	logPath string
}

func mustReplace(t *testing.T, s, old, replacement string) string {
	t.Helper()
	if !strings.Contains(s, old) {
		t.Fatalf("launcher copy: pattern %q not found in %s", old, testLauncherSource)
	}
	return strings.ReplaceAll(s, old, replacement)
}

func startTestLauncher(t *testing.T, capBytes int) *testLauncher {
	t.Helper()
	dir := t.TempDir()
	src, err := os.ReadFile(testLauncherSource)
	if err != nil {
		t.Fatalf("read launcher source: %v", err)
	}
	fakePath := filepath.Join(dir, "fake-skills-syncd")
	fake := "#!/bin/sh\ni=0\nwhile [ \"$i\" -lt 100 ]; do\n    echo \"skills-syncd: fake ver line padded aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa $i\" >&2\n    i=$((i + 1))\n    sleep 0.2\ndone\n"
	if err := os.WriteFile(fakePath, []byte(fake), 0o755); err != nil {
		t.Fatalf("write fake daemon: %v", err)
	}
	logPath := filepath.Join(dir, "skills-syncd.log")
	script := string(src)
	script = mustReplace(t, script, "log_file=/var/log/skills-syncd.log", "log_file="+logPath)
	script = mustReplace(t, script, "log_max_bytes=$((1024 * 1024))", fmt.Sprintf("log_max_bytes=%d", capBytes))
	script = mustReplace(t, script, "/usr/local/bin/skills-syncd", fakePath)
	script = mustReplace(t, script, "sleep 30", "sleep 1")
	scriptPath := filepath.Join(dir, "launcher.sh")
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		t.Fatalf("write launcher copy: %v", err)
	}
	if out, err := exec.Command("sh", "-n", scriptPath).CombinedOutput(); err != nil {
		t.Fatalf("launcher copy syntax check: %v: %s", err, out)
	}
	env := make([]string, 0, len(os.Environ())+1)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "SKILLSYNC_") {
			env = append(env, kv)
		}
	}
	env = append(env, "SKILLSYNC_BASE_URL=http://skillsync.invalid")
	cmd := exec.Command("sh", scriptPath, "sleep", "300")
	cmd.Env = env
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		t.Fatalf("start launcher: %v", err)
	}
	t.Cleanup(func() {
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		_ = cmd.Wait()
		_ = exec.Command("pkill", "-f", fakePath).Run()
	})
	return &testLauncher{logPath: logPath}
}

func waitForLogTrim(t *testing.T, logPath string, prefillBytes int) int64 {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for {
		info, err := os.Stat(logPath)
		if err != nil {
			t.Fatalf("stat log: %v", err)
		}
		if info.Size() < int64(prefillBytes) {
			return int64(info.Size())
		}
		if time.Now().After(deadline) {
			t.Fatalf("log never trimmed: size=%d prefill=%d", info.Size(), prefillBytes)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func requireNoTmpLeftover(t *testing.T, logPath string) {
	t.Helper()
	if _, err := os.Stat(logPath + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("stray tmp file left behind: %s.tmp", logPath)
	}
}

func TestLauncherLogCapTrimsAndKeepsTail(t *testing.T) {
	l := startTestLauncher(t, testLogCapBytes)
	prefill := strings.Repeat(testPrefillLine, 120) + testTailMarker
	if err := os.WriteFile(l.logPath, []byte(prefill), 0o644); err != nil {
		t.Fatalf("prefill log: %v", err)
	}
	trimmed := waitForLogTrim(t, l.logPath, len(prefill))
	if trimmed > testLogCapBytes {
		t.Fatalf("post-trim size %d exceeds cap %d", trimmed, testLogCapBytes)
	}
	time.Sleep(2 * time.Second)
	after, err := os.Stat(l.logPath)
	if err != nil {
		t.Fatalf("stat log after trim: %v", err)
	}
	if after.Size() < trimmed+400 {
		t.Fatalf("daemon writes did not resume after in-place trim: %d -> %d", trimmed, after.Size())
	}
	content, err := os.ReadFile(l.logPath)
	if err != nil {
		t.Fatalf("read log after trim: %v", err)
	}
	if !strings.Contains(string(content), strings.TrimSuffix(testTailMarker, "\n")) {
		t.Fatalf("tail marker lost after trim (cap dropped history instead of keeping tail)")
	}
	requireNoTmpLeftover(t, l.logPath)
}

func TestLauncherLogCapLeavesSmallLogUntouched(t *testing.T) {
	l := startTestLauncher(t, testLogCapBytes)
	head := strings.Repeat(testPrefillLine, 8)
	if err := os.WriteFile(l.logPath, []byte(head), 0o644); err != nil {
		t.Fatalf("prefill log: %v", err)
	}
	time.Sleep(4 * time.Second)
	content, err := os.ReadFile(l.logPath)
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	if !strings.HasPrefix(string(content), head) {
		t.Fatalf("small log was truncated: prefill head lost")
	}
	requireNoTmpLeftover(t, l.logPath)
}
