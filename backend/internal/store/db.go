package store

import (
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
	if err := db.AutoMigrate(&OafPackage{}, &ServiceEntity{}, &ServiceEvent{}); err != nil {
		return nil, err
	}
	return db, nil
}
