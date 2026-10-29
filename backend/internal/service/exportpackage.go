// 导出级包编辑与发布标记（跨集群导出设计 §3「发布标记冻结」/§8，2026-10）。
// 文件级契约见 docs/implementation/cross-cluster-export-contract.md §2.2/§5.6。
//
// 模型：每服务至多一条 draft 标记行（PUT 保存单文件即 upsert 该行的完整文件快照），
// 打标记把 draft（无 draft 时直接取源包）冻结为 frozen 行——分配 marker `{包版本}-e{N}`
// （序号按服务内递增，复合唯一约束 (service_id, marker)，契约 §9-D2）并落 checksum。
// 快照自包含（全部文件含二进制 base64，不回读源包——源包可能已被换版/删除），
// 是 OAF 镜像构建的唯一输入（无标记不可构建，标记不可修改——改动生成新标记）。
package service

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// maxMarkSnapshotBytes mark 快照 FilesJSON 上限（mediumtext 16MB，契约 §5.6/§9-D7：
// 超限 400 并提示改用更小包；快照体积 ≈ 包体积 ×1.33）。
const maxMarkSnapshotBytes = 16 << 20

// maxExportUpsertFileBytes 导出草稿单文件内容上限（对齐包在线编辑 maxUpsertFileBytes 256KB）。
const maxExportUpsertFileBytes = 256 << 10

// ErrMarkNotBuildable 无可用发布标记 / 标记未冻结（构建前置校验，mapError 走 400）。
var ErrMarkNotBuildable = errors.New("export package mark not found or not frozen")

// exportFileEntry 快照文件条目（FilesJSON 数组元素）：文本 utf8 直存、二进制 base64。
type exportFileEntry struct {
	Path     string `json:"path"`
	Encoding string `json:"encoding"` // utf8 | base64
	Content  string `json:"content"`
}

// ExportMarkView 发布标记视图（列表/冻结返回；FilesJSON 永不外露——模型 json:"-"）。
type ExportMarkView struct {
	ID             uint      `json:"id"`
	ServiceID      uint      `json:"serviceId"`
	Marker         string    `json:"marker"`
	PackageVersion string    `json:"packageVersion"`
	FileCount      int       `json:"fileCount"`
	Checksum       string    `json:"checksum"`
	Status         string    `json:"status"`
	CreatedBy      string    `json:"createdBy"`
	CreatedAt      time.Time `json:"createdAt"`
}

// ExportPackageSourceInfo 导出页源包信息（版本链锚点）。
type ExportPackageSourceInfo struct {
	PackageID uint   `json:"packageId"`
	Slug      string `json:"slug"`
	Version   string `json:"version"`
	Checksum  string `json:"checksum"`
}

// ExportPackageFileView 导出包编辑器文件行：binary=true 时 content 为空（前端引导走
// 既有整包/单文件下载端点）；readOnly=true 为 AGENTS.md / skills/ 只读项（设计 §9 标注）。
type ExportPackageFileView struct {
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	Binary   bool   `json:"binary"`
	ReadOnly bool   `json:"readOnly"`
	Encoding string `json:"encoding"` // utf8 | base64
	Content  string `json:"content,omitempty"`
}

// ExportPackageView GET .../export/package 响应：源包 + 编辑草稿叠加后的有效文件视图。
type ExportPackageView struct {
	Package ExportPackageSourceInfo `json:"package"`
	Files   []ExportPackageFileView `json:"files"`
	Draft   *ExportMarkView         `json:"draft,omitempty"`
}

// loadServiceForExport 导出链路服务归属校验（服务须属本空间，模式同 ConsoleAgentDetail）。
func (c *Core) loadServiceForExport(wsID, svcID uint) (*store.ServiceEntity, error) {
	var svc store.ServiceEntity
	if err := c.DB.First(&svc, svcID).Error; err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != wsID {
		return nil, ErrNotFound
	}
	return &svc, nil
}

// checksumOfEntries canonical checksum：条目按 path 排序后 marshal 整体求 sha256 hex（契约 §5.6）。
func checksumOfEntries(entries []exportFileEntry) string {
	sorted := make([]exportFileEntry, len(entries))
	copy(sorted, entries)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Path < sorted[j].Path })
	sum := sha256.Sum256([]byte(mustJSON(sorted)))
	return hex.EncodeToString(sum[:])
}

// encodeExportEntries 原始文件 map → 快照条目（文本/二进制以 store.IsTextContent 判定，fs.go:287）。
func encodeExportEntries(files map[string][]byte) []exportFileEntry {
	out := make([]exportFileEntry, 0, len(files))
	for p, data := range files {
		if store.IsTextContent(p, data) {
			out = append(out, exportFileEntry{Path: p, Encoding: "utf8", Content: string(data)})
		} else {
			out = append(out, exportFileEntry{Path: p, Encoding: "base64",
				Content: base64.StdEncoding.EncodeToString(data)})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

// decodeExportEntries 快照条目 → 原始文件 map（构建物化与快照叠加共用）。
func decodeExportEntries(entries []exportFileEntry) (map[string][]byte, error) {
	out := make(map[string][]byte, len(entries))
	for _, e := range entries {
		switch e.Encoding {
		case "utf8":
			out[e.Path] = []byte(e.Content)
		case "base64":
			raw, err := base64.StdEncoding.DecodeString(e.Content)
			if err != nil {
				return nil, fmt.Errorf("decode snapshot file %q: %w", e.Path, err)
			}
			out[e.Path] = raw
		default:
			return nil, fmt.Errorf("unknown snapshot encoding %q for %q", e.Encoding, e.Path)
		}
	}
	return out, nil
}

// parseMarkFiles 反序列化 FilesJSON（空串按空快照处理）。
func parseMarkFiles(raw string) ([]exportFileEntry, error) {
	if raw == "" {
		return nil, nil
	}
	var entries []exportFileEntry
	if err := json.Unmarshal([]byte(raw), &entries); err != nil {
		return nil, fmt.Errorf("corrupt mark files json: %w", err)
	}
	return entries, nil
}

// marshalMarkFiles 序列化 + 16MB 上限校验（§9-D7）。
func marshalMarkFiles(entries []exportFileEntry) (string, error) {
	raw, err := json.Marshal(entries)
	if err != nil {
		return "", err
	}
	if len(raw) > maxMarkSnapshotBytes {
		return "", fmt.Errorf("%w: export package snapshot exceeds %d bytes (package too large for export)",
			ErrInvalidForm, maxMarkSnapshotBytes)
	}
	return string(raw), nil
}

// loadDraftMark 取服务当前草稿标记（至多一条，Status=draft）。
func (c *Core) loadDraftMark(svcID uint) (*store.ExportPackageMarkEntity, error) {
	var m store.ExportPackageMarkEntity
	err := c.DB.Where("service_id = ? AND status = ?", svcID, store.ExportMarkDraft).First(&m).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// seedExportEntries 新草稿快照种子：最近一条 frozen 标记若与当前源包同版本则续用其快照
// （编辑会话跨标记连续：e2 = e1 改动 + 本次新改动）；源包已换版则重新取源包快照（rebase）。
func (c *Core) seedExportEntries(svcID uint, pkg *store.OafPackage) ([]exportFileEntry, error) {
	var last store.ExportPackageMarkEntity
	err := c.DB.Where("service_id = ? AND status = ?", svcID, store.ExportMarkFrozen).
		Order("id DESC").First(&last).Error
	if err == nil && last.PackageVersion == pkg.Version {
		entries, perr := parseMarkFiles(last.FilesJSON)
		if perr != nil {
			return nil, perr
		}
		if len(entries) > 0 {
			return entries, nil
		}
	} else if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}
	files, ferr := c.Packages.CollectBaseFiles(pkg)
	if ferr != nil {
		return nil, ferr
	}
	return encodeExportEntries(files), nil
}

// markView 实体 → 视图。
func markView(m *store.ExportPackageMarkEntity) *ExportMarkView {
	return &ExportMarkView{
		ID: m.ID, ServiceID: m.ServiceID, Marker: m.Marker, PackageVersion: m.PackageVersion,
		FileCount: m.FileCount, Checksum: m.Checksum, Status: m.Status,
		CreatedBy: m.CreatedBy, CreatedAt: m.CreatedAt,
	}
}

// ExportPackagePreview 导出包编辑页数据：源包信息 + 有效文件视图（源包叠加当前草稿）。
// AGENTS.md 与 skills/** 标注只读（设计 §9）；二进制文件不内联内容（引导走下载端点）。
func (c *Core) ExportPackagePreview(wsID, svcID uint) (*ExportPackageView, error) {
	svc, err := c.loadServiceForExport(wsID, svcID)
	if err != nil {
		return nil, err
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	files, err := c.Packages.CollectBaseFiles(pkg)
	if err != nil {
		return nil, err
	}
	draft, err := c.loadDraftMark(svcID)
	if err != nil {
		return nil, err
	}
	if draft != nil {
		entries, derr := parseMarkFiles(draft.FilesJSON)
		if derr != nil {
			return nil, derr
		}
		overlay, oerr := decodeExportEntries(entries)
		if oerr != nil {
			return nil, oerr
		}
		for p, data := range overlay {
			files[p] = data
		}
	}
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	out := make([]ExportPackageFileView, 0, len(paths))
	for _, p := range paths {
		data := files[p]
		text := store.IsTextContent(p, data)
		fv := ExportPackageFileView{
			Path: p, Size: int64(len(data)), Binary: !text, Encoding: "utf8",
			ReadOnly: exportFileReadOnly(p),
		}
		if text && len(data) <= store.MaxPreviewSize {
			fv.Content = string(data)
		}
		if !text {
			fv.Encoding = "base64"
		}
		out = append(out, fv)
	}
	view := &ExportPackageView{
		Package: ExportPackageSourceInfo{PackageID: pkg.ID, Slug: pkg.Slug, Version: pkg.Version, Checksum: pkg.Checksum},
		Files:   out,
	}
	if draft != nil {
		view.Draft = markView(draft)
	}
	return view, nil
}

// exportFileReadOnly 导出编辑只读项判定：根级 AGENTS.md（frontmatter 合规锚点）与
// skills/**（技能目录运行时合规校验，导出链路不碰）；mcp-configs/** 可编辑（MCP 生产地址改写主场景）。
func exportFileReadOnly(p string) bool {
	if p == "AGENTS.md" {
		return true
	}
	return strings.HasPrefix(p, "skills/")
}

// SaveExportPackageFile 保存单文件文本修改（写编辑草稿，不冻结；设计 §9 PUT file）。
// 同服务至多一条 draft：无则按 seedExportEntries 种子化，再 upsert 该文件内容。
func (c *Core) SaveExportPackageFile(wsID, svcID uint, username, filePath, content string) (*ExportMarkView, error) {
	svc, err := c.loadServiceForExport(wsID, svcID)
	if err != nil {
		return nil, err
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	clean, err := store.CleanSubPath(filePath)
	if err != nil {
		return nil, err // ErrZipSlip → 400（fs.go:310 同口径）
	}
	if path.Dir(clean) == "." && reservedRootDirs[clean] {
		// 根级保留目录名不可作为文件占用（package_version.go:126 同口径，issue #174）
		return nil, fmt.Errorf("%w: %q is a reserved root directory name", ErrInvalidForm, clean)
	}
	if exportFileReadOnly(clean) {
		return nil, fmt.Errorf("%w: %q is read-only in export editing (AGENTS.md/skills are immutable annotations)", ErrInvalidForm, clean)
	}
	if len(content) > maxExportUpsertFileBytes {
		return nil, fmt.Errorf("%w: file content exceeds %d bytes", ErrInvalidForm, maxExportUpsertFileBytes)
	}

	draft, err := c.loadDraftMark(svcID)
	if err != nil {
		return nil, err
	}
	if draft == nil {
		seed, serr := c.seedExportEntries(svcID, pkg)
		if serr != nil {
			return nil, serr
		}
		raw, merr := marshalMarkFiles(seed)
		if merr != nil {
			return nil, merr
		}
		draft = &store.ExportPackageMarkEntity{
			ServiceID: svcID, WorkspaceID: wsID, PackageVersion: pkg.Version,
			Status: store.ExportMarkDraft, CreatedBy: username,
			FilesJSON: raw, FileCount: len(seed), Checksum: checksumOfEntries(seed),
		}
	}
	entries, err := parseMarkFiles(draft.FilesJSON)
	if err != nil {
		return nil, err
	}
	replaced := false
	for i := range entries {
		if entries[i].Path == clean {
			entries[i].Encoding = "utf8"
			entries[i].Content = content
			replaced = true
			break
		}
	}
	if !replaced {
		entries = append(entries, exportFileEntry{Path: clean, Encoding: "utf8", Content: content})
	}
	raw, err := marshalMarkFiles(entries)
	if err != nil {
		return nil, err
	}
	draft.FilesJSON = raw
	draft.FileCount = len(entries)
	draft.Checksum = checksumOfEntries(entries)
	draft.CreatedBy = username
	if err := c.DB.Save(draft).Error; err != nil {
		return nil, err
	}
	return markView(draft), nil
}

// FreezeExportMark 打发布标记：冻结当前快照（draft 存在则冻结之；否则直接对源包快照冻结），
// 分配 marker `{包版本}-e{N}`（序号按服务内递增，取现存最大序号 +1）并写 checksum。
// 冻结后快照不可变；再编辑会生成新草稿、再打标记生成新 marker。
func (c *Core) FreezeExportMark(wsID, svcID uint, username string) (*ExportMarkView, error) {
	svc, err := c.loadServiceForExport(wsID, svcID)
	if err != nil {
		return nil, err
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	draft, err := c.loadDraftMark(svcID)
	if err != nil {
		return nil, err
	}
	if draft == nil {
		seed, serr := c.seedExportEntries(svcID, pkg)
		if serr != nil {
			return nil, serr
		}
		draft = &store.ExportPackageMarkEntity{
			ServiceID: svcID, WorkspaceID: wsID, PackageVersion: pkg.Version,
			Status: store.ExportMarkDraft, CreatedBy: username,
		}
		raw, merr := marshalMarkFiles(seed)
		if merr != nil {
			return nil, merr
		}
		draft.FilesJSON = raw
		draft.FileCount = len(seed)
		draft.Checksum = checksumOfEntries(seed)
	}
	seq, err := c.nextExportMarkSeq(svcID)
	if err != nil {
		return nil, err
	}
	draft.Marker = fmt.Sprintf("%s-e%d", draft.PackageVersion, seq)
	draft.Status = store.ExportMarkFrozen
	draft.CreatedBy = username
	if err := c.DB.Save(draft).Error; err != nil {
		// (service_id, marker) 复合唯一（契约 §9-D2）：并发打标记竞态落 409
		return nil, translateStorageError(err)
	}
	return markView(draft), nil
}

// nextExportMarkSeq 服务内标记序号：解析现存全部标记的 `-e{N}` 尾缀取最大值 +1
// （跨包版本连续递增；单副本部署下查库-写库竞态窗口由复合唯一索引兜底）。
func (c *Core) nextExportMarkSeq(svcID uint) (int, error) {
	var marks []store.ExportPackageMarkEntity
	if err := c.DB.Where("service_id = ?", svcID).Find(&marks).Error; err != nil {
		return 0, err
	}
	maxSeq := 0
	for _, m := range marks {
		if m.Marker == "" {
			continue
		}
		if i := strings.LastIndex(m.Marker, "-e"); i >= 0 {
			if n, err := strconv.Atoi(m.Marker[i+2:]); err == nil && n > maxSeq {
				maxSeq = n
			}
		}
	}
	return maxSeq + 1, nil
}

// ListExportMarks 发布标记列表（marker/checksum/时间/状态；快照内容不外露）。
// wsID 先行做服务归属校验（服务须属本空间，与导出域其余端点同口径）。
func (c *Core) ListExportMarks(wsID, svcID uint) ([]ExportMarkView, error) {
	if _, err := c.loadServiceForExport(wsID, svcID); err != nil {
		return nil, err
	}
	var marks []store.ExportPackageMarkEntity
	if err := c.DB.Where("service_id = ?", svcID).Order("id ASC").Find(&marks).Error; err != nil {
		return nil, err
	}
	out := make([]ExportMarkView, 0, len(marks))
	for i := range marks {
		out = append(out, *markView(&marks[i]))
	}
	return out, nil
}

// loadFrozenMark 按 marker 取服务 frozen 标记（创建导出单的唯一构建输入；无标记不可构建）。
func (c *Core) loadFrozenMark(svcID uint, marker string) (*store.ExportPackageMarkEntity, error) {
	var m store.ExportPackageMarkEntity
	err := c.DB.Where("service_id = ? AND marker = ? AND status = ?", svcID, marker, store.ExportMarkFrozen).
		First(&m).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrMarkNotBuildable
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}
