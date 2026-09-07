// Package mcpsrv：MCP streamableHttp 门面（与 REST 同进程，工具转发 service.Core）。
// 工具返回结构化 JSON 文本；长操作（publish/update_env/republish）立即返回 deploying，
// 调用方通过 get_service_status 轮询推进。
package mcpsrv

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

// New 构造挂载到 /mcp 的 http.Handler。
func New(core *service.Core) http.Handler {
	server := mcp.NewServer(&mcp.Implementation{Name: "oaf-platform", Version: "v1"}, nil)
	registerTools(server, core)
	return mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, nil)
}

// registerTools 注册全部工具（与 REST /api/v1 一一对应）。
func registerTools(s *mcp.Server, core *service.Core) {
	// ---- 环境/镜像 ----
	mcp.AddTool(s, &mcp.Tool{
		Name: "list_images",
		Description: "列出平台当前环境可用的镜像列表与默认镜像。" +
			"发布前应调用它确定 image 参数——镜像名（registry 前缀、tag）随环境变化，不要凭记忆写死。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in struct{}) (*mcp.CallToolResult, JSONOut, error) {
		return okResult(map[string]any{
			"images":       core.Cfg.ImageOptions,
			"defaultImage": core.Cfg.DefaultImage,
		})
	})

	// ---- 配置包 ----
	mcp.AddTool(s, &mcp.Tool{
		Name:        "upload_package",
		Description: "上传 OAF 配置包(zip, base64 编码)。返回 packageId/slug/version 与 warnings(宽松校验提示)。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in UploadPackageIn) (*mcp.CallToolResult, JSONOut, error) {
		raw, err := base64.StdEncoding.DecodeString(in.ContentBase64)
		if err != nil {
			return errResult("content_base64 is not valid base64: " + err.Error())
		}
		name := in.Filename
		if name == "" {
			name = "package.zip"
		}
		rec, err := core.Packages.Upload(name, bytes.NewReader(raw))
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{
			"packageId": rec.ID, "name": rec.Name, "slug": rec.Slug,
			"version": rec.Version, "warnings": json.RawMessage(rec.WarningsJSON),
			"fileCount": rec.FileCount,
		})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "list_packages",
		Description: "列出已上传的 OAF 配置包（可按关键字过滤），含被服务引用计数。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in ListPackagesIn) (*mcp.CallToolResult, JSONOut, error) {
		list, err := core.Packages.List(in.Keyword)
		if err != nil {
			return errResult(err.Error())
		}
		items := []map[string]any{}
		for _, p := range list {
			items = append(items, map[string]any{
				"packageId": p.ID, "name": p.Name, "slug": p.Slug, "version": p.Version,
				"refCount": p.RefCount, "createdAt": p.CreatedAt,
			})
		}
		return okResult(items)
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "get_package_detail",
		Description: "查看配置包详情：manifest 摘要、warnings、文件树与 AGENTS.md 正文。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in ByIDIn) (*mcp.CallToolResult, JSONOut, error) {
		pkg, err := core.Packages.Get(in.PackageID)
		if err != nil {
			return errResult("package not found: " + strconv.FormatUint(uint64(in.PackageID), 10))
		}
		tree, _ := core.Packages.Tree(pkg)
		agentsMD, _ := core.Packages.ReadFile(pkg, "AGENTS.md")
		var manifest, warnings json.RawMessage = json.RawMessage(pkg.ManifestJSON), json.RawMessage(pkg.WarningsJSON)
		return okResult(map[string]any{
			"packageId": pkg.ID, "name": pkg.Name, "slug": pkg.Slug, "version": pkg.Version,
			"description": pkg.Description, "refCount": pkg.RefCount,
			"manifest": manifest, "warnings": warnings,
			"files": tree, "agentsMd": string(agentsMD),
		})
	})

	// ---- 服务发布与管理 ----
	mcp.AddTool(s, &mcp.Tool{
		Name: "publish_service",
		Description: "发布一个 OAF 服务（长操作：立即返回 deploying，之后轮询 get_service_status 至 running/register_failed）。" +
			"image 缺省用平台默认镜像；env 为容器环境变量键值对。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in PublishIn) (*mcp.CallToolResult, JSONOut, error) {
		reqBody := service.PublishRequest{PackageID: in.PackageID, Name: in.Name, Image: in.Image,
			Env: in.Env, Replicas: int32(in.Replicas)}
		svc, err := core.Publish(reqBody)
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{
			"serviceId": svc.ID, "k8sName": svc.K8sName, "status": svc.Status,
			"endpoint": svc.Endpoint,
			"hint":     "long operation: poll get_service_status until running or register_failed",
		})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "list_services",
		Description: "列出已发布服务及实时状态（可按 status/keyword 过滤）。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in ListServicesIn) (*mcp.CallToolResult, JSONOut, error) {
		list, err := core.List(in.Status, in.Keyword)
		if err != nil {
			return errResult(err.Error())
		}
		items := []map[string]any{}
		for _, sv := range list {
			items = append(items, map[string]any{
				"serviceId": sv.ID, "k8sName": sv.K8sName, "displayName": sv.DisplayName,
				"status": sv.Status, "endpoint": sv.Endpoint, "image": sv.Image,
				"registeredName": sv.RegisteredName, "registeredVersion": sv.RegisteredVersion,
				"pods": podsJSON(sv.Pods),
			})
		}
		return okResult(items)
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "get_service_status",
		Description: "查询服务详情：状态、实时 Pod、Agent Card(A2A 注册信息)、最近事件。serviceId 或 k8sName 二选一。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in StatusIn) (*mcp.CallToolResult, JSONOut, error) {
		var (
			detail *service.ServiceDetail
			err    error
		)
		switch {
		case in.ServiceID > 0:
			detail, err = core.GetDetail(in.ServiceID)
		case in.K8sName != "":
			sv, e := core.GetByK8sName(in.K8sName)
			if e != nil {
				err = e
			} else {
				detail, err = core.GetDetail(sv.ID)
			}
		default:
			return errResult("serviceId or k8sName is required")
		}
		if err != nil {
			return errResult(err.Error())
		}
		var card, env json.RawMessage = json.RawMessage(detail.AgentCardJSON), json.RawMessage(detail.EnvJSON)
		return okResult(map[string]any{
			"serviceId": detail.ID, "k8sName": detail.K8sName, "status": detail.Status,
			"endpoint": detail.Endpoint, "image": detail.Image, "replicas": detail.Replicas,
			"env": env, "pods": podsJSON(detail.Pods),
			"agentCard": card, "registeredName": detail.RegisteredName,
			"registeredVersion": detail.RegisteredVersion,
			"registeredAt":      detail.RegisteredAt, "events": detail.Events,
		})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name: "update_service_env",
		Description: "全量替换服务环境变量并滚动重启（长操作：返回 deploying，需轮询）。" +
			"注意：env 必须包含全部所需键值（覆盖语义）；AGENT_CONFIG_DIR/SERVER_HOST/SERVER_PORT 为平台保留键不可设置。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in UpdateEnvIn) (*mcp.CallToolResult, JSONOut, error) {
		svc, err := core.UpdateEnv(in.ServiceID, in.Env)
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{"serviceId": svc.ID, "status": svc.Status,
			"hint": "poll get_service_status until running"})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "republish_service",
		Description: "重新发布服务：幂等重建资源并滚动重启；可选切换新版本配置包(packageId)或镜像。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in RepublishIn) (*mcp.CallToolResult, JSONOut, error) {
		opt := service.RepublishOptions{}
		if in.PackageID != nil && *in.PackageID > 0 {
			opt.PackageID = in.PackageID
		}
		if in.Image != nil && *in.Image != "" {
			opt.Image = in.Image
		}
		svc, err := core.Republish(in.ServiceID, opt)
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{"serviceId": svc.ID, "status": svc.Status,
			"packageId": svc.PackageID, "hint": "poll get_service_status until running"})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "unpublish_service",
		Description: "下线服务：删除 Deployment/Service/Ingress，保留配置包与记录，可再次 publish 上线。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in ByServiceIDIn) (*mcp.CallToolResult, JSONOut, error) {
		svc, err := core.Unpublish(in.ServiceID)
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{"serviceId": svc.ID, "status": svc.Status})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name:        "register_service",
		Description: "手动触发 A2A 注册（register_failed 时重试拉取 agent-card）。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in ByServiceIDIn) (*mcp.CallToolResult, JSONOut, error) {
		svc, err := core.Reregister(in.ServiceID)
		if err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{"serviceId": svc.ID, "status": svc.Status})
	})

	mcp.AddTool(s, &mcp.Tool{
		Name: "delete_service",
		Description: "删除服务：级联清理 K8s 资源与 PVC 包目录（无其他引用时）、删除数据库记录。不可恢复。" +
			"必须先 get_service_status 取得目标 k8sName 并向用户确认后，以 confirm_k8s_name 原样传入才会执行。",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in DeleteIn) (*mcp.CallToolResult, JSONOut, error) {
		var (
			svc *store.ServiceEntity
			err error
		)
		switch {
		case in.ServiceID > 0:
			svc, err = core.Get(in.ServiceID)
		case in.K8sName != "":
			svc, err = core.GetByK8sName(in.K8sName)
		default:
			return errResult("serviceId or k8sName is required")
		}
		if err != nil {
			return errResult(err.Error())
		}
		// 机械式二次确认：confirm_k8s_name 必须与目标完全一致
		if in.ConfirmK8sName == "" || in.ConfirmK8sName != svc.K8sName {
			return errResult(fmt.Sprintf("confirmation required: deleting %q is irreversible; retry with confirm_k8s_name=%q after user approval", svc.K8sName, svc.K8sName))
		}
		if err := core.Delete(svc.ID); err != nil {
			return errResult(err.Error())
		}
		return okResult(map[string]any{"deleted": true, "serviceId": svc.ID, "k8sName": svc.K8sName})
	})
}

// ---- 输入/输出类型 ----

type UploadPackageIn struct {
	Filename      string `json:"filename,omitempty" jsonschema:"zip 文件名"`
	ContentBase64 string `json:"content_base64" jsonschema:"zip 内容的 base64 编码"`
}
type ListPackagesIn struct {
	Keyword string `json:"keyword,omitempty"`
}
type ByIDIn struct {
	PackageID uint `json:"packageId"`
}
type PublishIn struct {
	PackageID uint              `json:"packageId"`
	Name      string            `json:"name,omitempty" jsonschema:"可选,自定义服务名"`
	Image     string            `json:"image,omitempty" jsonschema:"可选,须在 AVAILABLE_IMAGES 内"`
	Env       map[string]string `json:"env,omitempty"`
	Replicas  int               `json:"replicas,omitempty"`
}
type ListServicesIn struct {
	Status  string `json:"status,omitempty" jsonschema:"deploying|running|register_failed|stopped..."`
	Keyword string `json:"keyword,omitempty"`
}
type StatusIn struct {
	ServiceID uint   `json:"serviceId,omitempty"`
	K8sName   string `json:"k8sName,omitempty"`
}
type UpdateEnvIn struct {
	ServiceID uint              `json:"serviceId"`
	Env       map[string]string `json:"env"`
}
type RepublishIn struct {
	ServiceID uint    `json:"serviceId"`
	PackageID *uint   `json:"packageId,omitempty"`
	Image     *string `json:"image,omitempty"`
}
type ByServiceIDIn struct {
	ServiceID uint `json:"serviceId"`
}
type DeleteIn struct {
	ServiceID      uint   `json:"serviceId,omitempty"`
	K8sName        string `json:"k8sName,omitempty"`
	ConfirmK8sName string `json:"confirm_k8s_name,omitempty" jsonschema:"删除确认：必须填入目标的 k8sName 原文"`
}

// JSONOut 任意 JSON 输出。
type JSONOut = map[string]any

// ---- 工具函数 ----

// okResult 将输出序列化为文本内容。
func okResult(v any) (*mcp.CallToolResult, JSONOut, error) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		b = []byte(fmt.Sprintf("%v", v))
	}
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(b)}}},
		map[string]any{"_raw": json.RawMessage(b)}, nil
}

// errResult 业务错误以 IsError 文本返回（便于 LLM 阅读并自我修正）。
func errResult(msg string) (*mcp.CallToolResult, JSONOut, error) {
	return &mcp.CallToolResult{IsError: true,
		Content: []mcp.Content{&mcp.TextContent{Text: msg}}}, nil, nil
}

func podsJSON(pods any) any        { return pods }
func bytesReader(b []byte) *bytesR { return &bytesR{b: b} }

type bytesR struct{ b []byte }

func (r *bytesR) Read(p []byte) (int, error) {
	if len(r.b) == 0 {
		return 0, errors.New("EOF")
	}
	n := copy(p, r.b)
	r.b = r.b[n:]
	return n, nil
}

var _ = fmt.Sprintf
var _ = context.Background
