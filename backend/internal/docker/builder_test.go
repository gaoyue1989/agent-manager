package docker

import (
	"testing"
)

func TestImageExists_False(t *testing.T) {
	b := &Builder{}
	result := b.ImageExists("nonexistent-image-12345:latest")
	if result {
		t.Error("expected ImageExists to return false for nonexistent image")
	}
}

