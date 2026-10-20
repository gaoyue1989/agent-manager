// Skill 中心（2026-10-19，docs/design/console-user-workspace-split-design.md §6.3/§8.4）：
// 工作空间级独立技能资产——市场导入 / zip 技能包上传 / Markdown 定义创建，版本管理
// （工作区草稿可续改 + 正式版本不可变快照 + 最新发布版承担 latest 语义），zip 内容落
// 共享 PVC（skills/{skillID}/{version}/，与 oaf_packages 同盘同护栏）。
// Agent 引用 = 生成包自包含（agentgen 从技能版本目录复制），不落 services 新列。
package service

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// Skill 版本状态（对标 AgentCore：审核流一期不接，review/pending 仅预留槽位）。
const (
	SkillVerDraft     = "draft"
	SkillVerReview    = "review" // 预留：接 AI 安全护栏后启用
	SkillVerPending   = "pending"
	SkillVerPublished = "published"
	SkillVerOffline   = "offline"
)

var (
	// ErrSkillReferenced 技能被 Agent 引用（删除 409 / 下线提示）
	ErrSkillReferenced = errors.New("skill is referenced by agents")
	// ErrSkillVersionImmutable 正式版本不可变
	ErrSkillVersionImmutable = errors.New("published version is immutable")
)

var (
	skillVersionRe     = regexp.MustCompile(`^\d+\.\d+\.\d+$`)
	skillNameInvalidRe = regexp.MustCompile(`[^a-z0-9-]+`)
)

// validateSkillName 技能名校验（skillNameRe 同口径：包内目录与技能资产一致才能复制进包）。
func validateSkillName(name string) error {
	if name == "" || utf8.RuneCountInString(name) > 64 {
		return fmt.Errorf("%w: skill name must be 1-64 chars", ErrInvalidForm)
	}
	if !skillNameRe.MatchString(name) || strings.Contains(name, "--") {
		return fmt.Errorf("%w: skill name must match ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ without consecutive hyphens", ErrInvalidForm)
	}
	return nil
}

// normalizeTags 标签入参归一：去空白、去空、去重、JSON 序列化。
func normalizeTags(tags []string) string {
	seen := map[string]bool{}
	out := []string{}
	for _, t := range tags {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] || utf8.RuneCountInString(t) > 32 {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	if len(out) > 10 {
		out = out[:10]
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// SkillView 列表条目（含引用计数与最新发布版摘要）。
type SkillView struct {
	store.Skill
	RefAgentCount int64             `json:"refAgentCount"`
	LatestPublish *SkillPublishInfo `json:"latestPublish,omitempty"`
}

// SkillPublishInfo 最新发布版摘要（latest 语义 = 最新 published 版）。
type SkillPublishInfo struct {
	VersionID   uint       `json:"versionId"`
	Version     string     `json:"version"`
	PublishedAt *time.Time `json:"publishedAt"`
}

// GetSkill 按 id 查询主表行。
func (c *Core) GetSkill(id uint) (*store.Skill, error) {
	var s store.Skill
	if err := c.DB.First(&s, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &s, nil
}

// GetSkillVersion 版本行查询（agentgen 复制目录用）。
func (c *Core) GetSkillVersion(id uint) (*store.SkillVersion, error) {
	var v store.SkillVersion
	if err := c.DB.First(&v, id).Error; err != nil {
		return nil, ErrNotFound
	}
	return &v, nil
}

// skillRefAgents 空间内引用技能（按包内 skills/<name>/ 目录）的服务——
// 「最后生成的 OAF 包即引用快照」：解析运行包 frontmatter skills 声明命中技能名即计入。
func (c *Core) skillRefAgents(workspaceID uint, skillNames ...string) map[string][]store.ServiceEntity {
	out := map[string][]store.ServiceEntity{}
	if len(skillNames) == 0 {
		return out
	}
	nameSet := map[string]bool{}
	for _, n := range skillNames {
		nameSet[n] = true
	}
	var svcs []store.ServiceEntity
	if err := c.DB.Where("workspace_id = ?", workspaceID).Find(&svcs).Error; err != nil {
		return out
	}
	for i := range svcs {
		pkg, err := c.Packages.Get(svcs[i].PackageID)
		if err != nil {
			continue
		}
		var cfg struct {
			Skills []struct {
				Name string `json:"name"`
			} `json:"skills"`
		}
		if json.Unmarshal([]byte(pkg.ManifestJSON), &cfg) != nil {
			continue
		}
		for _, s := range cfg.Skills {
			if nameSet[s.Name] {
				out[s.Name] = append(out[s.Name], svcs[i])
			}
		}
	}
	return out
}

// ListSkills 空间技能资产列表（keyword 过滤名称/描述/标签；latestPublish = 最新 published 版）。
func (c *Core) ListSkills(workspaceID uint, keyword string) ([]SkillView, error) {
	q := c.DB.Model(&store.Skill{}).Where("workspace_id = ?", workspaceID)
	if keyword != "" {
		kw := "%" + keyword + "%"
		q = q.Where("name LIKE ? OR display_name LIKE ? OR description LIKE ? OR tags_json LIKE ?",
			kw, kw, kw, kw)
	}
	var rows []store.Skill
	if err := q.Order("id DESC").Find(&rows).Error; err != nil {
		return nil, err
	}
	refs := c.skillRefAgents(workspaceID, func() []string {
		names := make([]string, 0, len(rows))
		for _, r := range rows {
			names = append(names, r.Name)
		}
		return names
	}()...)
	// 最新发布版：一次查询全部技能的 published 版本取每技能最大 id
	latest := map[uint]*SkillPublishInfo{}
	if len(rows) > 0 {
		var vers []store.SkillVersion
		if err := c.DB.Where("skill_id IN ? AND status = ?", skillIDs(rows), SkillVerPublished).
			Order("id ASC").Find(&vers).Error; err != nil {
			return nil, err
		}
		for i := range vers {
			info := &SkillPublishInfo{VersionID: vers[i].ID, Version: vers[i].Version, PublishedAt: &vers[i].CreatedAt}
			latest[vers[i].SkillID] = info // id ASC：后写覆盖 = 最新
		}
	}
	out := make([]SkillView, 0, len(rows))
	for i := range rows {
		r := rows[i]
		v := SkillView{Skill: r, RefAgentCount: int64(len(refs[r.Name])), LatestPublish: latest[r.ID]}
		out = append(out, v)
	}
	return out, nil
}

func skillIDs(rows []store.Skill) []uint {
	ids := make([]uint, 0, len(rows))
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	return ids
}

// CreateSkillFromMarkdown Markdown 定义创建：frontmatter name/description 自动生成，
// 正文即 SKILL.md 内容本体；落 v1.0.0（draft 或直接 published，按 publishNow）。
func (c *Core) CreateSkillFromMarkdown(workspaceID, userID uint, name, displayName, description string, tags []string, content string, publishNow bool) (*store.Skill, error) {
	if err := validateSkillName(name); err != nil {
		return nil, err
	}
	description = strings.TrimSpace(description)
	if description == "" || utf8.RuneCountInString(description) > 1000 {
		return nil, fmt.Errorf("%w: description must be 1-1000 chars", ErrInvalidForm)
	}
	content = strings.TrimLeft(content, "\ufeff\n ")
	if content == "" || utf8.RuneCountInString(content) > 256<<10 {
		return nil, fmt.Errorf("%w: markdown content must be 1-256KB", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.Skill{}).Where("workspace_id = ? AND name = ?", workspaceID, name).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: skill %q already exists in workspace", ErrConflict, name)
	}
	skill := store.Skill{
		WorkspaceID: workspaceID, Name: name, DisplayName: displayName,
		Description: description, TagsJSON: normalizeTags(tags), Source: "markdown",
		Enabled: true, Status: "active", CreatedBy: userID,
	}
	ver := store.SkillVersion{Version: "1.0.0", Status: SkillVerDraft, Changelog: "Markdown 定义创建",
		CreatedBy: userID, ContentJSON: mustJSON(map[string]string{"content": content})}
	if publishNow {
		ver.Status = SkillVerPublished
	}
	return c.createSkillTx(&skill, &ver, nil)
}

// CreateSkillFromZip zip 技能包创建：解包校验（与 OAF 包同护栏）→ 提取 skills/<name>/
// → checkSkillDirs 合规检查（warnings 宽松不阻断）→ v1.0.0。
// zip 结构要求：skills/<name>/SKILL.md 为主文件（可选 scripts/ references/ assets/）。
func (c *Core) CreateSkillFromZip(workspaceID, userID uint, filename string, r io.Reader, name, displayName, description string, tags []string, publishNow bool) (*store.Skill, []string, error) {
	zi, err := c.FS.InspectSkillZip(r)
	if err != nil {
		return nil, nil, err
	}
	// 读取全部条目（InspectSkillZip 已做 zip-slip/大小/条目数校验）
	raw := map[string][]byte{}
	for _, zf := range zi.Reader.File {
		if zf.FileInfo().IsDir() {
			continue
		}
		p := strings.Trim(zf.Name, "/")
		rc, err := zf.Open()
		if err != nil {
			return nil, nil, err
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, nil, err
		}
		raw[p] = data
	}
	// 布局归一（2026-10-20）：真实技能包有多种形态，统一归一为包内 skills/<name>/ 结构：
	//   A) skills/<name>/SKILL.md（OAF 包内技能目录风格）
	//   B) 根目录 SKILL.md（标准技能包，如 Claude/AgentCore 技能包 + _meta.json）
	//   C) 单层目录 <dir>/SKILL.md（压缩整个技能文件夹）
	// 名称优先级：表单 name > 布局目录名（A/C）> SKILL.md frontmatter name > zip 文件名
	prefix, layoutName := "", ""
	underSkills := true
	skillDirs := map[string]bool{}
	for p := range raw {
		if !strings.HasPrefix(p, "skills/") {
			underSkills = false
			break
		}
		rest := strings.TrimPrefix(p, "skills/")
		if rest == "" {
			underSkills = false
			break
		}
		skillDirs[strings.SplitN(rest, "/", 2)[0]] = true
	}
	switch {
	case underSkills && len(skillDirs) == 1:
		for d := range skillDirs {
			layoutName = d
		}
		prefix = "skills/" + layoutName + "/"
	case raw["SKILL.md"] != nil:
		prefix = ""
	default:
		// C：仅一个顶层目录且其下有 SKILL.md
		top := map[string]bool{}
		for p := range raw {
			if i := strings.Index(p, "/"); i > 0 {
				top[p[:i]] = true
			}
		}
		if len(top) == 1 {
			var d string
			for x := range top {
				d = x
			}
			if raw[d+"/SKILL.md"] != nil {
				prefix = d + "/"
				layoutName = d
			}
		}
	}
	if prefix == "" && layoutName == "" && raw["SKILL.md"] == nil {
		return nil, nil, fmt.Errorf("%w: zip must contain SKILL.md at root, <dir>/SKILL.md, or skills/<name>/SKILL.md", ErrInvalidForm)
	}
	// 相对技能根的文件集合（不含布局前缀）
	rel := map[string][]byte{}
	for p, data := range raw {
		if prefix != "" {
			if !strings.HasPrefix(p, prefix) {
				continue // 布局根之外的杂项条目忽略（如 __MACOSX）
			}
			p = strings.TrimPrefix(p, prefix)
		}
		if p == "" {
			continue
		}
		rel[p] = data
	}
	// 技能名：表单 > 布局目录 > frontmatter > zip 文件名
	if name == "" {
		name = layoutName
	}
	if name == "" {
		if fmName, _, ok := parseSkillFrontmatter(string(rel["SKILL.md"])); ok && fmName != "" {
			name = fmName
		}
	}
	if name == "" {
		name = sanitizeSkillName(filename)
	}
	if err := validateSkillName(name); err != nil {
		return nil, nil, err
	}
	// 归一化为 skills/<name>/<rel>（落 PVC 与生成包复制都依赖该结构）
	files := map[string][]byte{}
	for p, data := range rel {
		files["skills/"+name+"/"+p] = data
	}
	// 合规检查（warnings 宽松；与包上传 registerZipCore 同一实现同一口径）
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	warnings := []string{}
	for _, it := range checkSkillDirs(func(p string) ([]byte, error) {
		if data, ok := files[p]; ok {
			return data, nil
		}
		return nil, fmt.Errorf("not found: %s", p)
	}, paths) {
		for _, iss := range it.Issues {
			warnings = append(warnings, fmt.Sprintf("skill %q: %s", it.Name, iss))
		}
	}
	// SKILL.md frontmatter 回填 description（入参缺省时）
	if description == "" {
		if _, fmDesc, ok := parseSkillFrontmatter(string(files["skills/"+name+"/SKILL.md"])); ok && fmDesc != "" {
			description = fmDesc
		}
	}
	if description == "" {
		return nil, nil, fmt.Errorf("%w: description required (none in SKILL.md frontmatter either)", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.Skill{}).Where("workspace_id = ? AND name = ?", workspaceID, name).Count(&cnt).Error; err != nil {
		return nil, nil, err
	}
	if cnt > 0 {
		return nil, nil, fmt.Errorf("%w: skill %q already exists in workspace", ErrConflict, name)
	}
	skill := store.Skill{
		WorkspaceID: workspaceID, Name: name, DisplayName: displayName,
		Description: description, TagsJSON: normalizeTags(tags), Source: "zip",
		Enabled: true, Status: "active", CreatedBy: userID,
	}
	ver := store.SkillVersion{Version: "1.0.0", Status: SkillVerDraft, Changelog: "zip 技能包上传（" + filename + "）",
		CreatedBy: userID}
	if publishNow {
		ver.Status = SkillVerPublished
	}
	created, err := c.createSkillTx(&skill, &ver, func(skillID uint) error {
		// 归一化后的 zip 落 PVC：skills/{skillID}/{version}/（WriteZipTo 再做一次条目校验）
		normalized, err := buildZipBytes(files)
		if err != nil {
			return err
		}
		ver.StorageDir = fmt.Sprintf("skills/%d/%s", skillID, ver.Version)
		sum := sha256.Sum256(normalized)
		ver.Checksum = hex.EncodeToString(sum[:])
		_, _, err = c.FS.WriteZipTo(ver.StorageDir, normalized)
		return err
	})
	if err != nil {
		return nil, nil, err
	}
	// StorageDir/Checksum 在 afterInsert 内赋值（事务 Create 之后），补持久化
	c.DB.Model(&store.SkillVersion{}).Where("id = ?", created.LatestVersionID).
		Updates(map[string]interface{}{"storage_dir": ver.StorageDir, "checksum": ver.Checksum})
	// 文件清单落 FilesJSON（含 sha256，供下载校验）
	filesInfo, _ := c.skillVersionFiles(created.ID, ver.Version)
	c.DB.Model(&store.SkillVersion{}).Where("id = ?", created.LatestVersionID).
		Update("files_json", mustJSON(filesInfo))
	return created, warnings, nil
}

// sanitizeSkillName zip 文件名 → 合法技能名（小写、非 [a-z0-9-] 转 -、压缩连字符、≤64）。
func sanitizeSkillName(filename string) string {
	s := strings.ToLower(strings.TrimSuffix(filepath.Base(strings.TrimSpace(filename)), ".zip"))
	s = skillNameInvalidRe.ReplaceAllString(s, "-")
	s = strings.Trim(s, "-")
	if len(s) > 64 {
		s = s[:64]
	}
	return strings.Trim(s, "-")
}

// createSkillTx 主表 + 首版本 + 发布记录同事务；afterInsert 在事务内落 PVC（失败回滚整单）。
func (c *Core) createSkillTx(skill *store.Skill, ver *store.SkillVersion, afterInsert func(skillID uint) error) (*store.Skill, error) {
	// MySQL 的 JSON 列拒绝空串（sqlite 宽松，单测掩盖——部署实测 500）：
	// 两种形态各自落默认值（zip 无 Markdown 正文 / markdown 无文件清单）
	if ver.FilesJSON == "" {
		ver.FilesJSON = "[]"
	}
	if ver.ContentJSON == "" {
		ver.ContentJSON = "{}"
	}
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(skill).Error; err != nil {
			return err
		}
		ver.SkillID = skill.ID
		if err := tx.Create(ver).Error; err != nil {
			return err
		}
		if err := tx.Model(skill).Update("latest_version_id", ver.ID).Error; err != nil {
			return err
		}
		skill.LatestVersionID = ver.ID
		if ver.Status == SkillVerPublished {
			if err := tx.Create(&store.SkillRelease{SkillID: skill.ID, VersionID: ver.ID,
				Action: "publish", Note: "创建即发布", CreatedBy: ver.CreatedBy}).Error; err != nil {
				return err
			}
		}
		if afterInsert != nil {
			return afterInsert(skill.ID)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return skill, nil
}

// skillVersionFiles 版本文件清单（zip 形态：PVC 目录 Tree；markdown 形态：nil）。
func (c *Core) skillVersionFiles(skillID uint, version string) ([]SkillFileInfo, error) {
	tree, err := c.FS.Tree(fmt.Sprintf("skills/%d/%s", skillID, version))
	if err != nil {
		return nil, err
	}
	out := []SkillFileInfo{}
	var walk func(ents []store.FileEntry) error
	walk = func(ents []store.FileEntry) error {
		for _, e := range ents {
			if e.IsDir {
				if err := walk(e.Children); err != nil {
					return err
				}
				continue
			}
			data, err := c.FS.ReadFile(fmt.Sprintf("skills/%d/%s", skillID, version), e.Path)
			if err != nil {
				return err
			}
			sum := sha256.Sum256(data)
			out = append(out, SkillFileInfo{Path: e.Path, Size: len(data), SHA256: hex.EncodeToString(sum[:])})
		}
		return nil
	}
	if err := walk(tree); err != nil {
		return nil, err
	}
	return out, nil
}

// SkillFileInfo 版本文件清单条目。
type SkillFileInfo struct {
	Path   string `json:"path"`
	Size   int    `json:"size"`
	SHA256 string `json:"sha256"`
}

// UpdateSkill 编辑（工作区内容/描述/标签；name 与来源不可变）。
func (c *Core) UpdateSkill(workspaceID, id uint, in UpdateSkillInput) (*store.Skill, error) {
	s, err := c.GetSkill(id)
	if err != nil || s.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	if in.Description != nil {
		d := strings.TrimSpace(*in.Description)
		if d == "" || utf8.RuneCountInString(d) > 1000 {
			return nil, fmt.Errorf("%w: description must be 1-1000 chars", ErrInvalidForm)
		}
		s.Description = d
	}
	if in.DisplayName != nil {
		s.DisplayName = *in.DisplayName
	}
	if in.Tags != nil {
		s.TagsJSON = normalizeTags(*in.Tags)
	}
	if err := c.DB.Save(s).Error; err != nil {
		return nil, err
	}
	// Markdown 形态：正文变更写当前 draft（正式版本不可变）
	if in.Content != nil && len(*in.Content) > 0 {
		if err := c.UpdateSkillDraft(workspaceID, id, *in.Content); err != nil {
			return nil, err
		}
	}
	return s, nil
}

// UpdateSkillInput 编辑入参（指针区分未提交）。
type UpdateSkillInput struct {
	DisplayName *string   `json:"displayName"`
	Description *string   `json:"description"`
	Tags        *[]string `json:"tags"`
	Content     *string   `json:"content"` // Markdown 形态工作区正文
}

// UpdateSkillDraft 更新 Markdown 工作区草稿（draft 版本不存在则创建）。
func (c *Core) UpdateSkillDraft(workspaceID, skillID uint, content string) error {
	s, err := c.GetSkill(skillID)
	if err != nil || s.WorkspaceID != workspaceID {
		return ErrNotFound
	}
	if s.Source != "markdown" {
		return fmt.Errorf("%w: only markdown-defined skills have editable draft content", ErrInvalidForm)
	}
	var draft store.SkillVersion
	err = c.DB.Where("skill_id = ? AND status = ?", skillID, SkillVerDraft).First(&draft).Error
	if err != nil {
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		// 无 draft：基于最新版本号顺延创建
		var latest store.SkillVersion
		if err := c.DB.Where("skill_id = ?", skillID).Order("id DESC").First(&latest).Error; err != nil {
			return err
		}
		draft = store.SkillVersion{SkillID: skillID, Version: nextVersion(latest.Version),
			Status: SkillVerDraft, Changelog: "工作区草稿", CreatedBy: s.CreatedBy}
		if err := c.DB.Create(&draft).Error; err != nil {
			return err
		}
	}
	return c.DB.Model(&draft).Update("content_json", mustJSON(map[string]string{"content": content})).Error
}

// SetSkillEnabled 启用/停用（停用后创建/编辑候选不再出现；已生成包内容不回变）。
func (c *Core) SetSkillEnabled(workspaceID, id uint, enabled bool) (*store.Skill, error) {
	s, err := c.GetSkill(id)
	if err != nil || s.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	s.Enabled = enabled
	s.Status = map[bool]string{true: "active", false: "disabled"}[enabled]
	if err := c.DB.Save(s).Error; err != nil {
		return nil, err
	}
	return s, nil
}

// DeleteSkill 删除技能资产（PVC 目录 + 版本行 + 主表；有 Agent 引用时 409）。
func (c *Core) DeleteSkill(workspaceID, id uint) error {
	s, err := c.GetSkill(id)
	if err != nil || s.WorkspaceID != workspaceID {
		return ErrNotFound
	}
	if refs := c.skillRefAgents(workspaceID, s.Name)[s.Name]; len(refs) > 0 {
		names := make([]string, 0, len(refs))
		for _, r := range refs {
			names = append(names, r.DisplayName)
		}
		return fmt.Errorf("%w: %s", ErrSkillReferenced, strings.Join(names, "、"))
	}
	var vers []store.SkillVersion
	if err := c.DB.Where("skill_id = ?", id).Find(&vers).Error; err != nil {
		return err
	}
	for _, v := range vers {
		if v.StorageDir != "" {
			if err := c.FS.RemovePackage(v.StorageDir); err != nil {
				return err
			}
		}
	}
	return c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("skill_id = ?", id).Delete(&store.SkillVersion{}).Error; err != nil {
			return err
		}
		if err := tx.Where("skill_id = ?", id).Delete(&store.SkillRelease{}).Error; err != nil {
			return err
		}
		return tx.Delete(s).Error
	})
}

// ListSkillVersions 版本列表（draft 优先展示，其余按 id 倒序）。
func (c *Core) ListSkillVersions(workspaceID, skillID uint) ([]store.SkillVersion, error) {
	s, err := c.GetSkill(skillID)
	if err != nil || s.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	var vers []store.SkillVersion
	if err := c.DB.Where("skill_id = ?", skillID).Order("id DESC").Find(&vers).Error; err != nil {
		return nil, err
	}
	return vers, nil
}

// CreateSkillVersion 基于指定版本创建新版本（语义化版本手填，默认 patch+1；
// 正式版本不可变——修正一律走「基于此版本创建新版本」）。
func (c *Core) CreateSkillVersion(workspaceID, skillID, baseVersionID uint, version, changelog string) (*store.SkillVersion, error) {
	s, err := c.GetSkill(skillID)
	if err != nil || s.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	var base store.SkillVersion
	if err := c.DB.First(&base, baseVersionID).Error; err != nil || base.SkillID != skillID {
		return nil, ErrNotFound
	}
	if version == "" {
		version = nextVersion(base.Version)
	}
	if !skillVersionRe.MatchString(version) {
		return nil, fmt.Errorf("%w: version must be semantic major.minor.patch", ErrInvalidForm)
	}
	var cnt int64
	if err := c.DB.Model(&store.SkillVersion{}).Where("skill_id = ? AND version = ?", skillID, version).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: version %s already exists", ErrConflict, version)
	}
	nv := store.SkillVersion{
		SkillID: skillID, Version: version, Status: SkillVerDraft,
		Changelog: changelog, ContentJSON: base.ContentJSON, FilesJSON: base.FilesJSON,
		StorageDir: base.StorageDir, Checksum: base.Checksum, CreatedBy: s.CreatedBy,
	}
	if base.StorageDir != "" {
		// zip 形态：PVC 内容 copy-on-write 到新版本目录（正式版本不可变 → 新版本独立目录）
		data, err := c.FS.ZipPackage(base.StorageDir)
		if err != nil {
			return nil, err
		}
		nv.StorageDir = fmt.Sprintf("skills/%d/%s", skillID, version)
		if _, _, err := c.FS.WriteZipTo(nv.StorageDir, data); err != nil {
			return nil, err
		}
		sum := sha256.Sum256(data)
		nv.Checksum = hex.EncodeToString(sum[:])
	}
	if err := c.DB.Create(&nv).Error; err != nil {
		return nil, err
	}
	c.DB.Model(s).Update("latest_version_id", nv.ID)
	return &nv, nil
}

// PublishSkillVersion 发布：draft/pending → published（写发布记录；发布即对外可用——
// 最新 published 版承担 latest 语义，创建/编辑候选默认选中）。
func (c *Core) PublishSkillVersion(workspaceID, skillID, versionID uint) (*store.SkillVersion, error) {
	return c.transitionSkillVersion(workspaceID, skillID, versionID, SkillVerPublished, "publish")
}

// OfflineSkillVersion 下线：published → offline（写发布记录；下线前引用校验由前端提示，
// 服务端在详情接口返回引用列表——锁定该版本的 Agent 不自动变化，包自包含）。
func (c *Core) OfflineSkillVersion(workspaceID, skillID, versionID uint) (*store.SkillVersion, error) {
	return c.transitionSkillVersion(workspaceID, skillID, versionID, SkillVerOffline, "offline")
}

// RelineSkillVersion 重新上线：offline → published。
func (c *Core) RelineSkillVersion(workspaceID, skillID, versionID uint) (*store.SkillVersion, error) {
	return c.transitionSkillVersion(workspaceID, skillID, versionID, SkillVerPublished, "reline")
}

func (c *Core) transitionSkillVersion(workspaceID, skillID, versionID uint, target, action string) (*store.SkillVersion, error) {
	s, err := c.GetSkill(skillID)
	if err != nil || s.WorkspaceID != workspaceID {
		return nil, ErrNotFound
	}
	var v store.SkillVersion
	if err := c.DB.First(&v, versionID).Error; err != nil || v.SkillID != skillID {
		return nil, ErrNotFound
	}
	switch action {
	case "publish", "reline":
		if v.Status != SkillVerDraft && v.Status != SkillVerPending && v.Status != SkillVerOffline {
			return nil, fmt.Errorf("%w: version %s is %s, cannot publish", ErrSkillVersionImmutable, v.Version, v.Status)
		}
	case "offline":
		if v.Status != SkillVerPublished {
			return nil, fmt.Errorf("%w: version %s is %s, only published can offline", ErrSkillVersionImmutable, v.Version, v.Status)
		}
	}
	v.Status = target
	if err := c.DB.Save(&v).Error; err != nil {
		return nil, err
	}
	c.DB.Create(&store.SkillRelease{SkillID: skillID, VersionID: versionID, Action: action, CreatedBy: s.CreatedBy})
	if target == SkillVerPublished {
		c.DB.Model(s).Update("latest_version_id", v.ID)
	}
	return &v, nil
}

// SkillVersionDownload 版本 zip 导出（zip 形态：PVC 打包；markdown 形态：拼 SKILL.md 结构）。
func (c *Core) SkillVersionDownload(workspaceID, skillID, versionID uint) (filename string, data []byte, err error) {
	s, err := c.GetSkill(skillID)
	if err != nil || s.WorkspaceID != workspaceID {
		return "", nil, ErrNotFound
	}
	var v store.SkillVersion
	if err := c.DB.First(&v, versionID).Error; err != nil || v.SkillID != skillID {
		return "", nil, ErrNotFound
	}
	if v.StorageDir != "" {
		data, err = c.FS.ZipPackage(v.StorageDir)
	} else {
		// markdown 形态导出：skills/<name>/SKILL.md 结构 zip（复用包 zip 管线）
		var content struct {
			Content string `json:"content"`
		}
		_ = json.Unmarshal([]byte(v.ContentJSON), &content)
		if content.Content == "" {
			return "", nil, fmt.Errorf("version %s has no content", v.Version)
		}
		files := map[string][]byte{
			"skills/" + s.Name + "/SKILL.md": []byte(content.Content),
		}
		data, err = buildZipBytes(files)
	}
	if err != nil {
		return "", nil, err
	}
	return fmt.Sprintf("%s-%s.zip", s.Name, v.Version), data, nil
}

// ---- Skill 广场（市场）----

// SkillMarketSourceView 市场源视图。
type SkillMarketSourceView struct {
	store.SkillMarketSource
	Items int64 `json:"items"`
}

// ListSkillMarketSources 市场源列表（一期仅内置源）。
func (c *Core) ListSkillMarketSources() ([]SkillMarketSourceView, error) {
	var rows []store.SkillMarketSource
	if err := c.DB.Order("sort ASC, id ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]SkillMarketSourceView, 0, len(rows))
	for i := range rows {
		var n int64
		c.DB.Model(&store.SkillMarketItem{}).Where("source_id = ?", rows[i].ID).Count(&n)
		out = append(out, SkillMarketSourceView{SkillMarketSource: rows[i], Items: n})
	}
	return out, nil
}

// MarketSkillView 广场条目视图（含本空间已安装标记）。
type MarketSkillView struct {
	store.SkillMarketItem
	SourceKey string `json:"sourceKey"`
	// InstalledSkillID 本空间已安装的技能资产 id（0 = 未安装）
	InstalledSkillID uint   `json:"installedSkillId"`
	InstalledVersion string `json:"installedVersion,omitempty"`
}

// ListMarketItems 广场条目（source 过滤；已安装标记按空间内同名技能资产回查）。
func (c *Core) ListMarketItems(workspaceID uint, sourceKey, keyword string) ([]MarketSkillView, error) {
	q := c.DB.Model(&store.SkillMarketItem{}).Where("enabled = ?", true)
	if sourceKey != "" {
		var src store.SkillMarketSource
		if err := c.DB.Where("`key` = ?", sourceKey).First(&src).Error; err != nil {
			return nil, ErrNotFound
		}
		q = q.Where("source_id = ?", src.ID)
	}
	if keyword != "" {
		kw := "%" + keyword + "%"
		q = q.Where("key LIKE ? OR display_name LIKE ? OR description LIKE ?", kw, kw, kw)
	}
	var items []store.SkillMarketItem
	if err := q.Order("downloads DESC, id ASC").Find(&items).Error; err != nil {
		return nil, err
	}
	srcByID := map[uint]string{}
	var sources []store.SkillMarketSource
	if err := c.DB.Find(&sources).Error; err != nil {
		return nil, err
	}
	for _, s := range sources {
		srcByID[s.ID] = s.Key
	}
	// 已安装标记：空间内技能资产按 name 命中（键=市场键=技能名）
	installed := map[string]store.Skill{}
	if len(items) > 0 {
		var skills []store.Skill
		names := make([]string, 0, len(items))
		for _, it := range items {
			names = append(names, it.Key)
		}
		if err := c.DB.Where("workspace_id = ? AND name IN ?", workspaceID, names).Find(&skills).Error; err != nil {
			return nil, err
		}
		for _, s := range skills {
			installed[s.Name] = s
		}
	}
	out := make([]MarketSkillView, 0, len(items))
	for i := range items {
		it := items[i]
		v := MarketSkillView{SkillMarketItem: it, SourceKey: srcByID[it.SourceID]}
		if s, ok := installed[it.Key]; ok {
			v.InstalledSkillID = s.ID
			var lv store.SkillVersion
			if err := c.DB.Where("skill_id = ? AND status = ?", s.ID, SkillVerPublished).Order("id DESC").First(&lv).Error; err == nil {
				v.InstalledVersion = lv.Version
			}
		}
		out = append(out, v)
	}
	return out, nil
}

// InstallMarketSkill 安装市场技能：复制内置 zip → 本空间技能资产 v1.0.0 published
// （source=market + sourceName；解包与合规检查与上传同管线）。
func (c *Core) InstallMarketSkill(workspaceID, userID uint, sourceKey, key string) (*store.Skill, error) {
	var src store.SkillMarketSource
	if err := c.DB.Where("`key` = ?", sourceKey).First(&src).Error; err != nil {
		return nil, ErrNotFound
	}
	if src.Type != "builtin" {
		// registry 外部源远期开放（远端拉取 zip；目录协议待评审——§6.3 决议 8）
		return nil, fmt.Errorf("%w: market source %q is not available yet", ErrInvalidForm, sourceKey)
	}
	var item store.SkillMarketItem
	if err := c.DB.Where("source_id = ? AND `key` = ?", src.ID, key).First(&item).Error; err != nil {
		return nil, ErrNotFound
	}
	var cnt int64
	if err := c.DB.Model(&store.Skill{}).Where("workspace_id = ? AND name = ?", workspaceID, item.Key).Count(&cnt).Error; err != nil {
		return nil, err
	}
	if cnt > 0 {
		return nil, fmt.Errorf("%w: skill %q already installed in workspace", ErrConflict, item.Key)
	}
	// 内置 bundle 读取（BundleDir = DATA_ROOT 下解包目录；Tree + ReadFile 汇聚为内存 zip
	// 再走与上传同一管线——解析/合规/落版本 PVC 全同路）。缺失 → 运营数据问题显式报错。
	tree, err := c.FS.Tree(item.BundleDir)
	if err != nil {
		return nil, fmt.Errorf("market bundle missing: %w", err)
	}
	files := map[string][]byte{}
	var collect func(ents []store.FileEntry) error
	collect = func(ents []store.FileEntry) error {
		for _, e := range ents {
			if e.IsDir {
				if err := collect(e.Children); err != nil {
					return err
				}
				continue
			}
			data, err := c.FS.ReadFile(item.BundleDir, e.Path)
			if err != nil {
				return err
			}
			files[e.Path] = data
		}
		return nil
	}
	if err := collect(tree); err != nil {
		return nil, fmt.Errorf("market bundle unreadable: %w", err)
	}
	bundleZip, err := buildZipBytes(files)
	if err != nil {
		return nil, err
	}
	skill, _, err := c.CreateSkillFromZip(workspaceID, userID, key+".zip", bytes.NewReader(bundleZip),
		item.Key, item.DisplayName, item.Description, decodeTags(item.TagsJSON), true)
	if err != nil {
		return nil, err
	}
	// 市场资产特化：source/sourceName 覆盖（CreateSkillFromZip 落的是 zip）
	c.DB.Model(&store.Skill{}).Where("id = ?", skill.ID).
		Updates(map[string]interface{}{"source": "market", "source_name": src.Name})
	c.DB.Model(&store.SkillMarketItem{}).Where("id = ?", item.ID).
		UpdateColumn("downloads", gorm.Expr("downloads + 1"))
	return c.GetSkill(skill.ID)
}

func decodeTags(raw string) []string {
	var tags []string
	_ = json.Unmarshal([]byte(raw), &tags)
	return tags
}

// SkillRefAgentView 技能引用 Agent 视图（详情抽屉「引用 Agent」：名称/状态/包版本/锁定版本）。
type SkillRefAgentView struct {
	ID             uint   `json:"id"`
	DisplayName    string `json:"displayName"`
	K8sName        string `json:"k8sName"`
	Status         string `json:"status"`
	PackageVersion string `json:"packageVersion"`
	// LockedVersion 该 Agent 生成包 frontmatter 里对本技能的版本声明（快照锁定值）
	LockedVersion string `json:"lockedVersion"`
}

// SkillRefAgents 单技能的引用 Agent 列表（详情抽屉「引用 Agent」；按名匹配生成包 frontmatter
// 声明，并回填包版本与锁定版本——只返回实体会让前端「锁定版本」列恒空）。
func (c *Core) SkillRefAgents(workspaceID uint, skillName string) []SkillRefAgentView {
	refs := c.skillRefAgents(workspaceID, skillName)[skillName]
	out := make([]SkillRefAgentView, 0, len(refs))
	for i := range refs {
		v := SkillRefAgentView{ID: refs[i].ID, DisplayName: refs[i].DisplayName,
			K8sName: refs[i].K8sName, Status: refs[i].Status}
		if pkg, err := c.Packages.Get(refs[i].PackageID); err == nil {
			v.PackageVersion = pkg.Version
			var cfg struct {
				Skills []struct {
					Name    string `json:"name"`
					Version string `json:"version"`
				} `json:"skills"`
			}
			if json.Unmarshal([]byte(pkg.ManifestJSON), &cfg) == nil {
				for _, s := range cfg.Skills {
					if s.Name == skillName {
						v.LockedVersion = s.Version
						break
					}
				}
			}
		}
		out = append(out, v)
	}
	return out
}
