{{/* ---------- 基础命名与标签 ---------- */}}

{{- define "oaf.namespace" -}}
{{- default .Release.Namespace .Values.namespaceOverride -}}
{{- end -}}

{{- define "oaf.fullname" -}}
{{- default .Chart.Name .Values.nameOverride -}}
{{- end -}}

{{- define "oaf.labels" -}}
app.kubernetes.io/name: {{ include "oaf.fullname" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "oaf.selectorLabels" -}}
app.kubernetes.io/name: {{ include "oaf.fullname" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* ---------- 镜像引用 ---------- */}}

{{- define "oaf.image" -}}
{{- printf "%s:%s" .repository (.tag | toString) -}}
{{- end -}}

{{- define "oaf.defaultBusinessImage" -}}
{{- if .Values.images.defaultBusiness -}}
{{- .Values.images.defaultBusiness -}}
{{- else if .Values.images.business -}}
{{- (first .Values.images.business).image -}}
{{- end -}}
{{- end -}}

{{/* ---------- 派生值 ---------- */}}

{{/* 后端 ServiceAccount 名：synthesized 模式用专用 SA（RBAC 与 token 都绑它） */}}
{{- define "oaf.backendServiceAccountName" -}}
{{- if eq .Values.backend.kubeconfig.mode "synthesized" -}}
{{- printf "%s-backend" (include "oaf.fullname" .) -}}
{{- else -}}
{{- printf "%s-backend" (include "oaf.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/* 业务数据卷 PVC 名 */}}
{{- define "oaf.pvcName" -}}
{{- required "persistence.existingClaim 必填（chart 不创建 PVC，须指向环境已有 PVC）" .Values.persistence.existingClaim -}}
{{- end -}}

{{/* MYSQL_DSN：dsn 直给优先，否则按字段拼装 */}}
{{- define "oaf.mysqlDSN" -}}
{{- if .Values.external.mysql.dsn -}}
{{- .Values.external.mysql.dsn -}}
{{- else -}}
{{- printf "%s:%s@tcp(%s:%v)/%s?%s" .Values.external.mysql.username .Values.external.mysql.password .Values.external.mysql.host (.Values.external.mysql.port | toString) .Values.external.mysql.database .Values.external.mysql.params -}}
{{- end -}}
{{- end -}}

{{/* 后端 base URL（集群内） */}}
{{- define "oaf.backendInternalUrl" -}}
{{- default (printf "http://%s-backend.%s.svc.cluster.local:%v" (include "oaf.fullname" .) (include "oaf.namespace" .) (.Values.backend.serverPort | toString)) .Values.frontend.env.backendInternalUrl -}}
{{- end -}}

{{/* 业务服务集群内地址前缀：oaf-{name}-svc */}}
{{- define "oaf.businessSvcUrl" -}}
{{- printf "http://oaf-%s-svc.%s.svc.cluster.local:8100" .name (include "oaf.namespace" .root) -}}
{{- end -}}

{{/* 是否形态 B（分域）：backend/frontend host 都非空 */}}
{{- define "oaf.splitDomain" -}}
{{- if and .Values.ingress.backend.host .Values.ingress.frontend.host -}}true{{- else -}}false{{- end -}}
{{- end -}}

{{/* ---------- 渲染期校验（fail 早于装出 CrashLoop） ---------- */}}

{{- define "oaf.validate" -}}
{{- if not .Values.images.business -}}
{{- fail "images.business 必填（至少一项，渲染 AVAILABLE_IMAGES；tag 须与内网仓库实际可拉取一致）" -}}
{{- end -}}
{{- if not .Values.backend.existingSecret -}}
{{- if and (not .Values.external.mysql.dsn) (not .Values.external.mysql.host) -}}
{{- fail "external.mysql 必填：给 host（+username/password）或 dsn 直给——平台元数据库为外部实例；也可用 backend.existingSecret 复用已有 Secret（key: mysql-dsn）" -}}
{{- end -}}
{{- if and (not .Values.external.mysql.dsn) (not .Values.external.mysql.password) -}}
{{- fail "external.mysql.password 为空：给密码、改 dsn 直给，或设 backend.existingSecret（key: mysql-dsn）" -}}
{{- end -}}
{{- end -}}
{{- if eq .Values.routing.mode "host" -}}
{{- if not .Values.routing.hostSuffix -}}
{{- fail "routing.mode=host 时 routing.hostSuffix 必填（以 \".\" 开头，后端会做格式 fail-fast）" -}}
{{- end -}}
{{- else if eq .Values.routing.mode "router" -}}
{{- if .Values.business.ingressOverlay.enabled -}}
{{- fail "routing.mode=router 时不可启用 business.ingressOverlay（router 模式无 per-service Ingress；后端对 suffix 空 + INGRESS_TEMPLATE 非空启动即拒）" -}}
{{- end -}}
{{- else -}}
{{- fail "routing.mode 只能是 router 或 host" -}}
{{- end -}}
{{- if and .Values.ingress.backend.host (not .Values.ingress.frontend.host) -}}
{{- fail "形态 B（分域）需同时设置 ingress.backend.host 与 ingress.frontend.host" -}}
{{- end -}}
{{- if and .Values.ingress.frontend.host (not .Values.ingress.backend.host) -}}
{{- fail "形态 B（分域）需同时设置 ingress.backend.host 与 ingress.frontend.host" -}}
{{- end -}}
{{- if eq .Values.backend.kubeconfig.mode "provided" -}}
{{- if and (not .Values.backend.kubeconfig.existingSecret) (not .Values.backend.kubeconfig.content) -}}
{{- fail "backend.kubeconfig.mode=provided 需给 existingSecret 或 content" -}}
{{- end -}}
{{- else if ne .Values.backend.kubeconfig.mode "synthesized" -}}
{{- fail "backend.kubeconfig.mode 只能是 synthesized 或 provided" -}}
{{- end -}}
{{- if .Values.platformDefaults.seed.enabled -}}
{{- if not .Values.platformDefaults.seed.values -}}
{{- fail "platformDefaults.seed.enabled=true 但 values 为空：请给 LLM/MySQL/Redis 等默认值，或关闭 seed" -}}
{{- end -}}
{{- end -}}
{{- if .Values.releaseAgent.enabled -}}
{{- if and (eq .Values.releaseAgent.packageSource "bundled") (not .Values.images.business) -}}
{{- fail "releaseAgent bundled 模式需 images.business（发布助手用业务镜像）" -}}
{{- end -}}
{{- if and (eq .Values.releaseAgent.packageSource "packageId") (eq (int .Values.releaseAgent.packageId) 0) -}}
{{- fail "releaseAgent.packageSource=packageId 时 packageId 必填" -}}
{{- end -}}
{{- end -}}
{{- end -}}
