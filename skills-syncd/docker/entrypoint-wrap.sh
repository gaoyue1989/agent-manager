#!/bin/sh
# entrypoint-wrap.sh（直建路径）：委托 launcher 后 exec 原入口（skills-syncd 设计 §5.1）
exec /usr/local/bin/skillsync-launcher.sh /opt/code-interpreter/code-interpreter.sh "$@"
