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

// VikingAdmin OpenViking Admin API 客户端（root key 身份）。nil = 平台未启用 OpenViking。
type VikingAdmin struct {
	baseURL string
	rootKey string
	http    *http.Client
}

// NewVikingAdmin 组装 Admin API 客户端；baseURL/rootKey 任一为空返回 nil（平台未配置）。
func NewVikingAdmin(baseURL, rootKey string) *VikingAdmin {
	if baseURL == "" || rootKey == "" {
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
// 首个 admin 的 user_key；account 已存在（并发发布/重试）时经 root key 轮换该 admin
// 的 key（同端点幂等，旧 key 即时失效——仅出现在建户竞态，常态发布一次即成）。
func (v *VikingAdmin) EnsureAccount(accountID string) (string, error) {
	out, err := v.call(http.MethodPost, "/api/v1/admin/accounts",
		map[string]string{"account_id": accountID, "admin_user_id": vikingAdminUser})
	if err == nil {
		return vikingResultKey(out)
	}
	if vikingConflict(err) {
		regen, err := v.call(http.MethodPost,
			fmt.Sprintf("/api/v1/admin/accounts/%s/users/%s/key", accountID, vikingAdminUser),
			map[string]string{})
		if err != nil {
			return "", err
		}
		return vikingResultKey(regen)
	}
	return "", err
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
//   - 平台未启用：用户显式 openviking → 400（fail-fast，不留半配置服务）；否则零接触。
//   - 生效 provider=openviking（用户显式或平台默认）：注入 URL/account（plain）、
//     OVK_ADMIN_KEY/OVK_USER_SEED（secret，sticky：两者齐备即跳过建户，保证 key 稳定、
//     轮换只发生在显式删除 Secret 重发布）；OVK_KNOWLEDGE_IMPORT / OVK_SHARED_SKILLS
//     为 true 时同步导入包内 knowledge//skills/。
//
// 与 applyIsolationEnv 同语义：用户显式 AGENT_MEMORY_PROVIDER=local 即退出（无任何注入）。
func (c *Core) applyOpenVikingEnv(k8sName string, pkg *store.OafPackage, plain, secret map[string]string) error {
	explicit := plain[AgentMemoryProviderEnv]
	if c.Viking == nil {
		if explicit == MemoryProviderOpenViking {
			return fmt.Errorf("%w: %s=%s requires OPENVIKING_ENABLED/OPENVIKING_BASE_URL/OPENVIKING_ROOT_API_KEY on platform",
				ErrInvalidForm, AgentMemoryProviderEnv, MemoryProviderOpenViking)
		}
		return nil
	}
	provider := explicit
	if provider == "" {
		provider = MemoryProviderOpenViking // 平台启用即默认接管（显式 local 可退出）
	}
	switch provider {
	case MemoryProviderLocal:
		return nil
	case MemoryProviderOpenViking:
	default:
		return fmt.Errorf("%w: %s must be %q or %q, got %q",
			ErrInvalidForm, AgentMemoryProviderEnv, MemoryProviderLocal, MemoryProviderOpenViking, provider)
	}

	account := VikingAccountID(k8sName)
	if secret[OvkAdminKeyEnv] == "" || secret[OvkUserSeedEnv] == "" {
		adminKey, err := c.Viking.EnsureAccount(account)
		if err != nil {
			return fmt.Errorf("openviking provision account %s: %w", account, err)
		}
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
			return fmt.Errorf("openviking knowledge import: %w", err)
		}
	}
	if plain[OvkSharedSkillsEnv] == "true" && pkg != nil {
		if err := c.importPackageDirWith(secret, pkg, "skills", "agent/skills/"+pkg.Slug); err != nil {
			return fmt.Errorf("openviking skills import: %w", err)
		}
	}
	return nil
}

// vikingUserIDRe OpenViking user_id 合法字符集（官方口径：字母/数字/_/./@/-，非空且
// 不为 "."/".."）；平台 userId（自增/雪花数字、X-User-Id 头透传值）天然满足。
var vikingUserIDRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.@-]*$`)

// IssueMemoryKey 为服务下的平台用户签发个人 OpenViking user key（root key 注册 +
// seed 本地推导，明文一次性返回；同 (account,user,seed) 重复签发得到相同 key，
// 轮换 seed 即作废全部旧 key）。用于 OpenViking Studio / 外部 MCP 凭证访问场景。
func (c *Core) IssueMemoryKey(serviceID uint, userID string) (string, error) {
	if c.Viking == nil {
		return "", fmt.Errorf("%w: OpenViking not enabled on platform", ErrInvalidForm)
	}
	if !vikingUserIDRe.MatchString(userID) || userID == "." || userID == ".." {
		return "", fmt.Errorf("%w: illegal user id %q (must match OpenViking user_id rules)", ErrInvalidForm, userID)
	}
	svc, err := c.Get(serviceID)
	if err != nil {
		return "", err
	}
	var secret map[string]string
	if err := json.Unmarshal([]byte(svc.EnvSecretJSON), &secret); err != nil {
		return "", fmt.Errorf("corrupt env secret json: %w", err)
	}
	seed := secret[OvkUserSeedEnv]
	if seed == "" || secret[OvkAdminKeyEnv] == "" {
		return "", fmt.Errorf("%w: service %d has no OpenViking provisioning (publish with %s=%s first)",
			ErrInvalidForm, serviceID, AgentMemoryProviderEnv, MemoryProviderOpenViking)
	}
	account := VikingAccountID(svc.K8sName)
	if err := c.Viking.RegisterUser(account, userID, seed); err != nil {
		return "", err
	}
	return DeriveVikingUserKey(account, userID, seed), nil
}

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
