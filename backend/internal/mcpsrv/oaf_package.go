package mcpsrv

// OAF 包生成工具（发布助手对话式打包）：check_oaf_package / create_oaf_zip。
// OAF 打包语义归平台（原先硬编码在 agent-framework OafPackageTools，2026-09 迁移，
// 设计见 docs/design/oaf-tools-extraction-design.md）：校验复用 internal/oaf 权威实现，
// 组包产物直接走 PackageService.Upload 管线入库落盘，LLM 全程零 base64 搬运。

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"path"
	"strings"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"agent-manager/backend/internal/oaf"
	"agent-manager/backend/internal/service"
)

// 与平台 Upload 管线一致的 zip 上限（InspectZip 会再校验一次，这里前置给出清晰错误）
const oafZipMaxBytes = 20 << 20

// registerOafTools 注册 OAF 包生成工具（配置包区段，registerTools 调用）。
func registerOafTools(s *mcp.Server, core *service.Core) {
	mcp.AddTool(s, &mcp.Tool{
		Name: "check_oaf_package",
		Description: "Validate an OAF package AGENTS.md frontmatter BEFORE packaging/publishing. " +
			"Pass the full AGENTS.md text you just wrote. Returns JSON {valid, missing, invalid, present}: " +
			"valid=true only when all required fields are present and well-formed " +
			"(name/vendorKey/agentKey kebab-case, version semver). " +
			"MUST be called before create_oaf_zip to avoid producing an unpublishable package.",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in CheckOafIn) (*mcp.CallToolResult, JSONOut, error) {
		return okResult(oaf.CheckAgentsMD(in.AgentsMD))
	})

	mcp.AddTool(s, &mcp.Tool{
		Name: "create_oaf_zip",
		Description: "Assemble an OAF deployment package zip from an AGENTS.md (and optional extra files), " +
			"validate it, and register it as a platform package in ONE step. " +
			"Returns {packageId, slug, version, warnings, file_name, size, download_url}. " +
			"After success: 1) call the agent-side present_url tool with file_name and download_url " +
			"so the user gets a download card; 2) publish with publish_service(packageId) — " +
			"do NOT call upload_package again for this package. " +
			"Rejects if frontmatter validation fails (same rules as check_oaf_package).",
	}, func(ctx context.Context, req *mcp.CallToolRequest, in CreateOafZipIn) (*mcp.CallToolResult, JSONOut, error) {
		if strings.TrimSpace(in.AgentsMD) == "" {
			return errResult("agents_md is empty")
		}
		if chk := oaf.CheckAgentsMD(in.AgentsMD); !chk.Valid {
			b, _ := json.Marshal(chk)
			return errResult("AGENTS.md 校验未通过: " + string(b))
		}
		name := normalizeZipName(in.PackageName)
		zipBytes, err := buildOafZip(in.AgentsMD, in.ExtraFiles)
		if err != nil {
			return errResult("zip build failed: " + err.Error())
		}
		if len(zipBytes) == 0 {
			return errResult("zip build produced empty archive")
		}
		if len(zipBytes) > oafZipMaxBytes {
			return errResult(fmt.Sprintf("package exceeds 20MB limit (%d bytes)", len(zipBytes)))
		}
		rec, err := core.Packages.Upload(name, bytes.NewReader(zipBytes))
		if err != nil {
			return errResult(err.Error())
		}
		var warnings json.RawMessage = json.RawMessage(rec.WarningsJSON)
		return okResult(map[string]any{
			"packageId": rec.ID, "name": rec.Name, "slug": rec.Slug, "version": rec.Version,
			"warnings": warnings, "fileCount": rec.FileCount,
			"file_name": name, "size": len(zipBytes),
			"download_url": core.PackageDownloadURL(rec.ID),
			"hint":         "deliver via agent present_url(file_name, url=download_url), then publish_service(packageId)",
		})
	})
}

// ---- 输入类型 ----

type CheckOafIn struct {
	AgentsMD string `json:"agents_md" jsonschema:"完整 AGENTS.md 内容（frontmatter + 正文）"`
}
type ExtraFileIn struct {
	Path    string `json:"path" jsonschema:"包内相对路径，如 skills/help/SKILL.md"`
	Content string `json:"content" jsonschema:"文件文本内容"`
}
type CreateOafZipIn struct {
	PackageName string        `json:"package_name,omitempty" jsonschema:"zip 文件名，如 weather-agent.zip"`
	AgentsMD    string        `json:"agents_md" jsonschema:"完整 AGENTS.md 内容（frontmatter + 正文）"`
	ExtraFiles  []ExtraFileIn `json:"extra_files,omitempty" jsonschema:"附加文件列表（可选）"`
}

// ---- 组包辅助 ----

// normalizeZipName 规范化 zip 文件名：缺省 oaf-package.zip、只取文件名部分、补 .zip 后缀。
func normalizeZipName(name string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		name = "oaf-package.zip"
	}
	name = path.Base(strings.ReplaceAll(name, "\\", "/"))
	if !strings.HasSuffix(strings.ToLower(name), ".zip") {
		name += ".zip"
	}
	return name
}

// buildOafZip 组装 OAF 包 zip：AGENTS.md 固定首项，extra_files 依序写入。
// 路径安全：拒绝空路径/绝对路径/`..` 逃逸/非规范化路径/重复条目（含再次写入 AGENTS.md），
// 保证产物能通过平台 Upload 的 zip 安全校验（zip-slip/符号链接/最低 0644 权限）。
func buildOafZip(agentsMD string, extra []ExtraFileIn) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	write := func(p, content string) error {
		hdr := &zip.FileHeader{Name: p, Method: zip.Deflate}
		hdr.SetMode(0o644)
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			return err
		}
		_, err = w.Write([]byte(content))
		return err
	}
	if err := write("AGENTS.md", agentsMD); err != nil {
		return nil, err
	}
	seen := map[string]bool{"AGENTS.md": true}
	for _, f := range extra {
		p, err := cleanEntryPath(f.Path)
		if err != nil {
			return nil, err
		}
		if seen[p] {
			return nil, fmt.Errorf("duplicate entry: %s", p)
		}
		seen[p] = true
		if err := write(p, f.Content); err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// cleanEntryPath 校验包内路径：非空、相对、规范化、无 `..` 逃逸。
func cleanEntryPath(p string) (string, error) {
	p = strings.TrimSpace(strings.ReplaceAll(p, "\\", "/"))
	if p == "" {
		return "", fmt.Errorf("empty path")
	}
	if strings.HasPrefix(p, "/") {
		return "", fmt.Errorf("absolute path not allowed: %s", p)
	}
	c := path.Clean(p)
	if c != p || c == ".." || strings.HasPrefix(c, "../") {
		return "", fmt.Errorf("invalid path (must be normalized, no .. escape): %s", p)
	}
	return p, nil
}
