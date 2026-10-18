package service

import (
	"errors"
	"strings"
	"testing"
)

var errSkillRead = errors.New("not found")

// TestCheckSkillDirs issue #163：skills/ 目录合规检查——对照黑盒回归构造的异常包
// 逐类断言；合法技能不产出条目。
func TestCheckSkillDirs(t *testing.T) {
	files := map[string]string{
		"AGENTS.md": "not a skill",
		// 合法：不产出
		"skills/webapp-testing/SKILL.md": "---\nname: webapp-testing\ndescription: Toolkit for testing web apps\n---\n\n# body",
		// 无 SKILL.md（仅 README / 仅二进制 / 空 .keep / SKILL.md 在二级目录）
		"skills/broken/README.md":      "readme",
		"skills/binary/logo.bin":       "\x00\x01\x02",
		"skills/empty/.keep":           "",
		"skills/nested/child/SKILL.md": "---\nname: nested\ndescription: d\n---\nbody",
		// 无 frontmatter / 缺 name / name 与目录不一致
		"skills/nofm/SKILL.md":     "# no frontmatter\nbody only\n---\nrule\n",
		"skills/noname/SKILL.md":   "---\ndescription: only desc\n---\n",
		"skills/dup-name/SKILL.md": "---\nname: other-name\ndescription: d\n---\n",
		// 非法名称 / 连续连字符 / 超长名称 / 描述超长
		"skills/My Skill!/SKILL.md":                        "---\nname: my-skill\ndescription: d\n---\n",
		"skills/a--b/SKILL.md":                             "---\nname: a--b\ndescription: d\n---\n",
		"skills/" + strings.Repeat("l", 200) + "/SKILL.md": "---\nname: x\ndescription: d\n---\n",
		"skills/desc-long/SKILL.md":                        "---\nname: desc-long\ndescription: " + strings.Repeat("d", 1025) + "\n---\n",
	}
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	issues := checkSkillDirs(func(name string) ([]byte, error) {
		if v, ok := files[name]; ok {
			return []byte(v), nil
		}
		return nil, errSkillRead
	}, paths)

	byName := map[string][]string{}
	for _, it := range issues {
		byName[it.Name] = it.Issues
	}
	if _, ok := byName["webapp-testing"]; ok {
		t.Fatalf("valid skill must not be flagged: %+v", byName["webapp-testing"])
	}
	for _, name := range []string{"broken", "binary", "empty", "nested"} {
		if len(byName[name]) != 1 || byName[name][0] != "missing skills/"+name+"/SKILL.md" {
			t.Fatalf("%s: want single missing-SKILL.md issue, got %v", name, byName[name])
		}
	}
	if len(byName["nofm"]) != 1 || byName["nofm"][0] != "SKILL.md has no frontmatter" {
		t.Fatalf("nofm: %v", byName["nofm"])
	}
	if len(byName["noname"]) != 1 || byName["noname"][0] != "SKILL.md frontmatter missing name" {
		t.Fatalf("noname: %v", byName["noname"])
	}
	if len(byName["dup-name"]) != 1 || byName["dup-name"][0] != `SKILL.md frontmatter name "other-name" does not match directory name` {
		t.Fatalf("dup-name: %v", byName["dup-name"])
	}
	if len(byName["My Skill!"]) != 2 || !strings.Contains(byName["My Skill!"][0], "invalid characters") {
		t.Fatalf("My Skill!: %v", byName["My Skill!"]) // 名称非法 + frontmatter name 与目录名不一致
	}
	if len(byName["a--b"]) != 1 || byName["a--b"][0] != "consecutive hyphens" {
		t.Fatalf("a--b: %v", byName["a--b"])
	}
	if got := byName[strings.Repeat("l", 200)]; len(got) != 2 || !strings.Contains(got[0], "name too long") {
		t.Fatalf("long name: %v", got) // 超长 + frontmatter name 与目录名不一致
	}
	if len(byName["desc-long"]) != 1 || !strings.Contains(byName["desc-long"][0], "exceeds 1024") {
		t.Fatalf("desc-long: %v", byName["desc-long"])
	}
}

// TestParseSkillFrontmatter 无 frontmatter 的正文水平线不误判（必须 --- 开头才认）。
func TestParseSkillFrontmatter(t *testing.T) {
	if _, _, ok := parseSkillFrontmatter("text\n---\nrule\n"); ok {
		t.Fatal("body horizontal rule must not count as frontmatter")
	}
	name, desc, ok := parseSkillFrontmatter("---\nname: a\ndescription: b\n---\nbody\n---\nmore")
	if !ok || name != "a" || desc != "b" {
		t.Fatalf("got %q %q ok=%v", name, desc, ok)
	}
}
