// 导出单（export_orders）链路：创建 → 异步构建 → 交付 → 人工推进（跨集群导出设计 §8/§9）。
// 文件级契约见 docs/implementation/cross-cluster-export-contract.md §2.2/§4。
//
// 状态机（设计 §8）：draft → building → built → approving → approved → deploying → deployed；
// 任意非终态可入 failed / cancelled。v1 推进由运维在平台人工操作（或脚本回填），记录操作人
// 与 ITSM 单号；draft 为 v1 预留（创建即入 building）。异步构建 goroutine 与既有
// asyncWaitAndRegister 同属「进程内后台推进」：backend 重启丢 in-flight 构建导出单停留
// building，v1 靠重新创建导出单解救（同 marker 幂等重推，内容寻址 digest 不变，§9-R1）。
// 导出链路对业务 K8s 集群零写入（只读包快照与 DB），不触碰控制面原则一红线。
package service

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"agent-manager/backend/internal/imagebuilder"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

// exportBuildTimeout 异步构建超时（remote.Write 直推 KB~MB 级纯数据镜像，10min 足够宽裕）。
const exportBuildTimeout = 10 * time.Minute

// ExportOrderRequest POST .../export 请求体（契约 §4.3）：
// marker 必填且须为该服务 frozen 标记；baseImage 必须命中 L1 BASE_IMAGE_WHITELIST
// （缺省取白名单首项）；env 走 ValidateEnv + 导出保留键/L1 锁定校验；chartVersion 缺省 0.1.0。
type ExportOrderRequest struct {
	Marker       string            `json:"marker"`
	BaseImage    string            `json:"baseImage"`
	ChartVersion string            `json:"chartVersion"`
	Env          map[string]string `json:"env"`
	SecretKeys   []string          `json:"secretKeys"`
	Replicas     int               `json:"replicas"`
}

// ExportAdvanceInput POST .../exports/:id/status 请求体（人工推进，记录操作人与 ITSM 单号）。
type ExportAdvanceInput struct {
	Status       string `json:"status"`
	ItsmTicketID string `json:"itsmTicketId"`
}

// exportAdvanceAllow 人工推进合法迁移表：前向链 built→approving→approved→deploying→deployed
// 逐步推进；任意非终态可入 failed/cancelled；终态（deployed/failed/cancelled）不可再迁移。
var exportAdvanceAllow = map[string]map[string]bool{
	store.ExportStatusDraft:     {store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	store.ExportStatusBuilding:  {store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	store.ExportStatusBuilt:     {store.ExportStatusApproving: true, store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	store.ExportStatusApproving: {store.ExportStatusApproved: true, store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	store.ExportStatusApproved:  {store.ExportStatusDeploying: true, store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	store.ExportStatusDeploying: {store.ExportStatusDeployed: true, store.ExportStatusFailed: true, store.ExportStatusCancelled: true},
	// deployed / failed / cancelled 为终态：缺席即不可推进
}

// exportOrderNo 导出单号：EXP-YYYYMMDD-%04d（%04d = export_orders 自增 id，契约 §5.7）。
func exportOrderNo(order *store.ExportOrderEntity) string {
	return fmt.Sprintf("EXP-%s-%04d", order.CreatedAt.Format("20060102"), order.ID)
}

// CreateExportOrder 创建导出单：校验（Images 已装配 / marker frozen / env / 四层裁决）→
// 建 status=building 行 → 同步渲染 values/manifest 存库 → goroutine 调 Images.Build →
// 回填 ref/digest 并重渲染 → built；构建失败入 failed 带原因。
func (c *Core) CreateExportOrder(username string, wsID, svcID uint, req *ExportOrderRequest) (*store.ExportOrderEntity, error) {
	if c.Images == nil {
		// 契约 §3.3：nil = 未配置镜像构建（main.go 集成阶段装配），不 panic、不降级假成功
		return nil, fmt.Errorf("%w: image builder not configured", ErrInvalidForm)
	}
	if strings.TrimSpace(req.Marker) == "" {
		return nil, fmt.Errorf("%w: marker is required (frozen package mark)", ErrInvalidForm)
	}
	svc, err := c.loadServiceForExport(wsID, svcID)
	if err != nil {
		return nil, err
	}
	if err := ValidateEnv(req.Env); err != nil {
		return nil, err
	}
	if err := validateSecretKeys(req.SecretKeys); err != nil {
		return nil, err
	}
	mark, err := c.loadFrozenMark(svcID, req.Marker)
	if err != nil {
		return nil, err // 无标记不可构建（设计 §3 要点）；draft 标记同拒
	}
	if mark.WorkspaceID != wsID {
		return nil, ErrNotFound
	}
	l1, merged, _, err := c.resolveExportLayers(wsID, req)
	if err != nil {
		return nil, err
	}
	chartVersion := orStr(req.ChartVersion, defaultExportChartVersion)
	short := k8s.ShortName(svc.K8sName)
	oafRef := buildOafImageRef(merged["REGISTRY_ADDRESS"], merged["REGISTRY_NAMESPACE"], short, mark.Marker)
	// L3 派生键试算前置（坏 BASE 等渲染期错误在落库前 400，不产生 building 垃圾行）
	if _, err := derivedExportEnv(merged, short); err != nil {
		return nil, err
	}
	// 源包信息（manifest 溯源字段）与工作空间（header/manifest.workspace.slug）；包缺失等同
	// 渲染失败，直接 400 不落库
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	var ws store.Workspace
	if err := c.DB.First(&ws, wsID).Error; err != nil {
		return nil, err
	}

	order := &store.ExportOrderEntity{
		ServiceID:       svcID,
		WorkspaceID:     wsID,
		SourcePackageID: svc.PackageID,
		ChartVersion:    chartVersion,
		BaseImage:       req.BaseImage,
		OafImageRef:     oafRef,
		Status:          store.ExportStatusBuilding,
		RequestJSON:     mustJSON(req),
		// ManifestJSON 先置合法 JSON 占位：列声明 gorm type:json（model.go:372），MySQL 8
		// 拒绝空串入库（Error 3140 Invalid JSON text）；单号依赖自增 ID（exportOrderNo），
		// 渲染只能在落库后进行，紧随其后的 renderExportArtifacts 落库即覆盖占位
		ManifestJSON: "{}",
		CreatedBy:    username,
	}
	if err := c.DB.Create(order).Error; err != nil {
		return nil, translateStorageError(err)
	}
	// 渲染产物落库（首渲 digest 为空，构建完成后据此重渲染回填，保证可复现）
	in := &exportRenderInput{
		OrderNo:      exportOrderNo(order),
		Svc:          svc,
		Workspace:    &ws,
		Pkg:          pkg,
		Mark:         mark,
		L1:           l1,
		Merged:       merged,
		Req:          req,
		CreatedBy:    username,
		OafImageRef:  oafRef,
		ChartVersion: chartVersion,
	}
	values, manifest, err := renderExportArtifacts(in)
	if err != nil {
		c.failExportOrder(order.ID, "render: "+err.Error())
		return nil, err
	}
	order.ValuesYAML = values
	order.ManifestJSON = manifest
	if err := c.DB.Save(order).Error; err != nil {
		return nil, err
	}
	c.asyncBuildExportOrder(order.ID)
	return order, nil
}

// asyncBuildExportOrder 后台推进：物化 mark 快照 → Images.Build 直推 → 回填 digest 并重渲染 → built。
// 只写平台 DB 与外部镜像仓库，不写任何业务 K8s 资源（原则一）。
func (c *Core) asyncBuildExportOrder(orderID uint) {
	go func() {
		defer func() { _ = recover() }() // 防御 goroutine panic 拖垮进程（asyncWaitAndRegister 同模式）
		ctx, cancel := context.WithTimeout(context.Background(), exportBuildTimeout)
		defer cancel()

		var order store.ExportOrderEntity
		if err := c.DB.First(&order, orderID).Error; err != nil {
			return
		}
		if order.Status != store.ExportStatusBuilding {
			return // 已被人工推进/取消：构建作废
		}
		var req ExportOrderRequest
		if err := jsonUnmarshalStrict(order.RequestJSON, &req); err != nil {
			c.failExportOrder(orderID, "corrupt request snapshot: "+err.Error())
			return
		}
		mark, err := c.loadFrozenMark(order.ServiceID, req.Marker)
		if err != nil {
			c.failExportOrder(orderID, "mark load: "+err.Error())
			return
		}
		entries, err := parseMarkFiles(mark.FilesJSON)
		if err != nil {
			c.failExportOrder(orderID, err.Error())
			return
		}
		files, err := decodeExportEntries(entries)
		if err != nil {
			c.failExportOrder(orderID, err.Error())
			return
		}
		// 快照物化到临时目录（自包含快照不回读源包——源包可能已被换版/删除，§5.6）；
		// 文件权限 0644 对齐上传链路最低权限约定
		dir, err := os.MkdirTemp("", "oaf-export-build-*")
		if err != nil {
			c.failExportOrder(orderID, "temp dir: "+err.Error())
			return
		}
		defer os.RemoveAll(dir)
		paths := make([]string, 0, len(files))
		for p := range files {
			paths = append(paths, p)
		}
		sort.Strings(paths)
		for _, p := range paths {
			clean, cerr := store.CleanSubPath(p)
			if cerr != nil {
				c.failExportOrder(orderID, "snapshot path "+cerr.Error())
				return
			}
			dst := filepath.Join(dir, filepath.FromSlash(clean))
			if !strings.HasPrefix(dst, dir+string(os.PathSeparator)) {
				c.failExportOrder(orderID, "snapshot path escapes build dir: "+p)
				return
			}
			if mkErr := os.MkdirAll(filepath.Dir(dst), 0o755); mkErr != nil {
				c.failExportOrder(orderID, "mkdir: "+mkErr.Error())
				return
			}
			if wErr := os.WriteFile(dst, files[p], 0o644); wErr != nil {
				c.failExportOrder(orderID, "write: "+wErr.Error())
				return
			}
		}
		var m *ExportManifest
		if m, err = parseExportManifest(order.ManifestJSON); err != nil {
			c.failExportOrder(orderID, err.Error())
			return
		}
		out, berr := c.Images.Build(ctx, imagebuilder.BuildInput{
			PackageDir: dir,
			TargetRef:  order.OafImageRef,
			// 审计标签仅含标记级不变量：slug/version/checksum 对同一 frozen marker 恒定，
			// 同 tag 重推 digest 不变（契约 §8.7#6/§9-R1「重新创建导出单解救，内容寻址不变」）。
			// export-id（EXP-单号）随导出单自增变化，若进 config labels 会使同 marker 两次
			// 导出产生不同 digest、断裂 §12.5 审计链——对契约 §3.1 标签清单的偏差：export-id
			// 审计由 values podAnnotations（exportvalues.go renderExportValues）与
			// manifest.exportOrder 承载，不入镜像（评审修复）
			Labels: map[string]string{
				"oaf.platform/slug":     m.Package.Slug,
				"oaf.platform/version":  mark.PackageVersion,
				"oaf.platform/checksum": "sha256:" + mark.Checksum,
			},
		})
		if berr != nil {
			c.failExportOrder(orderID, "image build: "+berr.Error())
			return
		}
		if err := c.rebuildExportArtifacts(&order, &req, out.Digest); err != nil {
			c.failExportOrder(orderID, "re-render: "+err.Error())
			return
		}
	}()
}

// rebuildExportArtifacts 构建完成后据请求快照重渲染 values/manifest（回填 digest）并转 built。
// 全量重读 svc/mark/pkg/workspace 与 L1（L2 取请求快照），保证产物与当前事实一致。
func (c *Core) rebuildExportArtifacts(order *store.ExportOrderEntity, req *ExportOrderRequest, digest string) error {
	var svc store.ServiceEntity
	if err := c.DB.First(&svc, order.ServiceID).Error; err != nil {
		return err
	}
	l1, merged, _, err := c.resolveExportLayers(order.WorkspaceID, req)
	if err != nil {
		return err
	}
	mark, err := c.loadFrozenMark(order.ServiceID, req.Marker)
	if err != nil {
		return err
	}
	pkg, err := c.Packages.Get(order.SourcePackageID)
	if err != nil {
		return err
	}
	var ws store.Workspace
	if err := c.DB.First(&ws, order.WorkspaceID).Error; err != nil {
		return err
	}
	in := &exportRenderInput{
		OrderNo:      exportOrderNo(order),
		Svc:          &svc,
		Workspace:    &ws,
		Pkg:          pkg,
		Mark:         mark,
		L1:           l1,
		Merged:       merged,
		Req:          req,
		CreatedBy:    order.CreatedBy,
		OafImageRef:  order.OafImageRef,
		OafDigest:    digest,
		ChartVersion: order.ChartVersion,
	}
	values, manifest, err := renderExportArtifacts(in)
	if err != nil {
		return err
	}
	// 状态守卫：构建期间被人工推进/取消（building→failed/cancelled，exportAdvanceAllow）
	// 则丢弃构建结果，不把终态拉回 built（条件更新，RowsAffected=0 即静默放弃）
	order.OafImageDigest = digest
	order.ValuesYAML = values
	order.ManifestJSON = manifest
	order.Status = store.ExportStatusBuilt
	order.Error = ""
	res := c.DB.Model(&store.ExportOrderEntity{}).
		Where("id = ? AND status = ?", order.ID, store.ExportStatusBuilding).
		Updates(map[string]interface{}{
			"oaf_image_digest": order.OafImageDigest,
			"values_yaml":      order.ValuesYAML,
			"manifest_json":    order.ManifestJSON,
			"status":           order.Status,
			"error":            "",
		})
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		log.Printf("[export_build] order=%d build result discarded (status left building during build)", order.ID)
	}
	return nil
}

// failExportOrder 构建失败：入 failed 带原因（Error 截断到列宽 512）。
// 状态守卫：仅 building 可入 failed（终态不可迁移，设计 §8）——构建期间被人工
// cancelled/failed 的导出单不得改写；条件更新 RowsAffected=0 即放弃并留痕
// （rebuildExportArtifacts 成功路径同款守卫，评审修复）。
func (c *Core) failExportOrder(orderID uint, reason string) {
	r := []rune(reason)
	if len(r) > 512 {
		r = r[:512]
	}
	res := c.DB.Model(&store.ExportOrderEntity{}).
		Where("id = ? AND status = ?", orderID, store.ExportStatusBuilding).
		Updates(map[string]interface{}{"status": store.ExportStatusFailed, "error": string(r)})
	if res.Error != nil {
		log.Printf("[export_build] order=%d mark failed failed: %v (reason=%s)", orderID, res.Error, reason)
		return
	}
	if res.RowsAffected == 0 {
		log.Printf("[export_build] order=%d failure discarded (status left building): %s", orderID, reason)
	}
}

// resolveExportOrderAccess 导出单可见性：调用者须为 order.workspace_id 成员或平台 admin
// （契约 §4.2，复用 ResolveWorkspace 的成员关系/隐式 admin 裁决），返回单与本人成员角色；
// 不可见统一 404。
func (c *Core) resolveExportOrderAccess(uid, orderID uint) (*store.ExportOrderEntity, string, error) {
	var order store.ExportOrderEntity
	if err := c.DB.First(&order, orderID).Error; err != nil {
		return nil, "", ErrNotFound
	}
	u, err := c.GetUser(uid)
	if err != nil {
		return nil, "", ErrNotFound
	}
	_, role, err := c.ResolveWorkspace(u, strconv.FormatUint(uint64(order.WorkspaceID), 10))
	if err != nil {
		return nil, "", ErrNotFound
	}
	return &order, role, nil
}

// GetExportOrder 导出单状态与详情（构建进度）。ValuesYAML/RequestJSON 模型 json:"-"，
// 详情 JSON 永不回显（仅下载端点交付）。
func (c *Core) GetExportOrder(uid, orderID uint) (*store.ExportOrderEntity, error) {
	order, _, err := c.resolveExportOrderAccess(uid, orderID)
	return order, err
}

// AdvanceExportOrder 人工推进（owner/admin，推进属运维动作）：合法迁移见 exportAdvanceAllow；
// 记录操作人与 ITSM 单号（写 ItsmTicketID / ManifestJSON.itsm）。
// 条件更新（读到的 status 未变化才落）：防与异步构建回写/并发推进的丢失更新竞态——
// 全行 Save 会把 stale 快照（digest 为空的读入值）整体覆盖回去（rebuildExportArtifacts
// 同款守卫，评审修复）；状态已变化时拒绝，客户端刷新后重试。
func (c *Core) AdvanceExportOrder(uid, orderID uint, username string, in *ExportAdvanceInput) (*store.ExportOrderEntity, error) {
	order, role, err := c.resolveExportOrderAccess(uid, orderID)
	if err != nil {
		return nil, err
	}
	if err := RequireWsRole(role); err != nil {
		return nil, err
	}
	if !exportAdvanceAllow[order.Status][in.Status] {
		return nil, fmt.Errorf("%w: export status %s -> %s", ErrBadState, order.Status, in.Status)
	}
	now := time.Now()
	manifest, err := manifestWithItsm(order.ManifestJSON, orStr(in.ItsmTicketID, order.ItsmTicketID), username, now)
	if err != nil {
		return nil, err
	}
	nextStatus, nextTicket := in.Status, orStr(in.ItsmTicketID, order.ItsmTicketID)
	res := c.DB.Model(&store.ExportOrderEntity{}).
		Where("id = ? AND status = ?", order.ID, order.Status).
		Updates(map[string]interface{}{
			"status":         nextStatus,
			"itsm_ticket_id": nextTicket,
			"manifest_json":  manifest,
		})
	if res.Error != nil {
		return nil, res.Error
	}
	if res.RowsAffected == 0 {
		return nil, fmt.Errorf("%w: export order state changed concurrently, refresh and retry", ErrBadState)
	}
	order.Status, order.ItsmTicketID, order.ManifestJSON = nextStatus, nextTicket, manifest
	return order, nil
}

// ExportOrderDownload 取交付物文本并落下载审计（§5.8）：结构化访问日志 +
// manifest audit.downloadedBy 追加。values 不经任何 JSON 响应回显，仅本端点文件交付。
// 审计为 manifest 读-改-写，仅条件更新 manifest_json 单列（不整行 Save——防 stale 快照
// 覆盖并发构建回写的 digest/重渲染产物）；丢失更新时重读重试，耗尽则 400。
func (c *Core) ExportOrderDownload(uid, orderID uint, username, file string) (*store.ExportOrderEntity, error) {
	order, _, err := c.resolveExportOrderAccess(uid, orderID)
	if err != nil {
		return nil, err
	}
	switch file {
	case "values.yaml", "manifest.json":
	default:
		return nil, fmt.Errorf("%w: unknown export artifact %q", ErrInvalidForm, file)
	}
	for attempt := 0; ; attempt++ {
		manifest, err := manifestAppendDownload(order.ManifestJSON, username, time.Now())
		if err != nil {
			return nil, err
		}
		res := c.DB.Model(&store.ExportOrderEntity{}).
			Where("id = ? AND status = ?", order.ID, order.Status).
			UpdateColumn("manifest_json", manifest)
		if res.Error != nil {
			return nil, res.Error
		}
		if res.RowsAffected > 0 {
			order.ManifestJSON = manifest
			log.Printf("[export_download] order=%d user=%s file=%s ts=%s", order.ID, username, file, time.Now().UTC().Format(time.RFC3339))
			return order, nil
		}
		if attempt >= 2 {
			return nil, fmt.Errorf("%w: export order state changed concurrently, retry", ErrBadState)
		}
		// 丢失更新：重读最新行（含并发方写入的 manifest）再追加一次
		if err := c.DB.First(&order, orderID).Error; err != nil {
			return nil, ErrNotFound
		}
	}
}

// ---- 导出页预填（设计 §9 GET .../export/preview；契约 §4.1）----

// ExportPreviewService 服务区（派生预览所需最小集）。
type ExportPreviewService struct {
	ID          uint   `json:"id"`
	DisplayName string `json:"displayName"`
	K8sName     string `json:"k8sName"`
	ShortName   string `json:"shortName"`
	Replicas    int    `json:"replicas"`
}

// ExportPreviewEnvEntry 现 env 行（敏感掩码：只回 hasValue/sensitive，不回值）。
type ExportPreviewEnvEntry struct {
	HasValue  bool `json:"hasValue"`
	Sensitive bool `json:"sensitive"`
}

// ExportDerivedPreview 派生键预览（导出页展示 L3 产物形状）。
type ExportDerivedPreview struct {
	CheckpointDB string `json:"checkpointDB"` // {基库}_{short}；L1 未配基库为空
	RedisPrefix  string `json:"redisPrefix"`  // {short}:
	OafImageRef  string `json:"oafImageRef"`  // 仓库已配时按首个可用 marker 形状预览（marker 占位 {marker}）
}

// ExportPreview 导出发布页预填数据源（契约 §4.1：源包结构与版本链、现 env（敏感掩码）、
// 空间默认值（掩码）、镜像白名单、派生键预览）。
type ExportPreview struct {
	Service            ExportPreviewService             `json:"service"`
	Package            ExportPackageSourceInfo          `json:"package"`
	ServiceEnv         map[string]ExportPreviewEnvEntry `json:"serviceEnv"`
	Marks              []ExportMarkView                 `json:"marks"`
	RuntimeDefaults    map[string]RuntimeDefaultEntry   `json:"runtimeDefaults"`
	BaseImageWhitelist []ImageOption                    `json:"baseImageWhitelist"`
	DerivedPreview     ExportDerivedPreview             `json:"derivedPreview"`
	LockedKeys         []string                         `json:"lockedKeys"` // L1 已配置键（导出页锁定不可编辑）
}

// ExportPreview 组装预填视图。
func (c *Core) ExportPreview(wsID, svcID uint) (*ExportPreview, error) {
	svc, err := c.loadServiceForExport(wsID, svcID)
	if err != nil {
		return nil, err
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	short := k8s.ShortName(svc.K8sName)
	l1, err := c.loadRuntimeDefaultValues(wsID)
	if err != nil {
		return nil, err
	}
	whitelist, err := parseBaseImageWhitelist(l1["BASE_IMAGE_WHITELIST"])
	if err != nil {
		return nil, err
	}
	marks, err := c.ListExportMarks(wsID, svcID)
	if err != nil {
		return nil, err
	}
	defaults, err := c.GetRuntimeDefaults(wsID)
	if err != nil {
		return nil, err
	}
	// 现 env（敏感掩码）：plain/secret 分区 + 模板敏感集合标注，绝不回值
	plain, secret, err := c.partitionServiceEnv(svc)
	if err != nil {
		return nil, err
	}
	sensitive := platformconfig.SensitiveKeys()
	envView := make(map[string]ExportPreviewEnvEntry, len(plain)+len(secret))
	for k, v := range plain {
		envView[k] = ExportPreviewEnvEntry{HasValue: v != "", Sensitive: sensitive[k]}
	}
	for k := range secret {
		envView[k] = ExportPreviewEnvEntry{HasValue: true, Sensitive: true}
	}
	locked := make([]string, 0, len(l1))
	for k, v := range l1 {
		if v != "" {
			locked = append(locked, k)
		}
	}
	sort.Strings(locked)
	// 派生键预览：checkpoint 库名 / Redis 前缀 / OAF 镜像引用形状（marker 占位）
	dp := ExportDerivedPreview{RedisPrefix: redisPrefixFor(short)}
	if base := l1["CHECKPOINT_JDBC_BASE"]; base != "" {
		dp.CheckpointDB = checkpointDBNameFor(base, short)
	}
	if l1["REGISTRY_ADDRESS"] != "" {
		dp.OafImageRef = buildOafImageRef(l1["REGISTRY_ADDRESS"], l1["REGISTRY_NAMESPACE"], short, "{marker}")
	}
	return &ExportPreview{
		Service:            ExportPreviewService{ID: svc.ID, DisplayName: svc.DisplayName, K8sName: svc.K8sName, ShortName: short, Replicas: svc.Replicas},
		Package:            ExportPackageSourceInfo{PackageID: pkg.ID, Slug: pkg.Slug, Version: pkg.Version, Checksum: pkg.Checksum},
		ServiceEnv:         envView,
		Marks:              marks,
		RuntimeDefaults:    defaults,
		BaseImageWhitelist: whitelist,
		DerivedPreview:     dp,
		LockedKeys:         locked,
	}, nil
}
