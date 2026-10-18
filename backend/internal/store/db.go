package store

import (
	"fmt"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// Open 连接 MySQL 并自动迁移全部表。
func Open(dsn string) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	if err != nil {
		return nil, err
	}
	// (slug, version) 唯一索引的存量重复行归一（issue #172）必须先于 AutoMigrate：
	// 重复行会让索引创建失败启动即挂；幂等，无重复行零写入
	if err := dedupPackageVersions(db); err != nil {
		return nil, fmt.Errorf("dedup package versions: %w", err)
	}
	if err := db.AutoMigrate(&OafPackage{}, &ServiceEntity{}, &ServiceEvent{},
		&PlatformConfigEntity{}, &PlatformConfigEvent{},
		&User{}, &Workspace{}, &WorkspaceMember{}, &ApiKey{},
		&ModelConnection{}, &ModelDelivery{}, &McpConnector{}, &AgentIntegration{}); err != nil {
		return nil, err
	}
	return db, nil
}
