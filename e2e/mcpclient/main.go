// E2E 场景 C：通过 MCP 协议走完「上传→发布→注册→改env→重发布→下线→删除」主链路。
// 用法: go run ./mcpclient -base http://localhost:30080/mcp -zip ../fixtures/demo-agent-v1.zip
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

var pass, fail int

func check(name string, cond bool, detail string) {
	if cond {
		pass++
		fmt.Printf("  \033[32mPASS\033[0m %s\n", name)
	} else {
		fail++
		fmt.Printf("  \033[31mFAIL\033[0m %s (%s)\n", name, detail)
	}
}

type tool struct{ cs *mcp.ClientSession }

// call 返回 isErr / 原始文本 / 解析后的 JSON。
func (t tool) call(name string, args map[string]any) (bool, map[string]any, string) {
	res, err := t.cs.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		return true, nil, "protocol error: " + err.Error()
	}
	if len(res.Content) == 0 {
		return res.IsError, nil, ""
	}
	text := res.Content[0].(*mcp.TextContent).Text
	var m map[string]any
	if err := json.Unmarshal([]byte(text), &m); err != nil {
		// 数组输出场景（如 list_packages）：包装为 {"items": [...]}
		var list []map[string]any
		if err2 := json.Unmarshal([]byte(text), &list); err2 == nil {
			m = map[string]any{"items": list}
		}
	}
	return res.IsError, m, text
}

func (t tool) waitForStatus(serviceID float64, want string, timeout time.Duration) (string, bool) {
	deadline := time.Now().Add(timeout)
	last := ""
	for time.Now().Before(deadline) {
		isErr, out, _ := t.call("get_service_status", map[string]any{"serviceId": serviceID})
		if !isErr {
			last, _ = out["status"].(string)
			if last == want {
				return last, true
			}
			if last == "deploy_failed" || last == "error" {
				return last, false
			}
		}
		time.Sleep(5 * time.Second)
	}
	return last, false
}

func main() {
	base := flag.String("base", "http://localhost:30080/mcp", "platform MCP endpoint")
	zipPath := flag.String("zip", "../fixtures/demo-agent-v1.zip", "oaf package zip")
	flag.Parse()

	ctx := context.Background()
	client := mcp.NewClient(&mcp.Implementation{Name: "e2e-mcpclient", Version: "1"}, nil)
	cs, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: *base}, nil)
	if err != nil {
		fmt.Printf("connect MCP failed: %v\n", err)
		os.Exit(1)
	}
	defer cs.Close()
	t := tool{cs}

	fmt.Println("== 场景 C：MCP 工具全流程 ==")

	// C1 initialize 已由 Connect 完成；列出工具验证服务端清单
	listRes, err := cs.ListTools(ctx, nil)
	if err != nil {
		fmt.Printf("list_tools failed: %v\n", err)
		os.Exit(1)
	}
	names := map[string]bool{}
	for _, tl := range listRes.Tools {
		names[tl.Name] = true
	}
	for _, want := range []string{"upload_package", "list_packages", "get_package_detail",
		"publish_service", "get_service_status", "update_service_env", "republish_service",
		"unpublish_service", "register_service", "delete_service"} {
		check("C1 工具存在 "+want, names[want], "")
	}
	// C1.9 list_images：镜像列表按环境注入，发布前可探测
	isErrImg, imgsOut, rawImg := t.call("list_images", map[string]any{})
	imgs, _ := imgsOut["images"].([]any)
	check("C1.9 list_images 非空", !isErrImg && len(imgs) > 0, rawImg)

	// C2 上传包
	zipBytes, err := os.ReadFile(*zipPath)
	if err != nil {
		fmt.Println("read zip:", err)
		os.Exit(1)
	}
	isErr, out, raw := t.call("upload_package", map[string]any{
		"filename": "demo.zip", "content_base64": base64.StdEncoding.EncodeToString(zipBytes)})
	pkgID, _ := out["packageId"].(float64)
	check("C2 upload_package", !isErr && pkgID > 0, raw)
	if isErr {
		os.Exit(1)
	}
	// C2.1/C2.2 包查询工具与上传记录一致
	isErr, out, raw = t.call("list_packages", map[string]any{"keyword": "e2e"})
	found := false
	if items, _ := out["items"].([]map[string]any); !isErr {
		for _, it := range items {
			if it["packageId"].(float64) == pkgID {
				found = true
			}
		}
	}
	check("C2.1 list_packages 含新包", found, raw)
	isErr, out, raw = t.call("get_package_detail", map[string]any{"packageId": pkgID})
	agentsMd, _ := out["agentsMd"].(string)
	check("C2.2 get_package_detail 返回 AGENTS.md", !isErr && strings.Contains(agentsMd, "E2E"), raw)

	// C3 发布（注入运行时 env，从环境读取 LLM 配置）
	env := map[string]any{}
	for k, v := range map[string]string{
		"LLM_API_KEY": os.Getenv("LLM_API_KEY"), "LLM_MODEL_ID": os.Getenv("LLM_MODEL"),
		"LLM_BASE_URL": os.Getenv("LLM_ENDPOINT"),
	} {
		if v != "" {
			env[k] = v
		}
	}
	env["CHECKPOINT_JDBC_URL"] = "jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
	env["CHECKPOINT_USERNAME"] = "oaf"
	env["CHECKPOINT_PASSWORD"] = "OafPlatform2026"

	// 镜像不写死：list_images 动态选取（优先节点内 registry 名义）
	isErr, out, raw = t.call("list_images", map[string]any{})
	image := ""
	if items, _ := out["images"].([]any); !isErr {
		for _, it := range items {
			name, _ := it.(map[string]any)["image"].(string)
			if strings.Contains(name, ":5001") {
				image = name
				break
			}
			if image == "" {
				image = name
			}
		}
	}
	if image == "" {
		image = "agent-framework:latest"
	}

	isErr, out, raw = t.call("publish_service", map[string]any{
		"packageId": pkgID, "image": image, "env": env})
	svcID, _ := out["serviceId"].(float64)
	status, _ := out["status"].(string)
	check("C3 publish_service 立即返回 deploying", !isErr && svcID > 0 && status == "deploying", raw)

	st, ok := t.waitForStatus(svcID, "running", 6*time.Minute)
	check("C4 状态到 running", ok && st == "running", st)

	isErr, out, _ = t.call("get_service_status", map[string]any{"serviceId": svcID})
	regName, _ := out["registeredName"].(string)
	cardOK := !isErr && regName == "E2E Demo Agent"
	check("C5 A2A 注册信息入库", cardOK, fmt.Sprint(out["registeredName"]))

	// C6 更新 env（全量语义：运行时 env + 覆盖项）
	env["LOG_LEVEL"] = "warn"
	isErr, out, raw = t.call("update_service_env", map[string]any{"serviceId": svcID, "env": env})
	check("C6 update_service_env 受理", !isErr && out["status"] == "deploying", raw)
	st, ok = t.waitForStatus(svcID, "running", 6*time.Minute)
	check("C7 env 更新后回到 running", ok && st == "running", st)

	// C8 重发布（沿用当前包）
	isErr, out, raw = t.call("republish_service", map[string]any{"serviceId": svcID})
	check("C8 republish_service 受理", !isErr && out["status"] == "deploying", raw)
	st, ok = t.waitForStatus(svcID, "running", 6*time.Minute)
	check("C9 republish 后 running", ok && st == "running", st)

	// C9.1 手动重注册（running 状态下重拉 agent-card）
	isErr, out, raw = t.call("register_service", map[string]any{"serviceId": svcID})
	check("C9.1 register_service 保持 running", !isErr && out["status"] == "running", raw)

	// C10 下线/上线/删除
	isErr, _, raw = t.call("unpublish_service", map[string]any{"serviceId": svcID})
	check("C10 unpublish", !isErr, raw)
	isErr, out, _ = t.call("get_service_status", map[string]any{"serviceId": svcID})
	st, _ = out["status"].(string)
	check("C11 状态 stopped", !isErr && st == "stopped", st)
	// C12 两步确认删除：先取 k8sName，再带 confirm_k8s_name 执行
	isErr, out, _ = t.call("get_service_status", map[string]any{"serviceId": svcID})
	k8sName, _ := out["k8sName"].(string)
	isErrNoConfirm, _, rawNoConfirm := t.call("delete_service", map[string]any{"serviceId": svcID})
	check("C12a 未确认删除被拒", isErrNoConfirm && strings.Contains(rawNoConfirm, "confirmation required"), rawNoConfirm)
	isErr, _, raw = t.call("delete_service",
		map[string]any{"serviceId": svcID, "confirm_k8s_name": k8sName})
	check("C12 确认后 delete", !isErr, raw)
	isErr, _, raw = t.call("get_service_status", map[string]any{"serviceId": svcID})
	check("C13 删除后查询报错", isErr, strings.TrimSpace(raw))

	// 包删除仅 REST 提供（DELETE /packages/:id），由场景 A/B 的清理段兜底回收
	fmt.Printf("\nPASS: %d  FAIL: %d\n", pass, fail)
	if fail > 0 {
		os.Exit(1)
	}
}
