#!/bin/sh
# skillsync-launcher.sh —— 通用启动器：watchdog 拉起守护后 exec 传入命令（skills-syncd 设计 §5.1）
# 两条铁律：1) 后台循环绝不阻塞主进程；2) exec 保持主进程语义
log_file=/var/log/skills-syncd.log
log_max_bytes=$((1024 * 1024))
if [ -n "$SKILLSYNC_BASE_URL" ]; then
    (while :; do
         setsid /usr/local/bin/skills-syncd 2>>"$log_file"
         sleep 30
     done) &
    (while :; do
         sleep 30
         if [ -f "$log_file" ] && [ "$(wc -c <"$log_file")" -gt "$log_max_bytes" ]; then
             tail -c "$((log_max_bytes / 2))" "$log_file" >"$log_file.tmp" &&
                 cat "$log_file.tmp" >"$log_file"
             rm -f "$log_file.tmp"
         fi
     done) &
fi
exec "$@"
