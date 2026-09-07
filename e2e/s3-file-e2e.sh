#!/usr/bin/env bash
# S3 存储档文件 E2E：切换 release-agent 存储后端为 S3（七牛云/任意 S3 兼容）→ 跑文件全链路 → 恢复 local。
# 凭据从 .env.secrets（gitignored）或环境变量读取：QINIU_S3_ENDPOINT/AK/SK/BUCKET。
# 前置：release-agent 已 running（local 模式）；kubectl 可用；跑完自动恢复 local。
set -u
cd "$(dirname "$0")"
source ../.env.secrets 2>/dev/null || true

S3_ENDPOINT="${QINIU_S3_ENDPOINT:?need QINIU_S3_ENDPOINT in .env.secrets or env}"
S3_AK="${QINIU_S3_ACCESS_KEY:?need QINIU_S3_ACCESS_KEY}"
S3_SK="${QINIU_S3_SECRET_KEY:?need QINIU_S3_SECRET_KEY}"
S3_BUCKET="${QINIU_S3_BUCKET:?need QINIU_S3_BUCKET}"
CM=oaf-release-agent-env
NS=agent-platform

cleanup() {
  echo "== 恢复 local 存储 =="
  kubectl -n $NS patch cm $CM --type json -p '[
    {"op":"remove","path":"/data/FILE_STORAGE_S3_ACCESS_KEY"},
    {"op":"remove","path":"/data/FILE_STORAGE_S3_SECRET_KEY"},
    {"op":"remove","path":"/data/FILE_STORAGE_S3_ENDPOINT"},
    {"op":"remove","path":"/data/FILE_STORAGE_S3_BUCKET"},
    {"op":"add","path":"/data/FILE_STORAGE_TYPE","value":"local"}]' > /dev/null 2>&1
  kubectl -n $NS rollout restart deploy/oaf-release-agent > /dev/null 2>&1
  kubectl -n $NS rollout status deploy/oaf-release-agent --timeout=150s > /dev/null 2>&1
  echo "已恢复 local（LocalFileStorage）"
}
trap cleanup EXIT

echo "== 1. 切换 S3 存储 =="
kubectl -n $NS patch cm $CM --type merge -p "{
  \"data\":{\"FILE_STORAGE_TYPE\":\"s3\",
  \"FILE_STORAGE_S3_ENDPOINT\":\"$S3_ENDPOINT\",
  \"FILE_STORAGE_S3_ACCESS_KEY\":\"$S3_AK\",
  \"FILE_STORAGE_S3_SECRET_KEY\":\"$S3_SK\",
  \"FILE_STORAGE_S3_BUCKET\":\"$S3_BUCKET\"}}"
kubectl -n $NS rollout restart deploy/oaf-release-agent
# kubelet 对新写入 CM key 的传播有延迟：首次 restart 可能仍读到旧 CM → 检测 S3FileStorage 日志，未出现则再 restart
for i in 1 2 3; do
  kubectl -n $NS rollout status deploy/oaf-release-agent --timeout=150s > /dev/null
  sleep 8
  if kubectl -n $NS logs deploy/oaf-release-agent 2>&1 | grep -q "S3FileStorage bucket"; then
    echo "S3FileStorage 已生效"; break
  fi
  echo "S3 env 未生效（kubelet CM 传播延迟），重试 restart ($i/3)"
  kubectl -n $NS rollout restart deploy/oaf-release-agent
done
kubectl -n $NS logs deploy/oaf-release-agent 2>&1 | grep -E "S3FileStorage bucket" | tail -1

echo "== 2. 跑文件 E2E（非沙箱档，上传/下载/present_file/生成包全链路落 S3）=="
./file-support-e2e.sh
RC=$?
echo "== 完成（exit=$RC），自动恢复 local =="
exit $RC
