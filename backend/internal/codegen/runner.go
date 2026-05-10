package codegen

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"agent-manager/backend/internal/minio"
	"agent-manager/backend/internal/model"
)

type Runner struct {
	script  string
	python  string
	storage *minio.Storage
	cliPath string
}

func NewRunner(script, python string, storage *minio.Storage) *Runner {
	cliPath := strings.Replace(script, "generator.py", "cli.py", 1)
	return &Runner{script: script, python: python, storage: storage, cliPath: cliPath}
}

func (r *Runner) Run(config map[string]interface{}) (map[string]string, error) {
	return r.RunAndStore(config, "")
}

func (r *Runner) RunAndStore(config map[string]interface{}, prefix string) (map[string]string, error) {
	return r.RunAndStoreWithBaseImage(config, prefix, "")
}

func (r *Runner) RunAndStoreWithBaseImage(config map[string]interface{}, prefix string, baseImage string) (map[string]string, error) {
	if baseImage != "" {
		config["base_image"] = baseImage
	}

	configJSON, err := json.Marshal(config)
	if err != nil {
		return nil, err
	}

	tmpDir, err := os.MkdirTemp("", "codegen-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmpDir)

	cmd := exec.Command(r.python, r.script, "--stdin", tmpDir)
	cmd.Stdin = bytes.NewReader(configJSON)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("codegen: %s\n%s", err.Error(), string(output))
	}

	if prefix == "" {
		result := map[string]string{}
		for _, filename := range []string{"agent.py", "Dockerfile", "requirements.txt"} {
			filePath := filepath.Join(tmpDir, filename)
			data, readErr := os.ReadFile(filePath)
			if readErr != nil {
				continue
			}
			result[filename] = string(data)
		}
		return result, nil
	}

	result := map[string]string{}
	for _, filename := range []string{"agent.py", "Dockerfile", "requirements.txt"} {
		filePath := filepath.Join(tmpDir, filename)
		data, readErr := os.ReadFile(filePath)
		if readErr != nil {
			continue
		}
		key := prefix + "/" + filename
		if _, putErr := r.storage.PutFile(key, data); putErr != nil {
			return nil, putErr
		}
		result[filename] = key
	}

	return result, nil
}

func (r *Runner) RunWithOAF(oafConfig *model.OAFConfig, prefix string) (map[string]string, error) {
	tmpDir, err := os.MkdirTemp("", "oaf-codegen-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmpDir)

	agentsMd, err := oafConfig.ToYAML()
	if err != nil {
		return nil, fmt.Errorf("serialize OAF: %w", err)
	}

	oafDir := filepath.Join(tmpDir, "agent")
	if err := os.MkdirAll(oafDir, 0755); err != nil {
		return nil, err
	}

	agentsMdPath := filepath.Join(oafDir, "AGENTS.md")
	if err := os.WriteFile(agentsMdPath, []byte(agentsMd), 0644); err != nil {
		return nil, err
	}

	if oafConfig.HasLocalSkills() {
		skillsDir := filepath.Join(oafDir, "skills")
		if err := os.MkdirAll(skillsDir, 0755); err != nil {
			return nil, err
		}
		for _, skill := range oafConfig.GetLocalSkills() {
			skillDir := filepath.Join(skillsDir, skill.Name)
			if err := os.MkdirAll(skillDir, 0755); err != nil {
				return nil, err
			}
			skillMd := fmt.Sprintf("---\nname: %s\nversion: %s\n---\n", skill.Name, skill.Version)
			skillMdPath := filepath.Join(skillDir, "SKILL.md")
			if err := os.WriteFile(skillMdPath, []byte(skillMd), 0644); err != nil {
				return nil, err
			}
		}
	}

	if oafConfig.HasMCPServers() {
		mcpDir := filepath.Join(oafDir, "mcp-configs")
		if err := os.MkdirAll(mcpDir, 0755); err != nil {
			return nil, err
		}
		for _, mcp := range oafConfig.MCPServers {
			serverDir := filepath.Join(mcpDir, mcp.Server)
			if err := os.MkdirAll(serverDir, 0755); err != nil {
				return nil, err
			}
			activeMCP := fmt.Sprintf(`{"vendor":"%s","server":"%s","version":"%s"}`, mcp.Vendor, mcp.Server, mcp.Version)
			activeMCPPath := filepath.Join(serverDir, "ActiveMCP.json")
			if err := os.WriteFile(activeMCPPath, []byte(activeMCP), 0644); err != nil {
				return nil, err
			}
			configYaml := fmt.Sprintf("vendor: %s\nserver: %s\nversion: %s\n", mcp.Vendor, mcp.Server, mcp.Version)
			configYamlPath := filepath.Join(serverDir, "config.yaml")
			if err := os.WriteFile(configYamlPath, []byte(configYaml), 0644); err != nil {
				return nil, err
			}
		}
	}

	outputDir := filepath.Join(tmpDir, "output")
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return nil, err
	}

	cmd := exec.Command(r.python, r.cliPath, "generate", "--oaf", oafDir, "--output", outputDir)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("codegen cli: %s\n%s", err.Error(), string(output))
	}

	if prefix == "" {
		return r.readOutputFiles(outputDir, nil)
	}

	return r.storeOutputFiles(outputDir, prefix)
}

func (r *Runner) readOutputFiles(outputDir string, files []string) (map[string]string, error) {
	if files == nil {
		files = []string{"main.py", "Dockerfile", "requirements.txt", "agent_card.json"}
	}

	result := map[string]string{}
	for _, filename := range files {
		filePath := filepath.Join(outputDir, filename)
		data, err := os.ReadFile(filePath)
		if err != nil {
			continue
		}
		result[filename] = string(data)
	}

	skillsDir := filepath.Join(outputDir, "skills")
	if skillFiles, err := filepath.Glob(filepath.Join(skillsDir, "*", "skill.py")); err == nil {
		for _, sf := range skillFiles {
			data, err := os.ReadFile(sf)
			if err != nil {
				continue
			}
			relPath, _ := filepath.Rel(outputDir, sf)
			result[relPath] = string(data)
		}
	}

	mcpDir := filepath.Join(outputDir, "mcp-configs")
	if mcpFiles, err := filepath.Glob(filepath.Join(mcpDir, "*", "*.json")); err == nil {
		for _, mf := range mcpFiles {
			data, err := os.ReadFile(mf)
			if err != nil {
				continue
			}
			relPath, _ := filepath.Rel(outputDir, mf)
			result[relPath] = string(data)
		}
	}

	return result, nil
}

func (r *Runner) storeOutputFiles(outputDir string, prefix string) (map[string]string, error) {
	result := map[string]string{}

	mainFiles := []string{"main.py", "Dockerfile", "requirements.txt", "agent_card.json"}
	for _, filename := range mainFiles {
		filePath := filepath.Join(outputDir, filename)
		data, err := os.ReadFile(filePath)
		if err != nil {
			continue
		}
		key := prefix + "/" + filename
		if _, putErr := r.storage.PutFile(key, data); putErr != nil {
			return nil, putErr
		}
		result[filename] = key
	}

	skillsDir := filepath.Join(outputDir, "skills")
	if skillFiles, err := filepath.Glob(filepath.Join(skillsDir, "*", "skill.py")); err == nil {
		for _, sf := range skillFiles {
			data, err := os.ReadFile(sf)
			if err != nil {
				continue
			}
			relPath, _ := filepath.Rel(outputDir, sf)
			key := prefix + "/" + relPath
			if _, putErr := r.storage.PutFile(key, data); putErr != nil {
				return nil, putErr
			}
			result[relPath] = key
		}
	}

	mcpDir := filepath.Join(outputDir, "mcp-configs")
	if mcpFiles, err := filepath.Glob(filepath.Join(mcpDir, "*", "*.json")); err == nil {
		for _, mf := range mcpFiles {
			data, err := os.ReadFile(mf)
			if err != nil {
				continue
			}
			relPath, _ := filepath.Rel(outputDir, mf)
			key := prefix + "/" + relPath
			if _, putErr := r.storage.PutFile(key, data); putErr != nil {
				return nil, putErr
			}
			result[relPath] = key
		}
	}

	return result, nil
}
