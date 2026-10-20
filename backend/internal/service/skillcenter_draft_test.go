package service

import (
	"encoding/json"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// TestUpdateSkillDraftJSONDefaults 回归：Markdown 编辑在工作区无 draft 时新建版本，
// MySQL JSON 列（content_json/files_json）不接受空串——新建时必须落合法 JSON 默认值。
// E2E（kind + MySQL）首次暴露该问题；sqlite 宽松，需在单测里显式断言字段形态。
func TestUpdateSkillDraftJSONDefaults(t *testing.T) {
	c, _, cleanup := newTestCore(t)
	defer cleanup()
	s, err := c.CreateSkillFromMarkdown(7, 1, "draft-probe", "d", "d", nil, "body v1", false)
	if err != nil {
		t.Fatal(err)
	}
	var v1 store.SkillVersion
	if err := c.DB.First(&v1, s.LatestVersionID).Error; err != nil {
		t.Fatal(err)
	}
	if _, err := c.PublishSkillVersion(7, s.ID, v1.ID); err != nil {
		t.Fatal(err)
	}
	v2, err := c.CreateSkillVersion(7, s.ID, v1.ID, "", "v2")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.PublishSkillVersion(7, s.ID, v2.ID); err != nil {
		t.Fatal(err)
	}
	content := "body v3"
	if _, err := c.UpdateSkill(7, s.ID, UpdateSkillInput{Content: &content}); err != nil {
		t.Fatal(err)
	}
	vers, err := c.ListSkillVersions(7, s.ID)
	if err != nil {
		t.Fatal(err)
	}
	var draft *store.SkillVersion
	for i := range vers {
		if vers[i].Version == "1.0.2" {
			draft = &vers[i]
			break
		}
	}
	if draft == nil {
		t.Fatalf("expected new draft 1.0.2, got %+v", vers)
	}
	if draft.Status != SkillVerDraft {
		t.Fatalf("1.0.2 status=%s want draft", draft.Status)
	}
	if draft.FilesJSON != "[]" {
		t.Fatalf("files_json default=%q want []", draft.FilesJSON)
	}
	if !json.Valid([]byte(draft.ContentJSON)) {
		t.Fatalf("content_json is not valid JSON: %q", draft.ContentJSON)
	}
	if draft.ContentJSON == "" || !strings.Contains(draft.ContentJSON, "body v3") {
		t.Fatalf("content_json missing edited content: %q", draft.ContentJSON)
	}
}
