{{/* ---------- 基础命名与标签 ---------- */}}

{{/* fullname：生产侧对象名基底（契约 §6.2 ★ 必填，导出渲染恒写入；格式校验在 oaf-agent.validate） */}}
{{- define "oaf-agent.fullname" -}}
{{- required "fullnameOverride 必填（生产侧对象名基底，导出渲染恒写入；如 travel-agent）" .Values.fullnameOverride -}}
{{- end -}}

{{- define "oaf-agent.labels" -}}
app.kubernetes.io/name: {{ include "oaf-agent.fullname" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "oaf-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "oaf-agent.fullname" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* ---------- 镜像引用 ---------- */}}

{{/* agent-framework 基础镜像（业务运行时） */}}
{{- define "oaf-agent.imageBase" -}}
{{- required "image.base 必填（agent-framework 基础镜像完整引用）" .Values.image.base -}}
{{- end -}}

{{/* OAF 配置镜像（busybox 基底 + tar 层挂载点 /oaf/，initContainer 从中拷贝包内容） */}}
{{- define "oaf-agent.imageOaf" -}}
{{- required "image.oaf 必填（OAF 配置镜像完整引用）" .Values.image.oaf -}}
{{- end -}}

{{/* env/secretEnv 同键有效值：ConfigMap 值优先（对齐 envFrom「后引用者覆盖先引用者」，objects.go:179-182 同语义） */}}
{{- define "oaf-agent.envValue" -}}
{{- $key := index . "key" -}}
{{- or (index . "env" $key) (index . "secretEnv" $key) | default "" -}}
{{- end -}}

{{/* ---------- 渲染期校验（fail 早于装出 CrashLoop；先例 oaf-platform _helpers.tpl oaf.validate） ---------- */}}

{{- define "oaf-agent.validate" -}}
{{- $name := required "fullnameOverride 必填（生产侧对象名基底，导出渲染恒写入；如 travel-agent）" .Values.fullnameOverride -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$" $name) -}}
{{- fail (printf "fullnameOverride=%q 非法：须为 DNS-1123 兼容小写串（小写字母/数字/中划线/点号）" $name) -}}
{{- end -}}
{{/* hook Job 名 {fullname}-mysql-init 受 K8s 对象名 63 字符上限约束（-mysql-init 共 11 字符） */}}
{{- if gt (len $name) 52 -}}
{{- fail (printf "fullnameOverride=%s 超长：须 ≤52 字符（保证 %s-mysql-init ≤63）" $name $name) -}}
{{- end -}}
{{- if not .Values.image.base -}}
{{- fail "image.base 必填（agent-framework 基础镜像完整引用）" -}}
{{- end -}}
{{- if not .Values.image.oaf -}}
{{- fail "image.oaf 必填（OAF 配置镜像完整引用）" -}}
{{- end -}}
{{- if ne .Values.configInjection.mode "initContainer" -}}
{{- fail (printf "configInjection.mode=%q 非法：v1 仅支持 initContainer" .Values.configInjection.mode) -}}
{{- end -}}
{{/* env.AGENT_CONFIG_DIR 与挂载目录错位 = 必然 CrashLoop，渲染期直接拒绝 */}}
{{- $configDir := include "oaf-agent.envValue" (dict "key" "AGENT_CONFIG_DIR" "env" .Values.env "secretEnv" .Values.secretEnv) -}}
{{- if and $configDir (ne $configDir .Values.configInjection.dir) -}}
{{- fail (printf "env.AGENT_CONFIG_DIR=%s 与 configInjection.dir=%s 不一致：二者必须同值（/config 挂载即包内容目录）" $configDir .Values.configInjection.dir) -}}
{{- end -}}
{{- if lt (int .Values.replicas) 1 -}}
{{- fail (printf "replicas=%v 非法：须 ≥1（缺省 2，多副本横向扩展语义与单集群一致）" .Values.replicas) -}}
{{- end -}}
{{/* 路由双模式校验（口径对齐 INGRESS_HOST_SUFFIX：host 模式后缀以 "." 开头、逐段合法 DNS label） */}}
{{- if eq .Values.ingress.mode "host" -}}
{{- if not .Values.ingress.hostSuffix -}}
{{- fail "ingress.mode=host 时 ingress.hostSuffix 必填（以 \".\" 开头，如 .prod.example.cn）" -}}
{{- else if not (regexMatch "^\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$" .Values.ingress.hostSuffix) -}}
{{- fail (printf "ingress.hostSuffix=%q 非法：须以 \".\" 开头、去点后逐段为合法 DNS-1123 label（同 backend config.go 口径）" .Values.ingress.hostSuffix) -}}
{{- end -}}
{{- else if eq .Values.ingress.mode "router" -}}
{{- if not .Values.ingress.pathPrefix -}}
{{- fail "ingress.mode=router 时 ingress.pathPrefix 必填（子路径前缀，如 /agent/travel-agent）" -}}
{{- else if or (not (regexMatch "^/" .Values.ingress.pathPrefix)) (regexMatch "/$" .Values.ingress.pathPrefix) -}}
{{- fail (printf "ingress.pathPrefix=%q 非法：须以 \"/\" 开头且不以 \"/\" 结尾" .Values.ingress.pathPrefix) -}}
{{- end -}}
{{- else -}}
{{- fail "ingress.mode 只能是 host 或 router" -}}
{{- end -}}
{{- if and .Values.ingress.tls.enabled (not .Values.ingress.tls.secretName) -}}
{{- fail "ingress.tls.enabled=true 时 ingress.tls.secretName 必填（TLS 证书 Secret）" -}}
{{- end -}}
{{/* mysql 建库双保险：Job 从 env/secretEnv 的 CHECKPOINT_JDBC_URL 解析建库目标，缺失即拒绝 */}}
{{- if .Values.mysql.createDatabase -}}
{{- if not .Values.mysql.jobImage -}}
{{- fail "mysql.createDatabase=true 时 mysql.jobImage 必填（agent-framework Java 基底不含 mysql CLI）" -}}
{{- end -}}
{{- $jdbc := include "oaf-agent.envValue" (dict "key" "CHECKPOINT_JDBC_URL" "env" .Values.env "secretEnv" .Values.secretEnv) -}}
{{- if not $jdbc -}}
{{- fail "mysql.createDatabase=true 时 env/secretEnv 须提供 CHECKPOINT_JDBC_URL（Job 据此解析建库 host/库名）" -}}
{{- end -}}
{{- end -}}
{{- end -}}
