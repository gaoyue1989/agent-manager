package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"
)

var buildSHA = "unknown"

const (
	envBaseURL        = "SKILLSYNC_BASE_URL"
	envToken          = "SKILLSYNC_TOKEN"
	envStoreDir       = "SKILLSYNC_STORE_DIR"
	envInterval       = "SKILLSYNC_INTERVAL"
	envTimeout        = "SKILLSYNC_TIMEOUT"
	envMaxBundleBytes = "SKILLSYNC_MAX_BUNDLE_BYTES"
	envLog            = "SKILLSYNC_LOG"

	defaultStoreDir       = "/skills-store"
	defaultInterval       = time.Minute
	defaultTimeout        = 120 * time.Second
	defaultMaxBundleBytes = int64(512) << 20
	logTargetStderr       = "stderr"
)

var errDisabled = errors.New("skills sync disabled")

type config struct {
	baseURL        string
	token          string
	storeDir       string
	interval       time.Duration
	timeout        time.Duration
	maxBundleBytes int64
	logPath        string
}

func loadConfig(getenv func(string) string) (config, error) {
	cfg := config{
		storeDir:       defaultStoreDir,
		interval:       defaultInterval,
		timeout:        defaultTimeout,
		maxBundleBytes: defaultMaxBundleBytes,
		logPath:        getenv(envLog),
	}
	base := strings.TrimSpace(getenv(envBaseURL))
	if base == "" {
		return cfg, errDisabled
	}
	cfg.baseURL = strings.TrimSuffix(base, "/")
	cfg.token = getenv(envToken)
	if v := getenv(envStoreDir); v != "" {
		cfg.storeDir = v
	}
	if v := getenv(envInterval); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return cfg, fmt.Errorf("invalid %s %q: %w", envInterval, v, err)
		}
		if d <= 0 {
			return cfg, fmt.Errorf("invalid %s %q: must be positive", envInterval, v)
		}
		cfg.interval = d
	}
	if v := getenv(envTimeout); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return cfg, fmt.Errorf("invalid %s %q: %w", envTimeout, v, err)
		}
		if d <= 0 {
			return cfg, fmt.Errorf("invalid %s %q: must be positive", envTimeout, v)
		}
		cfg.timeout = d
	}
	if v := getenv(envMaxBundleBytes); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			return cfg, fmt.Errorf("invalid %s %q: %w", envMaxBundleBytes, v, err)
		}
		if n <= 0 {
			return cfg, fmt.Errorf("invalid %s %q: must be positive", envMaxBundleBytes, v)
		}
		cfg.maxBundleBytes = n
	}
	return cfg, nil
}

func newLogger(path string) (*log.Logger, error) {
	flags := log.Ldate | log.Ltime | log.LUTC
	if path == "" || path == logTargetStderr {
		return log.New(os.Stderr, "skills-syncd: ", flags), nil
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	return log.New(f, "skills-syncd: ", flags), nil
}

func main() {
	os.Exit(run())
}

func run() int {
	cfg, err := loadConfig(os.Getenv)
	logger, logErr := newLogger(cfg.logPath)
	if logErr != nil {
		fmt.Fprintf(os.Stderr, "skills-syncd: log target %q: %v\n", cfg.logPath, logErr)
		return 1
	}
	if errors.Is(err, errDisabled) {
		logger.Printf("%s not set, skills sync disabled, exiting", envBaseURL)
		return 0
	}
	if err != nil {
		logger.Printf("invalid configuration: %v", err)
		return 1
	}
	d := newDaemon(cfg, logger)
	if err := d.cleanupStore(); err != nil {
		logger.Printf("init store %s: %v", cfg.storeDir, err)
		return 1
	}
	logger.Printf("starting build=%s base=%s store=%s interval=%s timeout=%s maxBundleBytes=%d", buildSHA, cfg.baseURL, cfg.storeDir, cfg.interval, cfg.timeout, cfg.maxBundleBytes)
	d.loop(context.Background())
	return 0
}
