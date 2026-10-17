// M1 治理 + M2 workspace 裁决测试：slug/bind 三态/成员矩阵/删除保护 + resolveWorkspace。
package service

import (
	"encoding/json"
	"testing"

	"agent-manager/backend/internal/store"
)

// seedWs 建工作空间（测试辅助，直接落库）。
func seedWs(t *testing.T, c *Core, slug, name string) *store.Workspace {
	t.Helper()
	w := &store.Workspace{Slug: slug, Name: name, Status: "active", QuotaJSON: "{}"}
	if err := c.DB.Create(w).Error; err != nil {
		t.Fatal(err)
	}
	return w
}

func seedUserIn(t *testing.T, c *Core, username, role string, wsID uint, memberRole string) *store.User {
	t.Helper()
	hash, _ := store.HashPassword("p-" + username)
	u := &store.User{Username: username, PasswordHash: hash, DisplayName: username, Role: role, Status: "active"}
	if err := c.DB.Create(u).Error; err != nil {
		t.Fatal(err)
	}
	if wsID > 0 {
		if err := c.DB.Create(&store.WorkspaceMember{WorkspaceID: wsID, UserID: u.ID, Role: memberRole}).Error; err != nil {
			t.Fatal(err)
		}
	}
	return u
}

func TestAdminWorkspaceCRUD(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	owner := seedUserIn(t, c, "owner1", "user", 0, "")

	// 创建：slug 自动生成 + owner 成员关系
	w, err := c.AdminCreateWorkspace(CreateWorkspaceInput{Name: "客服空间", OwnerUserID: owner.ID,
		Quota: map[string]interface{}{"maxAgents": 10}})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if len(w.Slug) != 11 || w.Slug[:3] != "ws-" {
		t.Fatalf("slug: %q", w.Slug)
	}
	var m store.WorkspaceMember
	if err := c.DB.Where("workspace_id = ?", w.ID).First(&m).Error; err != nil || m.Role != "owner" {
		t.Fatalf("owner membership: %+v err=%v", m, err)
	}
	// 空名 400
	if _, err := c.AdminCreateWorkspace(CreateWorkspaceInput{Name: " "}); err == nil {
		t.Fatal("empty name should fail")
	}
	// 列表聚合
	list, err := c.AdminListWorkspaces()
	if err != nil || len(list) < 1 {
		t.Fatalf("list: %v n=%d", err, len(list))
	}
	var found *WorkspaceView
	for i := range list {
		if list[i].ID == w.ID {
			found = &list[i]
		}
	}
	if found == nil || found.MemberCount != 1 || found.AgentCount != 0 || found.Owner == nil {
		t.Fatalf("aggregate view: %+v", found)
	}
	if found.Quota["maxAgents"].(float64) != 10 {
		t.Fatalf("quota: %+v", found.Quota)
	}

	// 删除保护：有成员 → 409
	if err := c.AdminDeleteWorkspace(w.ID); err == nil {
		t.Fatal("non-empty workspace should be protected")
	}
	// 空空间（无成员无 Agent）可删
	empty, err := c.AdminCreateWorkspace(CreateWorkspaceInput{Name: "空空间"})
	if err != nil {
		t.Fatalf("create empty: %v", err)
	}
	if err := c.AdminDeleteWorkspace(empty.ID); err != nil {
		t.Fatalf("delete empty: %v", err)
	}
}

func TestBindServiceThreeStates(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	if err := storeBackfill(c); err != nil {
		t.Fatal(err)
	}
	ws := seedWs(t, c, "ws-bbb0001", "业务空间")
	other := seedWs(t, c, "ws-ccc0002", "其他空间")

	// 造一个归属 default 的存量服务
	var def store.Workspace
	if err := c.DB.Where("slug = ?", "default").First(&def).Error; err != nil {
		t.Fatal(err)
	}
	svc := &store.ServiceEntity{K8sName: "oaf-legacy-x", DisplayName: "legacy", WorkspaceID: def.ID}
	if err := c.DB.Create(svc).Error; err != nil {
		t.Fatal(err)
	}

	// default → 目标空间 ok
	bound, err := c.BindService(ws.ID, svc.ID)
	if err != nil || bound.WorkspaceID != ws.ID {
		t.Fatalf("bind default→ws: %v %+v", err, bound)
	}
	// 已在业务空间 → 换绑到其他空间 409 already_bound
	if _, err := c.BindService(other.ID, svc.ID); err == nil {
		t.Fatal("bind bound service should 409")
	}
	// 不存在服务 404
	if _, err := c.BindService(ws.ID, 9999); err == nil {
		t.Fatal("missing service should 404")
	}
	// 未归属（0）→ 可绑
	var zero store.ServiceEntity
	if err := c.DB.Create(&zero).Error; err != nil { // workspace_id=0
		// zero 值 k8s 名为空串会撞唯一索引，改为显式
	}
	c.DB.Delete(&zero)
	svc2 := &store.ServiceEntity{K8sName: "oaf-orphan-y", DisplayName: "orphan", WorkspaceID: 0}
	if err := c.DB.Create(svc2).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := c.BindService(ws.ID, svc2.ID); err != nil {
		t.Fatalf("bind orphan: %v", err)
	}
}

func TestMemberRoleMatrix(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-ddd0003", "矩阵空间")
	owner := seedUserIn(t, c, "own", "user", 0, "")
	admin1 := seedUserIn(t, c, "adm", "user", 0, "")
	mem := seedUserIn(t, c, "mem", "user", 0, "")

	if _, err := c.AddMember(ws.ID, owner.ID, "owner"); err != nil {
		t.Fatalf("add owner: %v", err)
	}
	if _, err := c.AddMember(ws.ID, admin1.ID, "admin"); err != nil {
		t.Fatalf("add admin: %v", err)
	}
	if _, err := c.AddMember(ws.ID, mem.ID, "member"); err != nil {
		t.Fatalf("add member: %v", err)
	}
	// 重复加入 409
	if _, err := c.AddMember(ws.ID, mem.ID, "member"); err == nil {
		t.Fatal("duplicate member should conflict")
	}
	// owner 不可降级 / 不可移除
	if err := c.UpdateMemberRole(ws.ID, owner.ID, "member"); err == nil {
		t.Fatal("owner demote should fail")
	}
	if err := c.RemoveMember(ws.ID, owner.ID); err == nil {
		t.Fatal("owner remove should fail")
	}
	// member → admin ok；再移除 ok
	if err := c.UpdateMemberRole(ws.ID, mem.ID, "admin"); err != nil {
		t.Fatalf("promote member: %v", err)
	}
	if err := c.RemoveMember(ws.ID, mem.ID); err != nil {
		t.Fatalf("remove: %v", err)
	}
}

func TestUserManagement(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	u, err := c.AdminCreateUser("zhangsan", "secret1", "张工", "user")
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	if !store.CheckPassword(u.PasswordHash, "secret1") {
		t.Fatal("password hash mismatch")
	}
	// 重名 400
	if _, err := c.AdminCreateUser("zhangsan", "x", "", "user"); err == nil {
		t.Fatal("duplicate username should fail")
	}
	// 重置密码
	if _, err := c.AdminPatchUser(u.ID, nil, nil, strPtr("newpass")); err != nil {
		t.Fatalf("reset password: %v", err)
	}
	var fresh store.User
	c.DB.First(&fresh, u.ID)
	if !store.CheckPassword(fresh.PasswordHash, "newpass") {
		t.Fatal("password not reset")
	}
	// 禁用 → 登录被拒
	if _, err := c.AdminPatchUser(u.ID, nil, strPtr("disabled"), nil); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Authenticate("zhangsan", "newpass"); err != ErrUserDisabled {
		t.Fatalf("disabled login: %v", err)
	}
}

func TestResolveWorkspaceMatrix(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	if err := storeBackfill(c); err != nil {
		t.Fatal(err)
	}
	ws := seedWs(t, c, "ws-eee0004", "成员空间")
	owner := seedUserIn(t, c, "owner-u", "user", ws.ID, "owner")
	member := seedUserIn(t, c, "member-u", "user", ws.ID, "member")
	stranger := seedUserIn(t, c, "stranger", "user", 0, "")
	adminU := seedUserIn(t, c, "admin-u", "admin", 0, "")
	// admin 也加入 default（决议 2 中 admin 可解析 default）
	var def store.Workspace
	c.DB.Where("slug = ?", "default").First(&def)
	if err := c.DB.Create(&store.WorkspaceMember{WorkspaceID: def.ID, UserID: adminU.ID, Role: "member"}).Error; err != nil {
		t.Fatal(err)
	}

	// owner / member：me + 数字 id 均可
	if _, role, err := c.ResolveWorkspace(owner, "me"); err != nil || role != "owner" || ws.ID == 0 {
		t.Fatalf("owner me: %v role=%s", err, role)
	}
	if _, role, err := c.ResolveWorkspace(member, itoa(ws.ID)); err != nil || role != "member" {
		t.Fatalf("member by id: %v role=%s", err, role)
	}
	// 非成员 → 404（不暴露 403/404 差异）
	if _, _, err := c.ResolveWorkspace(stranger, itoa(ws.ID)); err != ErrWorkspaceNotFound {
		t.Fatalf("stranger: %v", err)
	}
	// admin 无成员记录 → 隐式放行（设计 §5.1 平台管理员身份；部署实测修正）
	_, role, err := c.ResolveWorkspace(adminU, itoa(ws.ID))
	if err != nil || role != "admin" {
		t.Fatalf("admin implicit membership: %v role=%s", err, role)
	}
	// default 空间：admin 可解析，普通用户 404
	if _, _, err := c.ResolveWorkspace(adminU, itoa(def.ID)); err != nil {
		t.Fatalf("admin default: %v", err)
	}
	if _, _, err := c.ResolveWorkspace(member, itoa(def.ID)); err != ErrWorkspaceNotFound {
		t.Fatalf("user default: %v", err)
	}
	// 不存在空间 404
	if _, _, err := c.ResolveWorkspace(member, "999"); err != ErrWorkspaceNotFound {
		t.Fatalf("missing ws: %v", err)
	}
}

func TestAuditLogsAggregation(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	ws := seedWs(t, c, "ws-fff0005", "审计空间")
	if _, err := c.AdminCreateWorkspace(CreateWorkspaceInput{Name: "审计2"}); err != nil {
		t.Fatal(err)
	}
	_ = ws
	items, total, err := c.AuditLogs(1, 50)
	if err != nil {
		t.Fatalf("audit: %v", err)
	}
	if total < 1 || items[0].Action != "workspace.create" {
		t.Fatalf("audit items: %d %+v", total, items)
	}
	b, _ := json.Marshal(items[0])
	if len(b) == 0 {
		t.Fatal("marshal")
	}
}

func strPtr(s string) *string { return &s }

// storeBackfill 测试内构造 default 空间（与启动路径同函数）。
func storeBackfill(c *Core) error { return store.Backfill(c.DB, "admin", "x") }

func itoa(v uint) string {
	if v == 0 {
		return "0"
	}
	digits := []byte{}
	for v > 0 {
		digits = append([]byte{byte('0' + v%10)}, digits...)
		v /= 10
	}
	return string(digits)
}
