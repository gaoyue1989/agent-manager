package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	ver1 = "1111111111111111"
	ver2 = "2222222222222222"
)

type fixtureFile struct {
	name    string
	content string
	mode    os.FileMode
}

func defaultFixture(tag string) []fixtureFile {
	return []fixtureFile{
		{name: "skill-a/", mode: 0o755},
		{name: "skill-a/SKILL.md", content: "skill a " + tag, mode: 0o644},
		{name: "skill-a/run.sh", content: "#!/bin/sh\necho " + tag + "\n", mode: 0o755},
		{name: "skill-b/", mode: 0o755},
		{name: "skill-b/SKILL.md", content: "skill b " + tag, mode: 0o644},
	}
}

func buildBundle(files []fixtureFile) ([]byte, string) {
	var tarBuf bytes.Buffer
	tw := tar.NewWriter(&tarBuf)
	for _, f := range files {
		hdr := &tar.Header{Name: f.name, Mode: int64(f.mode.Perm())}
		if strings.HasSuffix(f.name, "/") {
			hdr.Typeflag = tar.TypeDir
		} else {
			hdr.Typeflag = tar.TypeReg
			hdr.Size = int64(len(f.content))
		}
		if err := tw.WriteHeader(hdr); err != nil {
			panic(err)
		}
		if hdr.Typeflag == tar.TypeReg {
			if _, err := tw.Write([]byte(f.content)); err != nil {
				panic(err)
			}
		}
	}
	if err := tw.Close(); err != nil {
		panic(err)
	}
	var gzBuf bytes.Buffer
	gw := gzip.NewWriter(&gzBuf)
	if _, err := gw.Write(tarBuf.Bytes()); err != nil {
		panic(err)
	}
	if err := gw.Close(); err != nil {
		panic(err)
	}
	sum := sha256.Sum256(gzBuf.Bytes())
	return gzBuf.Bytes(), hex.EncodeToString(sum[:])
}

type fakeSource struct {
	srv   *httptest.Server
	token string

	mu              sync.Mutex
	version         string
	protocol        string
	versionJSON     []byte
	versionStatus   int
	bundleBody      []byte
	bundleHeaderSHA string
	bundleStatus    int
	abortAfter      int
	forceChunked    bool
	versionHits     int
	bundleHits      int
	bundleTimes     []time.Time
	authVersion     string
	authBundle      string
	lastBundleVer   string
}

func newFakeSource(t *testing.T) *fakeSource {
	t.Helper()
	fs := &fakeSource{}
	mux := http.NewServeMux()
	mux.HandleFunc("/internal/skills/version", fs.serveVersion)
	mux.HandleFunc("/internal/skills/bundle", fs.serveBundle)
	fs.srv = httptest.NewServer(mux)
	t.Cleanup(fs.srv.Close)
	return fs
}

func (fs *fakeSource) serveVersion(w http.ResponseWriter, r *http.Request) {
	fs.mu.Lock()
	fs.versionHits++
	auth := r.Header.Get("Authorization")
	fs.authVersion = auth
	status, ver, proto, raw, token := fs.versionStatus, fs.version, fs.protocol, fs.versionJSON, fs.token
	fs.mu.Unlock()
	if token != "" && auth != "Bearer "+token {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if status != 0 && status != http.StatusOK {
		w.WriteHeader(status)
		return
	}
	if raw != nil {
		w.Header().Set("Content-Type", "application/json")
		w.Write(raw)
		return
	}
	payload := map[string]any{"version": ver, "skillCount": 2, "sizeBytes": 123}
	if proto != "" {
		payload["serverProtocol"] = proto
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(payload)
}

func (fs *fakeSource) serveBundle(w http.ResponseWriter, r *http.Request) {
	fs.mu.Lock()
	fs.bundleHits++
	fs.bundleTimes = append(fs.bundleTimes, time.Now())
	auth := r.Header.Get("Authorization")
	fs.authBundle = auth
	fs.lastBundleVer = r.URL.Query().Get("version")
	body, headerSHA, status, abortAfter, chunked, token := fs.bundleBody, fs.bundleHeaderSHA, fs.bundleStatus, fs.abortAfter, fs.forceChunked, fs.token
	fs.mu.Unlock()
	if token != "" && auth != "Bearer "+token {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if status != 0 && status != http.StatusOK {
		w.WriteHeader(status)
		return
	}
	if headerSHA != "" {
		w.Header().Set("X-Bundle-Sha256", headerSHA)
	}
	if abortAfter > 0 {
		w.Write(body[:abortAfter])
		w.(http.Flusher).Flush()
		panic(http.ErrAbortHandler)
	}
	if chunked {
		w.WriteHeader(http.StatusOK)
		half := len(body) / 2
		w.Write(body[:half])
		w.(http.Flusher).Flush()
		w.Write(body[half:])
		return
	}
	w.Write(body)
}

func (fs *fakeSource) setVersion(v string)       { fs.mu.Lock(); fs.version = v; fs.mu.Unlock() }
func (fs *fakeSource) setProtocol(p string)      { fs.mu.Lock(); fs.protocol = p; fs.mu.Unlock() }
func (fs *fakeSource) setVersionJSON(raw []byte) { fs.mu.Lock(); fs.versionJSON = raw; fs.mu.Unlock() }
func (fs *fakeSource) setVersionStatus(code int) {
	fs.mu.Lock()
	fs.versionStatus = code
	fs.mu.Unlock()
}
func (fs *fakeSource) setBundleStatus(code int) { fs.mu.Lock(); fs.bundleStatus = code; fs.mu.Unlock() }
func (fs *fakeSource) setBundleAbort(after int) { fs.mu.Lock(); fs.abortAfter = after; fs.mu.Unlock() }
func (fs *fakeSource) setBundleChunked()        { fs.mu.Lock(); fs.forceChunked = true; fs.mu.Unlock() }
func (fs *fakeSource) setToken(tok string)      { fs.mu.Lock(); fs.token = tok; fs.mu.Unlock() }
func (fs *fakeSource) setBundleRaw(body []byte, sha string) {
	fs.mu.Lock()
	fs.bundleBody = body
	fs.bundleHeaderSHA = sha
	fs.abortAfter = 0
	fs.forceChunked = false
	fs.mu.Unlock()
}
func (fs *fakeSource) setBundleFiles(files []fixtureFile) {
	body, sha := buildBundle(files)
	fs.setBundleRaw(body, sha)
}

type sourceStats struct {
	versionHits   int
	bundleHits    int
	bundleTimes   []time.Time
	authVersion   string
	authBundle    string
	lastBundleVer string
}

func (fs *fakeSource) stats() sourceStats {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	return sourceStats{
		versionHits:   fs.versionHits,
		bundleHits:    fs.bundleHits,
		bundleTimes:   append([]time.Time(nil), fs.bundleTimes...),
		authVersion:   fs.authVersion,
		authBundle:    fs.authBundle,
		lastBundleVer: fs.lastBundleVer,
	}
}

func newTestDaemon(t *testing.T, src *fakeSource, mutate func(*config), logger *log.Logger) *daemon {
	t.Helper()
	if logger == nil {
		logger = log.New(io.Discard, "", 0)
	}
	cfg := config{
		baseURL:        src.srv.URL,
		storeDir:       t.TempDir(),
		interval:       50 * time.Millisecond,
		timeout:        5 * time.Second,
		maxBundleBytes: 64 << 20,
	}
	if mutate != nil {
		mutate(&cfg)
	}
	d := newDaemon(cfg, logger)
	d.jitter = 0
	return d
}

func writeVersionDir(t *testing.T, store, ver string, files map[string]string) {
	t.Helper()
	dir := filepath.Join(store, versionPrefix+ver)
	for name, content := range files {
		p := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func setCurrent(t *testing.T, store, ver string) {
	t.Helper()
	if err := swapCurrent(store, ver); err != nil {
		t.Fatal(err)
	}
}

func assertCurrent(t *testing.T, store, want string) {
	t.Helper()
	if got := currentVersion(store); got != want {
		t.Fatalf("CURRENT = %q, want %q", got, want)
	}
}

func assertNoStaging(t *testing.T, store string) {
	t.Helper()
	entries, err := os.ReadDir(store)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), stagingPrefix) {
			t.Fatalf("staging leftover in store: %s", e.Name())
		}
	}
}

func assertIndirectRead(t *testing.T, store, rel, want string) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(store, currentLink, rel))
	if err != nil {
		t.Fatalf("indirect read %s: %v", rel, err)
	}
	if string(data) != want {
		t.Fatalf("indirect read %s = %q, want %q", rel, string(data), want)
	}
}

func TestLoadConfigDefaults(t *testing.T) {
	getenv := func(k string) string { return "" }
	cfg, err := loadConfig(getenv)
	if !errors.Is(err, errDisabled) {
		t.Fatalf("err = %v, want errDisabled", err)
	}
	if cfg.storeDir != defaultStoreDir || cfg.interval != defaultInterval || cfg.timeout != defaultTimeout || cfg.maxBundleBytes != defaultMaxBundleBytes {
		t.Fatalf("defaults not applied: %+v", cfg)
	}

	env := map[string]string{
		envBaseURL:        "http://oaf-demo-svc.agent-platform.svc:8100/",
		envToken:          "tok",
		envStoreDir:       "/tmp/store",
		envInterval:       "30s",
		envTimeout:        "10s",
		envMaxBundleBytes: "1024",
		envLog:            "/var/log/skills-syncd.log",
	}
	cfg, err = loadConfig(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("err = %v, want nil", err)
	}
	if cfg.baseURL != "http://oaf-demo-svc.agent-platform.svc:8100" {
		t.Fatalf("baseURL = %q, trailing slash not trimmed", cfg.baseURL)
	}
	if cfg.token != "tok" || cfg.storeDir != "/tmp/store" || cfg.interval != 30*time.Second || cfg.timeout != 10*time.Second || cfg.maxBundleBytes != 1024 || cfg.logPath != "/var/log/skills-syncd.log" {
		t.Fatalf("overrides not applied: %+v", cfg)
	}
}

func TestLoadConfigOverridesAndValidation(t *testing.T) {
	cases := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{name: "bad interval", env: map[string]string{envBaseURL: "http://x", envInterval: "abc"}, wantErr: envInterval},
		{name: "zero interval", env: map[string]string{envBaseURL: "http://x", envInterval: "0s"}, wantErr: envInterval},
		{name: "bad timeout", env: map[string]string{envBaseURL: "http://x", envTimeout: "xyz"}, wantErr: envTimeout},
		{name: "negative timeout", env: map[string]string{envBaseURL: "http://x", envTimeout: "-1s"}, wantErr: envTimeout},
		{name: "bad max bytes", env: map[string]string{envBaseURL: "http://x", envMaxBundleBytes: "12ab"}, wantErr: envMaxBundleBytes},
		{name: "zero max bytes", env: map[string]string{envBaseURL: "http://x", envMaxBundleBytes: "0"}, wantErr: envMaxBundleBytes},
		{name: "negative max bytes", env: map[string]string{envBaseURL: "http://x", envMaxBundleBytes: "-5"}, wantErr: envMaxBundleBytes},
		{name: "valid", env: map[string]string{envBaseURL: "http://x", envInterval: "5m", envTimeout: "1m", envMaxBundleBytes: "999"}, wantErr: ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := loadConfig(func(k string) string { return tc.env[k] })
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("err = %v, want nil", err)
				}
				if cfg.interval != 5*time.Minute || cfg.timeout != time.Minute || cfg.maxBundleBytes != 999 {
					t.Fatalf("parsed values wrong: %+v", cfg)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("err = %v, want containing %q", err, tc.wantErr)
			}
		})
	}
}

func TestHelperProcess(t *testing.T) {
	if os.Getenv("SSD_HELPER_MODE") == "" {
		t.Skip("helper process for exit-code tests")
	}
	os.Exit(run())
}

func TestMissingBaseURLExitsGracefully(t *testing.T) {
	dir := t.TempDir()
	var env []string
	for _, e := range os.Environ() {
		if strings.HasPrefix(e, "SKILLSYNC_") || strings.HasPrefix(e, "SSD_HELPER_MODE=") {
			continue
		}
		env = append(env, e)
	}
	env = append(env,
		"SSD_HELPER_MODE=1",
		envBaseURL+"=",
		envStoreDir+"="+dir,
	)
	cmd := exec.Command(os.Args[0], "-test.run=^TestHelperProcess$")
	cmd.Env = env
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("exit code not 0: %v, stderr: %s", err, stderr.String())
	}
	if !strings.Contains(stderr.String(), envBaseURL) || !strings.Contains(stderr.String(), "disabled") {
		t.Fatalf("stderr missing disable notice: %s", stderr.String())
	}
}

func TestCurrentVersionFromReadlink(t *testing.T) {
	store := t.TempDir()
	if got := currentVersion(store); got != "" {
		t.Fatalf("empty store current = %q, want empty", got)
	}
	if err := os.Symlink(versionPrefix+"deadbeefdeadbeef", filepath.Join(store, currentLink)); err != nil {
		t.Fatal(err)
	}
	if got := currentVersion(store); got != "deadbeefdeadbeef" {
		t.Fatalf("current = %q, want deadbeefdeadbeef", got)
	}
	if err := os.Remove(filepath.Join(store, currentLink)); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(store, currentLink), []byte("regular file"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := currentVersion(store); got != "" {
		t.Fatalf("regular file current = %q, want empty", got)
	}
}

func TestVersionUnchangedSkipsDownload(t *testing.T) {
	src := newFakeSource(t)
	src.setVersion(ver1)
	src.setBundleFiles(defaultFixture("v1"))
	d := newTestDaemon(t, src, nil, nil)
	ctx := context.Background()

	if err := d.cycle(ctx); err != nil {
		t.Fatalf("first cycle: %v", err)
	}
	if err := d.cycle(ctx); err != nil {
		t.Fatalf("second cycle: %v", err)
	}
	stats := src.stats()
	if stats.bundleHits != 1 {
		t.Fatalf("bundle hits = %d, want 1 (second cycle must not download)", stats.bundleHits)
	}
	if stats.lastBundleVer != ver1 {
		t.Fatalf("bundle version param = %q, want %q", stats.lastBundleVer, ver1)
	}
	assertCurrent(t, d.cfg.storeDir, ver1)
	assertIndirectRead(t, d.cfg.storeDir, "skill-a/SKILL.md", "skill a v1")
}

func TestVersionChangeTriggersPull(t *testing.T) {
	src := newFakeSource(t)
	src.setVersion(ver1)
	src.setBundleFiles(defaultFixture("v1"))
	d := newTestDaemon(t, src, nil, nil)
	ctx := context.Background()

	if err := d.cycle(ctx); err != nil {
		t.Fatalf("first cycle: %v", err)
	}
	src.setVersion(ver2)
	src.setBundleFiles(defaultFixture("v2"))
	if err := d.cycle(ctx); err != nil {
		t.Fatalf("second cycle: %v", err)
	}

	stats := src.stats()
	if stats.bundleHits != 2 {
		t.Fatalf("bundle hits = %d, want 2", stats.bundleHits)
	}
	assertCurrent(t, d.cfg.storeDir, ver2)
	assertIndirectRead(t, d.cfg.storeDir, "skill-a/SKILL.md", "skill a v2")
	assertIndirectRead(t, d.cfg.storeDir, "skill-b/SKILL.md", "skill b v2")

	if _, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver1)); !os.IsNotExist(err) {
		t.Fatalf("old version dir still exists after single-version switch")
	}
	mdInfo, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver2, "skill-a", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if mdInfo.Mode().Perm() != 0o644 {
		t.Fatalf("regular file perm = %o, want 644", mdInfo.Mode().Perm())
	}
	shInfo, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver2, "skill-a", "run.sh"))
	if err != nil {
		t.Fatal(err)
	}
	if shInfo.Mode().Perm() != 0o755 {
		t.Fatalf("exec file perm = %o, want 755", shInfo.Mode().Perm())
	}
	dirInfo, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver2, "skill-a"))
	if err != nil {
		t.Fatal(err)
	}
	if dirInfo.Mode().Perm() != 0o755 {
		t.Fatalf("dir perm = %o, want 755", dirInfo.Mode().Perm())
	}
}

func TestVersionEndpointHTTPErrorHandling(t *testing.T) {
	for _, tc := range []struct {
		name string
		code int
	}{
		{"unauthorized", http.StatusUnauthorized},
		{"not found", http.StatusNotFound},
		{"server error", http.StatusInternalServerError},
	} {
		t.Run(tc.name, func(t *testing.T) {
			src := newFakeSource(t)
			src.setVersion(ver1)
			src.setBundleFiles(defaultFixture("v1"))
			src.setVersionStatus(tc.code)
			d := newTestDaemon(t, src, nil, nil)

			err := d.cycle(context.Background())
			if err == nil {
				t.Fatal("cycle must fail on version endpoint error")
			}
			if !strings.Contains(err.Error(), fmt.Sprintf("HTTP %d", tc.code)) {
				t.Fatalf("err = %v, want containing HTTP %d", err, tc.code)
			}
			stats := src.stats()
			if stats.bundleHits != 0 {
				t.Fatalf("bundle hits = %d, want 0", stats.bundleHits)
			}
			if _, err := os.Stat(filepath.Join(d.cfg.storeDir, currentLink)); !os.IsNotExist(err) {
				t.Fatal("CURRENT must not exist after failed version fetch")
			}
		})
	}
}

func TestBundleEndpointHTTPErrorHandling(t *testing.T) {
	for _, tc := range []struct {
		name string
		code int
	}{
		{"unauthorized", http.StatusUnauthorized},
		{"not found", http.StatusNotFound},
	} {
		t.Run(tc.name, func(t *testing.T) {
			src := newFakeSource(t)
			src.setVersion(ver1)
			src.setBundleFiles(defaultFixture("v1"))
			d := newTestDaemon(t, src, nil, nil)
			ctx := context.Background()

			if err := d.cycle(ctx); err != nil {
				t.Fatalf("initial cycle: %v", err)
			}
			src.setVersion(ver2)
			src.setBundleStatus(tc.code)
			err := d.cycle(ctx)
			if err == nil {
				t.Fatal("cycle must fail on bundle endpoint error")
			}
			if !strings.Contains(err.Error(), fmt.Sprintf("HTTP %d", tc.code)) {
				t.Fatalf("err = %v, want containing HTTP %d", err, tc.code)
			}
			assertCurrent(t, d.cfg.storeDir, ver1)
			assertIndirectRead(t, d.cfg.storeDir, "skill-a/SKILL.md", "skill a v1")
			assertNoStaging(t, d.cfg.storeDir)
		})
	}
}

func TestFetchRetryBackoff(t *testing.T) {
	t.Run("sequence exponential capped by interval", func(t *testing.T) {
		d := newDaemon(config{interval: 5 * time.Minute}, log.New(io.Discard, "", 0))
		want := []time.Duration{time.Minute, 2 * time.Minute, 4 * time.Minute, 5 * time.Minute, 5 * time.Minute}
		for i, w := range want {
			d.failures = i + 1
			if got := d.nextBackoff(); got != w {
				t.Fatalf("failure %d backoff = %v, want %v", i+1, got, w)
			}
		}
	})
	t.Run("capped by small interval", func(t *testing.T) {
		d := newDaemon(config{interval: 50 * time.Millisecond}, log.New(io.Discard, "", 0))
		for f := 1; f <= 5; f++ {
			d.failures = f
			if got := d.nextBackoff(); got != 50*time.Millisecond {
				t.Fatalf("failure %d backoff = %v, want capped 50ms", f, got)
			}
		}
	})
	t.Run("loop retries without bundle download and recovers", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		src.setVersionStatus(http.StatusInternalServerError)
		d := newTestDaemon(t, src, nil, nil)

		ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
		defer cancel()
		done := make(chan struct{})
		go func() {
			d.loop(ctx)
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("loop did not exit on cancelled context")
		}
		stats := src.stats()
		if stats.versionHits < 3 {
			t.Fatalf("version hits = %d, want >= 3 retries", stats.versionHits)
		}
		if stats.bundleHits != 0 {
			t.Fatalf("bundle hits = %d, want 0", stats.bundleHits)
		}

		src.setVersionStatus(0)
		if err := d.cycle(context.Background()); err != nil {
			t.Fatalf("cycle after source recovery: %v", err)
		}
		if after := src.stats(); after.bundleHits != 1 {
			t.Fatalf("bundle hits after recovery = %d, want 1", after.bundleHits)
		}
		assertCurrent(t, d.cfg.storeDir, ver1)
	})
}

func TestPullBackoffDelayBounds(t *testing.T) {
	t.Run("generator within [0, interval)", func(t *testing.T) {
		interval := 7 * time.Millisecond
		for i := 0; i < 500; i++ {
			got := pullDelay(interval)
			if got < 0 || got >= interval {
				t.Fatalf("pull delay %v outside [0, %v)", got, interval)
			}
		}
	})
	t.Run("cycle delays bundle request", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		interval := 50 * time.Millisecond
		d := newTestDaemon(t, src, func(c *config) { c.interval = interval }, nil)
		ctx := context.Background()

		if err := d.cycle(ctx); err != nil {
			t.Fatalf("first cycle: %v", err)
		}
		src.setVersion(ver2)
		src.setBundleFiles(defaultFixture("v2"))
		if err := d.cycle(ctx); err != nil {
			t.Fatalf("second cycle: %v", err)
		}
		times := src.stats().bundleTimes
		if len(times) != 2 {
			t.Fatalf("bundle request count = %d, want 2", len(times))
		}
		gap := times[1].Sub(times[0])
		if gap < 0 || gap >= interval+30*time.Millisecond {
			t.Fatalf("pull delay gap %v outside [0, %v+slack]", gap, interval)
		}
	})
}

func TestRecentlySeenToleranceWindow(t *testing.T) {
	src := newFakeSource(t)
	src.setVersion(ver1)
	src.setBundleFiles(defaultFixture("v1"))
	d := newTestDaemon(t, src, nil, nil)
	ctx := context.Background()

	if err := d.cycle(ctx); err != nil {
		t.Fatalf("pull v1: %v", err)
	}
	src.setVersion(ver2)
	src.setBundleFiles(defaultFixture("v2"))
	if err := d.cycle(ctx); err != nil {
		t.Fatalf("pull v2: %v", err)
	}

	src.setVersion(ver1)
	if err := d.cycle(ctx); err != nil {
		t.Fatalf("flap cycle: %v", err)
	}
	if stats := src.stats(); stats.bundleHits != 2 {
		t.Fatalf("bundle hits after flap = %d, want 2 (previous version within window must not re-pull)", stats.bundleHits)
	}
	assertCurrent(t, d.cfg.storeDir, ver2)

	time.Sleep(150 * time.Millisecond)
	if err := d.cycle(ctx); err != nil {
		t.Fatalf("cycle after window expiry: %v", err)
	}
	if stats := src.stats(); stats.bundleHits != 3 {
		t.Fatalf("bundle hits after window expiry = %d, want 3", stats.bundleHits)
	}
	assertCurrent(t, d.cfg.storeDir, ver1)
}

func TestStagingInterruptLeavesNoReadableState(t *testing.T) {
	cases := []struct {
		name  string
		setup func(src *fakeSource)
	}{
		{
			name: "sha mismatch",
			setup: func(src *fakeSource) {
				body, _ := buildBundle(defaultFixture("v2"))
				src.setBundleRaw(body, strings.Repeat("ab", 32))
			},
		},
		{
			name: "aborted mid-body",
			setup: func(src *fakeSource) {
				src.setBundleFiles(defaultFixture("v2"))
				src.setBundleAbort(16)
			},
		},
		{
			name: "corrupt archive with valid sha",
			setup: func(src *fakeSource) {
				body := []byte("this is definitely not a gzip stream")
				sum := sha256.Sum256(body)
				src.setBundleRaw(body, hex.EncodeToString(sum[:]))
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			src := newFakeSource(t)
			src.setVersion(ver1)
			src.setBundleFiles(defaultFixture("v1"))
			d := newTestDaemon(t, src, nil, nil)
			ctx := context.Background()

			if err := d.cycle(ctx); err != nil {
				t.Fatalf("initial pull: %v", err)
			}
			src.setVersion(ver2)
			tc.setup(src)
			if err := d.cycle(ctx); err == nil {
				t.Fatal("cycle must fail on interrupted staging")
			}

			assertCurrent(t, d.cfg.storeDir, ver1)
			assertIndirectRead(t, d.cfg.storeDir, "skill-a/SKILL.md", "skill a v1")
			if _, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver2)); !os.IsNotExist(err) {
				t.Fatal("failed pull must not produce a readable version dir")
			}
			assertNoStaging(t, d.cfg.storeDir)
		})
	}
}

func TestOldVersionReadableAcrossCurrentSwap(t *testing.T) {
	src := newFakeSource(t)
	src.setVersion(ver1)
	src.setBundleFiles(defaultFixture("v1"))
	d := newTestDaemon(t, src, nil, nil)

	if err := d.pull(ver1); err != nil {
		t.Fatalf("pull v1: %v", err)
	}
	handle, err := os.Open(filepath.Join(d.cfg.storeDir, currentLink, "skill-a", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	defer handle.Close()
	before, err := io.ReadAll(handle)
	if err != nil {
		t.Fatal(err)
	}
	if string(before) != "skill a v1" {
		t.Fatalf("pre-swap read = %q", string(before))
	}

	src.setVersion(ver2)
	src.setBundleFiles(defaultFixture("v2"))
	if err := d.install(ver2); err != nil {
		t.Fatalf("install v2: %v", err)
	}
	assertCurrent(t, d.cfg.storeDir, ver2)
	if _, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver1)); err != nil {
		t.Fatalf("old version dir must survive until GC after CURRENT swap: %v", err)
	}
	if _, err := handle.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	viaInode, err := io.ReadAll(handle)
	if err != nil {
		t.Fatal(err)
	}
	if string(viaInode) != "skill a v1" {
		t.Fatalf("open handle after swap = %q, want old content via inode", string(viaInode))
	}

	d.deleteSuperseded(ver2)
	if _, err := os.Stat(filepath.Join(d.cfg.storeDir, versionPrefix+ver1)); !os.IsNotExist(err) {
		t.Fatal("old version dir must be deleted after GC")
	}
	if _, err := handle.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	after, err := io.ReadAll(handle)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != "skill a v1" {
		t.Fatalf("open handle after GC = %q, want old content via inode", string(after))
	}
	assertIndirectRead(t, d.cfg.storeDir, "skill-a/SKILL.md", "skill a v2")
}

func TestSupersededVersionsDeletedCurrentKept(t *testing.T) {
	store := t.TempDir()
	writeVersionDir(t, store, "old0", map[string]string{"skill-x/SKILL.md": "old0"})
	writeVersionDir(t, store, "old1", map[string]string{"skill-y/SKILL.md": "old1"})
	setCurrent(t, store, "old0")

	src := newFakeSource(t)
	src.setVersion(ver1)
	src.setBundleFiles(defaultFixture("v1"))
	d := newTestDaemon(t, src, func(c *config) { c.storeDir = store }, nil)

	if err := d.pull(ver1); err != nil {
		t.Fatalf("pull: %v", err)
	}
	for _, stale := range []string{"old0", "old1"} {
		if _, err := os.Stat(filepath.Join(store, versionPrefix+stale)); !os.IsNotExist(err) {
			t.Fatalf("superseded v-%s still exists", stale)
		}
	}
	if _, err := os.Stat(filepath.Join(store, versionPrefix+ver1)); err != nil {
		t.Fatalf("current version dir missing: %v", err)
	}
	assertCurrent(t, store, ver1)
	assertIndirectRead(t, store, "skill-a/SKILL.md", "skill a v1")
}

func TestStartupCleanupIdempotent(t *testing.T) {
	t.Run("removes leftovers keeps current", func(t *testing.T) {
		store := t.TempDir()
		writeVersionDir(t, store, "cur", map[string]string{"skill-a/SKILL.md": "cur"})
		writeVersionDir(t, store, "stale1", map[string]string{"skill-a/SKILL.md": "stale1"})
		writeVersionDir(t, store, "stale2", map[string]string{"skill-a/SKILL.md": "stale2"})
		if err := os.WriteFile(filepath.Join(store, stagingPrefix+"abc.tar.gz"), []byte("junk"), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Join(store, stagingPrefix+"abc", "partial"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(store, stagingPrefix+"abc", "partial", "f"), []byte("half"), 0o644); err != nil {
			t.Fatal(err)
		}
		setCurrent(t, store, "cur")

		d := newDaemon(config{storeDir: store}, log.New(io.Discard, "", 0))
		if err := d.cleanupStore(); err != nil {
			t.Fatalf("cleanupStore: %v", err)
		}
		for _, gone := range []string{"v-stale1", "v-stale2", stagingPrefix + "abc.tar.gz", stagingPrefix + "abc"} {
			if _, err := os.Stat(filepath.Join(store, gone)); !os.IsNotExist(err) {
				t.Fatalf("%s still exists after cleanup", gone)
			}
		}
		if _, err := os.Stat(filepath.Join(store, versionPrefix+"cur", "skill-a", "SKILL.md")); err != nil {
			t.Fatalf("current version damaged by cleanup: %v", err)
		}
		assertCurrent(t, store, "cur")

		if err := d.cleanupStore(); err != nil {
			t.Fatalf("second cleanupStore: %v", err)
		}
		assertCurrent(t, store, "cur")
		if _, err := os.Stat(filepath.Join(store, versionPrefix+"cur", "skill-a", "SKILL.md")); err != nil {
			t.Fatalf("current version damaged by second cleanup: %v", err)
		}
	})
	t.Run("dangling current removes all versions", func(t *testing.T) {
		store := t.TempDir()
		writeVersionDir(t, store, "a", map[string]string{"skill-a/SKILL.md": "a"})
		writeVersionDir(t, store, "b", map[string]string{"skill-a/SKILL.md": "b"})
		setCurrent(t, store, "gone")

		d := newDaemon(config{storeDir: store}, log.New(io.Discard, "", 0))
		if err := d.cleanupStore(); err != nil {
			t.Fatalf("cleanupStore: %v", err)
		}
		entries, err := os.ReadDir(store)
		if err != nil {
			t.Fatal(err)
		}
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), versionPrefix) {
				t.Fatalf("v-* survived cleanup with dangling CURRENT: %s", e.Name())
			}
		}
		if got := currentVersion(store); got != "gone" {
			t.Fatalf("CURRENT link damaged by cleanup: %q", got)
		}
	})
}

func TestOversizedBundleRefused(t *testing.T) {
	t.Run("content length over limit", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		d := newTestDaemon(t, src, func(c *config) { c.maxBundleBytes = 1 }, nil)

		err := d.cycle(context.Background())
		if err == nil || !strings.Contains(err.Error(), "exceeds limit") {
			t.Fatalf("err = %v, want size limit refusal", err)
		}
		if _, err := os.Stat(filepath.Join(d.cfg.storeDir, currentLink)); !os.IsNotExist(err) {
			t.Fatal("CURRENT must not exist after oversized refusal")
		}
		assertNoStaging(t, d.cfg.storeDir)
	})
	t.Run("streamed over limit without content length", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		src.setBundleChunked()
		d := newTestDaemon(t, src, func(c *config) { c.maxBundleBytes = 1 }, nil)

		err := d.cycle(context.Background())
		if err == nil || !strings.Contains(err.Error(), "exceeds limit") {
			t.Fatalf("err = %v, want size limit refusal", err)
		}
		if _, err := os.Stat(filepath.Join(d.cfg.storeDir, currentLink)); !os.IsNotExist(err) {
			t.Fatal("CURRENT must not exist after oversized refusal")
		}
		assertNoStaging(t, d.cfg.storeDir)
	})
}

func TestVersionResponseUnknownFieldsIgnored(t *testing.T) {
	raw := []byte(`{"version":"abc123def4567890","skillCount":7,"sizeBytes":42,"serverProtocol":"proto-9","channels":{"skills":{"version":"x"}},"futureField":{"nested":true}}`)
	src := newFakeSource(t)
	src.setVersionJSON(raw)
	src.setBundleFiles(defaultFixture("v1"))
	var logs bytes.Buffer
	d := newTestDaemon(t, src, nil, log.New(&logs, "", 0))

	if err := d.cycle(context.Background()); err != nil {
		t.Fatalf("cycle with unknown version fields: %v", err)
	}
	if stats := src.stats(); stats.bundleHits != 1 {
		t.Fatalf("bundle hits = %d, want 1", stats.bundleHits)
	}
	assertCurrent(t, d.cfg.storeDir, "abc123def4567890")
	if !strings.Contains(logs.String(), "serverProtocol") || !strings.Contains(logs.String(), "proto-9") {
		t.Fatalf("serverProtocol not logged: %s", logs.String())
	}
}

func TestBearerTokenHeaderSemantics(t *testing.T) {
	t.Run("token attached", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		src.setToken("tok123")
		d := newTestDaemon(t, src, func(c *config) { c.token = "tok123" }, nil)

		if err := d.cycle(context.Background()); err != nil {
			t.Fatalf("cycle with token: %v", err)
		}
		stats := src.stats()
		if stats.authVersion != "Bearer tok123" || stats.authBundle != "Bearer tok123" {
			t.Fatalf("auth headers = %q / %q, want Bearer tok123 on both endpoints", stats.authVersion, stats.authBundle)
		}
	})
	t.Run("empty token sends no authorization header", func(t *testing.T) {
		src := newFakeSource(t)
		src.setVersion(ver1)
		src.setBundleFiles(defaultFixture("v1"))
		d := newTestDaemon(t, src, nil, nil)

		if err := d.cycle(context.Background()); err != nil {
			t.Fatalf("cycle without token: %v", err)
		}
		stats := src.stats()
		if stats.authVersion != "" || stats.authBundle != "" {
			t.Fatalf("auth headers = %q / %q, want empty on both endpoints", stats.authVersion, stats.authBundle)
		}
	})
}

func TestExtractRejectsUnsafeTarEntries(t *testing.T) {
	cases := []struct {
		name string
		evil string
	}{
		{name: "parent traversal", evil: "../escape.txt"},
		{name: "absolute path", evil: "/tmp/escape.txt"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			src := newFakeSource(t)
			src.setVersion(ver1)
			body, sha := buildBundle([]fixtureFile{{name: tc.evil, content: "escaped", mode: 0o644}})
			src.setBundleRaw(body, sha)
			d := newTestDaemon(t, src, nil, nil)

			err := d.cycle(context.Background())
			if err == nil || !strings.Contains(err.Error(), "unsafe tar entry") {
				t.Fatalf("err = %v, want unsafe tar entry rejection", err)
			}
			if _, err := os.Stat(filepath.Join(d.cfg.storeDir, currentLink)); !os.IsNotExist(err) {
				t.Fatal("CURRENT must not exist after unsafe tar rejection")
			}
			if _, err := os.Stat(filepath.Join(d.cfg.storeDir, "escape.txt")); !os.IsNotExist(err) {
				t.Fatal("tar entry escaped the staging dir")
			}
			assertNoStaging(t, d.cfg.storeDir)
		})
	}
}
