bg() {
  local group=$1 name=$2; shift 2
  echo "$name" >> "$BUILD_DIR/$group"
  ( "$@" > "$BUILD_DIR/$name.log" 2>&1; echo $? > "$BUILD_DIR/$name.rc" ) >/dev/null 2>&1 </dev/null &
}

wait_group() {
  local name
  while read -r name; do
    while [ ! -f "$BUILD_DIR/$name.rc" ]; do sleep 2; done
    cat "$BUILD_DIR/$name.log"
    if [ "$(cat "$BUILD_DIR/$name.rc")" != 0 ]; then
      echo "::error::后台任务失败：$name"; exit 1
    fi
  done < "$BUILD_DIR/$1"
}
