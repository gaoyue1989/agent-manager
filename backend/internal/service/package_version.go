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
	"gorm.io/gorm/clause"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/store"
)

var (
	ErrNoEffectiveChanges  = errors.New("no effective changes against base package")
	ErrChecksumMismatch    = errors.New("base package changed concurrently (checksum mismatch)")
	ErrAgentsMDUndeletable = errors.New("AGENTS.md at package root cannot be deleted")
	// ErrUpsertInvalid 版本请求的客户端输入类错误（超限/编码非法），统一映射 400
	ErrUpsertInvalid = errors.New("invalid version request")
	// ErrVersionConflict 同 slug 的版本号被并发保存占用（issue #172）：映射 409
	ErrVersionConflict = errors.New("package version conflict (concurrent save)")
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

// reservedRootDirs 运行时按目录扫描的保留根路径（OAF 规范 §目录结构）：包编辑器
// 禁止以文件形式占用（issue #174——根文件 skills 曾让 skills/ 技能目录不可读、
// 运行时扫描行为不可预期）。
var reservedRootDirs = map[string]bool{
	"skills":      true,
	"mcp-configs": true,
}

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
	invalid := func(format string, args ...any) error {
		return fmt.Errorf("%w: "+format, append([]any{ErrUpsertInvalid}, args...)...)
	}
	for _, u := range req.Upserts {
		clean, err := store.CleanSubPath(u.Path)
		if err != nil {
			return nil, nil, fmt.Errorf("upsert %q: %w", u.Path, err)
		}
		// 保留目录名不可被文件占用（issue #174）：一级路径恰为保留目录名 → 400
		if reservedRootDirs[clean] {
			return nil, nil, fmt.Errorf("%w: %q is a reserved directory name (skills, mcp-configs)", ErrUpsertInvalid, clean)
		}
		var content []byte
		switch strings.ToLower(u.Encoding) {
		case "", "utf8", "utf-8":
			content = []byte(u.Content)
		case "base64":
			// 先按 base64 长度预判（解码后 ≈ 3/4 长度），避免超大串先解码再拒的内存放大
			if len(u.Content) > base64LenLimit(maxUpsertFileBytes) {
				return nil, nil, invalid("upsert %q: content exceeds %d bytes", u.Path, maxUpsertFileBytes)
			}
			content, err = base64.StdEncoding.DecodeString(u.Content)
			if err != nil {
				return nil, nil, invalid("upsert %q: invalid base64: %v", u.Path, err)
			}
		default:
			return nil, nil, invalid("upsert %q: unsupported encoding %q", u.Path, u.Encoding)
		}
		if len(content) > maxUpsertFileBytes {
			return nil, nil, invalid("upsert %q: content exceeds %d bytes", u.Path, maxUpsertFileBytes)
		}
		sumUpsertBytes += len(content)
		if sumUpsertBytes > maxUpsertTotalBytes {
			return nil, nil, invalid("upserts total size exceeds %d bytes", maxUpsertTotalBytes)
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
	// 解析新 AGENTS.md frontmatter（事务前只读预检：解析失败属用户输入错误，
	// 提前返回可避免无谓的锁等待；事务内版本号决策以行锁下的最新链尾为准）
	preCfg, err := oaf.ParseOAF(string(files["AGENTS.md"]))
	if err != nil {
		return nil, nil, fmt.Errorf("parse AGENTS.md: %w", err)
	}
	if err := preCfg.Validate(); err != nil {
		return nil, nil, fmt.Errorf("invalid AGENTS.md: %w", err)
	}
	// 显式改了版本（≠ base.Version）→ 尊重显式值；否则自动 bump（具体值事务内定）
	explicitVersion := preCfg.Version != base.Version

	// 复用上传管线：校验 + 解析 frontmatter（版本决策在 commitVersionTx 事务内）
	zipData, err := buildZipBytes(files)
	if err != nil {
		return nil, nil, err
	}
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

	// 版本决策 + 入库在同一事务（issue #172）：对同 slug 版本链行加 FOR UPDATE 锁
	// 串行化「读链尾 → 定版本号 → 插入」，并发保存只有一个成功，其余 409 或串行递增。
	// 锁对象是 slug 链上行集——copy-on-write 不改任何已有行，锁只保证同一 slug 的
	// 并发创建互斥；无行时（首版本）退化为唯一索引兜底（并发插入撞 uk 必有一个失败）。
	rec, dupWarn, err := s.commitVersionTx(base, files, cfg, zi, explicitVersion)
	if dupWarn != "" {
		warnings = append(warnings, dupWarn)
	}
	if err != nil {
		return nil, nil, err
	}
	return rec, warnings, nil
}

// commitVersionTx 版本号决策（自动 bump / 显式值冲突裁决）+ zip 重打包 + 入库落盘。
// 事务内对同 slug 全部已有版本行加 FOR UPDATE：串行化并发保存的 nextVersion 计算，
// 消除「校验基础包 checksum（copy-on-write 恒通过）→ 双双插入同版本」的竞态窗口。
func (s *PackageService) commitVersionTx(base *store.OafPackage, files map[string][]byte,
	cfg *oaf.Config, zi *store.ZipInfo, explicitVersion bool) (*store.OafPackage, string, error) {

	var rec *store.OafPackage
	var dupWarn string
	err := s.DB.Transaction(func(tx *gorm.DB) error {
		rec = nil
		dupWarn = ""
		// 锁定同 slug 全部版本行（版本链）：并发事务在此排队
		var locked []store.OafPackage
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("slug = ?", cfg.Slug).Find(&locked).Error; err != nil {
			return err
		}
		// 链上最新版本（id 最大 = 最新创建）
		latest := base.Version
		if len(locked) > 0 {
			latest = locked[len(locked)-1].Version
		}
		version := cfg.Version
		if explicitVersion {
			// 用户显式指定版本：撞链即拒绝（409），不再静默并存（issue #172 预期语义）
			for _, p := range locked {
				if p.Version == version && p.ID != base.ID {
					return fmt.Errorf("%w: %s@%s already exists (id=%d)",
						ErrVersionConflict, cfg.Slug, version, p.ID)
				}
			}
		} else {
			// 自动 bump（issue #155 口径延续）：基于「行锁下的链尾最新版本」递增，
			// 并发串行化后第二个请求拿到 1.0.2 而非重复 1.0.1
			version = nextVersion(latest)
			if out, rerr := rewriteAgentsMDVersion(files["AGENTS.md"], version); rerr == nil {
				files["AGENTS.md"] = []byte(out)
			}
			// frontmatter 改动 → 重打包重校验（checksum/文件数以最终 zip 为准）
			zipData, berr := buildZipBytes(files)
			if berr != nil {
				return berr
			}
			zi2, err := s.FS.InspectZip(bytes.NewReader(zipData))
			if err != nil {
				return err
			}
			cfg2, err := oaf.ParseOAF(string(zi2.AgentsMD))
			if err != nil {
				return fmt.Errorf("parse AGENTS.md: %w", err)
			}
			if err := cfg2.Validate(); err != nil {
				return fmt.Errorf("invalid AGENTS.md: %w", err)
			}
			cfg = cfg2
			zi = zi2
		}
		// 防御：自动 bump 结果与显式值都再查一次唯一性（锁内天然无并发，防存量脏数据）
		var dupCnt int64
		if err := tx.Model(&store.OafPackage{}).Where("slug = ? AND version = ? AND id <> ?",
			cfg.Slug, cfg.Version, base.ID).Count(&dupCnt).Error; err != nil {
			return err
		}
		if dupCnt > 0 {
			if explicitVersion {
				return fmt.Errorf("%w: %s@%s already exists", ErrVersionConflict, cfg.Slug, cfg.Version)
			}
			// 自动 bump 撞存量（如人工造的 .fix 行）：继续顺延直至空位（链已锁，有限轮）
			for i := 0; i < store.MaxEntries; i++ {
				version = nextVersion(version)
				var c int64
				if err := tx.Model(&store.OafPackage{}).Where("slug = ? AND version = ? AND id <> ?",
					cfg.Slug, version, base.ID).Count(&c).Error; err != nil {
					return err
				}
				if c == 0 {
					if out, rerr := rewriteAgentsMDVersion(files["AGENTS.md"], version); rerr == nil {
						files["AGENTS.md"] = []byte(out)
					}
					zipData, berr := buildZipBytes(files)
					if berr != nil {
						return berr
					}
					zi2, err := s.FS.InspectZip(bytes.NewReader(zipData))
					if err != nil {
						return err
					}
					cfg2, err := oaf.ParseOAF(string(zi2.AgentsMD))
					if err != nil {
						return fmt.Errorf("parse AGENTS.md: %w", err)
					}
					if err := cfg2.Validate(); err != nil {
						return fmt.Errorf("invalid AGENTS.md: %w", err)
					}
					cfg = cfg2
					zi = zi2
					break
				}
			}
		}
		manifest, _ := json.Marshal(cfg)
		warnJSON, _ := json.Marshal(dupWarn)
		rec = &store.OafPackage{
			Name: cfg.Name, Slug: cfg.Slug, Version: cfg.Version,
			Description:  cfg.Description,
			ManifestJSON: string(manifest), WarningsJSON: string(warnJSON),
			Checksum:  zi.Checksum,
			FileCount: zi.FileCount, TotalSize: zi.TotalSize,
			SourcePackageID: base.ID,
		}
		if err := tx.Create(rec).Error; err != nil {
			return translateStorageError(err)
		}
		rec.DirPath = fmt.Sprintf("packages/%d", rec.ID)
		// 落盘失败则回滚记录（zip 重新打包后 ExtractZipTo 需最终 zi）
		if _, _, _, werr := s.FS.ExtractZipTo(rec.DirPath, zi); werr != nil {
			return werr
		}
		return tx.Model(rec).Update("dir_path", rec.DirPath).Error
	})
	if err != nil {
		return nil, dupWarn, err
	}
	return rec, dupWarn, nil
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
// 文件统一按 0755 落 zip：后续 ExtractZipTo 会按「有 x 位升 0755，否则 0644」归一，
// 此处统一给 x 位可保证基础包内 +x 脚本经在线编辑后不丢失可执行权限。
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
