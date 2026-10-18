package service

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/store"
)

// PackageService OAF 配置包上传与查询。
type PackageService struct {
	DB *gorm.DB
	FS *store.FS
}

var ErrPackageInUse = errors.New("package is referenced by services")

// skillWarningsFromZip 从 zip 条目做 skills/ 目录合规检查（checkSkillDirs 的 zip 数据源
// 适配），结果展平为包级 warnings（`skill "名": 问题`）。
func skillWarningsFromZip(zi *store.ZipInfo) []string {
	files := map[string]*zip.File{}
	paths := make([]string, 0, len(zi.Reader.File))
	for _, zf := range zi.Reader.File {
		if zf.FileInfo().IsDir() {
			continue
		}
		files[zf.Name] = zf
		paths = append(paths, zf.Name)
	}
	issues := checkSkillDirs(func(name string) ([]byte, error) {
		zf, ok := files[name]
		if !ok {
			return nil, fs.ErrNotExist
		}
		rc, err := zf.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		return io.ReadAll(rc)
	}, paths)
	warnings := make([]string, 0, len(issues))
	for _, is := range issues {
		for _, msg := range is.Issues {
			warnings = append(warnings, fmt.Sprintf("skill %q: %s", is.Name, msg))
		}
	}
	return warnings
}

// registerZipCore 上传/生成共用的落盘+登记核心（Upload 保持 multipart 入口语义）。
// workspaceID>0 = 空间包（仅本空间可见）；0 = 平台公共包（决议 7）。
// sourcePackageID>0（编辑/导入派生链路）时版本撞唯一索引 (slug, version) 自动顺延
// patch 重写 frontmatter（issue #172：曾静默并存重复版本行）；上传链路（=0）保持
// REDESIGN §3.3.5 语义：用户显式重复上传撞版返回 409。
func (s *PackageService) registerZipCore(filename string, r io.Reader, workspaceID uint, sourcePackageID uint, version string) (*store.OafPackage, error) {
	zi, err := s.FS.InspectZip(r)
	if err != nil {
		return nil, err
	}
	cfg, err := oaf.ParseOAF(string(zi.AgentsMD))
	if err != nil {
		return nil, fmt.Errorf("parse AGENTS.md: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, fmt.Errorf("invalid AGENTS.md: %w", err)
	}
	// 宽松模式：引用缺失降为 warnings
	warnings := cfg.Warnings()
	for _, m := range cfg.MCPServers {
		if m.ConfigDir != "" && !zi.HasDir(m.ConfigDir) {
			warnings = append(warnings, fmt.Sprintf("mcpServer %q configDir %q missing in package", m.Server, m.ConfigDir))
		}
	}
	// skills/ 目录合规检查（issue #163）：宽松模式同引用缺失——只写 warnings 不阻断
	warnings = append(warnings, skillWarningsFromZip(zi)...)

	var recOut *store.OafPackage
	err = s.DB.Transaction(func(tx *gorm.DB) error {
		// 派生链路（编辑/导入生成）：锁 slug 版本链，撞版自动顺延 patch 并重写
		// AGENTS.md/zip（issue #172——原实现同版本静默并存或以 500 直出）
		if sourcePackageID > 0 {
			var locked []store.OafPackage
			if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
				Where("slug = ?", cfg.Slug).Order("id ASC").Find(&locked).Error; err != nil {
				return err
			}
			conflict := false
			for _, p := range locked {
				if p.Version == cfg.Version && p.ID != sourcePackageID {
					conflict = true
					break
				}
			}
			if conflict {
				// 以锁内链尾为基顺延：cfg.Version 通常是 base 的下一跳，撞版说明
				// 链上又有新版本，继续从链尾递增直至空位（有限轮，链长受 MaxEntries 约束）
				latest := cfg.Version
				if len(locked) > 0 {
					latest = locked[len(locked)-1].Version
				}
				for i := 0; i < store.MaxEntries; i++ {
					candidate := nextVersion(latest)
					var c int64
					if err := tx.Model(&store.OafPackage{}).
						Where("slug = ? AND version = ?", cfg.Slug, candidate).
						Count(&c).Error; err != nil {
						return err
					}
					latest = candidate
					if c == 0 {
						agentsMD := zi.AgentsMD
						if out, rerr := rewriteAgentsMDVersion(agentsMD, candidate); rerr == nil {
							agentsMD = []byte(out)
						}
						if _, err := s.rezipWithAgentsMD(&zi, agentsMD); err != nil {
							return err
						}
						cfg, err = oaf.ParseOAF(string(zi.AgentsMD))
						if err != nil {
							return fmt.Errorf("parse AGENTS.md: %w", err)
						}
						if err := cfg.Validate(); err != nil {
							return fmt.Errorf("invalid AGENTS.md: %w", err)
						}
						warnings = nil
						for _, m := range cfg.MCPServers {
							if m.ConfigDir != "" && !zi.HasDir(m.ConfigDir) {
								warnings = append(warnings, fmt.Sprintf("mcpServer %q configDir %q missing in package", m.Server, m.ConfigDir))
							}
						}
						warnings = append(warnings, skillWarningsFromZip(zi)...)
						break
					}
				}
			}
		}
		manifest, _ := json.Marshal(cfg)
		warnJSON, _ := json.Marshal(warnings)
		rec := &store.OafPackage{
			Name: cfg.Name, Slug: cfg.Slug, Version: cfg.Version,
			Description:  cfg.Description,
			ManifestJSON: string(manifest), WarningsJSON: string(warnJSON),
			Checksum:  zi.Checksum,
			FileCount: zi.FileCount, TotalSize: zi.TotalSize,
			WorkspaceID: workspaceID, SourcePackageID: sourcePackageID,
		}
		recOut = rec
		if err := tx.Create(rec).Error; err != nil {
			return translateStorageError(err)
		}
		rec.DirPath = fmt.Sprintf("packages/%d", rec.ID)
		// 落盘失败则回滚记录
		if _, _, _, werr := s.FS.ExtractZipTo(rec.DirPath, zi); werr != nil {
			return werr
		}
		return tx.Model(rec).Update("dir_path", rec.DirPath).Error
	})
	if err != nil {
		return nil, err
	}
	return recOut, nil
}

// Upload 校验并落盘一个 OAF zip 包，返回入库记录（公共包语义 workspace_id=0）。
func (s *PackageService) Upload(filename string, r io.Reader) (*store.OafPackage, error) {
	return s.registerZipCore(filename, r, 0, 0, "")
}

// rezipWithAgentsMD 重打包 zip：AGENTS.md 条目替换为新内容，其余条目原样复制，
// 重算 checksum/文件数/体积并返回新 zip 字节流（版本顺延重写用）。
func (s *PackageService) rezipWithAgentsMD(z **store.ZipInfo, agentsMD []byte) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, zf := range (*z).Reader.File {
		hdr := &zip.FileHeader{Name: zf.Name, Method: zf.Method}
		hdr.SetMode(zf.Mode())
		hdr.Modified = zf.Modified
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			return nil, err
		}
		if zf.Name == "AGENTS.md" {
			if _, err := w.Write(agentsMD); err != nil {
				return nil, err
			}
			continue
		}
		rc, err := zf.Open()
		if err != nil {
			return nil, err
		}
		_, err = io.Copy(w, rc)
		rc.Close()
		if err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	zi2, err := s.FS.InspectZip(bytes.NewReader(buf.Bytes()))
	if err != nil {
		return nil, err
	}
	*z = zi2
	return buf.Bytes(), nil
}

// UploadTo 空间包上传（console import 模式）：落库即归属该空间（决议 7）。
func (s *PackageService) UploadTo(filename string, r io.Reader, workspaceID uint) (*store.OafPackage, error) {
	return s.registerZipCore(filename, r, workspaceID, 0, "")
}

// RegisterGenerated 生成器产物登记（M3）：内存 zip → 落 PVC + 入库（workspace 归属，
// source_package_id=nil，is_template=false）。version 为空取 frontmatter 自带版本。
func (s *PackageService) RegisterGenerated(workspaceID uint, files map[string][]byte) (*store.OafPackage, error) {
	return s.RegisterGeneratedFrom(workspaceID, nil, files, "")
}

// RegisterGeneratedFrom 编辑链路登记：以源包为新版本的派生来源（copy-on-write 语义）。
func (s *PackageService) RegisterGeneratedFrom(workspaceID uint, base *store.OafPackage, files map[string][]byte, version string) (*store.OafPackage, error) {
	zipData, err := buildZipBytes(files)
	if err != nil {
		return nil, err
	}
	var sourceID uint
	if base != nil {
		sourceID = base.ID
	}
	return s.registerZipCore("generated.zip", bytes.NewReader(zipData), workspaceID, sourceID, version)
}

// CollectBaseFiles 读取基础包全部文件内容（编辑链路 copy-on-write 的底）。
func (s *PackageService) CollectBaseFiles(base *store.OafPackage) (map[string][]byte, error) {
	return s.collectBaseFiles(base, nil)
}

// packageAgentKey 从 manifest 还原 agentKey（编辑链路锁定 agentKey，仅 version/name 变更）。
func packageAgentKey(p *store.OafPackage) string {
	var cfg struct {
		AgentKey string `json:"agentKey"`
	}
	if err := json.Unmarshal([]byte(p.ManifestJSON), &cfg); err != nil {
		return ""
	}
	return cfg.AgentKey
}

// ListTemplates 公共模板包（workspace_id=0 且 is_template，M2 模板页数据源）。
func (s *PackageService) ListTemplates() ([]store.OafPackage, error) {
	var out []store.OafPackage
	err := s.DB.Where("workspace_id = 0 AND is_template = ?", true).
		Order("id DESC").Find(&out).Error
	return out, err
}

// SetTemplate 模板标记开关（运维侧包列表页，M2 §2.3）。
func (s *PackageService) SetTemplate(id uint, isTemplate bool) error {
	res := s.DB.Model(&store.OafPackage{}).Where("id = ?", id).
		Update("is_template", isTemplate)
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

// SetWorkspace 包归属（创建链路落库用；不做跨空间迁移入口——包可见性按决议 7）。
func (s *PackageService) SetWorkspace(id, workspaceID uint) error {
	return s.DB.Model(&store.OafPackage{}).Where("id = ?", id).
		Update("workspace_id", workspaceID).Error
}

func (s *PackageService) List(keyword, slug string) ([]store.OafPackage, error) {
	q := s.DB.Model(&store.OafPackage{}).Order("id DESC")
	if keyword != "" {
		q = q.Where("name LIKE ? OR slug LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	if slug != "" {
		q = q.Where("slug = ?", slug)
	}
	var out []store.OafPackage
	return out, q.Find(&out).Error
}

func (s *PackageService) Get(id uint) (*store.OafPackage, error) {
	var rec store.OafPackage
	if err := s.DB.First(&rec, id).Error; err != nil {
		return nil, err
	}
	return &rec, nil
}

func (s *PackageService) Tree(pkg *store.OafPackage) ([]store.FileEntry, error) {
	return s.FS.Tree(pkg.DirPath)
}

func (s *PackageService) ReadFile(pkg *store.OafPackage, sub string) ([]byte, error) {
	return s.FS.ReadFile(pkg.DirPath, sub)
}

// Delete 删除未被任何服务引用的包。
func (s *PackageService) Delete(id uint) error {
	var cnt int64
	if err := s.DB.Model(&store.ServiceEntity{}).Where("package_id = ?", id).Count(&cnt).Error; err != nil {
		return err
	}
	if cnt > 0 {
		return ErrPackageInUse
	}
	return s.DB.Transaction(func(tx *gorm.DB) error {
		var rec store.OafPackage
		if err := tx.First(&rec, id).Error; err != nil {
			return err
		}
		if err := s.FS.RemovePackage(rec.DirPath); err != nil {
			return err
		}
		return tx.Delete(&rec).Error
	})
}
