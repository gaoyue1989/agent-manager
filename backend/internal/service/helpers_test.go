package service

import (
	"archive/zip"
	"bytes"
	"io"
	"strings"
)

// buildTestZip 构造内存 zip。
func buildTestZip(files map[string]string) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		w, _ := zw.Create(name)
		w.Write([]byte(content))
	}
	zw.Close()
	return buf.Bytes()
}

func bytesReader(b []byte) io.Reader { return bytes.NewReader(b) }

// replaceSlug 替换 AGENTS.md 中的 slug/vendorKey/agentKey 以制造不同包。
func replaceSlug(md, suffix string) string {
	parts := strings.SplitN(slugParts(suffix), "|", 2)
	vendor, agent := parts[0], parts[1]
	md = strings.ReplaceAll(md, `vendorKey: acme`, "vendorKey: "+vendor)
	md = strings.ReplaceAll(md, `agentKey: demo`, "agentKey: "+agent)
	md = strings.ReplaceAll(md, `slug: acme/demo`, "slug: "+vendor+"/"+agent)
	return md
}

func slugParts(suffix string) string {
	switch suffix {
	case "2":
		return "acme|demo2"
	case "3":
		return "acme|demo3"
	default:
		return strings.SplitN(suffix, "/", 2)[0] + "|" + strings.SplitN(suffix, "/", 2)[0]
	}
}
