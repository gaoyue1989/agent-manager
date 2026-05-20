package k8s

import (
	"bytes"
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"text/template"
)

//go:embed templates/deployment.yaml.tmpl
var defaultDeploymentTpl string

//go:embed templates/service.yaml.tmpl
var defaultServiceTpl string

//go:embed templates/ingress.yaml.tmpl
var defaultIngressTpl string

// DeployParams 模板渲染参数
type DeployParams struct {
	Name           string
	Namespace      string
	Image          string
	Port           int
	Replicas       int
	EnvYAML        string
	Resources      string
	LivenessProbe  string
	ReadinessProbe string
	VolumeMounts   string
	Volumes        string
}

// ServiceParams Service 模板参数
type ServiceParams struct {
	Name      string
	Namespace string
	Port      int
}

// IngressParams Ingress 模板参数
type IngressParams struct {
	Name         string
	Namespace    string
	Path         string
	Port         int
	IngressClass string
}

// TemplateEngine 管理内置和外部 K8s 资源模板
type TemplateEngine struct {
	templates map[string]*template.Template
}

// NewTemplateEngine 初始化模板引擎，加载内置模板并从可选外部目录覆盖
func NewTemplateEngine(templateDir string) (*TemplateEngine, error) {
	engine := &TemplateEngine{
		templates: map[string]*template.Template{
			"deployment": template.Must(template.New("deployment").Parse(defaultDeploymentTpl)),
			"service":    template.Must(template.New("service").Parse(defaultServiceTpl)),
			"ingress":    template.Must(template.New("ingress").Parse(defaultIngressTpl)),
		},
	}

	if templateDir != "" {
		if err := engine.loadExternalTemplates(templateDir); err != nil {
			return nil, fmt.Errorf("load external templates: %w", err)
		}
	}

	return engine, nil
}

func (e *TemplateEngine) loadExternalTemplates(dir string) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return fmt.Errorf("read template dir %s: %w", dir, err)
	}

	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".yaml.tmpl") {
			continue
		}

		name := strings.TrimSuffix(entry.Name(), ".yaml.tmpl")
		loaded := e.templates[name]

		fullPath := filepath.Join(dir, entry.Name())
		content, err := os.ReadFile(fullPath)
		if err != nil {
			return fmt.Errorf("read template %s: %w", fullPath, err)
		}

		if loaded == nil {
			loaded = template.New(name)
		}
		parsed, err := loaded.Parse(string(content))
		if err != nil {
			return fmt.Errorf("parse template %s: %w", fullPath, err)
		}
		e.templates[name] = parsed
	}

	return nil
}

func (e *TemplateEngine) RenderDeployment(params DeployParams) (string, error) {
	if params.Replicas == 0 {
		params.Replicas = 1
	}
	if params.Namespace == "" {
		params.Namespace = "default"
	}
	return e.render("deployment", params)
}

func (e *TemplateEngine) RenderService(params ServiceParams) (string, error) {
	return e.render("service", params)
}

func (e *TemplateEngine) RenderIngress(params IngressParams) (string, error) {
	return e.render("ingress", params)
}

func (e *TemplateEngine) render(name string, data interface{}) (string, error) {
	tpl, ok := e.templates[name]
	if !ok {
		return "", fmt.Errorf("template %q not found", name)
	}

	var buf bytes.Buffer
	if err := tpl.Execute(&buf, data); err != nil {
		return "", fmt.Errorf("render %s: %w", name, err)
	}
	return buf.String(), nil
}
