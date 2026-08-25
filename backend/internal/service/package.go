package service

import (
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

// Upload 校验并落盘一个 OAF zip 包，返回入库记录。
func (s *PackageService) Upload(filename string, r io.Reader) (*store.OafPackage, error) {
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
		Checksum: zi.Checksum,
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

func (s *PackageService) List(keyword string) ([]store.OafPackage, error) {
	q := s.DB.Model(&store.OafPackage{}).Order("id DESC")
	if keyword != "" {
		q = q.Where("name LIKE ? OR slug LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
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
