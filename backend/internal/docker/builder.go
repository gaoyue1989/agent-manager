package docker

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"agent-manager/backend/internal/minio"
)

type Builder struct {
	username string
	password string
}

func NewBuilder(username, password string) (*Builder, error) {
	return &Builder{username: username, password: password}, nil
}

func (b *Builder) Build(localTag, remoteTag, prefix string, storage *minio.Storage) (string, error) {
	tmpDir, err := os.MkdirTemp("", "docker-build-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmpDir)

	var logLines []string

	for _, filename := range []string{"Dockerfile", "agent.py", "requirements.txt"} {
		key := prefix + "/" + filename
		data, err := storage.GetFile(key)
		if err != nil {
			return "", fmt.Errorf("get %s: %w", key, err)
		}
		if err := os.WriteFile(filepath.Join(tmpDir, filename), []byte(data), 0644); err != nil {
			return "", err
		}
	}

	loginCmd := exec.Command("docker", "login", "-u", b.username, "--password-stdin")
	loginCmd.Stdin = strings.NewReader(b.password)
	if out, err := loginCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, fmt.Sprintf("login: %s", string(out)))
	}

	buildCmd := exec.Command("docker", "build", "-t", localTag, tmpDir)
	out, err := buildCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("build: %w", err)
	}

	tagCmd := exec.Command("docker", "tag", localTag, remoteTag)
	if out, err := tagCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, string(out))
		return strings.Join(logLines, "\n"), fmt.Errorf("tag: %w", err)
	}

	pushCmd := exec.Command("docker", "push", remoteTag)
	out, err = pushCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("push: %w", err)
	}

	return strings.Join(logLines, "\n"), nil
}
