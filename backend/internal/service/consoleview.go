// 用户侧 console 视图聚合（M2/M4）：概览统计、Agent 列表视图、详情包装、版本列表。
// 数据源 = services 按 workspace 过滤（不新建 Agent 实体，设计 §3.2 决议）。
package service

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

// SummaryView 工作空间概览（M2 §2.3）。
type SummaryView struct {
	Agents struct {
		Total   int64 `json:"total"`
		Running int64 `json:"running"`
		Error   int64 `json:"error"`
		Stopped int64 `json:"stopped"`
	} `json:"agents"`
	RecentEvents  []SummaryEvent `json:"recentEvents"`
	TodaySessions interface{}    `json:"todaySessions"` // 一期无会话聚合，字段预留 null
}

// SummaryEvent 概览时间线条目。
type SummaryEvent struct {
	ID          uint      `json:"id"`
	Reason      string    `json:"reason"`
	ToStatus    string    `json:"toStatus"`
	DisplayName string    `json:"displayName"`
	ServiceID   uint      `json:"serviceId"`
	CreatedAt   time.Time `json:"createdAt"`
}

// ConsoleSummary 概览聚合：状态计数 + 最近事件（service_events 倒序 10 条）。
func (c *Core) ConsoleSummary(workspaceID uint) (*SummaryView, error) {
	var sv SummaryView
	type cnt struct {
		Status string
		N      int64
	}
	var counts []cnt
	if err := c.DB.Model(&store.ServiceEntity{}).
		Select("status, count(*) AS n").
		Where("workspace_id = ?", workspaceID).
		Group("status").Scan(&counts).Error; err != nil {
		return nil, err
	}
	for _, r := range counts {
		sv.Agents.Total += r.N
		switch r.Status {
		case store.StatusRunning:
			sv.Agents.Running = r.N
		case store.StatusStopped:
			sv.Agents.Stopped = r.N
		case store.StatusError, store.StatusDeployFailed, store.StatusRegisterFailed:
			sv.Agents.Error += r.N
		}
	}
	// 最近事件：本空间服务的事件（join 过滤），倒序 10 条
	var events []SummaryEvent
	err := c.DB.Table("service_events e").
		Select("e.id, e.reason, e.to_status, e.created_at, s.display_name, s.id AS service_id").
		Joins("JOIN services s ON s.id = e.service_id").
		Where("s.workspace_id = ?", workspaceID).
		Order("e.created_at DESC").Limit(10).Scan(&events).Error
	if err != nil {
		return nil, err
	}
	sv.RecentEvents = events // 扫描结果回写视图（漏赋值会让时间线恒空，issue #144）
	if sv.RecentEvents == nil {
		sv.RecentEvents = []SummaryEvent{} // 空时间线序列化 []（nil → null）
	}
	return &sv, nil
}

// AgentCardSummary agent_card 摘要（不整包直出）。
type AgentCardSummary struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

// AgentListItem console Agent 列表项（= services 视图增强，M2 §2.3）。
type AgentListItem struct {
	ID          uint             `json:"id"`
	DisplayName string           `json:"displayName"`
	K8sName     string           `json:"k8sName"`
	ShortName   string           `json:"shortName"`
	Status      string           `json:"status"`
	Endpoint    string           `json:"endpoint"`
	Model       string           `json:"model"`
	Version     string           `json:"version"`
	Replicas    int              `json:"replicas"`
	CardSummary AgentCardSummary `json:"cardSummary"`
	UpdatedAt   time.Time        `json:"updatedAt"`
}

// ConsoleAgents 空间内 Agent 列表（status/keyword 过滤；default 空间仅 admin 经中间件可达）。
func (c *Core) ConsoleAgents(workspaceID uint, status, keyword string) ([]AgentListItem, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", workspaceID)
	if status != "" {
		q = q.Where("status = ?", status)
	}
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]AgentListItem, 0, len(rows))
	for _, r := range rows {
		out = append(out, c.agentListItem(&r))
	}
	return out, nil
}

func (c *Core) agentListItem(r *store.ServiceEntity) AgentListItem {
	item := AgentListItem{
		ID: r.ID, DisplayName: r.DisplayName, K8sName: r.K8sName, ShortName: r.ShortName,
		Status: r.Status, Endpoint: r.Endpoint, Replicas: r.Replicas, UpdatedAt: r.UpdatedAt,
	}
	// model：env 注入的 LLM_MODEL（M3 创建链路）；历史行空串
	env := map[string]string{}
	_ = json.Unmarshal([]byte(r.EnvJSON), &env)
	item.Model = env["LLM_MODEL"]
	// version：agent_card 注册版本 → 退回包版本
	item.Version = r.RegisteredVersion
	if item.Version == "" && r.PackageID > 0 {
		if pkg, err := c.Packages.Get(r.PackageID); err == nil {
			item.Version = pkg.Version
			// agent_card 缺失/损坏时摘要降级为包名+描述（不 500）
			if item.CardSummary.Name == "" {
				item.CardSummary.Name = pkg.Name
				item.CardSummary.Description = pkg.Description
			}
		}
	}
	if r.AgentCardJSON != "" && r.AgentCardJSON != "{}" {
		var card struct {
			Name        string `json:"name"`
			Description string `json:"description"`
			Version     string `json:"version"`
		}
		if err := json.Unmarshal([]byte(r.AgentCardJSON), &card); err == nil {
			item.CardSummary = AgentCardSummary{Name: card.Name, Description: card.Description}
			if card.Version != "" {
				item.Version = card.Version
			}
		}
	}
	return item
}

// ConsoleAgentDetail 详情包装：core.GetDetail + workspace 归属校验（非本空间 404）。
func (c *Core) ConsoleAgentDetail(workspaceID, id uint) (*ServiceDetail, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound // 不暴露存在性
	}
	return c.GetDetail(id)
}

// PackageVersionView 版本 Tab 条目。
type PackageVersionView struct {
	store.OafPackage
	Current bool `json:"current"` // 当前运行版本
}

// ConsoleAgentVersions 同 slug 包版本链（含其他空间不可见的过滤由查询保证：本空间包 + 公共包）。
func (c *Core) ConsoleAgentVersions(workspaceID, serviceID uint) ([]PackageVersionView, error) {
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
	var rows []store.OafPackage
	if err := c.DB.Where("slug = ? AND (workspace_id = 0 OR workspace_id = ?)",
		pkg.Slug, workspaceID).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]PackageVersionView, 0, len(rows))
	for _, r := range rows {
		out = append(out, PackageVersionView{OafPackage: r, Current: r.ID == svc.PackageID})
	}
	return out, nil
}

// ConsolePackages 空间可见包列表：公共包（workspace_id=0）+ 本空间包（决议 7）。
func (c *Core) ConsolePackages(workspaceID uint, keyword string) ([]store.OafPackage, error) {
	q := c.DB.Model(&store.OafPackage{}).
		Where("workspace_id = 0 OR workspace_id = ?", workspaceID)
	if keyword != "" {
		q = q.Where("name LIKE ? OR slug LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var out []store.OafPackage
	err := q.Order("id DESC").Find(&out).Error
	return out, err
}

// ConsoleAgentPods 实时 Pod 表（详情 Tab1；5s 轮询由前端驱动）。
func (c *Core) ConsoleAgentPods(workspaceID, id uint) ([]k8s.PodInfo, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	pods, _ := c.K8s.PodStatuses(c.Cfg.Namespace, k8s.LabelKey+"="+svc.K8sName)
	return pods, nil
}

// SpaceSkillItem 工作空间技能清单条目：来自 OAF 包的配置技能（frontmatter skills 声明
// 优先；无声明但包内有 skills/ 目录时按目录条目列出——目录是事实来源，声明仅为意图）。
type SpaceSkillItem struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	// PackageID / PackageSlug / PackageVersion 技能所在包（copy-on-write 编辑入口）
	PackageID      uint   `json:"packageId"`
	PackageSlug    string `json:"packageSlug"`
	PackageVersion string `json:"packageVersion"`
	WorkspaceID    uint   `json:"workspaceId"` // 0=公共包技能
	// Declared 来自包 frontmatter skills 声明（true）或包内 skills/ 目录扫描（false）
	Declared bool `json:"declared"`
	// Issues 目录合规问题（issue #163：与包上传 warnings 同口径；空/缺省 = 无）
	Issues []string `json:"issues"`
	// UsedBy 空间内引用该包为运行包的服务名列表
	UsedBy []string `json:"usedBy"`
}

// SpaceSkills 工作空间技能清单聚合：遍历空间可见包（公共 + 本空间，决议 7），
// 解析 frontmatter skills 声明 + 扫描包内 skills/ 目录（SKILL.md 的 frontmatter
// description 字段取描述），并标注引用服务。数据源全部是 OAF 包配置——不涉运行时分级。
func (c *Core) SpaceSkills(workspaceID uint, keyword string) ([]SpaceSkillItem, error) {
	pkgs, err := c.Packages.List("", "")
	if err != nil {
		return nil, err
	}
	// 引用表：包 id → 使用中的服务名
	usedBy := map[uint][]string{}
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return nil, err
	}
	for _, s := range svcs {
		if s.PackageID > 0 {
			usedBy[s.PackageID] = append(usedBy[s.PackageID], s.DisplayName)
		}
	}
	items := []SpaceSkillItem{}
	seen := map[string]bool{} // 包id+技能名 去重
	for _, p := range pkgs {
		if p.WorkspaceID != 0 && p.WorkspaceID != workspaceID {
			continue
		}
		if keyword != "" && !strings.Contains(p.Name, keyword) && !strings.Contains(p.Slug, keyword) {
			// 包名不匹配也继续——技能名可能匹配
		}
		// 无引用的包 usedBy 为 nil：JSON 序列化成 null，前端直接取 length 崩（issue #138）
		ub := usedBy[p.ID]
		if ub == nil {
			ub = []string{}
		}
		manifest := p.ManifestJSON
		var cfg struct {
			Skills []struct {
				Name        string `json:"name"`
				Description string `json:"description"`
			} `json:"skills"`
		}
		_ = json.Unmarshal([]byte(manifest), &cfg)
		// skills/ 目录合规检查（issue #163）：zip 上传 warnings 同口径，标注在条目上；
		// 同时拿到「声明技能是否有目录」供声明条目标注
		tree, err := c.Packages.Tree(&p)
		if err != nil {
			continue
		}
		var filePaths []string
		var walk func(ents []store.FileEntry)
		walk = func(ents []store.FileEntry) {
			for _, e := range ents {
				if e.IsDir {
					walk(e.Children)
					continue
				}
				filePaths = append(filePaths, e.Path)
			}
		}
		walk(tree)
		issuesBySkill := map[string][]string{}
		for _, it := range checkSkillDirs(func(name string) ([]byte, error) {
			return c.Packages.ReadFile(&p, name)
		}, filePaths) {
			issuesBySkill[it.Name] = it.Issues
		}
		dirExists := map[string]bool{}
		for _, fp := range filePaths {
			if strings.HasPrefix(fp, "skills/") {
				if rest := strings.TrimPrefix(strings.Trim(fp, "/"), "skills/"); rest != "" {
					if name := strings.SplitN(rest, "/", 2)[0]; name != "" && name != "SKILL.md" {
						dirExists[name] = true
					}
				}
			}
		}
		declared := map[string]string{}
		for _, s := range cfg.Skills {
			if s.Name == "" {
				continue
			}
			// 声明结构无 description 字段：回读包内 SKILL.md（issue #164——曾恒为空串，
			// 运行时 /skills 却有完整描述）
			desc := s.Description
			if desc == "" {
				if data, err := c.Packages.ReadFile(&p, "skills/"+s.Name+"/SKILL.md"); err == nil {
					desc = parseSkillDescription(string(data))
				}
			}
			declared[s.Name] = desc
			issues := issuesBySkill[s.Name]
			if !dirExists[s.Name] {
				issues = append(append([]string{}, issues...), "declared but skills/"+s.Name+"/ directory missing")
			}
			items = append(items, SpaceSkillItem{
				Name: s.Name, Description: desc,
				PackageID: p.ID, PackageSlug: p.Slug, PackageVersion: p.Version,
				WorkspaceID: p.WorkspaceID, Declared: true,
				Issues: issues,
				UsedBy: ub,
			})
			seen[fmt.Sprintf("%d/%s", p.ID, s.Name)] = true
		}
		// 目录扫描：skills/*/SKILL.md（未声明也列出——目录是事实来源）
		var scan func(ents []store.FileEntry)
		scan = func(ents []store.FileEntry) {
			for _, e := range ents {
				if e.IsDir {
					if strings.HasPrefix(e.Path, "skills/") && strings.Count(e.Path, "/") == 1 {
						name := strings.TrimPrefix(e.Path, "skills/")
						key := fmt.Sprintf("%d/%s", p.ID, name)
						if !seen[key] {
							desc := ""
							if data, err := c.Packages.ReadFile(&p, e.Path+"/SKILL.md"); err == nil {
								desc = parseSkillDescription(string(data))
							}
							items = append(items, SpaceSkillItem{
								Name: name, Description: desc,
								PackageID: p.ID, PackageSlug: p.Slug, PackageVersion: p.Version,
								WorkspaceID: p.WorkspaceID, Declared: false,
								Issues: issuesBySkill[name],
								UsedBy: ub,
							})
							seen[key] = true
						}
					}
					scan(e.Children)
				}
			}
		}
		scan(tree)
	}
	// keyword 过滤（技能名/描述）
	if keyword != "" {
		filtered := items[:0]
		for _, it := range items {
			if strings.Contains(it.Name, keyword) || strings.Contains(it.Description, keyword) {
				filtered = append(filtered, it)
			}
		}
		items = filtered
	}
	return items, nil
}

// SandboxItem 沙箱配置总览条目（demo c/sandbox，只读）：每 Agent 的沙箱启用状态
// + 生效来源（SandboxRuntime 三层裁决的展示还原）+ 资源规格。
type SandboxItem struct {
	ServiceID   uint   `json:"serviceId"`
	DisplayName string `json:"displayName"`
	K8sName     string `json:"k8sName"`
	Status      string `json:"status"` // services 行状态（运行中/已下线…）
	// Enabled 生效结果（env 显式 > 包声明 > 默认 false）
	Enabled bool `json:"enabled"`
	// Source 生效来源："env" | "package" | "default"
	Source string `json:"source"`
	// PackageDeclared 包 frontmatter config.sandbox.enabled（未声明 nil；展示用）
	PackageDeclared *bool `json:"packageDeclared,omitempty"`
	// EnvExplicit 部署 env SANDBOX_ENABLED 显式设置（值不回传，仅事实）
	EnvExplicit bool `json:"envExplicit"`
	// 资源规格（env 注入值；空 = 运行时默认）
	Image         string `json:"image,omitempty"`
	TimeoutMinutes int   `json:"timeoutMinutes,omitempty"`
	MemoryMB      int    `json:"memoryMb,omitempty"`
	CPUCount      int    `json:"cpuCount,omitempty"`
}

// ConsoleSandbox 空间内 Agent 沙箱配置总览（只读；修改入口在编辑页沙箱区块）。
func (c *Core) ConsoleSandbox(workspaceID uint, keyword string) ([]SandboxItem, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", workspaceID)
	if keyword != "" {
		q = q.Where("display_name LIKE ? OR k8s_name LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	var rows []store.ServiceEntity
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]SandboxItem, 0, len(rows))
	for i := range rows {
		r := &rows[i]
		item := SandboxItem{
			ServiceID: r.ID, DisplayName: r.DisplayName, K8sName: r.K8sName, Status: r.Status,
		}
		env := map[string]string{}
		_ = json.Unmarshal([]byte(r.EnvJSON), &env)
		if v, ok := env["SANDBOX_ENABLED"]; ok {
			item.EnvExplicit = true
			item.Enabled = v == "true"
			item.Source = "env"
		}
		if v, ok := env["SANDBOX_IMAGE"]; ok {
			item.Image = v
		}
		item.TimeoutMinutes = envInt(env["SANDBOX_TIMEOUT_MINUTES"])
		item.MemoryMB = envInt(env["SANDBOX_MEMORY_MB"])
		item.CPUCount = envInt(env["SANDBOX_CPU_COUNT"])
		// 包声明：env 显式设置时仅作展示（不改变生效结果）
		if r.PackageID > 0 {
			if pkg, err := c.Packages.Get(r.PackageID); err == nil {
				if md, err := c.Packages.ReadFile(pkg, "AGENTS.md"); err == nil {
					if cfg, err := parseOAFConfig(string(md)); err == nil && cfg.Config != nil && cfg.Config.Sandbox != nil {
						declared := cfg.Config.Sandbox.Enabled
						item.PackageDeclared = &declared
						if !item.EnvExplicit {
							item.Enabled = declared
							item.Source = "package"
						}
					}
				}
			}
		}
		if !item.EnvExplicit && item.PackageDeclared == nil {
			item.Source = "default" // 三层均未声明：默认关闭
		}
		out = append(out, item)
	}
	return out, nil
}

// envInt env 数值解析（非法/缺省回 0 = 不展示）。
func envInt(v string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(v))
	return n
}

// parseSkillDescription 从 SKILL.md 提取描述：frontmatter description 字段，
// 缺失时取首个非标题正文行。
func parseSkillDescription(content string) string {
	parts := strings.SplitN(content, "---", 3)
	if len(parts) == 3 {
		var fm struct {
			Description string `yaml:"description"`
		}
		if err := yaml.Unmarshal([]byte(parts[1]), &fm); err == nil && fm.Description != "" {
			return fm.Description
		}
	}
	for _, line := range strings.Split(parts[len(parts)-1], "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if len(line) > 120 {
			line = line[:120] + "…"
		}
		return line
	}
	return ""
}

// ---- Skill 中心 · Agent 使用情况（§8.4 第三 Tab；2026-10-19）----
// 数据源 = 每个 Agent 最后生成的 OAF 包（services.package_id 当前指向版本）实时解析：
// 包内 skills/ 目录扫描（事实来源）+ frontmatter skills 声明（意图与版本）。
// 与沙箱总览（ConsoleSandbox）同构：无新表、无后台任务、请求期解析包文件。

// SkillUsageItem Agent 使用情况条目（第三 Tab 列表行）。
type SkillUsageItem struct {
	AgentID    uint   `json:"agentId"`
	AgentName  string `json:"agentName"`
	K8sName    string `json:"k8sName"`
	Status     string `json:"status"` // services 行状态
	// PackageID/PackageVersion 最后生成包（引用快照载体）
	PackageID      uint   `json:"packageId"`
	PackageVersion string `json:"packageVersion"`
	SkillCount     int    `json:"skillCount"`
	Skills         []SkillUsageRef `json:"skills"`
	// PackageGeneratedAt 包登记时间（oaf_packages.created_at）
	PackageGeneratedAt *time.Time `json:"packageGeneratedAt,omitempty"`
}

// SkillUsageRef 包内技能条目（声明/目录扫描合并；角标语义见设计 §8.4）。
type SkillUsageRef struct {
	Name        string `json:"name"`
	// Declared frontmatter skills 声明（true）| 仅目录扫描（false）
	Declared bool `json:"declared"`
	// DeclaredVersion frontmatter skills 声明的版本号（仅声明条目）
	DeclaredVersion string `json:"declaredVersion,omitempty"`
	// MatchedSkillID/MatchedVersion 匹配的空间技能资产与锁定版本（包内自带/无资产 = 0）
	MatchedSkillID   uint   `json:"matchedSkillId,omitempty"`
	MatchedVersion   string `json:"matchedVersion,omitempty"`
	MatchedSource    string `json:"matchedSource,omitempty"` // market | zip | markdown
	MatchedAssetName string `json:"matchedAssetName,omitempty"` // 匹配资产的版本行展示名（资产名）
}

// ConsoleSkillUsages 工作空间内全部 Agent 的技能使用总览（keyword 过滤 Agent 名/技能名）。
func (c *Core) ConsoleSkillUsages(workspaceID uint, keyword string) ([]SkillUsageItem, error) {
	// keyword 只做内存过滤（同时命中 Agent 名与技能名，SQL LIKE 会把技能名命中先行排除）
	var rows []store.ServiceEntity
	if err := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", workspaceID).
		Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	// 空间技能资产一次载入（按名匹配）
	assets := map[string]store.Skill{}
	var skills []store.Skill
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&skills).Error; err != nil {
		return nil, err
	}
	for _, s := range skills {
		assets[s.Name] = s
	}
	// 资产最新 published 版本（latest 语义）
	latestPub := map[uint]store.SkillVersion{}
	if len(skills) > 0 {
		var vers []store.SkillVersion
		if err := c.DB.Where("skill_id IN ? AND status = ?", skillIDs(skills), SkillVerPublished).
			Order("id ASC").Find(&vers).Error; err != nil {
			return nil, err
		}
		for _, v := range vers {
			latestPub[v.SkillID] = v // id ASC：后写覆盖 = 最新
		}
	}
	out := make([]SkillUsageItem, 0, len(rows))
	for i := range rows {
		r := &rows[i]
		item := SkillUsageItem{AgentID: r.ID, AgentName: r.DisplayName, K8sName: r.K8sName, Status: r.Status}
		if r.PackageID > 0 {
			if pkg, err := c.Packages.Get(r.PackageID); err == nil {
				item.PackageID = pkg.ID
				item.PackageVersion = pkg.Version
				t := pkg.CreatedAt
				item.PackageGeneratedAt = &t
				item.Skills = c.parsePackageSkills(workspaceID, pkg, assets, latestPub)
				item.SkillCount = len(item.Skills)
			}
		}
		if item.Skills == nil {
			item.Skills = []SkillUsageRef{}
		}
		if keyword != "" && !strings.Contains(r.DisplayName, keyword) && !strings.Contains(r.K8sName, keyword) {
			// Agent 名不匹配时看技能名是否命中
			hit := false
			for _, s := range item.Skills {
				if strings.Contains(s.Name, keyword) {
					hit = true
					break
				}
			}
			if !hit {
				continue
			}
		}
		out = append(out, item)
	}
	return out, nil
}

// parsePackageSkills 解析单包技能清单：目录扫描（事实来源）∪ frontmatter 声明（意图/版本），
// 同名合并（声明优先带版本；仅目录 = Declared=false）；匹配技能资产回填锁定版本。
func (c *Core) parsePackageSkills(workspaceID uint, pkg *store.OafPackage, assets map[string]store.Skill, latestPub map[uint]store.SkillVersion) []SkillUsageRef {
	var cfg struct {
		Skills []struct {
			Name    string `json:"name"`
			Version string `json:"version"`
		} `json:"skills"`
	}
	_ = json.Unmarshal([]byte(pkg.ManifestJSON), &cfg)
	declared := map[string]string{}
	for _, s := range cfg.Skills {
		if s.Name != "" {
			declared[s.Name] = s.Version
		}
	}
	// 目录扫描（skills/ 一级目录，目录是事实来源——未声明也列出）
	tree, err := c.Packages.Tree(pkg)
	if err != nil {
		return nil
	}
	dirNames := []string{}
	var walk func(ents []store.FileEntry)
	walk = func(ents []store.FileEntry) {
		for _, e := range ents {
			if e.IsDir {
				if strings.HasPrefix(e.Path, "skills/") && strings.Count(strings.Trim(e.Path, "/"), "/") == 1 {
					n := strings.TrimPrefix(strings.Trim(e.Path, "/"), "skills/")
					if n != "" && n != "SKILL.md" {
						dirNames = append(dirNames, n)
					}
				}
				walk(e.Children)
			}
		}
	}
	walk(tree)
	sort.Strings(dirNames)
	// 合并：声明条目 ∪ 目录条目（同名去重）
	merged := map[string]bool{}
	refs := []SkillUsageRef{}
	for _, s := range cfg.Skills {
		if s.Name == "" || merged[s.Name] {
			continue
		}
		merged[s.Name] = true
		refs = append(refs, c.matchUsageRef(workspaceID, s.Name, true, s.Version, assets, latestPub))
	}
	for _, n := range dirNames {
		if merged[n] {
			continue
		}
		merged[n] = true
		refs = append(refs, c.matchUsageRef(workspaceID, n, false, "", assets, latestPub))
	}
	return refs
}

// matchUsageRef 单条目组装 + 技能资产匹配（锁定版本 = 声明版本优先，否则资产最新发布版）。
func (c *Core) matchUsageRef(_ uint, name string, declared bool, declaredVersion string, assets map[string]store.Skill, latestPub map[uint]store.SkillVersion) SkillUsageRef {
	ref := SkillUsageRef{Name: name, Declared: declared, DeclaredVersion: declaredVersion}
	if a, ok := assets[name]; ok {
		ref.MatchedSkillID = a.ID
		ref.MatchedSource = a.Source
		ref.MatchedAssetName = a.DisplayName
		if declaredVersion != "" {
			// 锁定版本以声明为准；资产存在即回填资产名（版本无法反查版本行 id——快照语义）
			ref.MatchedVersion = declaredVersion
		} else if lv, ok := latestPub[a.ID]; ok {
			ref.MatchedVersion = lv.Version
		}
	}
	return ref
}

// ConsoleSkillUsageDetail 单 Agent 详情（skill-usages/:agentId）：当前包技能完整清单
//（含 SKILL.md 描述与合规 issues，与 SpaceSkills 同口径）+ 包版本链信息。
type SkillUsageDetail struct {
	Agent SkillUsageItem `json:"agent"`
	// Items 完整清单（比列表行多 description/issues）
	Items []SkillUsageDetailItem `json:"items"`
}

// SkillUsageDetailItem 详情条目。
type SkillUsageDetailItem struct {
	SkillUsageRef
	// PackagePath 包内目录
	PackagePath string   `json:"packagePath"`
	Description string   `json:"description"`
	Issues      []string `json:"issues"`
}

// ConsoleSkillUsageDetail 组装单 Agent 技能使用详情。
func (c *Core) ConsoleSkillUsageDetail(workspaceID, agentID uint) (*SkillUsageDetail, error) {
	var svc store.ServiceEntity
	if err := c.DB.Where("id = ? AND workspace_id = ?", agentID, workspaceID).First(&svc).Error; err != nil {
		return nil, ErrNotFound
	}
	assets := map[string]store.Skill{}
	var skills []store.Skill
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&skills).Error; err != nil {
		return nil, err
	}
	for _, s := range skills {
		assets[s.Name] = s
	}
	latestPub := map[uint]store.SkillVersion{}
	if len(skills) > 0 {
		var vers []store.SkillVersion
		if err := c.DB.Where("skill_id IN ? AND status = ?", skillIDs(skills), SkillVerPublished).
			Order("id ASC").Find(&vers).Error; err != nil {
			return nil, err
		}
		for _, v := range vers {
			latestPub[v.SkillID] = v
		}
	}
	detail := &SkillUsageDetail{}
	item := SkillUsageItem{AgentID: svc.ID, AgentName: svc.DisplayName, K8sName: svc.K8sName, Status: svc.Status}
	if svc.PackageID > 0 {
		pkg, err := c.Packages.Get(svc.PackageID)
		if err != nil {
			return nil, err
		}
		item.PackageID = pkg.ID
		item.PackageVersion = pkg.Version
		t := pkg.CreatedAt
		item.PackageGeneratedAt = &t
		refs := c.parsePackageSkills(workspaceID, pkg, assets, latestPub)
		item.Skills = refs
		item.SkillCount = len(refs)
		// 详情条目：回读 SKILL.md 描述 + 合规 issues（SpaceSkills 同口径 checkSkillDirs）
		tree, err := c.Packages.Tree(pkg)
		if err == nil {
			var filePaths []string
			var walk func(ents []store.FileEntry)
			walk = func(ents []store.FileEntry) {
				for _, e := range ents {
					if e.IsDir {
						walk(e.Children)
						continue
					}
					filePaths = append(filePaths, e.Path)
				}
			}
			walk(tree)
			issuesBySkill := map[string][]string{}
			for _, it := range checkSkillDirs(func(name string) ([]byte, error) {
				return c.Packages.ReadFile(pkg, name)
			}, filePaths) {
				issuesBySkill[it.Name] = it.Issues
			}
			for _, ref := range refs {
				di := SkillUsageDetailItem{SkillUsageRef: ref, PackagePath: "skills/" + ref.Name + "/"}
				if data, err := c.Packages.ReadFile(pkg, "skills/"+ref.Name+"/SKILL.md"); err == nil {
					di.Description = parseSkillDescription(string(data))
				} else if a, ok := assets[ref.Name]; ok && a.Description != "" {
					// 声明了但包内目录缺失（仅声明）：回落技能资产描述（版本快照缺目录属不合规，提示在 issues）
					di.Description = a.Description
				}
				di.Issues = issuesBySkill[ref.Name]
				if di.Issues == nil {
					di.Issues = []string{}
				}
				detail.Items = append(detail.Items, di)
			}
		}
	}
	if item.Skills == nil {
		item.Skills = []SkillUsageRef{}
	}
	detail.Agent = item
	if detail.Items == nil {
		detail.Items = []SkillUsageDetailItem{}
	}
	return detail, nil
}
