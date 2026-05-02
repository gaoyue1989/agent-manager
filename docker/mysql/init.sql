-- Agent Manager 数据库初始化
CREATE DATABASE IF NOT EXISTS agent_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS agent_manager_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON agent_manager.* TO 'agent_manager'@'%';
GRANT ALL PRIVILEGES ON agent_manager_test.* TO 'agent_manager'@'%';
FLUSH PRIVILEGES;
