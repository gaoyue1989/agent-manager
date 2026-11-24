// OpenViking 记忆供给集成（openviking-memory-integration-design-261116）：
// 发布链路自动建/删 account、服务 env/Secret 注入、per-user key 签发与包内知识导入。
// 身份模型为三凭证分层：root key 仅控制面（本文件全部调用）、ADMIN key 与 user seed
// 经服务 Secret 下发业务面、per-user user key 由业务面本地推导（免存储表）。
package service

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strings"
	"time"

	"agent-manager/backend/internal/store"
)

// 服务 env 键（平台注入/消费）。OPENVIKING_URL / OPENVIKING_ACCOUNT / OVK_ADMIN_KEY /
// OVK_USER_SEED 为平台保留键（用户不可设置，防止把记忆指向外部服务器）；其余 OVK_*
// 为用户可设的服务级能力开关。
const (
	AgentMemoryProviderEnv = "AGENT_MEMORY_PROVIDER" // local | openviking
	OpenVikingURLEnv       = "OPENVIKING_URL"
	OpenVikingAccountEnv   = "OPENVIKING_ACCOUNT"
	OvkAdminKeyEnv         = "OVK_ADMIN_KEY"
	OvkUserSeedEnv         = "OVK_USER_SEED"
	OvkSessionCommitEnv    = "OVK_SESSION_COMMIT"     // true = 会话归档+自动提取（与逐 turn remember 互斥）
	OvkKnowledgeImportEnv  = "OVK_KNOWLEDGE_IMPORT"   // true = 发布时导入包 knowledge/ → viking://resources/
	OvkSharedSkillsEnv     = "OVK_SHARED_SKILLS"      // true = 发布时导入包 skills/ → viking://agent/skills/
	OvkMCPSharedCtxEnv     = "OVK_MCP_SHARED_CONTEXT" // true = 注入 openviking MCP server（服务级共享检索工具）

	vikingAdminUser = "svc-system" // account 首个 admin（兼任服务身份，承载 MCP 连接与共享 resources 写入）
)

// MemoryProviderOpenViking / MemoryProviderLocal AGENT_MEMORY_PROVIDER 合法取值。
const (
	MemoryProviderOpenViking = "openviking"
	MemoryProviderLocal      = "local"
)

// platformInjectedEnvKeys 平台注入类保留键（applyOpenVikingEnv 派生）。写路径在
// ValidateEnv 之前从用户 env 中剥离：UpdateEnv 全量覆盖语义会把 CM 现值原样回传，
// 保留键校验会把正常回显拒绝成死锁（真机验证发现——provider=openviking 的服务从此
// 无法更新任何 env）；剥离后值由 applyOpenVikingEnv 按当前 provider 重建，注入面不
// 开放（用户显式设置的恶意值同样被静默忽略而非报错，键属平台管理面）。
var platformInjectedEnvKeys = []string{OpenVikingURLEnv, OpenVikingAccountEnv, OvkAdminKeyEnv, OvkUserSeedEnv}

// dropPlatformInjectedEnv 就地剥离平台注入键（env 与 secretKeys 双侧，写路径共用）。
func dropPlatformInjectedEnv(env map[string]string, secretKeys []string) (map[string]string, []string) {
	for _, k := range platformInjectedEnvKeys {
		delete(env, k)
	}
	out := secretKeys[:0]
	for _, k := range secretKeys {
		skip := false
		for _, p := range platformInjectedEnvKeys {
			if k == p {
				skip = true
				break
			}
		}
		if !skip {
			out = append(out, k)
		}
	}
	return env, out
}

// VikingAdmin OpenViking Admin API 客户端（root key 身份）。nil = 平台未启用 OpenViking。
type VikingAdmin struct {
	baseURL string
	rootKey string
	http    *http.Client
}

// NewVikingAdmin 组装 Admin API 客户端；总开关关闭（enabled=false）或 baseURL/rootKey
// 任一为空返回 nil（平台未启用 OpenViking，发布链路零接触，issue #414：总开关是
// 紧急回退/灰度手段，仅配置 URL/key 不构成启用）。
func NewVikingAdmin(enabled bool, baseURL, rootKey string) *VikingAdmin {
	if !enabled || baseURL == "" || rootKey == "" {
		return nil
	}
	return &VikingAdmin{
		baseURL: baseURL,
		rootKey: rootKey,
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

// vikingAPIResponse OpenViking 统一响应壳（PoC 实测：{"status":"ok","result":...}）。
type vikingAPIResponse struct {
	Status string          `json:"status"`
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func (v *VikingAdmin) call(method, apiPath string, body any) (*vikingAPIResponse, error) {
	var payload io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		payload = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, v.baseURL+apiPath, payload)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", v.rootKey)
	resp, err := v.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	out := &vikingAPIResponse{}
	if err := json.Unmarshal(raw, out); err != nil {
		return nil, fmt.Errorf("openviking %s %s: HTTP %d non-JSON: %.200s", method, apiPath, resp.StatusCode, raw)
	}
	if resp.StatusCode >= 400 || out.Status == "error" {
		msg := http.StatusText(resp.StatusCode)
		if out.Error != nil {
			msg = out.Error.Code + ": " + out.Error.Message
		}
		return out, fmt.Errorf("openviking %s %s: %s", method, apiPath, msg)
	}
	return out, nil
}

// vikingConflict 判断 call 错误是否为「已存在」语义（建户/开户幂等的判据）。
// OpenViking 各端点冲突码不统一（CONFLICT / ALREADY_EXISTS / *_EXISTS），按子串匹配。
func vikingConflict(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "CONFLICT") || strings.Contains(msg, "ALREADY_EXISTS") ||
		strings.Contains(msg, "EXISTS")
}

// vikingResultKey 从建户/开户响应提取明文 user_key。
func vikingResultKey(out *vikingAPIResponse) (string, error) {
	var res struct {
		UserKey string `json:"user_key"`
	}
	if err := json.Unmarshal(out.Result, &res); err != nil || res.UserKey == "" {
		return "", fmt.Errorf("openviking response missing user_key")
	}
	return res.UserKey, nil
}

// EnsureAccount 幂等创建 account 并返回 svc-system 的明文 ADMIN key：建户响应自带
// 首个 admin 的 user_key；account 已存在（Secret 丢失后的重供给）时经 root key 轮换
// 该 admin 的 key（同端点幂等，旧 key 即时失效）。created 区分两种结果：新建（调用方
// 失败路径须回滚删除）与轮换已有 account（持有者在用，绝不删除——issue #430）。
func (v *VikingAdmin) EnsureAccount(accountID string) (adminKey string, created bool, err error) {
	out, err := v.call(http.MethodPost, "/api/v1/admin/accounts",
		map[string]string{"account_id": accountID, "admin_user_id": vikingAdminUser})
	if err == nil {
		key, err := vikingResultKey(out)
		return key, true, err
	}
	if vikingConflict(err) {
		regen, err := v.call(http.MethodPost,
			fmt.Sprintf("/api/v1/admin/accounts/%s/users/%s/key", accountID, vikingAdminUser),
			map[string]string{})
		if err != nil {
			return "", false, err
		}
		key, err := vikingResultKey(regen)
		return key, false, err
	}
	return "", false, err
}

// DeleteAccount 删除 account（服务删除链路调用；调用方 best-effort，不阻断服务删除）。
func (v *VikingAdmin) DeleteAccount(accountID string) error {
	_, err := v.call(http.MethodDelete, "/api/v1/admin/accounts/"+accountID, nil)
	return err
}

// RegisterUser 以 root key 注册（或确认已存在）account 内用户，带 seed 确定性派生。
// 已存在视为成功：key 由 DeriveVikingUserKey 本地推导，无需读取服务端返回。
func (v *VikingAdmin) RegisterUser(accountID, userID, seed string) error {
	_, err := v.call(http.MethodPost, fmt.Sprintf("/api/v1/admin/accounts/%s/users", accountID),
		map[string]string{"user_id": userID, "role": "user", "seed": seed})
	if err == nil || vikingConflict(err) {
		return nil
	}
	return err
}

// DeriveVikingUserKey 本地确定性推导 per-user user key（OpenViking 三段式：
// b64url(account).b64url(user).b64url(hex(sha256(user + "\0" + seed)))）。
// PoC 261116 实测与服务端返回逐字节一致；Go/Java 双端以同一测试向量对齐
// （viking_test.go / VikingMemoryClientTest）。
func DeriveVikingUserKey(accountID, userID, seed string) string {
	seg := func(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
	sum := sha256.Sum256([]byte(userID + "\x00" + seed))
	return seg([]byte(accountID)) + "." + seg([]byte(userID)) + "." + seg([]byte(hex.EncodeToString(sum[:])))
}

// VikingUserSeed 生成随机 user seed（32 字节 hex）。
func VikingUserSeed() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand 失败极罕见；回退时间熵仍优于空 seed
		return hex.EncodeToString([]byte(time.Now().Format("20060102150405.000000000")))
	}
	return hex.EncodeToString(b)
}

// VikingAccountID 服务 → OpenViking account 派生：k8s 名字符集（[a-z0-9-]，前缀 oaf-）
// 天然满足 account_id 规则（字母数字_.-、不以 _ 开头），原样复用保持与隔离键派生同源。
func VikingAccountID(k8sName string) string {
	return k8sName
}

// ---- 发布链路 env 注入 ----

// applyOpenVikingEnv 就地派生 OpenViking env（Publish/UpdateEnv/Republish 共用，幂等）：
//   - 显式 local：无条件退出并清掉全部派生键（plain 与 secret 双侧；总开关关闭同样
//     可用——存量服务须能干净退回 local，issue #477）。
//   - 平台未启用：无显式 provider → 零接触（issue #414）；显式 openviking → 凭据齐备
//     （ADMIN key + seed + URL）的存量已建户服务维持现状、零外呼（紧急回退不得锁死
//     存量服务的扩缩容/升级，issue #477），否则属新建供给、无法建户，400 fail-fast
//     （不留半配置服务）。
//   - 生效 provider=openviking（用户显式或平台默认）：注入 URL/account（plain）、
//     OVK_ADMIN_KEY/OVK_USER_SEED（secret，sticky：两者齐备即跳过建户，保证 key 稳定、
//     轮换只发生在显式删除 Secret 重发布）；OVK_KNOWLEDGE_IMPORT / OVK_SHARED_SKILLS
//     为 true 时同步导入包内 knowledge//skills/。
//
// 与 applyIsolationEnv 同语义：用户显式 AGENT_MEMORY_PROVIDER=local 即退出（无任何注入）。
func (c *Core) applyOpenVikingEnv(k8sName string, pkg *store.OafPackage, plain, secret map[string]string) error {
	explicit := plain[AgentMemoryProviderEnv]
	if explicit == MemoryProviderLocal {
		// 显式退出：清掉全部派生键（plain 与 secret 双侧；secret 残留曾让签发入口凭
		// 旧凭据继续签发无对应供给的 userKey——issue #430）
		delete(plain, OpenVikingURLEnv)
		delete(plain, OpenVikingAccountEnv)
		delete(secret, OvkAdminKeyEnv)
		delete(secret, OvkUserSeedEnv)
		return nil
	}
	provider := explicit
	if provider == "" {
		if c.Viking == nil {
			return nil // 平台未启用且用户未要求 → 零接触（issue #414）
		}
		provider = MemoryProviderOpenViking // 平台启用即默认接管（显式 local 可退出）
	}
	if provider != MemoryProviderOpenViking {
		return fmt.Errorf("%w: %s must be %q or %q, got %q",
			ErrInvalidForm, AgentMemoryProviderEnv, MemoryProviderLocal, MemoryProviderOpenViking, provider)
	}
	if c.Viking == nil {
		if secret[OvkAdminKeyEnv] != "" && secret[OvkUserSeedEnv] != "" && plain[OpenVikingURLEnv] != "" {
			return nil
		}
		return fmt.Errorf("%w: %s=%s requires OPENVIKING_ENABLED/OPENVIKING_BASE_URL/OPENVIKING_ROOT_API_KEY on platform",
			ErrInvalidForm, AgentMemoryProviderEnv, MemoryProviderOpenViking)
	}

	account := VikingAccountID(k8sName)
	created := false
	if secret[OvkAdminKeyEnv] == "" || secret[OvkUserSeedEnv] == "" {
		adminKey, isNew, err := c.Viking.EnsureAccount(account)
		if err != nil {
			return fmt.Errorf("openviking provision account %s: %w", account, err)
		}
		created = isNew
		seed := secret[OvkUserSeedEnv]
		if seed == "" {
			seed = VikingUserSeed()
		}
		secret[OvkAdminKeyEnv] = adminKey
		secret[OvkUserSeedEnv] = seed
	}
	plain[AgentMemoryProviderEnv] = MemoryProviderOpenViking
	plain[OpenVikingURLEnv] = c.Viking.baseURL
	plain[OpenVikingAccountEnv] = account
	if plain[OvkKnowledgeImportEnv] == "true" && pkg != nil {
		if err := c.importPackageDirWith(secret, pkg, "knowledge", "resources/"+pkg.Slug); err != nil {
			c.rollbackProvision(account, created)
			return fmt.Errorf("openviking knowledge import: %w", err)
		}
	}
	if plain[OvkSharedSkillsEnv] == "true" && pkg != nil {
		if err := c.importPackageDirWith(secret, pkg, "skills", "agent/skills/"+pkg.Slug); err != nil {
			c.rollbackProvision(account, created)
			return fmt.Errorf("openviking skills import: %w", err)
		}
	}
	return nil
}

// rollbackProvision 供给失败回滚（issue #430）：仅删除本次新建的 account（best-effort，
// 失败仅告警）；CONFLICT 轮换分支（account 已存在、持有者在用）绝不删除。
func (c *Core) rollbackProvision(account string, created bool) {
	if !created {
		return
	}
	if err := c.Viking.DeleteAccount(account); err != nil {
		log.Printf("[openviking] rollback account %s after failed provision: %v", account, err)
	}
}

// vikingUserIDRe OpenViking user_id 合法字符集（官方口径：字母/数字/_/./@/-，非空且
// 不为 "."/".."）；平台 userId（自增/雪花数字、X-User-Id 头透传值）天然满足。
var vikingUserIDRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.@-]*$`)

// IssueMemoryKey 为服务下的平台用户签发个人 OpenViking user key（root key 注册 +
// seed 本地推导，明文一次性返回；同 (account,user,seed) 重复签发得到相同 key，
// 轮换 seed 即作废全部旧 key）。用于 OpenViking Studio / 外部 MCP 凭证访问场景。
func (c *Core) IssueMemoryKey(serviceID uint, userID string) (string, error) {
	if !vikingUserIDRe.MatchString(userID) {
		return "", fmt.Errorf("%w: illegal user id %q (must match OpenViking user_id rules)", ErrInvalidForm, userID)
	}
	key, _, err := c.issueMemoryKeyFor(serviceID, userID)
	return key, err
}

// ConsoleIssueMemoryKey 用户侧自助签发（console 前端「记忆」Tab）：userId 固定为当前
// 登录用户的业务面身份 `u-{uid}`——与数据面 X-User-Id 注入同源，签发即本人记忆空间
// 的 OpenViking Studio 访问凭证（服务端隔离保证只能看自己的记忆，无法签发他人）。
// 返回 key + Studio 地址（服务 env 中的 OPENVIKING_URL）。
func (c *Core) ConsoleIssueMemoryKey(workspaceID, serviceID, uid uint) (map[string]string, error) {
	if _, err := c.ConsoleAgentDetail(workspaceID, serviceID); err != nil {
		return nil, err // 归属校验（不暴露存在性）
	}
	key, ovURL, err := c.issueMemoryKeyFor(serviceID, fmt.Sprintf("u-%d", uid))
	if err != nil {
		return nil, err
	}
	return map[string]string{"userKey": key, "openVikingUrl": ovURL, "userId": fmt.Sprintf("u-%d", uid)}, nil
}

// issueMemoryKeyFor 签发共用实现：返回 (user key, OPENVIKING_URL)。
func (c *Core) issueMemoryKeyFor(serviceID uint, userID string) (string, string, error) {
	if c.Viking == nil {
		return "", "", fmt.Errorf("%w: OpenViking not enabled on platform", ErrInvalidForm)
	}
	svc, err := c.Get(serviceID)
	if err != nil {
		return "", "", err
	}
	var secret, plain map[string]string
	if err := json.Unmarshal([]byte(svc.EnvSecretJSON), &secret); err != nil {
		return "", "", fmt.Errorf("corrupt env secret json: %w", err)
	}
	_ = json.Unmarshal([]byte(svc.EnvJSON), &plain)
	// provider 事实校验（issue #430）：凭据残留不等同供给有效，切 local 的服务拒绝签发
	if plain[AgentMemoryProviderEnv] != MemoryProviderOpenViking {
		return "", "", fmt.Errorf("%w: service %d is not on OpenViking memory supply",
			ErrInvalidForm, serviceID)
	}
	seed := secret[OvkUserSeedEnv]
	if seed == "" || secret[OvkAdminKeyEnv] == "" {
		return "", "", fmt.Errorf("%w: service %d has no OpenViking provisioning (publish with %s=%s first)",
			ErrInvalidForm, serviceID, AgentMemoryProviderEnv, MemoryProviderOpenViking)
	}
	account := VikingAccountID(svc.K8sName)
	if err := c.Viking.RegisterUser(account, userID, seed); err != nil {
		return "", "", err
	}
	return DeriveVikingUserKey(account, userID, seed), plain[OpenVikingURLEnv], nil
}

// ---- 记忆供给聚合总览（与 ConsoleSandbox 平级，用户侧/运维侧共用）----

// MemorySupplyItem 记忆供给总览条目：每 Agent 的供给模式 + 能力开关 + 接入地址。
type MemorySupplyItem struct {
	ServiceID   uint   `json:"serviceId"`
	DisplayName string `json:"displayName"`
	K8sName     string `json:"k8sName"`
	Status      string `json:"status"`
	// Provider 生效供给："openviking" | "local"（env 缺省 = local）
	Provider string `json:"provider"`
	// SessionCommit / KnowledgeImport / SharedSkills / MCPSharedContext 能力开关（env 事实）
	SessionCommit   bool `json:"sessionCommit"`
	KnowledgeImport bool `json:"knowledgeImport"`
	SharedSkills    bool `json:"sharedSkills"`
	MCPSharedCtx    bool `json:"mcpSharedCtx"`
	// Provisioned 是否已完成 OpenViking 供给（Secret 含 ADMIN key + seed）
	Provisioned bool `json:"provisioned"`
	// OpenVikingURL 数据面地址（provider=openviking 时非空；供前端拼 Studio 指引）
	OpenVikingURL string `json:"openVikingUrl,omitempty"`
}

func memorySupplyFromRow(r *store.ServiceEntity) MemorySupplyItem {
	env := map[string]string{}
	_ = json.Unmarshal([]byte(r.EnvJSON), &env)
	secret := map[string]string{}
	_ = json.Unmarshal([]byte(r.EnvSecretJSON), &secret)
	provider := env[AgentMemoryProviderEnv]
	if provider == "" {
		provider = MemoryProviderLocal
	}
	return MemorySupplyItem{
		ServiceID: r.ID, DisplayName: r.DisplayName, K8sName: r.K8sName, Status: r.Status,
		Provider:        provider,
		SessionCommit:   env[OvkSessionCommitEnv] == "true",
		KnowledgeImport: env[OvkKnowledgeImportEnv] == "true",
		SharedSkills:    env[OvkSharedSkillsEnv] == "true",
		MCPSharedCtx:    env[OvkMCPSharedCtxEnv] == "true",
		Provisioned:     secret[OvkAdminKeyEnv] != "" && secret[OvkUserSeedEnv] != "",
		OpenVikingURL:   env[OpenVikingURLEnv],
	}
}

// ConsoleMemorySupply 空间内 Agent 记忆供给总览（只读；切换入口在服务 env 编辑）。
func (c *Core) ConsoleMemorySupply(workspaceID uint, keyword string) ([]MemorySupplyItem, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", workspaceID)
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]MemorySupplyItem, 0, len(rows))
	for i := range rows {
		out = append(out, memorySupplyFromRow(&rows[i]))
	}
	return out, nil
}

// AdminMemorySupply 全平台 Agent 记忆供给总览（运维治理页；只读）。
func (c *Core) AdminMemorySupply(keyword string) ([]MemorySupplyItem, error) {
	q := c.DB.Model(&store.ServiceEntity{})
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]MemorySupplyItem, 0, len(rows))
	for i := range rows {
		out = append(out, memorySupplyFromRow(&rows[i]))
	}
	return out, nil
}

// ---- 记忆浏览只读代理（用户侧资产页；backend 以推导 key 访问外部 OpenViking）----

// ConsoleMemoryBrowse 只读代理当前登录人的 OpenViking 记忆空间：
//   - action=list → GET /api/v1/fs/ls（目录列表）
//   - action=read → GET /api/v1/content/read（文件内容，限 .md/.txt 文本）
//
// 身份：userId = u-{uid}（与数据面 X-User-Id 同源），key 本地推导；uri 双向归一到
// viking://~/ 本人空间单一口径（issue #374）：入参接受 ~/ 契约口径与自有 user/{uid}
// 展开形态（外部服务原生形态），出参 list 条目统一改写为 ~/ 形态（read 纯文本透传）。
// 归一在白名单之前，最终闸门仍是 viking://~/ 前缀；穿越段（../、%2e%2e）在闸门
// 之前拒绝、绝不代理上游（issue #420）；他人 user/{uid} 形态一律 ErrInvalidForm
// 拒绝、绝不代理给上游。外部服务不可用/失败原样返回错误
// （fail-soft 由前端呈现空态）。
func (c *Core) ConsoleMemoryBrowse(workspaceID, serviceID, uid uint, action, uri string) (json.RawMessage, error) {
	if _, err := c.ConsoleAgentDetail(workspaceID, serviceID); err != nil {
		return nil, err
	}
	if action != "list" && action != "read" {
		return nil, fmt.Errorf("%w: unsupported browse action %q", ErrInvalidForm, action)
	}
	ownID := fmt.Sprintf("u-%d", uid)
	ownUserPrefix := "viking://user/" + ownID + "/"
	uri, err := vikingNormalizeBrowseInput(uri, ownUserPrefix)
	if err != nil {
		return nil, err
	}
	if err := vikingRejectTraversalSegments(uri); err != nil {
		return nil, err
	}
	const prefix = "viking://~/"
	if !strings.HasPrefix(uri, prefix) {
		return nil, fmt.Errorf("%w: uri must start with %s (own space only)", ErrInvalidForm, prefix)
	}
	ovURL, key, err := c.memoryBrowseClient(workspaceID, serviceID, ownID)
	if err != nil {
		return nil, err
	}
	apiPath := "/api/v1/fs/ls?uri=" + url.QueryEscape(uri)
	if action == "read" {
		apiPath = "/api/v1/content/read?uri=" + url.QueryEscape(uri)
	}
	req, err := http.NewRequest(http.MethodGet, ovURL+apiPath, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-API-Key", key)
	resp, err := VikingHTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("openviking browse: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("openviking %s: HTTP %d: %.200s", action, resp.StatusCode, raw)
	}
	if action == "list" {
		raw = vikingNormalizeListOutput(raw, ownUserPrefix)
	}
	return json.RawMessage(raw), nil
}

// vikingNormalizeBrowseInput 入参 URI 归一（issue #374）：在 ~/ 契约口径外同时接受
// 本人展开形态 viking://user/u-{uid}/…，仅当 {uid} 段与登录人身份精确相等时改写为
// ~/ 形态；他人展开形态 ErrInvalidForm 拒绝、绝不代理给上游；其余形态原样返回交
// 白名单闸门（viking://~/）拒绝。
func vikingNormalizeBrowseInput(uri, ownUserPrefix string) (string, error) {
	if !strings.HasPrefix(uri, "viking://user/") {
		return uri, nil
	}
	if rest, ok := strings.CutPrefix(uri, ownUserPrefix); ok {
		return "viking://~/" + rest, nil
	}
	return "", fmt.Errorf("%w: uri %q is not own space, expected prefix %s",
		ErrInvalidForm, uri, strings.TrimSuffix(ownUserPrefix, "/"))
}

// vikingRejectTraversalSegments 拒绝路径穿越段（issue #420）：任一路径段为 `..` 或
// 其百分号编码形态（%2e%2e，大小写不敏感）即 ErrInvalidForm。~/ 前缀闸门只验首段
// 形态，`viking://~/../u-9999/…`（含自有 user/{uid}/../ 改写产物）会带着穿越段通过
// 并被代理上游——仅本人空间闸门可被越权；段级精确匹配不误伤文件名中的点（a..b）。
func vikingRejectTraversalSegments(uri string) error {
	rest, _ := strings.CutPrefix(uri, "viking://~/")
	for _, seg := range strings.Split(rest, "/") {
		if seg == ".." || strings.EqualFold(seg, "%2e%2e") {
			return fmt.Errorf("%w: uri %q contains path traversal segment %q", ErrInvalidForm, uri, seg)
		}
	}
	return nil
}

// vikingNormalizeListOutput 出参条目 URI 归一（仅 action=list，issue #374）：OpenViking
// 返回条目 uri 为本人展开形态 viking://user/u-{uid}/…，改写前缀为前端契约口径
// viking://~/…；仅改写 own-uid 前缀（他人 URI 不动，防伪造归属），其余字段与未知
// 字段原样保留（UseNumber 保数字精度）；非预期 envelope 形状原样透传。
func vikingNormalizeListOutput(raw json.RawMessage, ownUserPrefix string) json.RawMessage {
	var envelope map[string]json.RawMessage
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return raw
	}
	var entries []map[string]any
	dec := json.NewDecoder(bytes.NewReader(envelope["result"]))
	dec.UseNumber()
	if err := dec.Decode(&entries); err != nil {
		return raw
	}
	changed := false
	for _, e := range entries {
		u, _ := e["uri"].(string)
		if rest, ok := strings.CutPrefix(u, ownUserPrefix); ok {
			e["uri"] = "viking://~/" + rest
			changed = true
		}
	}
	if !changed {
		return raw
	}
	res, err := json.Marshal(entries)
	if err != nil {
		return raw
	}
	envelope["result"] = res
	out, err := json.Marshal(envelope)
	if err != nil {
		return raw
	}
	return out
}

// memoryBrowseClient 校验供给并返回 (数据面地址, 本人推导 key)。总开关关闭即拒绝
// （对齐 issueMemoryKeyFor 门控，issue #477：紧急回退须止血，不得以 DB 残留凭据外呼）。
func (c *Core) memoryBrowseClient(workspaceID, serviceID uint, userID string) (string, string, error) {
	if c.Viking == nil {
		return "", "", fmt.Errorf("%w: OpenViking not enabled on platform", ErrInvalidForm)
	}
	var secret, plain map[string]string
	var rec store.ServiceEntity
	if err := c.DB.First(&rec, serviceID).Error; err != nil {
		return "", "", ErrNotFound
	}
	_ = json.Unmarshal([]byte(rec.EnvSecretJSON), &secret)
	_ = json.Unmarshal([]byte(rec.EnvJSON), &plain)
	if plain[AgentMemoryProviderEnv] != MemoryProviderOpenViking || secret[OvkUserSeedEnv] == "" {
		return "", "", fmt.Errorf("%w: service %d is not on OpenViking memory supply", ErrInvalidForm, serviceID)
	}
	_ = workspaceID // 归属已由调用方 ConsoleAgentDetail 校验
	key := DeriveVikingUserKey(VikingAccountID(rec.K8sName), userID, secret[OvkUserSeedEnv])
	if plain[OpenVikingURLEnv] == "" {
		return "", "", fmt.Errorf("%w: service %d missing %s", ErrInvalidForm, serviceID, OpenVikingURLEnv)
	}
	return plain[OpenVikingURLEnv], key, nil
}

// VikingHTTP 数据面只读代理共用 HTTP 客户端（browse；低频管理面调用，默认超时即可）。
var VikingHTTP = &http.Client{Timeout: 20 * time.Second}

// ---- 包内知识/技能导入（OVK_KNOWLEDGE_IMPORT / OVK_SHARED_SKILLS，发布期一次性）----

// vikingWritableExt OpenViking content/write 新建文件接受的文本扩展（官方 API 口径）。
var vikingWritableExt = map[string]bool{
	".md": true, ".txt": true, ".json": true, ".yaml": true, ".yml": true,
	".toml": true, ".py": true, ".js": true, ".ts": true,
}

// importPackageDirWith 把包内 sub 目录（knowledge|skills）下的可写文本文件导入 account
// 共享空间：knowledge → viking://resources/{slug}/…，skills → viking://agent/skills/{slug}/…
// （resources 与 agent/skills 均为 account 内共享段，身份由 key 决定，URI 不含 account）。
// 非文本扩展跳过（二进制走 OpenViking temp_upload 流程，属二期导出链路），不阻断发布。
// secret 由调用方（applyOpenVikingEnv）持有——ADMIN key 注入完成后才进入本函数。
func (c *Core) importPackageDirWith(secret map[string]string, pkg *store.OafPackage, sub, targetBase string) error {
	data, err := newVikingDataClient(c.Viking.baseURL, secret[OvkAdminKeyEnv])
	if err != nil {
		return err
	}
	var walk func(entries []store.FileEntry) error
	walk = func(entries []store.FileEntry) error {
		for _, e := range entries {
			if e.IsDir {
				if err := walk(e.Children); err != nil {
					return err
				}
				continue
			}
			rel, ok := strings.CutPrefix(e.Path, sub+"/")
			if !ok || !vikingWritableExt[path.Ext(e.Path)] {
				continue
			}
			content, err := c.Packages.ReadFile(pkg, e.Path)
			if err != nil {
				return fmt.Errorf("read %s: %w", e.Path, err)
			}
			if err := data.writeText("viking://"+targetBase+"/"+rel, string(content)); err != nil {
				return err
			}
		}
		return nil
	}
	entries, err := c.Packages.Tree(pkg)
	if err != nil {
		return fmt.Errorf("package tree: %w", err)
	}
	if err := walk(entries); err != nil {
		return err
	}
	log.Printf("[openviking] package %s: %s/ imported to viking://%s/", pkg.Slug, sub, targetBase)
	return nil
}

// vikingDataClient 数据面调用（ADMIN key = 服务身份，非 root key）。
type vikingDataClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func newVikingDataClient(baseURL, apiKey string) (*vikingDataClient, error) {
	if baseURL == "" || apiKey == "" {
		return nil, fmt.Errorf("missing %s (provision first)", OvkAdminKeyEnv)
	}
	return &vikingDataClient{baseURL: baseURL, apiKey: apiKey, http: &http.Client{Timeout: 60 * time.Second}}, nil
}

// writeText 写入文本文件（覆盖语义，异步向量化 wait=false；语义/向量刷新失败不回滚
// 内容，重试同一写即重提交派生维护——OpenViking 官方口径）。
func (d *vikingDataClient) writeText(uri, content string) error {
	body, _ := json.Marshal(map[string]any{"uri": uri, "content": content, "mode": "replace"})
	req, err := http.NewRequest(http.MethodPost, d.baseURL+"/api/v1/content/write", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", d.apiKey)
	resp, err := d.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		return fmt.Errorf("write %s: HTTP %d: %.200s", uri, resp.StatusCode, raw)
	}
	return nil
}
