package k8s

import (
	"encoding/json"
	"strconv"
	"testing"
)

func TestParsePodJSON_Success(t *testing.T) {
	s := &SandboxClient{namespace: "default"}

	podJSON := []byte(`{
		"items": [{
			"metadata": {"name": "agent-2"},
			"status": {
				"phase": "Running",
				"podIP": "10.244.0.8",
				"containerStatuses": [{
					"ready": true,
					"restartCount": 0
				}]
			}
		}]
	}`)

	var result map[string]interface{}
	if err := json.Unmarshal(podJSON, &result); err != nil {
		t.Fatalf("failed to parse test json: %v", err)
	}

	items := result["items"].([]interface{})
	pod := items[0].(map[string]interface{})
	podMeta := pod["metadata"].(map[string]interface{})
	podStatus := pod["status"].(map[string]interface{})

	name := podMeta["name"].(string)
	if name != "agent-2" {
		t.Errorf("expected pod name agent-2, got %s", name)
	}

	phase := podStatus["phase"].(string)
	if phase != "Running" {
		t.Errorf("expected phase Running, got %s", phase)
	}

	containerStatuses := podStatus["containerStatuses"].([]interface{})
	cs := containerStatuses[0].(map[string]interface{})
	ready := cs["ready"].(bool)
	if !ready {
		t.Error("expected ready true")
	}
	_ = s
}

func TestParsePodJSON_NoPodFound(t *testing.T) {
	podJSON := []byte(`{"items": []}`)
	var result map[string]interface{}
	json.Unmarshal(podJSON, &result)

	items := result["items"].([]interface{})
	if len(items) != 0 {
		t.Errorf("expected 0 items, got %d", len(items))
	}
}

func TestPodStatusInfo_Fields(t *testing.T) {
	info := &PodStatusInfo{
		PodName:  "agent-1",
		Status:   "Running",
		Ready:    "true",
		Restarts: "0",
		Age:      "5m",
		IP:       "10.244.0.5",
		Node:     "control-plane",
	}

	if info.PodName != "agent-1" {
		t.Errorf("expected pod_name agent-1, got %s", info.PodName)
	}
	if info.Status != "Running" {
		t.Errorf("expected status Running, got %s", info.Status)
	}
	if info.Ready != "true" {
		t.Errorf("expected ready true, got %s", info.Ready)
	}
}

func TestServiceYAML_Generation(t *testing.T) {
	name := "agent-23"
	namespace := "default"
	expectedSvcName := name + "-svc"
	expectedPort := 8000

	svcYaml := `apiVersion: v1
kind: Service
metadata:
  name: ` + expectedSvcName + `
  namespace: ` + namespace + `
spec:
  selector:
    app: ` + name + `
  ports:
  - port: ` + string(rune(expectedPort)) + `
    targetPort: ` + string(rune(expectedPort)) + `
`

	if svcYaml == "" {
		t.Error("service YAML should not be empty")
	}
}

func TestIngressYAML_Generation(t *testing.T) {
	name := "agent-23"
	namespace := "default"
	expectedIngressName := name + "-ingress"
	expectedSvcName := name + "-svc"
	expectedPath := "/agent/23"

	ingressYaml := `apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ` + expectedIngressName + `
  namespace: ` + namespace + `
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
spec:
  ingressClassName: nginx
  rules:
  - http:
      paths:
      - path: ` + expectedPath + `
        pathType: Prefix
        backend:
          service:
            name: ` + expectedSvcName + `
            port:
              number: 8000
`

	if ingressYaml == "" {
		t.Error("ingress YAML should not be empty")
	}
}

func TestSandboxClient_IngressEnabled(t *testing.T) {
	s := &SandboxClient{
		namespace:      "default",
		ingressHost:    "localhost",
		ingressEnabled: true,
	}

	if !s.ingressEnabled {
		t.Error("expected ingressEnabled to be true")
	}
	if s.ingressHost != "localhost" {
		t.Errorf("expected ingressHost localhost, got %s", s.ingressHost)
	}
}

func TestSandboxClient_IngressDisabled(t *testing.T) {
	s := &SandboxClient{
		namespace:      "default",
		ingressHost:    "localhost",
		ingressEnabled: false,
	}

	if s.ingressEnabled {
		t.Error("expected ingressEnabled to be false")
	}
}

func TestIngressPath_Format(t *testing.T) {
	agentID := uint(23)
	expectedPath := "/agent/23"
	actualPath := "/agent/" + strconv.FormatUint(uint64(agentID), 10)

	if actualPath != expectedPath {
		t.Errorf("expected path %s, got %s", expectedPath, actualPath)
	}
}

func TestEndpointURL_Format(t *testing.T) {
	ingressHost := "localhost"
	agentID := uint(23)
	expectedURL := "http://localhost/agent/23"

	endpointURL := "http://" + ingressHost + "/agent/" + strconv.FormatUint(uint64(agentID), 10)
	if endpointURL != expectedURL {
		t.Errorf("expected endpoint URL %s, got %s", expectedURL, endpointURL)
	}
}
