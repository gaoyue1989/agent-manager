package store

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"time"

	"gorm.io/gorm"
)

// Backfill 存量数据幂等回填（设计 §6.3）：仅写平台自有表，不碰任何业务 K8s 资源。
// 启动顺序 = Open(AutoMigrate) → Backfill → 路由装配；全部步骤逐条幂等，二次启动零写入。
// adminUser/adminPassword 为空时不种子用户（空库无 admin 属部署配置问题，warn 不阻断启动）。
func Backfill(db *gorm.DB, adminUser, adminPassword string) error {
	return db.Transaction(func(tx *gorm.DB) error {
		// 1) users 空表 → 种子 admin（一次性；两者齐备才生效）
		var userCnt int64
		if err := tx.Model(&User{}).Count(&userCnt).Error; err != nil {
			return err
		}
		var seedAdmin User
		if userCnt == 0 && adminUser != "" && adminPassword != "" {
			hash, err := HashPassword(adminPassword)
			if err != nil {
				return fmt.Errorf("seed admin: %w", err)
			}
			seedAdmin = User{Username: adminUser, PasswordHash: hash,
				DisplayName: "平台管理员", Role: "admin", Status: "active"}
			if err := tx.Create(&seedAdmin).Error; err != nil {
				return err
			}
			log.Printf("[migrate] seeded initial admin %q", adminUser)
		}
		// 2) slug=default 工作空间只建一次，owner 指向种子 admin（若有）
		var defWS Workspace
		err := tx.Where("slug = ?", "default").First(&defWS).Error
		if err != nil {
			if err != gorm.ErrRecordNotFound {
				return err
			}
			defWS = Workspace{Slug: "default", Name: "default",
				Description: "存量服务归属（对普通用户不可见，由管理员纳管再分配）",
				OwnerUserID: seedAdmin.ID, Status: "active", QuotaJSON: "{}"}
			if err := tx.Create(&defWS).Error; err != nil {
				return err
			}
			if seedAdmin.ID > 0 {
				if err := tx.Create(&WorkspaceMember{WorkspaceID: defWS.ID,
					UserID: seedAdmin.ID, Role: "owner"}).Error; err != nil {
					return err
				}
			}
			log.Printf("[migrate] created default workspace (id=%d)", defWS.ID)
		}
		// 3) 未归属服务 → default 空间（决议 2：用户侧不可见，admin 经纳管再分配）。
		// 注意 NULL：AutoMigrate 对既有表新增列时旧行填 NULL（非 0），两种形态都要回填
		if err := tx.Model(&ServiceEntity{}).
			Where("workspace_id IS NULL OR workspace_id = 0").
			Update("workspace_id", defWS.ID).Error; err != nil {
			return err
		}
		// 4) oaf_packages：决议 7 语义 0=公共包；AutoMigrate 对存量表加列旧行为 NULL
		// （与 services 同坑，部署实测：旧包版本链在 console 不可见）——NULL 归一为 0
		if err := tx.Model(&OafPackage{}).
			Where("workspace_id IS NULL").
			Update("workspace_id", 0).Error; err != nil {
			return err
		}
		// 5) oaf_packages：(slug, version) 唯一索引的存量重复行归一（issue #172）
		// 已前移到 Open（AutoMigrate 之前，见 dedupPackageVersions）——重复行会让
		// 唯一索引创建失败启动即挂
		return nil
	})
}

// dedupPackageVersions (slug, version) 唯一索引的存量重复行归一（issue #172）。
// 必须在 AutoMigrate 之前执行：重复行会让唯一索引创建失败启动即挂。
// 保留同组最早一行（版本链起点/被引用概率最高），后续行 version 加 ".fix{N}"
// 后缀让位——历史黑盒测试产生的重复节点按 id 先后可区分。
// 幂等：正常库无重复行，二次启动零写入。仅写平台自有表。
func dedupPackageVersions(db *gorm.DB) error {
	type dupRow struct {
		ID   uint
		Slug string
	}
	var dupGroups []dupRow
	if err := db.Model(&OafPackage{}).
		Select("MIN(id) as id, slug").
		Group("slug, version").
		Having("COUNT(*) > 1").
		Scan(&dupGroups).Error; err != nil {
		return err
	}
	for _, g := range dupGroups {
		var recs []OafPackage
		if err := db.Where("slug = ?", g.Slug).
			Order("id ASC").Find(&recs).Error; err != nil {
			return err
		}
		// 同 slug 内按 version 分桶：桶内首行（最早 id）保持，后续行顺延加后缀
		seen := map[string]int{}
		for _, r := range recs {
			n, ok := seen[r.Version]
			seen[r.Version] = n + 1
			if !ok {
				continue
			}
			if err := db.Model(&OafPackage{}).Where("id = ?", r.ID).
				Update("version", fmt.Sprintf("%s.fix%d", r.Version, n)).Error; err != nil {
				return err
			}
			log.Printf("[migrate] normalized duplicate package slug=%s version=%s id=%d -> %s.fix%d",
				r.Slug, r.Version, r.ID, r.Version, n)
		}
	}
	return nil
}

// NewSlug 生成 ws-{8 hex} 形态的工作空间 slug（冲突重试由调用方负责）。
func NewSlug() string {
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("ws-%08x", uint32(time.Now().UnixNano()%1e8))
	}
	return "ws-" + hex.EncodeToString(b)
}
