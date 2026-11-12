package main

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	versionPrefix  = "v-"
	stagingPrefix  = ".staging-"
	currentLink    = "CURRENT"
	currentTmpLink = ".CURRENT.tmp"

	jitterMax      = 30 * time.Second
	backoffInitial = time.Minute
	recentWindowX  = 2
	maxVersionBody = 1 << 16
)

type versionInfo struct {
	Version        string `json:"version"`
	SkillCount     int    `json:"skillCount"`
	SizeBytes      int64  `json:"sizeBytes"`
	ServerProtocol string `json:"serverProtocol"`
}

type daemon struct {
	cfg          config
	client       *http.Client
	log          *log.Logger
	jitter       time.Duration
	failures     int
	recent       map[string]time.Time
	lastProtocol string
}

func newDaemon(cfg config, logger *log.Logger) *daemon {
	return &daemon{
		cfg:    cfg,
		client: &http.Client{Timeout: cfg.timeout},
		log:    logger,
		jitter: jitterMax,
		recent: make(map[string]time.Time),
	}
}

func (d *daemon) loop(ctx context.Context) {
	for ctx.Err() == nil {
		d.sleep(ctx, d.jitter)
		if ctx.Err() != nil {
			return
		}
		if err := d.cycle(ctx); err != nil {
			d.failures++
			wait := d.nextBackoff()
			d.log.Printf("sync failed attempts=%d retryIn=%v: %v", d.failures, wait, err)
			d.sleep(ctx, wait)
			continue
		}
		d.failures = 0
		d.sleep(ctx, d.cfg.interval)
	}
}

func (d *daemon) nextBackoff() time.Duration {
	wait := backoffInitial
	for range d.failures - 1 {
		wait *= 2
		if wait >= d.cfg.interval {
			return d.cfg.interval
		}
	}
	if wait > d.cfg.interval {
		return d.cfg.interval
	}
	return wait
}

func (d *daemon) cycle(ctx context.Context) error {
	info, err := d.fetchVersion()
	if err != nil {
		return err
	}
	current := currentVersion(d.cfg.storeDir)
	d.log.Printf("version ver=%s skills=%d size=%d current=%s", info.Version, info.SkillCount, info.SizeBytes, current)
	if info.ServerProtocol != "" && info.ServerProtocol != d.lastProtocol {
		d.lastProtocol = info.ServerProtocol
		d.log.Printf("serverProtocol=%s", info.ServerProtocol)
	}
	if info.Version == current {
		return nil
	}
	if d.withinTolerance(info.Version) {
		d.log.Printf("ver=%s recently pulled, skipping re-pull within tolerance window", info.Version)
		return nil
	}
	d.sleep(ctx, pullDelay(d.cfg.interval))
	return d.pull(info.Version)
}

func pullDelay(interval time.Duration) time.Duration {
	if interval <= 0 {
		return 0
	}
	return time.Duration(rand.Int64N(int64(interval)))
}

func (d *daemon) withinTolerance(ver string) bool {
	at, ok := d.recent[ver]
	return ok && time.Since(at) < recentWindowX*d.cfg.interval
}

func (d *daemon) recordRecent(ver string) {
	d.recent[ver] = time.Now()
	type seen struct {
		ver string
		at  time.Time
	}
	all := make([]seen, 0, len(d.recent))
	for v, at := range d.recent {
		all = append(all, seen{v, at})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].at.After(all[j].at) })
	for _, s := range all[min(2, len(all)):] {
		delete(d.recent, s.ver)
	}
}

func (d *daemon) fetchVersion() (versionInfo, error) {
	req, err := http.NewRequest(http.MethodGet, d.cfg.baseURL+"/internal/skills/version", nil)
	if err != nil {
		return versionInfo{}, err
	}
	d.setAuth(req)
	resp, err := d.client.Do(req)
	if err != nil {
		return versionInfo{}, fmt.Errorf("version request: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return versionInfo{}, fmt.Errorf("version endpoint: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxVersionBody))
	if err != nil {
		return versionInfo{}, fmt.Errorf("version response body: %w", err)
	}
	var info versionInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return versionInfo{}, fmt.Errorf("version response decode: %w", err)
	}
	if !validVersion(info.Version) {
		return versionInfo{}, fmt.Errorf("version endpoint returned invalid version %q", info.Version)
	}
	return info, nil
}

func (d *daemon) setAuth(req *http.Request) {
	if d.cfg.token != "" {
		req.Header.Set("Authorization", "Bearer "+d.cfg.token)
	}
}

func validVersion(v string) bool {
	if v == "" || len(v) > 128 || v == "." || v == ".." {
		return false
	}
	for _, r := range v {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r == '-', r == '_', r == '.':
		default:
			return false
		}
	}
	return true
}

func (d *daemon) pull(ver string) error {
	if err := d.install(ver); err != nil {
		return err
	}
	d.recordRecent(ver)
	d.deleteSuperseded(ver)
	return nil
}

func (d *daemon) install(ver string) (err error) {
	tarPath := d.stagingTar(ver)
	dirPath := d.stagingDir(ver)
	defer func() {
		if err != nil {
			os.Remove(tarPath)
			os.RemoveAll(dirPath)
		}
	}()
	if err = d.downloadBundle(ver, tarPath); err != nil {
		return err
	}
	if err = extractBundle(tarPath, dirPath); err != nil {
		return err
	}
	os.Remove(tarPath)
	if err = d.makeWorldReadable(dirPath); err != nil {
		return err
	}
	vDir := filepath.Join(d.cfg.storeDir, versionPrefix+ver)
	if err = os.RemoveAll(vDir); err != nil {
		return err
	}
	if err = os.Rename(dirPath, vDir); err != nil {
		return err
	}
	if err = swapCurrent(d.cfg.storeDir, ver); err != nil {
		return err
	}
	d.log.Printf("installed ver=%s", ver)
	return nil
}

func (d *daemon) stagingTar(ver string) string {
	return filepath.Join(d.cfg.storeDir, stagingPrefix+ver+".tar.gz")
}

func (d *daemon) stagingDir(ver string) string {
	return filepath.Join(d.cfg.storeDir, stagingPrefix+ver)
}

func (d *daemon) downloadBundle(ver, tarPath string) error {
	req, err := http.NewRequest(http.MethodGet, d.cfg.baseURL+"/internal/skills/bundle?version="+url.QueryEscape(ver), nil)
	if err != nil {
		return err
	}
	d.setAuth(req)
	resp, err := d.client.Do(req)
	if err != nil {
		return fmt.Errorf("bundle request: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("bundle endpoint: HTTP %d", resp.StatusCode)
	}
	wantSHA := resp.Header.Get("X-Bundle-Sha256")
	if wantSHA == "" {
		return errors.New("bundle response missing X-Bundle-Sha256 header")
	}
	if resp.ContentLength > d.cfg.maxBundleBytes {
		return fmt.Errorf("bundle size %d exceeds limit %d", resp.ContentLength, d.cfg.maxBundleBytes)
	}
	f, err := os.OpenFile(tarPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, d.cfg.maxBundleBytes+1))
	closeErr := f.Close()
	if err != nil {
		return fmt.Errorf("bundle download: %w", err)
	}
	if closeErr != nil {
		return closeErr
	}
	if n > d.cfg.maxBundleBytes {
		return fmt.Errorf("bundle size %d exceeds limit %d", n, d.cfg.maxBundleBytes)
	}
	gotSHA := hex.EncodeToString(h.Sum(nil))
	if !strings.EqualFold(gotSHA, wantSHA) {
		return fmt.Errorf("bundle sha256 mismatch computed=%s header=%s", gotSHA, wantSHA)
	}
	d.log.Printf("downloaded bundle ver=%s bytes=%d sha=%s", ver, n, gotSHA)
	return nil
}

func extractBundle(tarPath, dest string) error {
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	f, err := os.Open(tarPath)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return fmt.Errorf("bundle gzip: %w", err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("bundle tar: %w", err)
		}
		target, err := safeExtractPath(dest, hdr.Name)
		if err != nil {
			return err
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			if err := writeFile(target, tr, hdr.FileInfo().Mode()); err != nil {
				return err
			}
		default:
			return fmt.Errorf("unsupported tar entry %q type %v", hdr.Name, hdr.Typeflag)
		}
	}
}

func writeFile(target string, r io.Reader, mode os.FileMode) error {
	f, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, r); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func safeExtractPath(dest, name string) (string, error) {
	if name == "" || filepath.IsAbs(name) {
		return "", fmt.Errorf("unsafe tar entry path %q", name)
	}
	for _, part := range strings.Split(filepath.ToSlash(name), "/") {
		if part == ".." {
			return "", fmt.Errorf("unsafe tar entry path %q", name)
		}
	}
	return filepath.Join(dest, filepath.FromSlash(name)), nil
}

func (d *daemon) makeWorldReadable(root string) error {
	return filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		mode := info.Mode()
		switch {
		case mode.IsDir():
			if err := os.Chmod(path, 0o755); err != nil {
				return err
			}
		case mode.IsRegular():
			perm := os.FileMode(0o644)
			if mode&0o111 != 0 {
				perm = 0o755
			}
			if err := os.Chmod(path, perm); err != nil {
				return err
			}
		default:
			return nil
		}
		if os.Geteuid() == 0 {
			if err := os.Chown(path, 0, 0); err != nil {
				d.log.Printf("chown failed path=%s: %v", path, err)
			}
		}
		return nil
	})
}

func swapCurrent(storeDir, ver string) error {
	tmp := filepath.Join(storeDir, currentTmpLink)
	os.Remove(tmp)
	if err := os.Symlink(versionPrefix+ver, tmp); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, filepath.Join(storeDir, currentLink)); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

func (d *daemon) deleteSuperseded(keepVer string) {
	entries, err := os.ReadDir(d.cfg.storeDir)
	if err != nil {
		d.log.Printf("list store for gc: %v", err)
		return
	}
	keep := versionPrefix + keepVer
	removed := 0
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, versionPrefix) || name == keep {
			continue
		}
		if err := os.RemoveAll(filepath.Join(d.cfg.storeDir, name)); err != nil {
			d.log.Printf("gc remove failed path=%s: %v", name, err)
			continue
		}
		removed++
	}
	if removed > 0 {
		d.log.Printf("removed superseded versions count=%d keep=%s", removed, keep)
	}
}

func (d *daemon) cleanupStore() error {
	if err := os.MkdirAll(d.cfg.storeDir, 0o755); err != nil {
		return err
	}
	keep := ""
	if target, err := os.Readlink(filepath.Join(d.cfg.storeDir, currentLink)); err == nil {
		keep = target
	}
	entries, err := os.ReadDir(d.cfg.storeDir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		name := entry.Name()
		stale := strings.HasPrefix(name, stagingPrefix) || (strings.HasPrefix(name, versionPrefix) && name != keep)
		if !stale {
			continue
		}
		if err := os.RemoveAll(filepath.Join(d.cfg.storeDir, name)); err != nil {
			d.log.Printf("cleanup failed path=%s: %v", name, err)
			continue
		}
		d.log.Printf("cleaned stale path=%s", name)
	}
	return nil
}

func currentVersion(storeDir string) string {
	target, err := os.Readlink(filepath.Join(storeDir, currentLink))
	if err != nil {
		return ""
	}
	return strings.TrimPrefix(target, versionPrefix)
}

func (d *daemon) sleep(ctx context.Context, dur time.Duration) {
	if dur <= 0 {
		return
	}
	timer := time.NewTimer(dur)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}
