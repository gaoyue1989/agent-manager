#!/bin/sh
# skillsync-launcher.sh —— 通用启动器：watchdog 拉起守护后 exec 传入命令（skills-syncd 设计 §5.1）
# 两条铁律：1) 后台循环绝不阻塞主进程；2) exec 保持主进程语义
if [ -n "$SKILLSYNC_BASE_URL" ]; then
    (while :; do
         setsid /usr/local/bin/skills-syncd 2>>/var/log/skills-syncd.log
         sleep 30
     done) &
fi
exec "$@"
