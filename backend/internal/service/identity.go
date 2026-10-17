// 身份与工作空间领域逻辑：登录校验、me 视图、成员关系裁决（console/admin 共用）。
package service

import (
	"errors"
	"fmt"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrUserDisabled       = errors.New("user is disabled")
	ErrDuplicateName      = errors.New("name already exists")
	ErrConflict           = errors.New("resource state conflict")
	ErrForbidden          = errors.New("forbidden")
	ErrInvalidForm        = errors.New("invalid form")
	ErrWorkspaceNotFound  = errors.New("workspace not found")
)

// WorkspaceBrief 工作空间简要视图（me / 切换器 / 成员行共用）。
type WorkspaceBrief struct {
	ID   uint   `json:"id"`
	Slug string `json:"slug"`
	Name string `json:"name"`
	Role string `json:"role"` // 本人在该空间的成员角色
}

// UserView 用户视图（登录响应 / me / 用户管理共用）。
type UserView struct {
	ID          uint            `json:"id"`
	Username    string          `json:"username"`
	DisplayName string          `json:"displayName"`
	Email       string          `json:"email"`
	Role        string          `json:"role"`
	Status      string          `json:"status"`
	LastLoginAt *time.Time      `json:"lastLoginAt"`
	Workspaces  []WorkspaceBrief `json:"workspaces,omitempty"`
}

func userView(u *store.User) UserView {
	return UserView{ID: u.ID, Username: u.Username, DisplayName: u.DisplayName,
		Email: u.Email, Role: u.Role, Status: u.Status, LastLoginAt: u.LastLoginAt}
}

// ViewOf 导出用户视图（handler 层构造响应用）。
func ViewOf(u *store.User) UserView { return userView(u) }

// Authenticate 登录校验：bcrypt + disabled 拦截 + last_login_at 落库。
func (c *Core) Authenticate(username, password string) (*store.User, error) {
	var u store.User
	if err := c.DB.Where("username = ?", username).First(&u).Error; err != nil {
		// 统一文案，不区分用户不存在/密码错
		return nil, ErrInvalidCredentials
	}
	if !store.CheckPassword(u.PasswordHash, password) {
		return nil, ErrInvalidCredentials
	}
	if u.Status == "disabled" {
		return nil, ErrUserDisabled
	}
	now := time.Now()
	if err := c.DB.Model(&store.User{}).Where("id = ?", u.ID).
		Update("last_login_at", &now).Error; err != nil {
		return nil, err
	}
	u.LastLoginAt = &now
	return &u, nil
}

// ListUserWorkspaces 用户可见工作空间列表。admin 追加虚拟成员关系
// {id:0, slug:"admin", name:"平台管理员", role:"admin"}（前端运维侧入口判定用）。
func (c *Core) ListUserWorkspaces(u *store.User) ([]WorkspaceBrief, error) {
	type row struct {
		ID   uint
		Slug string
		Name string
		Role string
	}
	var rows []row
	err := c.DB.Table("workspace_members AS wm").
		Select("wm.workspace_id AS id, w.slug, w.name, wm.role").
		Joins("JOIN workspaces w ON w.id = wm.workspace_id").
		Where("wm.user_id = ? AND w.status = ?", u.ID, "active").
		Order("wm.workspace_id ASC").Scan(&rows).Error
	if err != nil {
		return nil, err
	}
	out := make([]WorkspaceBrief, 0, len(rows))
	for _, r := range rows {
		out = append(out, WorkspaceBrief{ID: r.ID, Slug: r.Slug, Name: r.Name, Role: r.Role})
	}
	if u.Role == "admin" {
		out = append(out, WorkspaceBrief{ID: 0, Slug: "admin", Name: "平台管理员", Role: "admin"})
	}
	return out, nil
}

// Me 登录态视图：用户 + 工作空间列表。
func (c *Core) Me(u *store.User) (UserView, []WorkspaceBrief, error) {
	ws, err := c.ListUserWorkspaces(u)
	if err != nil {
		return UserView{}, nil, err
	}
	return userView(u), ws, nil
}

// DefaultWorkspace 用户默认工作空间（me 第一个真实成员空间；admin 虚拟空间不计入）。
func (c *Core) DefaultWorkspace(u *store.User) (*store.Workspace, error) {
	var ws store.Workspace
	err := c.DB.Table("workspace_members AS wm").
		Select("w.*").
		Joins("JOIN workspaces w ON w.id = wm.workspace_id").
		Where("wm.user_id = ? AND w.status = ? AND wm.workspace_id <> 0", u.ID, "active").
		Order("wm.workspace_id ASC").First(&ws).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrWorkspaceNotFound
		}
		return nil, err
	}
	return &ws, nil
}

// ResolveWorkspace console :ws 参数裁决（M2 §2.2）：
//   - "me"：默认 = 该用户第一个成员空间；
//   - 数字：必须是该用户成员空间；slug=default 的空间仅 admin 可解析（决议 2）；
//   - admin 的虚拟空间（id=0）仅允许命中 admin 语义，console 端点取其真实成员空间 → 404。
//
// 返回空间与本人成员角色（owner/admin/member）。
func (c *Core) ResolveWorkspace(u *store.User, wsParam string) (*store.Workspace, string, error) {
	var ws store.Workspace
	if wsParam == "" || wsParam == "me" {
		w, err := c.DefaultWorkspace(u)
		if err != nil {
			return nil, "", err
		}
		ws = *w
	} else if isUint(wsParam) {
		if err := c.DB.First(&ws, wsParam).Error; err != nil {
			return nil, "", ErrWorkspaceNotFound
		}
	} else {
		// slug 形态（如 default）：按 slug 查
		if err := c.DB.Where("slug = ?", wsParam).First(&ws).Error; err != nil {
			return nil, "", ErrWorkspaceNotFound
		}
	}
	if ws.ID == 0 {
		return nil, "", ErrWorkspaceNotFound // admin 虚拟空间无 console 语义
	}
	if ws.Slug == "default" && u.Role != "admin" {
		return nil, "", ErrWorkspaceNotFound // 存量空间对普通用户不可见
	}
	if ws.Status != "active" {
		return nil, "", fmt.Errorf("%w: workspace disabled", ErrWorkspaceNotFound)
	}
	var m store.WorkspaceMember
	err := c.DB.Where("workspace_id = ? AND user_id = ?", ws.ID, u.ID).First(&m).Error
	if err != nil {
		// 设计 §5.1：admin 可进任意工作空间（平台管理员身份）——无成员记录时给隐式
		// admin 角色（覆盖 admin 建空间未传 ownerUserId 的场景；部署实测发现）
		if u.Role == "admin" {
			m = store.WorkspaceMember{WorkspaceID: ws.ID, UserID: u.ID, Role: "admin"}
		} else {
			// 不暴露 403/404 差异细节，统一 404
			return nil, "", ErrWorkspaceNotFound
		}
	}
	return &ws, m.Role, nil
}

// RequireWsRole 写权限裁决：member 只读（M0 设计 §5.1），owner/admin 放行。
func RequireWsRole(role string) error {
	if role != "owner" && role != "admin" {
		return ErrForbidden
	}
	return nil
}

func isUint(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
