// 包在线预览 / 编辑派生：单文件读取、整包打包、基于基础包合成新版本。
// 核心约束：包目录经 subPath 只读挂载进业务 Pod，编辑永不原地写 —— 一律另存为新版本包
// （新 OafPackage 记录 + 新 PVC 目录 packages/{newID}），复用上传校验管线落盘。
package service

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"path"
	"strings"

	"gorm.io/gorm"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/store"
)

var (
	ErrNoEffectiveChanges  = errors.New("no effective changes against base package")
	ErrChecksumMismatch    = errors.New("base package changed concurrently (checksum mismatch)")
	ErrAgentsMDUndeletable = errors.New("AGENTS.md at package root cannot be deleted")
)

// FileContent 单文件预览载荷：binary=true 时 content 为空，前端引导走下载端点。
type FileContent struct {
	Path    string `json:"path"`
	Size    int64  `json:"size"`
	Binary  bool   `json:"binary"`
	Content string `json:"content,omitempty"`
}

// FileContent 读包内单文件并判定是否可文本预览。
func (s *PackageService) FileContent(pkg *store.OafPackage, sub string) (*FileContent, error) {
	data, err := s.FS.ReadFile(pkg.DirPath, sub)
	if err != nil {
		switch {
		case errors.Is(err, store.ErrZipSlip):
			return nil, err
		case errors.Is(err, fs.ErrNotExist):
			return nil, ErrNotFound
		default:
			return nil, fmt.Errorf("read file: %w", err)
		}
	}
	if !store.IsTextContent(sub, data) {
		return &FileContent{Path: sub, Size: int64(len(data)), Binary: true}, nil
	}
	return &FileContent{Path: sub, Size: int64(len(data)), Content: string(data)}, nil
}

// Zip 整包打包为 zip 字节流（在线下载用）。
func (s *PackageService) Zip(pkg *store.OafPackage) ([]byte, error) {
	return s.FS.ZipPackage(pkg.DirPath)
}

// VersionUpsert 单文件新增/覆盖。
type VersionUpsert struct {
	Path     string `json:"path"`
	Content  string `json:"content"`
	Encoding string `json:"encoding,omitempty"` // utf8(默认) | base64
}

// VersionRequest 基于基础包生成新版本的请求体。
type VersionRequest struct {
	Upserts []VersionUpsert `json:"upserts"`
	Deletes []string        `json:"deletes"`
	// ExpectedBaseChecksum 可选乐观锁：与当前基础包 checksum 不符即拒绝（409）。
	ExpectedBaseChecksum string `json:"expectedBaseChecksum,omitempty"`
}

// maxUpsertFileBytes 单文件内容上限（utf8 字节数 / base64 解码后字节数）。
const maxUpsertFileBytes = 256 << 10

// maxUpsertTotalBytes 单次请求 upserts 内容总量上限（防多文件聚合放大内存）。
const maxUpsertTotalBytes = 4 << 20

// base64LenLimit 由目标字节数反推 base64 编码串长度上限（4 字符编 3 字节）。
func base64LenLimit(n int) int { return (n*4 + 2) / 3 }

// CreateVersion 基于基础包 + 变更集合生成新版本包（copy-on-write，不改基础包任何字节）。
// 实现上在内存合成完整 zip，再走与 Upload 完全相同的校验落盘管线（InspectZip →
// ParseOAF/Validate → 事务入库 → ExtractZipTo），zip 安全上限与 warnings 计算自动复用。
func (s *PackageService) CreateVersion(base *store.OafPackage, req VersionRequest) (*store.OafPackage, []string, error) {
	if req.ExpectedBaseChecksum != "" && req.ExpectedBaseChecksum != base.Checksum {
		return nil, nil, ErrChecksumMismatch
	}
	if len(req.Upserts) > store.MaxEntries || len(req.Deletes) > store.MaxEntries {
		return nil, nil, store.ErrTooManyFiles
	}

	// 解码并预校验 upserts：路径合法性 + 单文件上限 + 内容可得
	type pending struct {
		path    string
		content []byte
	}
	ups := make([]pending, 0, len(req.Upserts))
	sumUpsertBytes := 0
	for _, u := range req.Upserts {
		clean, err := store.CleanSubPath(u.Path)
		if err != nil {
			return nil, nil, fmt.Errorf("upsert %q: %w", u.Path, err)
		}
		var content []byte
		switch strings.ToLower(u.Encoding) {
		case "", "utf8", "utf-8":
			content = []byte(u.Content)
		case "base64":
			// 先按 base64 长度预判（解码后 ≈ 3/4 长度），避免超大串先解码再拒的内存放大
			if len(u.Content) > base64LenLimit(maxUpsertFileBytes) {
				return nil, nil, fmt.Errorf("upsert %q: content exceeds %d bytes", u.Path, maxUpsertFileBytes)
			}
			content, err = base64.StdEncoding.DecodeString(u.Content)
			if err != nil {
				return nil, nil, fmt.Errorf("upsert %q: invalid base64: %w", u.Path, err)
			}
		default:
			return nil, nil, fmt.Errorf("upsert %q: unsupported encoding %q", u.Path, u.Encoding)
		}
		if len(content) > maxUpsertFileBytes {
			return nil, nil, fmt.Errorf("upsert %q: content exceeds %d bytes", u.Path, maxUpsertFileBytes)
		}
		sumUpsertBytes += len(content)
		if sumUpsertBytes > maxUpsertTotalBytes {
			return nil, nil, fmt.Errorf("upserts total size exceeds %d bytes", maxUpsertTotalBytes)
		}
		ups = append(ups, pending{path: clean, content: content})
	}

	// deletes 预校验：路径合法性 + 根级 AGENTS.md 保护
	dels := make(map[string]bool, len(req.Deletes))
	for _, d := range req.Deletes {
		clean, err := store.CleanSubPath(d)
		if err != nil {
			return nil, nil, fmt.Errorf("delete %q: %w", d, err)
		}
		if clean == "AGENTS.md" {
			return nil, nil, ErrAgentsMDUndeletable
		}
		dels[clean] = true
	}
	// upsert 与 delete 同路径冲突时以 upsert 为准（删除条目无效化）
	for _, u := range ups {
		delete(dels, u.path)
	}

	// 读取基础包全部文件，应用变更集，合成新 zip
	files, err := s.collectBaseFiles(base, nil)
	if err != nil {
		return nil, nil, err
	}
	changed := false
	for _, u := range ups {
		if old, ok := files[u.path]; !ok || !bytes.Equal(old, u.content) {
			changed = true
		}
		files[u.path] = u.content
	}
	// 删除检测必须基于未剔除的原始文件集（剔除后查不到 = 无变更判断恒假）
	for d := range dels {
		if _, ok := files[d]; ok {
			changed = true
			delete(files, d)
		}
	}
	if !changed {
		return nil, nil, ErrNoEffectiveChanges
	}
	zipData, err := buildZipBytes(files)
	if err != nil {
		return nil, nil, err
	}

	// 复用上传管线：校验 + 解析 frontmatter + 事务入库 + 落盘
	zi, err := s.FS.InspectZip(bytes.NewReader(zipData))
	if err != nil {
		return nil, nil, err
	}
	cfg, err := oaf.ParseOAF(string(zi.AgentsMD))
	if err != nil {
		return nil, nil, fmt.Errorf("parse AGENTS.md: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, nil, fmt.Errorf("invalid AGENTS.md: %w", err)
	}
	// 宽松模式 warnings（同 Upload，含 mcpServer configDir 缺失检查）
	warnings := cfg.Warnings()
	for _, m := range cfg.MCPServers {
		if m.ConfigDir != "" && !zi.HasDir(m.ConfigDir) {
			warnings = append(warnings, fmt.Sprintf("mcpServer %q configDir %q missing in package", m.Server, m.ConfigDir))
		}
	}
	// 同 slug+version 已有其他包：提示不阻断
	var dupCnt int64
	if err := s.DB.Model(&store.OafPackage{}).Where("slug = ? AND version = ? AND id <> ?",
		cfg.Slug, cfg.Version, base.ID).Count(&dupCnt).Error; err != nil {
		return nil, nil, err
	}
	if dupCnt > 0 {
		warnings = append(warnings, fmt.Sprintf("package %s@%s already exists (other than base)", cfg.Slug, cfg.Version))
	}

	manifest, _ := json.Marshal(cfg)
	warnJSON, _ := json.Marshal(warnings)
	rec := &store.OafPackage{
		Name: cfg.Name, Slug: cfg.Slug, Version: cfg.Version,
		Description:  cfg.Description,
		ManifestJSON: string(manifest), WarningsJSON: string(warnJSON),
		Checksum:  zi.Checksum,
		FileCount: zi.FileCount, TotalSize: zi.TotalSize,
		SourcePackageID: base.ID,
	}
	err = s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(rec).Error; err != nil {
			return err
		}
		rec.DirPath = fmt.Sprintf("packages/%d", rec.ID)
		// 落盘失败则回滚记录
		if _, _, _, werr := s.FS.ExtractZipTo(rec.DirPath, zi); werr != nil {
			return werr
		}
		return tx.Model(rec).Update("dir_path", rec.DirPath).Error
	})
	if err != nil {
		return nil, nil, err
	}
	return rec, warnings, nil
}

// collectBaseFiles 读取基础包全部文件内容为 path→content 映射。
// dels 非空时剔除删除集（当前调用恒传 nil：删除统一在 CreateVersion 主流程
// 基于完整文件集判定 changed 后再剔除，避免纯删除误判为无变更）。
func (s *PackageService) collectBaseFiles(base *store.OafPackage, dels map[string]bool) (map[string][]byte, error) {
	tree, err := s.FS.Tree(base.DirPath)
	if err != nil {
		return nil, fmt.Errorf("walk base package: %w", err)
	}
	files := make(map[string][]byte)
	var walk func(ents []store.FileEntry) error
	walk = func(ents []store.FileEntry) error {
		for _, e := range ents {
			if e.IsDir {
				if err := walk(e.Children); err != nil {
					return err
				}
				continue
			}
			if dels[e.Path] {
				continue
			}
			data, err := s.FS.ReadFile(base.DirPath, e.Path)
			if err != nil {
				return fmt.Errorf("read base file %q: %w", e.Path, err)
			}
			files[e.Path] = data
		}
		return nil
	}
	if err := walk(tree); err != nil {
		return nil, err
	}
	if _, ok := files["AGENTS.md"]; !ok {
		return nil, store.ErrNoAgentsMD
	}
	return files, nil
}

// buildZipBytes 将 path→content 映射合成为 zip 字节流（Deflate，路径统一 / 分隔）。
// 权限保持与 ExtractZipTo 相反方向的一致性：普通文件 0644（可执行位丢失不可接受，
// 基础包内 +x 脚本经在线编辑后必须保持可执行）。
func buildZipBytes(files map[string][]byte) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for p, content := range files {
		if p == "" || strings.HasSuffix(p, "/") {
			continue
		}
		// 统一 zip 内路径分隔符并防御畸形键
		p = path.Clean(strings.ReplaceAll(p, "\\", "/"))
		if strings.HasPrefix(p, "../") || p == ".." {
			return nil, store.ErrZipSlip
		}
		hdr := &zip.FileHeader{Name: p, Method: zip.Deflate}
		hdr.SetMode(0o755)
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			return nil, err
		}
		if _, err := io.Copy(w, bytes.NewReader(content)); err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
