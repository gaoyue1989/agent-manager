//go:build !api

package k8s

import "fmt"

// newK8sAPIClient stub: K8S API 模式需要添加 client-go 依赖并构建时指定 -tags api
// 启用方式:
//   1. go get k8s.io/client-go@latest && go mod tidy
//   2. go build -tags api ./cmd/server
//   3. export K8S_CLIENT_MODE=api
func newK8sAPIClient(kubeconfigPath, namespace string) (K8sClient, error) {
	return nil, fmt.Errorf("K8s API client not available: rebuild with -tags api and ensure k8s.io/client-go dependencies installed (see api_client.go)")
}
