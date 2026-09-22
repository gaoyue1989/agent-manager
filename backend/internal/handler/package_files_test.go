package handler

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// newReq 构造测试请求。
func newReq(method, path string) *http.Request {
	return httptest.NewRequest(method, path, nil)
}

// serve 执行请求并返回 recorder。
func serve(r *gin.Engine, req *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// TestPackageFilePreviewAPI 单文件预览：文本 / 二进制 / 路径穿越 / 404。
func TestPackageFilePreviewAPI(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := int(rec["id"].(float64))

	// 文本文件预览
	w, out := doJSON(t, r, http.MethodGet, "/api/v1/packages", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("list: %d", w.Code)
	}
	_ = out
	w, out = doJSON(t, r, http.MethodGet, apiPath(pkgID, "files", "path=AGENTS.md"), nil)
	if w.Code != http.StatusOK {
		t.Fatalf("file preview: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	if data["binary"] != false || !strings.Contains(data["content"].(string), "vendorKey: acme") {
		t.Fatalf("text preview wrong: %+v", data)
	}

	// 路径穿越 → 400
	w, _ = doJSON(t, r, http.MethodGet, apiPath(pkgID, "files", "path=../../etc/passwd"), nil)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("zip-slip should 400, got %d", w.Code)
	}
	// 不存在的文件 → 500 内含读错误（fs NotFound）→ 当前映射为 500；至少不能 200
	w, _ = doJSON(t, r, http.MethodGet, apiPath(pkgID, "files", "path=no/such.txt"), nil)
	if w.Code == http.StatusOK {
		t.Fatal("missing file should not 200")
	}
	// 缺 path 参数 → 400
	w, _ = doJSON(t, r, http.MethodGet, apiPath(pkgID, "files", ""), nil)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("missing path should 400, got %d", w.Code)
	}
	// 包不存在 → 404
	w, _ = doJSON(t, r, http.MethodGet, apiPath(999, "files", "path=AGENTS.md"), nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("missing package should 404, got %d", w.Code)
	}
}

// TestPackageDownloadAPI 整包下载与单文件下载。
func TestPackageDownloadAPI(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := int(rec["id"].(float64))

	req := newReq(http.MethodGet, apiPath(pkgID, "download", ""))
	w := serve(r, req)
	if w.Code != http.StatusOK {
		t.Fatalf("package download: %d", w.Code)
	}
	if ct := w.Header().Get("Content-Disposition"); !strings.Contains(ct, ".zip") {
		t.Fatalf("content-disposition: %q", ct)
	}
	if len(w.Body.Bytes()) < 50 {
		t.Fatalf("zip too small: %d bytes", len(w.Body.Bytes()))
	}

	// 单文件下载
	w = serve(r, newReq(http.MethodGet, apiPath(pkgID, "files/download", "path=AGENTS.md")))
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "vendorKey") {
		t.Fatalf("file download: %d %s", w.Code, w.Body.String())
	}
	// 非法路径
	w = serve(r, newReq(http.MethodGet, apiPath(pkgID, "files/download", "path=../x")))
	if w.Code == http.StatusOK {
		t.Fatal("zip-slip download should not 200")
	}
}

// TestPackageCreateVersionAPI 编辑生成新版本主链路 + 异常映射。
func TestPackageCreateVersionAPI(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	rec := uploadZip(t, r)
	pkgID := int(rec["id"].(float64))

	// 正常生成新版本
	w, out := doJSON(t, r, http.MethodPost, versionPath(pkgID), map[string]interface{}{
		"upserts": []map[string]string{{"path": "AGENTS.md", "content": replacedMD()}},
		"deletes": []string{"README.md"},
	})
	if w.Code != http.StatusOK {
		t.Fatalf("create version: %d %s", w.Code, w.Body.String())
	}
	data := out["data"].(map[string]interface{})
	newPkg := data["package"].(map[string]interface{})
	if newPkg["version"] != "1.1.0" || newPkg["sourcePackageId"].(float64) != float64(pkgID) {
		t.Fatalf("new pkg wrong: %+v", newPkg)
	}

	// 无有效变更 → 400
	w, _ = doJSON(t, r, http.MethodPost, versionPath(pkgID), map[string]interface{}{
		"deletes": []string{"not-exist.txt"},
	})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("no-change should 400, got %d", w.Code)
	}
	// 删除 AGENTS.md → 400
	w, _ = doJSON(t, r, http.MethodPost, versionPath(pkgID), map[string]interface{}{
		"deletes": []string{"AGENTS.md"},
	})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("delete AGENTS.md should 400, got %d", w.Code)
	}
	// 乐观锁冲突 → 409
	w, _ = doJSON(t, r, http.MethodPost, versionPath(pkgID), map[string]interface{}{
		"upserts":              []map[string]string{{"path": "AGENTS.md", "content": replacedMD()}},
		"expectedBaseChecksum": "deadbeef",
	})
	if w.Code != http.StatusConflict {
		t.Fatalf("checksum mismatch should 409, got %d", w.Code)
	}
	// 基础包未被改动（copy-on-write）
	w, out = doJSON(t, r, http.MethodGet, packageDetailPath(pkgID), nil)
	if w.Code != http.StatusOK {
		t.Fatalf("base detail: %d", w.Code)
	}
	d := out["data"].(map[string]interface{})
	if !strings.Contains(d["agentsMd"].(string), "version: 1.0.0") {
		t.Fatal("base package was mutated")
	}
}

// TestPackageListSlugFilter slug 过滤参数。
func TestPackageListSlugFilter(t *testing.T) {
	r, _, _, done := newTestServer(t)
	defer done()
	uploadZip(t, r)
	w, out := doJSON(t, r, http.MethodGet, "/api/v1/packages?slug=acme/demo", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("list: %d", w.Code)
	}
	list := out["data"].([]interface{})
	if len(list) != 1 {
		t.Fatalf("slug filter: %d", len(list))
	}
	w, out = doJSON(t, r, http.MethodGet, "/api/v1/packages?slug=no-such", nil)
	list = out["data"].([]interface{})
	if len(list) != 0 {
		t.Fatalf("slug filter miss: %d", len(list))
	}
}

// ---- 小工具 ----

func replacedMD() string {
	return strings.Replace(agentsMD, "version: 1.0.0", "version: 1.1.0", 1)
}

func apiPath(pkgID int, action, query string) string {
	p := "/api/v1/packages/" + strconv.Itoa(pkgID) + "/" + action
	if query != "" {
		p += "?" + query
	}
	return p
}

func versionPath(pkgID int) string {
	return "/api/v1/packages/" + strconv.Itoa(pkgID) + "/versions"
}

func packageDetailPath(pkgID int) string {
	return "/api/v1/packages/" + strconv.Itoa(pkgID)
}
