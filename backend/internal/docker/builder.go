package docker

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

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

	skillsPrefix := prefix + "/skills/"
	skillsFiles, err := storage.ListFiles(skillsPrefix)
	if err == nil && len(skillsFiles) > 0 {
		skillsDir := filepath.Join(tmpDir, "skills")
		os.MkdirAll(skillsDir, 0755)
		for _, objName := range skillsFiles {
			relPath := strings.TrimPrefix(objName, skillsPrefix)
			if relPath == "" {
				continue
			}
			dstPath := filepath.Join(skillsDir, relPath)
			os.MkdirAll(filepath.Dir(dstPath), 0755)
			data, err := storage.GetFile(objName)
			if err != nil {
				continue
			}
			os.WriteFile(dstPath, []byte(data), 0644)
		}
	}

	loginCmd := exec.Command("docker", "login", "-u", b.username, "--password-stdin")
	loginCmd.Stdin = strings.NewReader(b.password)
	if out, err := loginCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, fmt.Sprintf("login: %s", string(out)))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()

	buildCmd := exec.CommandContext(ctx, "docker", "build", "-t", localTag, tmpDir)
	out, err := buildCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("build: %w", err)
	}

	tagCmd := exec.CommandContext(ctx, "docker", "tag", localTag, remoteTag)
	if out, err := tagCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, string(out))
		return strings.Join(logLines, "\n"), fmt.Errorf("tag: %w", err)
	}

	pushCmd := exec.CommandContext(ctx, "docker", "push", remoteTag)
	out, err = pushCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("push: %w", err)
	}

	return strings.Join(logLines, "\n"), nil
}

func (b *Builder) RemoveImage(imageTag string) error {
	cmd := exec.Command("docker", "rmi", "-f", imageTag)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker rmi: %s\n%s", err.Error(), string(out))
	}
	return nil
}

func (b *Builder) ImageExists(imageTag string) bool {
	cmd := exec.Command("docker", "image", "inspect", imageTag)
	err := cmd.Run()
	return err == nil
}

func (b *Builder) BuildBaseImage(registry, baseImageName string) (string, error) {
	dockerfileDir := "/root/agent-manager/backend/internal/docker"

	localTag := baseImageName
	remoteTag := fmt.Sprintf("%s/%s", registry, baseImageName)

	loginCmd := exec.Command("docker", "login", "-u", b.username, "--password-stdin")
	loginCmd.Stdin = strings.NewReader(b.password)
	if out, err := loginCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("login: %s", string(out))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	buildCmd := exec.CommandContext(ctx, "docker", "build", "-t", localTag, "-f", dockerfileDir+"/Dockerfile.base", dockerfileDir)
	out, err := buildCmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("build base image: %s\n%s", err.Error(), string(out))
	}

	tagCmd := exec.CommandContext(ctx, "docker", "tag", localTag, remoteTag)
	if out, err := tagCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("tag: %s\n%s", err.Error(), string(out))
	}

	pushCmd := exec.CommandContext(ctx, "docker", "push", remoteTag)
	out, err = pushCmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("push: %s\n%s", err.Error(), string(out))
	}

	return remoteTag, nil
}

func (b *Builder) BuildWithBaseImage(localTag, remoteTag, prefix string, storage *minio.Storage, registry string) (string, error) {
	tmpDir, err := os.MkdirTemp("", "docker-build-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmpDir)

	var logLines []string

	dockerfileData, err := storage.GetFile(prefix + "/Dockerfile")
	if err != nil {
		return "", fmt.Errorf("get Dockerfile: %w", err)
	}

	baseImageTag := fmt.Sprintf("%s/agent-base:latest", registry)
	dockerfileData = strings.Replace(dockerfileData, "FROM python:3.12-slim", fmt.Sprintf("FROM %s", baseImageTag), 1)

	if err := os.WriteFile(filepath.Join(tmpDir, "Dockerfile"), []byte(dockerfileData), 0644); err != nil {
		return "", err
	}

	for _, filename := range []string{"agent.py", "requirements.txt"} {
		key := prefix + "/" + filename
		data, err := storage.GetFile(key)
		if err != nil {
			return "", fmt.Errorf("get %s: %w", key, err)
		}
		if err := os.WriteFile(filepath.Join(tmpDir, filename), []byte(data), 0644); err != nil {
			return "", err
		}
	}

	skillsPrefix := prefix + "/skills/"
	skillsFiles, err := storage.ListFiles(skillsPrefix)
	if err == nil && len(skillsFiles) > 0 {
		skillsDir := filepath.Join(tmpDir, "skills")
		os.MkdirAll(skillsDir, 0755)
		for _, objName := range skillsFiles {
			relPath := strings.TrimPrefix(objName, skillsPrefix)
			if relPath == "" {
				continue
			}
			dstPath := filepath.Join(skillsDir, relPath)
			os.MkdirAll(filepath.Dir(dstPath), 0755)
			data, err := storage.GetFile(objName)
			if err != nil {
				continue
			}
			os.WriteFile(dstPath, []byte(data), 0644)
		}
	}

	loginCmd := exec.Command("docker", "login", "-u", b.username, "--password-stdin")
	loginCmd.Stdin = strings.NewReader(b.password)
	if out, err := loginCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, fmt.Sprintf("login: %s", string(out)))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()

	buildCmd := exec.CommandContext(ctx, "docker", "build", "-t", localTag, tmpDir)
	out, err := buildCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("build: %w", err)
	}

	tagCmd := exec.CommandContext(ctx, "docker", "tag", localTag, remoteTag)
	if out, err := tagCmd.CombinedOutput(); err != nil {
		logLines = append(logLines, string(out))
		return strings.Join(logLines, "\n"), fmt.Errorf("tag: %w", err)
	}

	pushCmd := exec.CommandContext(ctx, "docker", "push", remoteTag)
	out, err = pushCmd.CombinedOutput()
	logLines = append(logLines, string(out))
	if err != nil {
		return strings.Join(logLines, "\n"), fmt.Errorf("push: %w", err)
	}

	return strings.Join(logLines, "\n"), nil
}
