-- 添加运行模式相关字段到 agents 表
-- 执行时间: 2026-05-18

ALTER TABLE `agents` 
ADD COLUMN `runtime_mode` ENUM('build', 'mount') DEFAULT 'build' COMMENT '运行模式: build=构建模式, mount=挂载模式' AFTER `config_type`,
ADD COLUMN `image` VARCHAR(256) DEFAULT '' COMMENT '预构建镜像地址(挂载模式)' AFTER `runtime_mode`,
ADD COLUMN `checkpoint_dsn` VARCHAR(512) DEFAULT '' COMMENT 'Checkpoint DSN(可选,为空则共用)' AFTER `image`;

-- 为新字段添加索引
CREATE INDEX `idx_runtime_mode` ON `agents` (`runtime_mode`);
