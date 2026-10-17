// OAF 包生成器（M3）：创建页表单 → AGENTS.md（frontmatter 派生）+ skills 复制 +
// mcp-configs 注入 → zip 登记 → 复用既有 Publish 发布。
// 决议 5：vendorKey 固定平台命名空间；agentKey/slug 由名称 slugify 同源派生；
// 生成 v1 即版本链起点。业务运行时契约以 agent-framework OafConfigLoader 为准：
// 子 agent 的 frontmatter YAML 键为 `agents`（含 endpoint 字段），非设计稿措辞的
// subAgents —— 生成包必须能被运行时解析（业务面零改动优先）。
package service

import (
	"bytes"
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"

	"agent-manager/backend/internal/store"
)

// platformVendorKey 平台命名空间（同 release-agent 先例）。
const platformVendorKey = "agentmanager"

// PlatformVendorKey 导出（console preview-name 响应使用）。
func PlatformVendorKey() string { return platformVendorKey }

// builtinPlatformMCPServer 内置虚拟连接器（M6 §2.2）：不落表，创建页注入选项，
// 生成包 mcp-configs/platform/config.yaml 按 release-agent 同款格式硬编码模板。
const builtinPlatformMCPServer = "platform"

// specProfileOf 规格档直译（决议 4，见 publish.go specProfiles）。

// ModelConnRef 创建表单的模型连接勾选。
type ModelConnRef struct {
	ConnectionID uint `json:"connectionId"`
	IsDefault    bool `json:"isDefault"`
}

// SkillRef 创建表单的 Skill 勾选（source: "package"（带 packageId，进包）| "l4"（运行时注入，不进包））。
type SkillRef struct {
	Name      string `json:"name"`
	Source    string `json:"source"`
	PackageID uint   `json:"packageId"`
	Version   string `json:"version"`
}

// SubAgentRef 创建表单的 SubAgent 行（远程形态带 endpoint）。
type SubAgentRef struct {
	Agent       string   `json:"agent"`
	Role        string   `json:"role"`
	Delegations []string `json:"delegations"`
	Endpoint    string   `json:"endpoint"` // 空=本地子代理（workspace subagents/*.md）
}

// McpServerRef 创建表单的 MCP 勾选（ConnectorID 引用工具管理连接器；Builtin=platform 内置）。
type McpServerRef struct {
	ConnectorID uint   `json:"connectorId"`
	Builtin     string `json:"builtin,omitempty"` // "platform"
}

// AgentForm 创建/编辑共用表单（编辑无 mode，见 UpdateAgent）。
type AgentForm struct {
	Mode             string         `json:"mode"` // harness | import | adopt
	Name             string         `json:"name"`
	Description      string         `json:"description"`
	Image            string         `json:"image"`
	ModelConnections []ModelConnRef `json:"modelConnections"`
	Prompt           string         `json:"prompt"`
	Skills           []SkillRef     `json:"skills"`
	SubAgents        []SubAgentRef  `json:"subAgents"`
	McpServers       []McpServerRef `json:"mcpServers"`
	Spec             string         `json:"spec"`
	Replicas         int            `json:"replicas"`
	PublicEndpoint   *bool          `json:"publicEndpoint"`
	// RemoteAuth 集成凭证（endpoint → 认证头值）：仅进当次发布 Secret，不落表不落包（M7 §2.3）
	RemoteAuth map[string]string `json:"remoteAuth,omitempty"`
	// ImportPackageID import 模式：上传包 / 公共包派生来源
	ImportPackageID *uint `json:"importPackageId,omitempty"`
	// AdoptServiceID adopt 模式（纳管存量服务，仅平台 admin）：存量 service id
	AdoptServiceID *uint `json:"adoptServiceId,omitempty"`
}

var (
	agentKeyInvalidRe = regexp.MustCompile(`[^a-z0-9-]+`)
	agentKeyDashRe    = regexp.MustCompile(`-{2,}`)
)

// SlugifyAgentKey 名称 → agentKey（决议 5）：小写、非 [a-z0-9-] 转 -、压缩连续 -、
// 去首尾 -。中文字符全部剥离；结果为空时确定性回退 agent-{md5 前 6 位}——同名恒同 key，
// preview-name 预览与实际创建必然一致（随机回退会让两次派生不一致，部署实测发现）。
func SlugifyAgentKey(name string) string {
	lower := strings.ToLower(name)
	converted := agentKeyInvalidRe.ReplaceAllString(lower, "-")
	converted = agentKeyDashRe.ReplaceAllString(converted, "-")
	converted = strings.Trim(converted, "-")
	if converted == "" {
		sum := md5.Sum([]byte(name))
		return "agent-" + hex.EncodeToString(sum[:3])
	}
	if len(converted) > 63 {
		converted = strings.Trim(converted[:63], "-")
	}
	return converted
}

// nextVersion 语义化版本递增（patch+1，>999 进位 minor；编辑链路 copy-on-write 用）。
func nextVersion(v string) string {
	var a, b, c int
	if _, err := fmt.Sscanf(v, "%d.%d.%d", &a, &b, &c); err != nil {
		return "1.0.1"
	}
	c++
	if c > 999 {
		c = 0
		b++
		if b > 999 {
			b = 0
			a++
		}
	}
	return fmt.Sprintf("%d.%d.%d", a, b, c)
}

// ---- frontmatter 生成（与 agent-framework OafConfigLoader 契约对齐）----

type fmModel struct {
	Provider string `yaml:"provider" json:"provider"`
	ModelID  string `yaml:"modelId" json:"modelId"`
}

type fmSkill struct {
	Name     string `yaml:"name" json:"name"`
	Source   string `yaml:"source" json:"source"`
	Version  string `yaml:"version" json:"version"`
	Required bool   `yaml:"required,omitempty" json:"required,omitempty"`
}

type fmSubAgent struct {
	Vendor      string   `yaml:"vendor,omitempty" json:"vendor,omitempty"`
	Agent       string   `yaml:"agent" json:"agent"`
	Version     string   `yaml:"version,omitempty" json:"version,omitempty"`
	Role        string   `yaml:"role,omitempty" json:"role,omitempty"`
	Delegations []string `yaml:"delegations,omitempty" json:"delegations,omitempty"`
	Required    bool     `yaml:"required,omitempty" json:"required,omitempty"`
	Endpoint    string   `yaml:"endpoint,omitempty" json:"endpoint,omitempty"` // 远程形态
}

type fmMCPServer struct {
	Vendor    string `yaml:"vendor" json:"vendor"`
	Server    string `yaml:"server" json:"server"`
	Version   string `yaml:"version" json:"version"`
	ConfigDir string `yaml:"configDir" json:"configDir"`
	Required  bool   `yaml:"required,omitempty" json:"required,omitempty"`
}

type fmRoot struct {
	Name        string        `yaml:"name"`
	VendorKey   string        `yaml:"vendorKey"`
	AgentKey    string        `yaml:"agentKey"`
	Version     string        `yaml:"version"`
	Slug        string        `yaml:"slug"`
	Description string        `yaml:"description"`
	Author      string        `yaml:"author"`
	License     string        `yaml:"license"`
	Model       *fmModel      `yaml:"model,omitempty"`
	Skills      []fmSkill     `yaml:"skills,omitempty"`
	Agents      []fmSubAgent  `yaml:"agents,omitempty"`
	MCPServers  []fmMCPServer `yaml:"mcpServers,omitempty"`
}

// buildAgentsMD 生成 AGENTS.md（frontmatter + 提示词正文，所见即所得不改写）。
func buildAgentsMD(fm fmRoot, prompt string) (string, error) {
	fm.VendorKey = platformVendorKey
	fm.Slug = fm.VendorKey + "/" + fm.AgentKey
	if fm.Author == "" {
		fm.Author = "@console"
	}
	if fm.License == "" {
		fm.License = "MIT"
	}
	head, err := yaml.Marshal(fm)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	b.WriteString("---\n")
	b.Write(head)
	b.WriteString("---\n\n")
	b.WriteString(strings.TrimRight(prompt, "\n"))
	b.WriteString("\n")
	return b.String(), nil
}

// mcpConfigYAML 连接器行 → mcp-configs/<name>/config.yaml（M6 §2.3 字段契约）。
// mode=all 且未声明工具 → 不写 tools 段（运行时按未声明自动放行）。
func mcpConfigYAML(name, transport, endpoint string, timeoutSec int, toolScope, permissions string) string {
	if timeoutSec <= 0 {
		timeoutSec = 120
	}
	type block struct {
		Server      string `yaml:"server"`
		Vendor      string `yaml:"vendor"`
		Version     string `yaml:"version"`
		Connection  struct {
			Type    string `yaml:"type"`
			URL     string `yaml:"url"`
			Timeout int    `yaml:"timeout"`
		} `yaml:"connection"`
		Permissions struct {
			Tools map[string]string `yaml:"tools,omitempty"`
		} `yaml:"permissions,omitempty"`
	}
	var b block
	b.Server = name
	b.Vendor = platformVendorKey
	b.Version = "1.0.0"
	b.Connection.Type = transport
	if b.Connection.Type == "" {
		b.Connection.Type = "streamable_http"
	}
	// OAF 运行时传输枚举为 streamableHttp（camel），连接器表单语义 streamable_http 同义转换
	if b.Connection.Type == "streamable_http" {
		b.Connection.Type = "streamableHttp"
	}
	b.Connection.URL = endpoint
	b.Connection.Timeout = timeoutSec
	var perm struct {
		Tools map[string]string `json:"tools"`
	}
	_ = yamlOrJSONUnmarshal(permissions, &perm)
	if len(perm.Tools) > 0 {
		b.Permissions.Tools = perm.Tools
	}
	var scope struct {
		Mode  string   `json:"mode"`
		Tools []string `json:"tools"`
	}
	_ = yamlOrJSONUnmarshal(toolScope, &scope)
	_ = scope // subset 模式由前端选项控制勾选范围，注入仍按 permissions 展开（M6 §2.3 降级口径）
	out, err := yaml.Marshal(b)
	if err != nil {
		return ""
	}
	return string(out)
}

func yamlOrJSONUnmarshal(raw string, v interface{}) error {
	if raw == "" {
		return nil
	}
	if err := yaml.Unmarshal([]byte(raw), v); err == nil {
		return nil
	}
	return jsonUnmarshalStrict(raw, v)
}

// builtinPlatformMCPConfig 内置 platform 连接器 config.yaml（release-agent 同款格式，
// endpoint 指向集群内 backend /mcp）。
func builtinPlatformMCPConfig() string {
	return `server: platform-publisher
vendor: agentmanager
version: "1.0.0"

connection:
  type: streamableHttp
  url: http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/mcp
  timeout: 300

permissions:
  tools:
    list_images: allow
    list_packages: allow
    get_package_detail: allow
    list_services: allow
    get_service_status: allow
    check_oaf_package: allow
    create_oaf_zip: allow
    upload_package: allow
    register_service: allow
    publish_service: ask
    update_service_env: ask
    republish_service: ask
    unpublish_service: ask
    delete_service: ask
`
}

// buildAgentFiles 表单 → 包文件集。
// base 非空时（编辑链路）以基础包文件为底（copy-on-write 沿用 SOUL.md 等补充文件），
// AGENTS.md / mcp-configs/ 按表单重新生成；skills 按勾选重建。
func (c *Core) buildAgentFiles(fm fmRoot, form *AgentForm, base map[string][]byte) (map[string][]byte, error) {
	agentsMD, err := buildAgentsMD(fm, form.Prompt)
	if err != nil {
		return nil, err
	}
	files := map[string][]byte{}
	for k, v := range base {
		files[k] = v
	}
	files["AGENTS.md"] = []byte(agentsMD)

	// skills：source=package 的勾选从被引用包复制目录进生成包（copy-on-write 自包含）；
	// source=l4 不进包（运行时注入，frontmatter 不写）
	// 清掉 base 里旧的 skills 目录（按新勾选重建）
	for p := range files {
		if strings.HasPrefix(p, "skills/") {
			delete(files, p)
		}
	}
	for _, s := range form.Skills {
		if s.Source != "package" || s.PackageID == 0 || s.Name == "" {
			continue
		}
		src, err := c.Packages.Get(s.PackageID)
		if err != nil {
			return nil, fmt.Errorf("%w: skill source package %d not found", ErrInvalidForm, s.PackageID)
		}
		copied, err := c.copySkillDir(src, s.Name)
		if err != nil {
			return nil, err
		}
		for p, content := range copied {
			files[p] = content
		}
	}

	// mcp-configs：连接器行生成（bearer token 不落包，走发布 env → 服务 Secret）
	for p := range files {
		if strings.HasPrefix(p, "mcp-configs/") {
			delete(files, p)
		}
	}
	for _, ref := range form.McpServers {
		if ref.Builtin != builtinPlatformMCPServer && ref.ConnectorID == 0 {
			ref.Builtin = builtinPlatformMCPServer // 无 connectorId 视为内置（前端兼容）
		}
		if ref.Builtin == builtinPlatformMCPServer {
			files["mcp-configs/"+builtinPlatformMCPServer+"/config.yaml"] = []byte(builtinPlatformMCPConfig())
			continue
		}
		conn, err := c.GetMcpConnector(ref.ConnectorID)
		if err != nil {
			return nil, fmt.Errorf("%w: mcp connector %d not found", ErrInvalidForm, ref.ConnectorID)
		}
		files["mcp-configs/"+conn.Name+"/config.yaml"] =
			[]byte(mcpConfigYAML(conn.Name, conn.Transport, conn.Endpoint, conn.TimeoutSec, conn.ToolScope, conn.Permissions))
	}
	return files, nil
}

// copySkillDir 从源包复制 skills/<name>/ 目录（Tree 递归 + ReadFile）。
func (c *Core) copySkillDir(src *store.OafPackage, name string) (map[string][]byte, error) {
	prefix := "skills/" + name + "/"
	tree, err := c.Packages.Tree(src)
	if err != nil {
		return nil, err
	}
	out := map[string][]byte{}
	var walk func(ents []store.FileEntry) error
	walk = func(ents []store.FileEntry) error {
		for _, e := range ents {
			if e.IsDir {
				if err := walk(e.Children); err != nil {
					return err
				}
				continue
			}
			if !strings.HasPrefix(e.Path, prefix) {
				continue
			}
			data, err := c.Packages.ReadFile(src, e.Path)
			if err != nil {
				return err
			}
			out[e.Path] = data
		}
		return nil
	}
	if err := walk(tree); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		// 宽松：声明了但源包无目录 → 只写 frontmatter（warnings 管线会在包校验时提示）
		return out, nil
	}
	return out, nil
}

// deriveFrontmatter 表单 → frontmatter（决议 5 派生规则；编辑链路 name 允许改显示名）。
func (c *Core) deriveFrontmatter(form *AgentForm, agentKey, version string) fmRoot {
	fm := fmRoot{
		Name: form.Name, AgentKey: agentKey, Version: version,
		Description: form.Description,
	}
	if len(form.ModelConnections) > 0 {
		// model 段仅写默认连接 modelId（展示语义，key 永不落包，M3 §2.3）
		for i, ref := range form.ModelConnections {
			if ref.IsDefault || i == 0 {
				if conn, err := c.GetModelConnection(ref.ConnectionID); err == nil {
					fm.Model = &fmModel{Provider: conn.Provider, ModelID: conn.ModelID}
				}
				break
			}
		}
	}
	for _, s := range form.Skills {
		if s.Source == "l4" || s.Name == "" {
			continue
		}
		v := s.Version
		if v == "" {
			v = "1.0.0"
		}
		fm.Skills = append(fm.Skills, fmSkill{Name: s.Name, Source: "local", Version: v})
	}
	for _, sa := range form.SubAgents {
		if sa.Agent == "" {
			continue
		}
		fm.Agents = append(fm.Agents, fmSubAgent{
			Vendor: platformVendorKey, Agent: sa.Agent, Version: "1.0.0",
			Role: sa.Role, Delegations: sa.Delegations, Endpoint: sa.Endpoint,
		})
	}
	for _, ref := range form.McpServers {
		if ref.Builtin == builtinPlatformMCPServer {
			fm.MCPServers = append(fm.MCPServers, fmMCPServer{
				Vendor: platformVendorKey, Server: builtinPlatformMCPServer,
				Version: "1.0.0", ConfigDir: "mcp-configs/" + builtinPlatformMCPServer,
			})
			continue
		}
		if ref.ConnectorID == 0 {
			continue
		}
		if conn, err := c.GetMcpConnector(ref.ConnectorID); err == nil {
			fm.MCPServers = append(fm.MCPServers, fmMCPServer{
				Vendor: platformVendorKey, Server: conn.Name,
				Version: "1.0.0", ConfigDir: "mcp-configs/" + conn.Name,
			})
		}
	}
	return fm
}

// validateModelConnections 勾选连接校验（M3 §2.5）：至少一条、全部 verified、
// isDefault 恰好一个（缺省取首个）。返回（默认连接, 全部勾选连接）。
func (c *Core) validateModelConnections(workspaceID uint, refs []ModelConnRef) (*store.ModelConnection, []*store.ModelConnection, error) {
	if len(refs) == 0 {
		return nil, nil, fmt.Errorf("%w: modelConnections is required", ErrInvalidForm)
	}
	conns := make([]*store.ModelConnection, 0, len(refs))
	for _, ref := range refs {
		conn, err := c.GetModelConnectionInWorkspace(workspaceID, ref.ConnectionID)
		if err != nil {
			return nil, nil, fmt.Errorf("%w: model connection %d not found", ErrInvalidForm, ref.ConnectionID)
		}
		if conn.Status != "verified" {
			return nil, nil, fmt.Errorf("%w: model connection %q is not verified", ErrInvalidForm, conn.Name)
		}
		conns = append(conns, conn)
	}
	def := conns[0]
	marked := 0
	for i, ref := range refs {
		if ref.IsDefault {
			def = conns[i]
			marked++
		}
	}
	if marked > 1 {
		return nil, nil, fmt.Errorf("%w: only one default model connection allowed", ErrInvalidForm)
	}
	return def, conns, nil
}

// buildPublishEnv 默认连接 → LLM_* env + SecretKeys（M3 §2.5）；
// bearer 连接器 token → MCP_TOKEN_<NAME>；集成认证头 → AGENT_REMOTE_HEADERS_JSON。
func (c *Core) buildPublishEnv(workspaceID uint, def *store.ModelConnection, form *AgentForm) (map[string]string, []string, error) {
	env := map[string]string{}
	var secretKeys []string
	// 平台默认配置预填（与发布向导「已预填平台默认配置」同语义，R3）：checkpoint/redis 等
	// 运行必需 env 自动带入；下方模型连接 / MCP token 注入覆盖同名键（表单优先）
	if defaults, err := c.GetPlatformDefaults(); err == nil {
		for k, v := range defaults {
			if v != "" {
				env[k] = v
			}
		}
	}
	if def != nil {
		env["LLM_BASE_URL"] = def.BaseURL
		env["LLM_MODEL"] = def.ModelID
		// 旧键同步覆盖：agent-framework 系统默认模型读 LLM_MODEL_ID（部署实测：平台
		// defaults 里的旧 LLM_MODEL_ID 不覆盖会让系统模型与勾选连接不一致，对话 400）
		env["LLM_MODEL_ID"] = def.ModelID
		if def.APIKey != "" {
			env["LLM_API_KEY"] = def.APIKey
			secretKeys = append(secretKeys, "LLM_API_KEY")
		}
	}
	for _, ref := range form.McpServers {
		if ref.Builtin != "" || ref.ConnectorID == 0 {
			continue
		}
		conn, err := c.GetMcpConnector(ref.ConnectorID)
		if err != nil || conn.AuthType != "bearer" || conn.AuthToken == "" {
			continue
		}
		key := "MCP_TOKEN_" + sanitizeEnvKey(conn.Name)
		env[key] = conn.AuthToken
		secretKeys = append(secretKeys, key)
	}
	// 集成凭证：勾选的远程 SubAgent 对应集成关系的认证头键（M7 §2.3）；值不落表不落包
	if len(form.SubAgents) > 0 {
		integrations, err := c.ListIntegrations(workspaceID)
		if err == nil {
			headers := map[string]string{}
			for _, sa := range form.SubAgents {
				for _, it := range integrations.Relations {
					if it.EndpointURL == sa.Endpoint && it.AuthHeaderKey != "" {
						// 占位值：真实凭证由创建页「集成凭证」输入框提供（AgentForm.RemoteAuthValue）
						if v := form.remoteAuthFor(sa.Endpoint); v != "" {
							headers[it.AuthHeaderKey] = v
						}
					}
				}
			}
			if len(headers) > 0 {
				b, _ := jsonMarshal(headers)
				env["AGENT_REMOTE_HEADERS_JSON"] = string(b)
				secretKeys = append(secretKeys, "AGENT_REMOTE_HEADERS_JSON")
			}
		}
	}
	return env, secretKeys, nil
}

func sanitizeEnvKey(name string) string {
	out := agentKeyInvalidRe.ReplaceAllString(strings.ToLower(name), "_")
	return strings.ToUpper(strings.Trim(out, "_"))
}

// remoteAuthFor 集成凭证取值（AgentForm.RemoteAuth 为 endpoint→value 映射）。
func (f *AgentForm) remoteAuthFor(endpoint string) string {
	if f.RemoteAuth == nil {
		return ""
	}
	return f.RemoteAuth[endpoint]
}

// CreateAgent 创建并发布（M3 §2.2）：生成包 v1 → core.Publish → 下发登记 pending。
// 事务边界：包登记成功 → Publish 失败时包保留（孤儿包无害，可删）。
func (c *Core) CreateAgent(workspaceID, userID uint, form *AgentForm) (*store.ServiceEntity, *store.OafPackage, error) {
	if form.Mode == "" {
		form.Mode = "harness"
	}
	if strings.TrimSpace(form.Name) == "" {
		return nil, nil, fmt.Errorf("%w: name is required", ErrInvalidForm)
	}
	if strings.TrimSpace(form.Prompt) == "" {
		return nil, nil, fmt.Errorf("%w: prompt is required", ErrInvalidForm)
	}
	if form.Replicas < 1 {
		form.Replicas = 1
	}
	def, conns, err := c.validateModelConnections(workspaceID, form.ModelConnections)
	if err != nil {
		return nil, nil, err
	}
	// agentKey 派生（决议 5）：与 k8s short 名同源 slugify；空间内包名唯一校验
	agentKey := SlugifyAgentKey(form.Name)
	var cnt int64
	if err := c.DB.Model(&store.OafPackage{}).Where("slug = ? AND workspace_id = ?",
		platformVendorKey+"/"+agentKey, workspaceID).Count(&cnt).Error; err != nil {
		return nil, nil, err
	}
	if cnt > 0 {
		return nil, nil, fmt.Errorf("%w: duplicate_name (package slug exists in workspace)", ErrConflict)
	}
	fm := c.deriveFrontmatter(form, agentKey, "1.0.0")
	files, err := c.buildAgentFiles(fm, form, nil)
	if err != nil {
		return nil, nil, err
	}
	pkg, err := c.Packages.RegisterGenerated(workspaceID, files)
	if err != nil {
		return nil, nil, err
	}
	env, secretKeys, err := c.buildPublishEnv(workspaceID, def, form)
	if err != nil {
		return nil, nil, err
	}
	image := form.Image
	if image == "" {
		image = c.Cfg.DefaultImage
	}
	svc, err := c.Publish(PublishRequest{
		PackageID: pkg.ID, Name: form.Name, Image: image,
		Env: env, SecretKeys: secretKeys,
		Replicas: int32(form.Replicas), Spec: form.Spec,
		PublicEndpoint: form.PublicEndpoint,
		WorkspaceID:    workspaceID, CreatedBy: userID,
		K8sNameBase: agentKey, // 决议 5：k8s 名与 agentKey 同源（中文名 sanitize 后为兜底名）
	})
	if err != nil {
		return nil, pkg, err // 包保留（孤儿包无害），502 语义由 handler 层包装
	}
	// 下发登记：全部勾选连接 pending（running 后 OnServiceRunning 回调逐条 deliver）
	if err := c.registerDeliveries(conns, svc.ID); err != nil {
		return svc, pkg, nil // 下发登记失败不阻断创建（usage 页可手动触发）
	}
	return svc, pkg, nil
}

// CreateAgentImport 导入模式（M3 §2.6）：公共包 copy-on-write 派生本空间副本（或
// 直接引用本空间已上传包）→ 复用 Publish 发布。form.Name 缺省取包名。
func (c *Core) CreateAgentImport(workspaceID, userID uint, form *AgentForm, packageID uint) (*store.ServiceEntity, *store.OafPackage, error) {
	base, err := c.Packages.Get(packageID)
	if err != nil {
		return nil, nil, ErrNotFound
	}
	if base.WorkspaceID != workspaceID && base.WorkspaceID != 0 {
		return nil, nil, ErrNotFound // 非公共包且非本空间 → 不可见
	}
	pkg := base
	if base.WorkspaceID == 0 {
		// 公共包派生副本（决议 7：导入/派生在本空间包列表内进行，不影响原包）
		files, err := c.Packages.CollectBaseFiles(base)
		if err != nil {
			return nil, nil, err
		}
		// frontmatter version 递增避免同 slug+version 重复提示；slug 保持（空间内唯一即可）
		cfg, err := parseOAFConfig(string(files["AGENTS.md"]))
		if err != nil {
			return nil, nil, err
		}
		files["AGENTS.md"] = []byte(mustRewriteVersion(files["AGENTS.md"], nextVersion(cfg.Version)))
		pkg, err = c.Packages.RegisterGeneratedFrom(workspaceID, base, files, nextVersion(cfg.Version))
		if err != nil {
			return nil, nil, err
		}
	}
	image := form.Image
	if image == "" {
		image = c.Cfg.DefaultImage
	}
	svc, err := c.Publish(PublishRequest{
		PackageID: pkg.ID, Name: orStr(form.Name, pkg.Name), Image: image,
		Replicas: int32(form.Replicas), Spec: form.Spec, PublicEndpoint: form.PublicEndpoint,
		WorkspaceID: workspaceID, CreatedBy: userID,
	})
	if err != nil {
		return nil, pkg, err
	}
	return svc, pkg, nil
}

// mustRewriteVersion 替换 frontmatter version 行（导入派生用，正文不动；失败返回原文）。
func mustRewriteVersion(agentsMD []byte, version string) string {
	out, err := rewriteAgentsMDVersion(agentsMD, version)
	if err != nil {
		return string(agentsMD)
	}
	return out
}

// rewriteAgentsMDVersion 替换 frontmatter version 行（导入派生用，正文不动）。
func rewriteAgentsMDVersion(agentsMD []byte, version string) (string, error) {
	s := string(agentsMD)
	parts := strings.SplitN(s, "---", 3)
	if len(parts) < 3 {
		return s, fmt.Errorf("invalid AGENTS.md")
	}
	re := regexp.MustCompile(`(?m)^version:\s*".*?"\s*$`)
	head := re.ReplaceAllString(parts[1], fmt.Sprintf("version: %q", version))
	return head + "---" + parts[2], nil
}

// EditFormView 编辑页预填体（M3 §2.7）。
type EditFormView struct {
	Name             string         `json:"name"`
	K8sName          string         `json:"k8sName"`
	Description      string         `json:"description"`
	Image            string         `json:"image"`
	ModelConnections []ModelConnRef `json:"modelConnections"`
	Prompt           string         `json:"prompt"`
	Skills           []SkillRef     `json:"skills"`
	SubAgents        []SubAgentRef  `json:"subAgents"`
	McpServers       []McpServerRef `json:"mcpServers"`
	Spec             string         `json:"spec"`
	Replicas         int            `json:"replicas"`
	PublicEndpoint   bool           `json:"publicEndpoint"`
	Version          string         `json:"version"`
	PackageID        uint           `json:"packageId"`
}

// EditForm 组装编辑预填：当前包 AGENTS.md 解析 + services 行 + 下发关联。
func (c *Core) EditForm(workspaceID, serviceID uint) (*EditFormView, error) {
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	pkg, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, err
	}
	agentsMD, err := c.Packages.ReadFile(pkg, "AGENTS.md")
	if err != nil {
		return nil, err
	}
	cfg, err := parseOAFConfig(string(agentsMD))
	if err != nil {
		return nil, err
	}
	view := &EditFormView{
		Name: svc.DisplayName, K8sName: svc.K8sName, Description: cfg.Description,
		Image: svc.Image, Spec: svc.Spec, Replicas: svc.Replicas,
		PublicEndpoint: svc.EndpointPublic(), Version: pkg.Version, PackageID: pkg.ID,
	}
	view.Prompt = cfg.Instructions
	for _, s := range cfg.Skills {
		// frontmatter 声明来自创建链路 source=local（进包）；L4 技能不在包 frontmatter
		view.Skills = append(view.Skills, SkillRef{Name: s.Name, Source: "package", PackageID: pkg.ID, Version: s.Version})
	}
	for _, a := range cfg.Agents {
		view.SubAgents = append(view.SubAgents, SubAgentRef{
			Agent: a.Agent, Role: a.Role, Delegations: a.Delegations, Endpoint: a.Endpoint,
		})
	}
	// mcpServers → 连接器反查（按空间内名称匹配；platform 内置特殊标记）
	for _, m := range cfg.MCPServers {
		if m.Server == builtinPlatformMCPServer || m.Server == "platform-publisher" && m.ConfigDir == "mcp-configs/"+builtinPlatformMCPServer {
			view.McpServers = append(view.McpServers, McpServerRef{Builtin: builtinPlatformMCPServer})
			continue
		}
		var conn store.McpConnector
		if err := c.DB.Where("workspace_id = ? AND name = ?", workspaceID, m.Server).First(&conn).Error; err == nil {
			view.McpServers = append(view.McpServers, McpServerRef{ConnectorID: conn.ID})
		}
	}
	// 模型连接勾选恢复：delivery 行 + env LLM_* 匹配默认
	deliveries, err := c.ListServiceDeliveries(serviceID)
	if err == nil {
		env := map[string]string{}
		_ = jsonUnmarshalStrict(svc.EnvJSON, &env)
		for _, d := range deliveries {
			ref := ModelConnRef{ConnectionID: d.ConnectionID}
			if conn, err := c.GetModelConnection(d.ConnectionID); err == nil {
				if conn.ModelID == env["LLM_MODEL"] && conn.BaseURL == env["LLM_BASE_URL"] {
					ref.IsDefault = true
				}
			}
			view.ModelConnections = append(view.ModelConnections, ref)
		}
		// env 有 LLM_MODEL 但无 delivery 行（存量手配）→ 不渲染连接勾选
	}
	if view.ModelConnections == nil {
		view.ModelConnections = []ModelConnRef{}
	}
	return view, nil
}

// UpdateAgent 编辑保存（M3 §2.7）：生成包新版本（copy-on-write）→ republish →
// 模型连接增量登记/清理。名称锁定：k8s short 名不可变；name 仅允许改显示名。
func (c *Core) UpdateAgent(workspaceID, userID, serviceID uint, form *AgentForm) (*store.ServiceEntity, *store.OafPackage, error) {
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, nil, ErrNotFound
	}
	role, err := c.userWsRole(userID, workspaceID)
	if err != nil {
		return nil, nil, err
	}
	if err := RequireWsRole(role); err != nil {
		return nil, nil, err
	}
	if strings.TrimSpace(form.Prompt) == "" {
		return nil, nil, fmt.Errorf("%w: prompt is required", ErrInvalidForm)
	}
	if form.Replicas < 1 {
		form.Replicas = svc.Replicas
	}
	def, conns, err := c.validateModelConnections(workspaceID, form.ModelConnections)
	if err != nil {
		return nil, nil, err
	}
	base, err := c.Packages.Get(svc.PackageID)
	if err != nil {
		return nil, nil, err
	}
	// copy-on-write：以源包为底重建文件集（frontmatter version 递增）
	agentKey := packageAgentKey(base)
	if agentKey == "" {
		agentKey = SlugifyAgentKey(form.Name)
	}
	fm := c.deriveFrontmatter(form, agentKey, nextVersion(base.Version))
	baseFiles, err := c.Packages.CollectBaseFiles(base)
	if err != nil {
		return nil, nil, err
	}
	files, err := c.buildAgentFiles(fm, form, baseFiles)
	if err != nil {
		return nil, nil, err
	}
	newPkg := base
	changed, err := filesChanged(baseFiles, files)
	if err != nil {
		return nil, nil, err
	}
	if changed {
		newPkg, err = c.Packages.RegisterGeneratedFrom(workspaceID, base, files, fm.Version)
		if err != nil {
			return nil, nil, err
		}
	}
	// 模型链路：默认连接 env 更新（LLM_* 全量覆盖语义）+ 勾选集增量登记
	env, secretKeys, err := c.buildPublishEnv(workspaceID, def, form)
	if err != nil {
		return nil, nil, err
	}
	oldPlain, _, err := c.partitionServiceEnv(svc)
	if err != nil {
		return nil, nil, err
	}
	for k, v := range env { // 仅覆盖表单管的键，其余用户 env sticky
		oldPlain[k] = v
	}
	public := form.PublicEndpoint == nil || *form.PublicEndpoint
	newPkgID := newPkg.ID
	opt := RepublishOptions{
		PackageID: &newPkgID,
		Replicas:  intPtr(form.Replicas), Env: oldPlain, SecretKeys: secretKeys,
		Spec: &form.Spec, PublicEndpoint: &public,
	}
	if form.Image != "" {
		opt.Image = &form.Image // 空 = 沿用现镜像（UpdateEnv/Republish 语义）
	}
	updated, err := c.Republish(serviceID, opt)
	if err != nil {
		return nil, newPkg, err
	}
	// 显示名同步新版本 frontmatter name（k8s 名不可变）
	if form.Name != "" && form.Name != svc.DisplayName {
		if err := c.DB.Model(&store.ServiceEntity{}).Where("id = ?", serviceID).
			Update("display_name", form.Name).Error; err != nil {
			return nil, newPkg, err
		}
		updated.DisplayName = form.Name
	}
	// 勾选集增量：重建 delivery 行（被移除的连接不自动删 Agent 侧 model_config，保守）
	_ = c.syncDeliveries(conns, serviceID)
	if updated.Status == store.StatusRunning {
		go func(id uint) {
			defer func() { _ = recover() }()
			c.DeliverPendingForService(id)
		}(serviceID)
	}
	return updated, newPkg, nil
}

// filesChanged 判定新文件集相对基础包是否有实际变化（无变化则不新建版本）。
func filesChanged(base, next map[string][]byte) (bool, error) {
	if len(base) != len(next) {
		return true, nil
	}
	for p, v := range next {
		old, ok := base[p]
		if !ok || !bytes.Equal(old, v) {
			return true, nil
		}
	}
	return false, nil
}

func intPtr(v int) *int32 { x := int32(v); return &x }
