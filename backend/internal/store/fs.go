package store

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// 包上传安全限制。
const (
	MaxZipSize   = 20 << 20  // zip 原始大小上限 20MB
	MaxEntries   = 2000      // 条目数上限
	MaxExtracted = 100 << 20 // 解压总量上限 100MB
)

var (
	ErrNoAgentsMD   = errors.New("zip must contain AGENTS.md at root")
	ErrZipTooLarge  = fmt.Errorf("zip exceeds %d bytes", MaxZipSize)
	ErrTooManyFiles = fmt.Errorf("zip exceeds %d entries", MaxEntries)
	ErrZipSlip      = errors.New("illegal path in zip (zip-slip or symlink)")
)

// ZipInfo 校验通过后的 zip 摘要与读取器。
type ZipInfo struct {
	Reader    *zip.Reader
	AgentsMD  []byte
	FileCount int
	TotalSize int64
	Checksum  string
}

// HasDir 判断包内是否存在目录 dir（任一条目位于 dir/ 下）。
func (z *ZipInfo) HasDir(dir string) bool {
	dir = strings.Trim(dir, "/")
	for _, zf := range z.Reader.File {
		n := strings.Trim(zf.Name, "/")
		if n == dir || strings.HasPrefix(n, dir+"/") {
			return true
		}
	}
	return false
}

// FS 管理 DATA_ROOT 下的包目录。
type FS struct{ Root string }

// PackageDir 返回包的绝对目录。
func (f *FS) PackageDir(rel string) string {
	return filepath.Join(f.Root, rel)
}

// InspectZip 读取并安全校验 zip：大小、条目数、zip-slip、符号链接、根级 AGENTS.md。
func (f *FS) InspectZip(r io.Reader) (*ZipInfo, error) {
	data, err := io.ReadAll(io.LimitReader(r, MaxZipSize+1))
	if err != nil {
		return nil, err
	}
	if len(data) > MaxZipSize {
		return nil, ErrZipTooLarge
	}
	sum := sha256.Sum256(data)
	zr, err := zip.NewReader(strings.NewReader(string(data)), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("invalid zip: %w", err)
	}
	if len(zr.File) > MaxEntries {
		return nil, ErrTooManyFiles
	}

	info := &ZipInfo{Reader: zr, Checksum: hex.EncodeToString(sum[:])}
	var total int64
	for _, zf := range zr.File {
		if err := validateEntry(zf); err != nil {
			return nil, err
		}
		total += int64(zf.UncompressedSize64)
		if total > MaxExtracted {
			return nil, errors.New("extracted content exceeds limit")
		}
		if zf.Name == "AGENTS.md" {
			rc, err := zf.Open()
			if err != nil {
				return nil, err
			}
			info.AgentsMD, err = io.ReadAll(rc)
			rc.Close()
			if err != nil {
				return nil, err
			}
		}
		if !zf.FileInfo().IsDir() {
			info.FileCount++
		}
	}
	if info.AgentsMD == nil {
		return nil, ErrNoAgentsMD
	}
	info.TotalSize = total
	return info, nil
}

// validateEntry 单条目路径与类型校验。
func validateEntry(zf *zip.File) error {
	name := zf.Name
	if len(name) > 512 || zf.Mode()&os.ModeSymlink != 0 {
		return ErrZipSlip
	}
	clean := filepath.Clean(name)
	if filepath.IsAbs(clean) || strings.HasPrefix(clean, "..") ||
		strings.Contains(name, "\\") || strings.Contains(clean, "../") {
		return ErrZipSlip
	}
	return nil
}

// ExtractZipTo 将已校验的 zip 解包到 root/rel。
func (f *FS) ExtractZipTo(rel string, zi *ZipInfo) (int, int64, string, error) {
	dest := f.PackageDir(rel)
	var count int
	var total int64
	for _, zf := range zi.Reader.File {
		target := filepath.Join(dest, filepath.Clean(zf.Name))
		if !strings.HasPrefix(target, filepath.Clean(dest)+string(os.PathSeparator)) {
			return 0, 0, "", ErrZipSlip
		}
		if zf.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return 0, 0, "", err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return 0, 0, "", err
		}
		// 保证最低可读权限（zip 条目可能自带 0600 等私有权限，业务 Pod 非 root 用户需可读）
		perm := os.FileMode(0o644)
		if zf.Mode().Perm()&0o111 != 0 {
			perm = 0o755
		}
		src, err := zf.Open()
		if err != nil {
			return 0, 0, "", err
		}
		out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, perm)
		if err != nil {
			src.Close()
			return 0, 0, "", err
		}
		n, err := io.Copy(out, src)
		src.Close()
		out.Close()
		if err != nil {
			return 0, 0, "", err
		}
		count++
		total += n
	}
	return count, total, zi.Checksum, nil
}

// RemovePackage 删除包目录。
func (f *FS) RemovePackage(rel string) error {
	return os.RemoveAll(f.PackageDir(rel))
}

// FileEntry 文件树节点。
type FileEntry struct {
	Name     string      `json:"name"`
	Path     string      `json:"path"`
	IsDir    bool        `json:"isDir"`
	Size     int64       `json:"size,omitempty"`
	Children []FileEntry `json:"children,omitempty"`
}

// Tree 列出包内文件树。
func (f *FS) Tree(rel string) ([]FileEntry, error) {
	base := f.PackageDir(rel)
	var walk func(dir, prefix string) ([]FileEntry, error)
	walk = func(dir, prefix string) ([]FileEntry, error) {
		ents, err := os.ReadDir(dir)
		if err != nil {
			return nil, err
		}
		var out []FileEntry
		for _, e := range ents {
			relPath := e.Name()
			if prefix != "" {
				relPath = prefix + "/" + e.Name()
			}
			entry := FileEntry{Name: e.Name(), Path: relPath, IsDir: e.IsDir()}
			if e.IsDir() {
				kids, err := walk(filepath.Join(dir, e.Name()), relPath)
				if err != nil {
					return nil, err
				}
				entry.Children = kids
			} else if info, err := e.Info(); err == nil {
				entry.Size = info.Size()
			}
			out = append(out, entry)
		}
		return out, nil
	}
	return walk(base, "")
}

// ReadFile 读包内单个文件（仅允许相对包根的路径）。
func (f *FS) ReadFile(rel, sub string) ([]byte, error) {
	clean, err := CleanSubPath(sub)
	if err != nil {
		return nil, err
	}
	return os.ReadFile(filepath.Join(f.PackageDir(rel), clean))
}
// MaxPreviewSize 在线预览的文本文件大小上限（超出走下载）。
const MaxPreviewSize = 512 << 10

// textExts 视为文本可预览的扩展名白名单（之外靠内容嗅探兜底）。
var textExts = map[string]bool{
	".md": true, ".markdown": true, ".txt": true, ".yaml": true, ".yml": true,
	".json": true, ".xml": true, ".html": true, ".css": true, ".csv": true,
	".js": true, ".mjs": true, ".ts": true, ".tsx": true, ".jsx": true,
	".go": true, ".py": true, ".sh": true, ".bash": true, ".sql": true,
	".toml": true, ".ini": true, ".conf": true, ".properties": true,
	".java": true, ".gradle": true, ".dockerfile": true, ".env": true,
	".gitignore": true, ".license": true,
}

// IsTextContent 判定文件是否可作为文本预览：扩展名白名单 + 内容嗅探（含 NUL 即二进制）双保险。
// 二进制或超过 MaxPreviewSize 的文件返回 false，由调用方引导走下载。
func IsTextContent(name string, data []byte) bool {
	if len(data) > MaxPreviewSize {
		return false
	}
	ext := strings.ToLower(filepath.Ext(name))
	if ext == "" && (strings.EqualFold(filepath.Base(name), "dockerfile") || strings.HasPrefix(filepath.Base(name), ".")) {
		// 无扩展名的 Dockerfile / dotfile（.gitignore 等）按文本处理
		return !bytes.ContainsRune(data, 0)
	}
	if !textExts[ext] {
		return false
	}
	// 嗅探前 8KB，出现 NUL 字节视为二进制
	sniff := data
	if len(sniff) > 8192 {
		sniff = sniff[:8192]
	}
	return !bytes.ContainsRune(sniff, 0)
}

// CleanSubPath 包内相对路径合法性校验：拒绝空、绝对路径、..、反斜杠、超长路径。
// 返回清理后的相对路径（不含前导 /），供 ReadFile/WriteZipTo 等复用。
// 非法路径统一包装 ErrZipSlip（与 zip 条目校验同一哨兵，mapError 映射 400）。
func CleanSubPath(sub string) (string, error) {
	if sub == "" {
		return "", fmt.Errorf("%w: empty", ErrZipSlip)
	}
	if len(sub) > 512 || strings.Contains(sub, "\\") || strings.Contains(sub, "..") {
		return "", fmt.Errorf("%w: %q", ErrZipSlip, sub)
	}
	clean := strings.TrimPrefix(filepath.Clean("/"+sub), "/")
	if clean == "" || clean == "." {
		return "", fmt.Errorf("%w: %q", ErrZipSlip, sub)
	}
	return clean, nil
}

// ZipPackage 将包目录打包为 zip 字节流（用于整包在线下载）。
func (f *FS) ZipPackage(rel string) ([]byte, error) {
	base := f.PackageDir(rel)
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	err := filepath.WalkDir(base, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return werr
		}
		sub, err := filepath.Rel(base, p)
		if err != nil {
			return err
		}
		if sub == "." {
			return nil
		}
		if d.IsDir() {
			_, err = zw.Create(sub + "/")
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		hdr := &zip.FileHeader{Name: sub, Method: zip.Deflate, Modified: info.ModTime()}
		hdr.SetMode(d.Type().Perm())
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			return err
		}
		src, err := os.Open(p)
		if err != nil {
			return err
		}
		defer src.Close()
		_, err = io.Copy(w, src)
		return err
	})
	if err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// WriteZipTo 将已通过安全校验的 zip 字节流解包到 root/rel（目录必须为空或不存在）。
// 权限与路径规则同 ExtractZipTo（最低 0644，可执行位升 0755）。
func (f *FS) WriteZipTo(rel string, zipData []byte) (int, int64, error) {
	zr, err := zip.NewReader(bytes.NewReader(zipData), int64(len(zipData)))
	if err != nil {
		return 0, 0, fmt.Errorf("invalid zip: %w", err)
	}
	if len(zr.File) > MaxEntries {
		return 0, 0, ErrTooManyFiles
	}
	dest := f.PackageDir(rel)
	var count int
	var total int64
	for _, zf := range zr.File {
		if err := validateEntry(zf); err != nil {
			return 0, 0, err
		}
		target := filepath.Join(dest, filepath.Clean(zf.Name))
		if !strings.HasPrefix(target, filepath.Clean(dest)+string(os.PathSeparator)) {
			return 0, 0, ErrZipSlip
		}
		if zf.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return 0, 0, err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return 0, 0, err
		}
		perm := os.FileMode(0o644)
		if zf.Mode().Perm()&0o111 != 0 {
			perm = 0o755
		}
		src, err := zf.Open()
		if err != nil {
			return 0, 0, err
		}
		out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, perm)
		if err != nil {
			src.Close()
			return 0, 0, err
		}
		n, err := io.Copy(out, src)
		src.Close()
		out.Close()
		if err != nil {
			return 0, 0, err
		}
		count++
		total += n
	}
	return count, total, nil
}
