package codegen

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"agent-manager/backend/internal/minio"
)

type Runner struct {
	script  string
	python  string
	storage *minio.Storage
}

func NewRunner(script, python string, storage *minio.Storage) *Runner {
	return &Runner{script: script, python: python, storage: storage}
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
