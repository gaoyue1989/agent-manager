package oaf

import (
	"fmt"
	"regexp"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// 发布助手对话式打包的工具级校验（check_oaf_package / create_oaf_zip 共用）。
// 与 Validate()（平台上传门禁）的差异是有意保留的：正则与检查项平移自原
// agent-framework OafPackageTools（name 也校验 kebab；semver 允许 prerelease/
// build 后缀），保证助手侧行为在迁移前后不变。两套正则的已知偏差见
// docs/design/oaf-tools-extraction-design.md。
var (
	checkKebabRe  = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)
	checkSemverRe = regexp.MustCompile(`^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$`)
)

// CheckAgentsMDRequired 平台 Validate 强制的 7 个必填字段。
var CheckAgentsMDRequired = []string{"name", "vendorKey", "agentKey", "version", "description", "author", "license"}

// CheckResult check_oaf_package 的聚合输出（契约平移自原 Java 工具）。
type CheckResult struct {
	Valid   bool     `json:"valid"`
	Note    string   `json:"note"`
	Missing []string `json:"missing"`
	Invalid []string `json:"invalid"`
	Present []string `json:"present"`
}

// CheckAgentsMD 聚合校验 AGENTS.md frontmatter：必填字段缺失清单 + 格式非法清单。
// 字段值经 ParseOAF（真 YAML 解析）取得，键列表独立解析 frontmatter 保持与旧契约一致。
func CheckAgentsMD(content string) CheckResult {
	res := CheckResult{Missing: []string{}, Invalid: []string{}, Present: []string{}}
	finish := func() CheckResult {
		res.Valid = len(res.Missing) == 0 && len(res.Invalid) == 0
		if res.Valid {
			res.Note = "OK"
		} else {
			res.Note = "fix the issues and re-check"
		}
		return res
	}
	if strings.TrimSpace(content) == "" {
		res.Missing = append(res.Missing, "AGENTS.md 内容为空")
		res.Note = "agents_md is empty"
		return res
	}
	cfg, perr := ParseOAF(content)
	keys, kerr := frontmatterKeys(content)
	if kerr != nil {
		res.Invalid = append(res.Invalid, fmt.Sprintf("frontmatter 缺失或无法解析（必须以 --- 开头并以 --- 结束声明头部字段）: %v", kerr))
		return finish()
	}
	res.Present = keys

	fields := map[string]string{
		"name": cfg.Name, "vendorKey": cfg.VendorKey, "agentKey": cfg.AgentKey,
		"version": cfg.Version, "description": cfg.Description,
		"author": cfg.Author, "license": cfg.License,
	}
	if perr != nil {
		// frontmatter 结构合法但字段类型异常（如 name 写成数字）：按必填缺失处理并提示
		res.Invalid = append(res.Invalid, fmt.Sprintf("frontmatter 字段类型异常: %v", perr))
	}
	for _, f := range CheckAgentsMDRequired {
		if strings.TrimSpace(fields[f]) == "" {
			res.Missing = append(res.Missing, f)
		}
	}
	for _, f := range []string{"name", "vendorKey", "agentKey"} {
		if v := fields[f]; v != "" && !checkKebabRe.MatchString(v) {
			res.Invalid = append(res.Invalid, f+" 须为 kebab-case（小写字母/数字/连字符）")
		}
	}
	if v := fields["version"]; v != "" && !checkSemverRe.MatchString(v) {
		res.Invalid = append(res.Invalid, "version 须为 semver（如 1.0.0）")
	}
	return finish()
}

// frontmatterKeys 提取 frontmatter 顶层键（保持字母序，替代原 Java 的插入序）。
func frontmatterKeys(content string) ([]string, error) {
	parts := strings.SplitN(content, "---", 3)
	// frontmatter 必须从文件头开始：SplitN 的第一段应为空
	if len(parts) < 3 || strings.TrimSpace(parts[0]) != "" {
		return nil, fmt.Errorf("frontmatter not found")
	}
	var node yaml.Node
	if err := yaml.Unmarshal([]byte(strings.TrimSpace(parts[1])), &node); err != nil {
		return nil, err
	}
	if len(node.Content) == 0 || node.Content[0].Kind != yaml.MappingNode {
		return nil, fmt.Errorf("frontmatter is not a mapping")
	}
	// mapping 的 Content 是 [key, value, key, value, ...]，只取偶数位
	mapping := node.Content[0]
	keys := []string{}
	for i := 0; i+1 < len(mapping.Content); i += 2 {
		if k := mapping.Content[i]; k.Kind == yaml.ScalarNode {
			keys = append(keys, k.Value)
		}
	}
	sort.Strings(keys)
	return keys, nil
}
