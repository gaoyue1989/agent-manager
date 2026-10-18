// OAF 包 skills/ 目录合规检查（issue #163）：包上传登记（registerZipCore 的 warnings）
// 与空间技能清单（SpaceSkills 的条目标注）共用，宽松模式只产提示不阻断上传/发布。
// 规则与业务运行时 OafConfigLoader 的降级验证同口径：名称 ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$
// 且 ≤64 字符、无连续连字符；描述 1-1024 字符；SKILL.md 必须位于 skills/<name>/ 一级目录。
package service

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"

	"gopkg.in/yaml.v3"
)

var skillNameRe = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`)

// SkillDirIssue 单个技能目录的不合规项集合（Issues 空 = 合规，不产出该结构）。
type SkillDirIssue struct {
	Name   string   `json:"name"`
	Issues []string `json:"issues"`
}

// checkSkillDirs 校验包内 skills/ 一级目录：
//   - 缺 skills/<name>/SKILL.md（空目录 / 仅 README / 仅二进制 / SKILL.md 在二级目录均算）
//   - SKILL.md 无 frontmatter / 缺 name / 缺 description / 描述超 1024 字符
//   - frontmatter name 与目录名不一致
//   - 名称非法（字符集 / 超 64 字符 / 连续连字符）
//
// read 为包内文件读取函数（参数是包相对路径），paths 是包内全部文件相对路径——
// zip 条目（上传登记）与 PVC 文件树（SpaceSkills）两种数据源复用。
func checkSkillDirs(read func(string) ([]byte, error), paths []string) []SkillDirIssue {
	dirs := map[string]bool{}       // skills/ 下一级目录 → 存在（目录即技能名）
	hasSkillMD := map[string]bool{} // 目录下直接持有 SKILL.md
	for _, raw := range paths {
		p := strings.Trim(raw, "/")
		if !strings.HasPrefix(p, "skills/") {
			continue
		}
		rest := strings.TrimPrefix(p, "skills/")
		if rest == "" {
			continue
		}
		parts := strings.Split(rest, "/")
		name := parts[0]
		if name == "" || name == "SKILL.md" {
			continue // skills/SKILL.md 散文件不是技能目录
		}
		dirs[name] = true
		if len(parts) == 2 && parts[1] == "SKILL.md" {
			hasSkillMD[name] = true
		}
	}
	names := make([]string, 0, len(dirs))
	for n := range dirs {
		names = append(names, n)
	}
	sort.Strings(names)

	var out []SkillDirIssue
	for _, name := range names {
		var issues []string
		if utf8.RuneCountInString(name) > 64 {
			issues = append(issues, fmt.Sprintf("name too long (max 64 chars, got %d)", utf8.RuneCountInString(name)))
		}
		if !skillNameRe.MatchString(name) {
			issues = append(issues, "invalid characters (expected: lowercase letters, numbers, hyphens; no leading/trailing hyphen)")
		}
		if strings.Contains(name, "--") {
			issues = append(issues, "consecutive hyphens")
		}
		if !hasSkillMD[name] {
			issues = append(issues, "missing skills/"+name+"/SKILL.md")
		} else if data, err := read("skills/" + name + "/SKILL.md"); err != nil {
			issues = append(issues, "SKILL.md unreadable: "+err.Error())
		} else {
			fmName, fmDesc, ok := parseSkillFrontmatter(string(data))
			if !ok {
				issues = append(issues, "SKILL.md has no frontmatter")
			} else {
				if fmName == "" {
					issues = append(issues, "SKILL.md frontmatter missing name")
				} else if fmName != name {
					issues = append(issues, fmt.Sprintf("SKILL.md frontmatter name %q does not match directory name", fmName))
				}
				if fmDesc == "" {
					issues = append(issues, "SKILL.md frontmatter missing description")
				} else if n := utf8.RuneCountInString(fmDesc); n > 1024 {
					issues = append(issues, fmt.Sprintf("SKILL.md description exceeds 1024 chars (%d)", n))
				}
			}
		}
		if len(issues) > 0 {
			out = append(out, SkillDirIssue{Name: name, Issues: issues})
		}
	}
	return out
}

// parseSkillFrontmatter 解析 SKILL.md 的 frontmatter name/description。
// ok=false 表示无 frontmatter 段——文件必须以 --- 开头才认，避免正文水平线误判。
func parseSkillFrontmatter(content string) (name, desc string, ok bool) {
	s := strings.TrimPrefix(content, "\ufeff") // BOM
	if !strings.HasPrefix(s, "---") {
		return "", "", false
	}
	parts := strings.SplitN(s, "---", 3)
	if len(parts) < 3 {
		return "", "", false
	}
	var fm struct {
		Name        string `yaml:"name"`
		Description string `yaml:"description"`
	}
	if err := yaml.Unmarshal([]byte(parts[1]), &fm); err != nil {
		return "", "", false
	}
	return fm.Name, fm.Description, true
}
