package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"gorm.io/gorm"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/store"
)

// PackageService OAF 配置包上传与查询。
type PackageService struct {
	DB *gorm.DB
	FS *store.FS
}

var ErrPackageInUse = errors.New("package is referenced by services")

// registerZipCore 上传/生成共用的落盘+登记核心（Upload 保持 multipart 入口语义）。
// workspaceID>0 = 空间包（仅本空间可见）；0 = 平台公共包（决议 7）。
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
		return nil, err
	}
	return rec, nil
}

// Upload 校验并落盘一个 OAF zip 包，返回入库记录。
func (s *PackageService) Upload(filename string, r io.Reader) (*store.OafPackage, error) {
	return s.registerZipCore(filename, r, 0, 0, "")
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
