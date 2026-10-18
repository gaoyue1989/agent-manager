// 运维侧治理领域逻辑（M1）：工作空间 CRUD / 用户管理 / 成员关系 / 纳管 / 审计聚合。
package service

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

var slugRe = regexp.MustCompile(`^ws-[0-9a-f]{8}$`)

// WorkspaceView 运维侧工作空间列表/详情视图（聚合 memberCount/agentCount）。
type WorkspaceView struct {
	store.Workspace
	Owner       *UserView `json:"owner,omitempty"`
	MemberCount int64     `json:"memberCount"`
	AgentCount  int64     `json:"agentCount"`
	Quota       map[string]interface{} `json:"quota"`
}

func (c *Core) quotaView(raw string) map[string]interface{} {
	out := map[string]interface{}{}
	if raw != "" {
		_ = json.Unmarshal([]byte(raw), &out)
	}
	return out
}

// AdminListWorkspaces 工作空间列表（含聚合计数）。
func (c *Core) AdminListWorkspaces() ([]WorkspaceView, error) {
	var rows []store.Workspace
	if err := c.DB.Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	type cnt struct {
		WorkspaceID uint
		N           int64
	}
	var mc, ac []cnt
	if err := c.DB.Model(&store.WorkspaceMember{}).
		Select("workspace_id, count(*) AS n").Group("workspace_id").
		Scan(&mc).Error; err != nil {
		return nil, err
	}
	if err := c.DB.Model(&store.ServiceEntity{}).
		Select("workspace_id, count(*) AS n").Group("workspace_id").
		Scan(&ac).Error; err != nil {
		return nil, err
	}
	mmap, amap := map[uint]int64{}, map[uint]int64{}
	for _, r := range mc {
		mmap[r.WorkspaceID] = r.N
	}
	for _, r := range ac {
		amap[r.WorkspaceID] = r.N
	}
	out := make([]WorkspaceView, 0, len(rows))
	for _, w := range rows {
		v := WorkspaceView{Workspace: w, MemberCount: mmap[w.ID], AgentCount: amap[w.ID],
			Quota: c.quotaView(w.QuotaJSON)}
		if w.OwnerUserID > 0 {
			if u, err := c.GetUser(w.OwnerUserID); err == nil {
				uv := userView(u)
				v.Owner = &uv
			}
		}
		out = append(out, v)
	}
	return out, nil
}

// AdminGetWorkspace 单个空间视图。
func (c *Core) AdminGetWorkspace(id uint) (*WorkspaceView, error) {
	var w store.Workspace
	if err := c.DB.First(&w, id).Error; err != nil {
		return nil, ErrNotFound
	}
	list, err := c.AdminListWorkspaces()
	if err != nil {
		return nil, err
	}
	for i := range list {
		if list[i].ID == id {
			return &list[i], nil
		}
	}
	return nil, ErrNotFound
}

// CreateWorkspaceInput 创建入参（slug 自动生成 ws-{8hex}，冲突重试）。
type CreateWorkspaceInput struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	OwnerUserID uint   `json:"ownerUserId"`
	Quota       map[string]interface{} `json:"quota"`
}

// AdminCreateWorkspace 创建空间并写入 owner 成员关系（同事务）。
func (c *Core) AdminCreateWorkspace(actor uint, in CreateWorkspaceInput) (*store.Workspace, error) {
	if strings.TrimSpace(in.Name) == "" {
		return nil, fmt.Errorf("%w: name is required", ErrInvalidForm)
	}
	// workspaces.name 列 varchar(128)（issue #149：超长输入曾以 500 Data too long 直出）
	if utf8.RuneCountInString(in.Name) > 128 {
		return nil, fmt.Errorf("%w: name too long (max 128)", ErrInvalidForm)
	}
	if in.OwnerUserID > 0 {
		if _, err := c.GetUser(in.OwnerUserID); err != nil {
			return nil, fmt.Errorf("%w: owner user not found", ErrInvalidForm)
		}
	}
	w := &store.Workspace{Name: in.Name, Description: in.Description,
		OwnerUserID: in.OwnerUserID, Status: "active"}
	if in.Quota != nil {
		b, _ := json.Marshal(in.Quota)
		w.QuotaJSON = string(b)
	} else {
		w.QuotaJSON = "{}"
	}
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		// slug 冲突重试 3 次（随机 8 hex，碰撞概率极低）
		var lastErr error
		for i := 0; i < 3; i++ {
			w.Slug = store.NewSlug()
			lastErr = tx.Create(w).Error
			if lastErr == nil {
				break
			}
		}
		if lastErr != nil {
			return lastErr
		}
		if !slugRe.MatchString(w.Slug) {
			return fmt.Errorf("generated slug %q invalid", w.Slug)
		}
		if in.OwnerUserID > 0 {
			return tx.Create(&store.WorkspaceMember{WorkspaceID: w.ID,
				UserID: in.OwnerUserID, Role: "owner"}).Error
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	c.recordAudit("workspace.create", w.Slug, actor, map[string]interface{}{
		"name": w.Name, "ownerUserId": in.OwnerUserID,
	})
	return w, nil
}

// PatchWorkspaceInput 更新入参（nil 字段=不变）。OwnerUserID 非 nil 即 owner 转移
// （issue #157：原实现静默忽略该字段，owner 空间既不能转移也不能删，形成删除死锁）。
type PatchWorkspaceInput struct {
	Name        *string                `json:"name"`
	Description *string                `json:"description"`
	Status      *string                `json:"status"`
	OwnerUserID *uint                  `json:"ownerUserId"`
	Quota       map[string]interface{} `json:"quota"`
}

// AdminPatchWorkspace 停用/改配额/转移 owner：仅控制台层数据，不影响已运行业务面（原则一）。
func (c *Core) AdminPatchWorkspace(id uint, in PatchWorkspaceInput) (*store.Workspace, error) {
	var w store.Workspace
	if err := c.DB.First(&w, id).Error; err != nil {
		return nil, ErrNotFound
	}
	updates := map[string]interface{}{}
	if in.Name != nil && *in.Name != "" {
		updates["name"] = *in.Name
	}
	if in.Description != nil {
		updates["description"] = *in.Description
	}
	if in.Status != nil {
		if *in.Status != "active" && *in.Status != "disabled" {
			return nil, fmt.Errorf("%w: invalid status", ErrInvalidForm)
		}
		updates["status"] = *in.Status
	}
	if in.Quota != nil {
		b, _ := json.Marshal(in.Quota)
		updates["quota_json"] = string(b)
	}
	// owner 转移（issue #157）：workspace.owner_user_id 与 members 角色同事务演进——
	// 新 owner 建行/提为 owner，原 owner 降为 member（之后可按普通成员移除）
	transferOwner := in.OwnerUserID != nil && *in.OwnerUserID != w.OwnerUserID
	oldOwner := w.OwnerUserID
	if transferOwner {
		newOwner := *in.OwnerUserID
		if newOwner == 0 {
			return nil, fmt.Errorf("%w: ownerUserId must be a valid user id", ErrInvalidForm)
		}
		if _, err := c.GetUser(newOwner); err != nil {
			return nil, fmt.Errorf("%w: owner user not found", ErrInvalidForm)
		}
	}
	if len(updates) == 0 && !transferOwner {
		return &w, nil
	}
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		if len(updates) > 0 {
			if err := tx.Model(&w).Updates(updates).Error; err != nil {
				return err
			}
		}
		if transferOwner {
			newOwner := *in.OwnerUserID
			if err := tx.Model(&w).Update("owner_user_id", newOwner).Error; err != nil {
				return err
			}
			// 原 owner 成员行降级为 member（无成员行的空间仅头指针变化）。
			// 注意用事务前捕获的 oldOwner：上方 Update 会把 w.OwnerUserID 回写为新值
			if err := tx.Model(&store.WorkspaceMember{}).
				Where("workspace_id = ? AND user_id = ? AND role = ?", id, oldOwner, "owner").
				Update("role", "member").Error; err != nil {
				return err
			}
			// 新 owner：已有成员行提为 owner，否则补建
			res := tx.Model(&store.WorkspaceMember{}).
				Where("workspace_id = ? AND user_id = ?", id, newOwner).
				Update("role", "owner")
			if res.Error != nil {
				return res.Error
			}
			if res.RowsAffected == 0 {
				if err := tx.Create(&store.WorkspaceMember{WorkspaceID: id,
					UserID: newOwner, Role: "owner"}).Error; err != nil {
					return err
				}
			}
			w.OwnerUserID = newOwner
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if transferOwner {
		c.recordAudit("workspace.transfer_owner", w.Slug, w.OwnerUserID, map[string]interface{}{
			"newOwnerUserId": *in.OwnerUserID, "oldOwnerUserId": oldOwner,
		})
	}
	return &w, nil
}

// AdminDeleteWorkspace 删除空间：无 Agent 且成员仅剩 owner（或无成员）可删；
// 仅剩 owner 时级联清理成员关系（issue #157：owner 保护 + 删除保护曾构成删除死锁）。
func (c *Core) AdminDeleteWorkspace(id uint) error {
	var w store.Workspace
	if err := c.DB.First(&w, id).Error; err != nil {
		return ErrNotFound
	}
	if strings.EqualFold(w.Slug, "default") {
		return fmt.Errorf("%w: default workspace is reserved", ErrConflict)
	}
	var members, agents int64
	if err := c.DB.Model(&store.WorkspaceMember{}).Where("workspace_id = ?", id).Count(&members).Error; err != nil {
		return err
	}
	if err := c.DB.Model(&store.ServiceEntity{}).Where("workspace_id = ?", id).Count(&agents).Error; err != nil {
		return err
	}
	if agents > 0 || members > 1 {
		return fmt.Errorf("%w: workspace_not_empty", ErrConflict)
	}
	return c.DB.Transaction(func(tx *gorm.DB) error {
		// 级联清掉仅剩的 owner 成员行（members==1）；0 成员时无行可删
		if members > 0 {
			if err := tx.Where("workspace_id = ?", id).Delete(&store.WorkspaceMember{}).Error; err != nil {
				return err
			}
		}
		return tx.Delete(&w).Error
	})
}

// ---- 用户管理 ----

// AdminCreateUser 建号（密码 bcrypt；username 冲突 400）。
func (c *Core) AdminCreateUser(actor uint, username, password, displayName, role string) (*store.User, error) {
	if strings.TrimSpace(username) == "" || strings.TrimSpace(password) == "" {
		return nil, fmt.Errorf("%w: username and password are required", ErrInvalidForm)
	}
	// users.username 列 varchar(64)（issue #149：超长输入曾以 500 Data too long 直出）
	if utf8.RuneCountInString(username) > 64 {
		return nil, fmt.Errorf("%w: username too long (max 64)", ErrInvalidForm)
	}
	if role != "admin" && role != "user" {
		role = "user"
	}
	// 先查重：MySQL driver 未开 TranslateError 时 duplicate key 不翻译为
	// gorm.ErrDuplicatedKey，靠 errors.Is 拦不住（部署实测 500）——显式查重兜主路径
	var cnt int64
	if err := c.DB.Model(&store.User{}).Where("username = ?", username).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: duplicate_username", ErrDuplicateName)
	}
	hash, err := store.HashPassword(password)
	if err != nil {
		return nil, err
	}
	u := &store.User{Username: username, PasswordHash: hash,
		DisplayName: displayName, Role: role, Status: "active"}
	if err := c.DB.Create(u).Error; err != nil {
		// 并发窗口兜底：唯一索引冲突按重名语义返回
		if strings.Contains(strings.ToLower(err.Error()), "duplicate") ||
			strings.Contains(err.Error(), "1062") {
			return nil, fmt.Errorf("%w: duplicate_username", ErrDuplicateName)
		}
		return nil, err
	}
	c.recordAudit("user.create", username, actor, map[string]interface{}{"role": role})
	return u, nil
}

// AdminPatchUser 角色/状态/重置密码。
func (c *Core) AdminPatchUser(id uint, role, status, password *string) (*store.User, error) {
	u, err := c.GetUser(id)
	if err != nil {
		return nil, err
	}
	updates := map[string]interface{}{}
	if role != nil {
		if *role != "admin" && *role != "user" {
			return nil, fmt.Errorf("%w: invalid role", ErrInvalidForm)
		}
		updates["role"] = *role
	}
	if status != nil {
		if *status != "active" && *status != "disabled" {
			return nil, fmt.Errorf("%w: invalid status", ErrInvalidForm)
		}
		updates["status"] = *status
	}
	if password != nil && *password != "" {
		hash, err := store.HashPassword(*password)
		if err != nil {
			return nil, err
		}
		updates["password_hash"] = hash
	}
	if len(updates) > 0 {
		if err := c.DB.Model(u).Updates(updates).Error; err != nil {
			return nil, err
		}
	}
	return u, nil
}

// AdminListUsers 用户列表（附所属空间）。
func (c *Core) AdminListUsers() ([]UserView, error) {
	var rows []store.User
	if err := c.DB.Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	type rel struct {
		UserID      uint
		WorkspaceID uint
		Name        string
		Role        string
	}
	var rels []rel
	if err := c.DB.Table("workspace_members wm").
		Select("wm.user_id, wm.workspace_id, w.name, wm.role").
		Joins("JOIN workspaces w ON w.id = wm.workspace_id").
		Scan(&rels).Error; err != nil {
		return nil, err
	}
	byUser := map[uint][]WorkspaceBrief{}
	for _, r := range rels {
		byUser[r.UserID] = append(byUser[r.UserID],
			WorkspaceBrief{ID: r.WorkspaceID, Name: r.Name, Role: r.Role})
	}
	out := make([]UserView, 0, len(rows))
	for i := range rows {
		v := userView(&rows[i])
		v.Workspaces = byUser[rows[i].ID]
		out = append(out, v)
	}
	return out, nil
}

// ---- 成员关系 ----

// AddMember 加入成员（停用空间拒绝新成员加入）。
func (c *Core) AddMember(actor, workspaceID, userID uint, role string) (*store.WorkspaceMember, error) {
	if role != "owner" && role != "admin" && role != "member" {
		return nil, fmt.Errorf("%w: invalid role", ErrInvalidForm)
	}
	var ws store.Workspace
	if err := c.DB.First(&ws, workspaceID).Error; err != nil {
		return nil, ErrNotFound
	}
	if ws.Status != "active" {
		return nil, fmt.Errorf("%w: workspace disabled", ErrConflict)
	}
	if _, err := c.GetUser(userID); err != nil {
		return nil, fmt.Errorf("%w: user not found", ErrInvalidForm)
	}
	// 先查重：MySQL driver 未开 TranslateError，唯一索引冲突会裸 500（部署实测）
	var dup int64
	if err := c.DB.Model(&store.WorkspaceMember{}).
		Where("workspace_id = ? AND user_id = ?", workspaceID, userID).Count(&dup).Error; err != nil {
		return nil, err
	}
	if dup > 0 {
		return nil, fmt.Errorf("%w: already a member", ErrConflict)
	}
	m := &store.WorkspaceMember{WorkspaceID: workspaceID, UserID: userID, Role: role}
	if err := c.DB.Create(m).Error; err != nil {
		if strings.Contains(err.Error(), "1062") || strings.Contains(strings.ToLower(err.Error()), "duplicate") {
			return nil, fmt.Errorf("%w: already a member", ErrConflict)
		}
		return nil, err
	}
	c.recordAudit("member.update", fmt.Sprintf("ws-%d/user-%d", workspaceID, userID), actor,
		map[string]interface{}{"op": "add", "role": role})
	return m, nil
}

// UpdateMemberRole 改角色（owner 不可降级为 member——空间必须有 owner）。
func (c *Core) UpdateMemberRole(actor, workspaceID, userID uint, role string) error {
	if role != "owner" && role != "admin" && role != "member" {
		return fmt.Errorf("%w: invalid role", ErrInvalidForm)
	}
	var m store.WorkspaceMember
	if err := c.DB.Where("workspace_id = ? AND user_id = ?", workspaceID, userID).
		First(&m).Error; err != nil {
		return ErrNotFound
	}
	if m.Role == "owner" && role != "owner" {
		return fmt.Errorf("%w: workspace owner cannot be demoted", ErrConflict)
	}
	if err := c.DB.Model(&m).Update("role", role).Error; err != nil {
		return err
	}
	c.recordAudit("member.update", fmt.Sprintf("ws-%d/user-%d", workspaceID, userID), actor,
		map[string]interface{}{"op": "role", "role": role})
	return nil
}

// RemoveMember 移除成员（owner 不可移除）。
func (c *Core) RemoveMember(actor, workspaceID, userID uint) error {
	var m store.WorkspaceMember
	if err := c.DB.Where("workspace_id = ? AND user_id = ?", workspaceID, userID).
		First(&m).Error; err != nil {
		return ErrNotFound
	}
	if m.Role == "owner" {
		return fmt.Errorf("%w: workspace owner cannot be removed", ErrConflict)
	}
	if err := c.DB.Delete(&m).Error; err != nil {
		return err
	}
	c.recordAudit("member.update", fmt.Sprintf("ws-%d/user-%d", workspaceID, userID), actor,
		map[string]interface{}{"op": "remove"})
	return nil
}

// ListMembers 空间成员视图。
type MemberView struct {
	store.WorkspaceMember
	User *UserView `json:"user,omitempty"`
}

func (c *Core) ListMembers(workspaceID uint) ([]MemberView, error) {
	var rows []store.WorkspaceMember
	if err := c.DB.Where("workspace_id = ?", workspaceID).
		Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]MemberView, 0, len(rows))
	for _, r := range rows {
		v := MemberView{WorkspaceMember: r}
		if u, err := c.GetUser(r.UserID); err == nil {
			uv := userView(u)
			v.User = &uv
		}
		out = append(out, v)
	}
	return out, nil
}

// InvitableUsers 可邀请用户（未加入本空间的 active 用户；owner/admin 视图）。
func (c *Core) InvitableUsers(workspaceID uint) ([]UserView, error) {
	var rows []store.User
	if err := c.DB.Where("status = ? AND id NOT IN (?)", "active",
		c.DB.Model(&store.WorkspaceMember{}).Select("user_id").
			Where("workspace_id = ?", workspaceID),
	).Order("id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]UserView, 0, len(rows))
	for i := range rows {
		out = append(out, userView(&rows[i]))
	}
	return out, nil
}

// ---- 纳管 ----

// BindService 纳管：存量服务挂入空间（仅当当前归属为 default 空间或 0；
// 已在其他业务空间 → 409 already_bound）。
func (c *Core) BindService(actor, workspaceID, serviceID uint) (*store.ServiceEntity, error) {
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, err
	}
	var ws store.Workspace
	if err := c.DB.First(&ws, workspaceID).Error; err != nil {
		return nil, ErrNotFound
	}
	if svc.WorkspaceID != 0 && svc.WorkspaceID != ws.ID {
		var cur store.Workspace
		if err := c.DB.First(&cur, svc.WorkspaceID).Error; err == nil && cur.Slug != "default" {
			return nil, fmt.Errorf("%w: already_bound to workspace %s", ErrConflict, cur.Slug)
		}
	}
	if err := c.DB.Model(svc).Update("workspace_id", workspaceID).Error; err != nil {
		return nil, err
	}
	svc.WorkspaceID = workspaceID
	c.recordAudit("workspace.bind", svc.K8sName, actor, map[string]interface{}{
		"workspaceId": workspaceID, "serviceId": serviceID,
	})
	return svc, nil
}

// ---- 审计聚合（一期：service_events + platform_config_events 视图）----

// AuditItem 审计条目。
type AuditItem struct {
	Time   time.Time `json:"time"`
	Actor  string    `json:"actor"`
	Action string    `json:"action"`
	Object string    `json:"object"`
	Detail string    `json:"detail"`
}

type auditReason struct {
	Kind   string      `json:"kind"`
	Actor  interface{} `json:"actor"`
	Object string      `json:"object"`
	Name   string      `json:"name"`
}

// actionFromReason 状态变迁 reason → 动作词（service_events 既有文案映射）。
func actionFromReason(reason string) string {
	switch {
	case strings.Contains(reason, "publish requested"), strings.Contains(reason, "started again"):
		return "publish"
	case strings.Contains(reason, "republish"):
		return "republish"
	case strings.Contains(reason, "unpublished"):
		return "unpublish"
	case strings.Contains(reason, "env updated"):
		return "env.update"
	case strings.Contains(reason, "register"):
		return "register"
	default:
		return ""
	}
}

// AuditLogs 聚合审计（分页）。工作空间/用户/凭证治理动作（service_id=0 且 reason 为
// kind JSON）+ 服务状态变迁（映射 publish/republish/unpublish/env.update）+ 平台配置更新。
func (c *Core) AuditLogs(page, size int) ([]AuditItem, int64, error) {
	if page < 1 {
		page = 1
	}
	if size < 1 || size > 200 {
		size = 50
	}
	// 全量合并后在内存排序分页（一期量级可控；独立 audit_logs 表列 P2）
	items := []AuditItem{}

	// 1) 治理类事件（service_id=0）
	var gov []store.ServiceEvent
	if err := c.DB.Where("service_id = 0").Order("created_at DESC").Limit(500).Find(&gov).Error; err != nil {
		return nil, 0, err
	}
	for _, e := range gov {
		var r auditReason
		_ = json.Unmarshal([]byte(e.Reason), &r)
		actor := "—"
		switch v := r.Actor.(type) {
		case string:
			actor = v
		case float64:
			if u, err := c.GetUser(uint(v)); err == nil {
				actor = u.DisplayName
			}
		}
		if r.Kind == "" {
			continue
		}
		obj := r.Object
		if obj == "" {
			obj = r.Name
		}
		items = append(items, AuditItem{Time: e.CreatedAt, Actor: actor, Action: r.Kind, Object: obj, Detail: e.Reason})
	}

	// 2) 服务事件（publish/unpublish/republish/env.update；actor 取服务 created_by）
	var svcRows []store.ServiceEntity
	_ = c.DB.Find(&svcRows).Error
	actorByService := map[uint]string{}
	nameByService := map[uint]string{}
	for _, s := range svcRows {
		nameByService[s.ID] = s.K8sName
		if s.CreatedBy > 0 {
			if u, err := c.GetUser(s.CreatedBy); err == nil {
				actorByService[s.ID] = u.DisplayName
			}
		}
	}
	var svcEvents []store.ServiceEvent
	if err := c.DB.Where("service_id > 0").Order("created_at DESC").Limit(500).Find(&svcEvents).Error; err != nil {
		return nil, 0, err
	}
	for _, e := range svcEvents {
		action := actionFromReason(e.Reason)
		if action == "" {
			continue
		}
		actor := actorByService[e.ServiceID]
		if actor == "" {
			actor = "—"
		}
		items = append(items, AuditItem{Time: e.CreatedAt, Actor: actor, Action: action,
			Object: nameByService[e.ServiceID], Detail: e.Reason})
	}

	// 3) 平台配置事件
	var pcEvents []store.PlatformConfigEvent
	if err := c.DB.Order("created_at DESC").Limit(200).Find(&pcEvents).Error; err != nil {
		return nil, 0, err
	}
	for _, e := range pcEvents {
		items = append(items, AuditItem{Time: e.CreatedAt, Actor: "admin",
			Action: "platform_config.update", Object: e.EnvKeys, Detail: e.Action})
	}

	total := int64(len(items))
	// 时间倒序（合并来源各自有序，合并后重排）
	for i := 0; i < len(items); i++ {
		for j := i + 1; j < len(items); j++ {
			if items[j].Time.After(items[i].Time) {
				items[i], items[j] = items[j], items[i]
			}
		}
	}
	start := (page - 1) * size
	if start > len(items) {
		start = len(items)
	}
	end := start + size
	if end > len(items) {
		end = len(items)
	}
	return items[start:end], total, nil
}
