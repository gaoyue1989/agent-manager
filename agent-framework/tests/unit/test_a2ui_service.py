import json
import pytest
from server.services.a2ui_service import A2UIService


class TestA2UIService:
    """A2UI 服务单元测试"""

    @pytest.fixture
    def a2ui(self):
        return A2UIService()

    def test_init_defaults(self, a2ui):
        assert "a2ui.org" in a2ui.catalog_id
        assert len(a2ui.supported_catalogs) == 1
        assert a2ui.accepts_inline_catalogs is True

    def test_generate_surface_update(self, a2ui):
        result = a2ui.generate_surface_update("main", [{"id": "c1", "component": {"Text": {"text": {"literalString": "Hello"}}}}])
        data = json.loads(result)
        assert data["surfaceUpdate"]["surfaceId"] == "main"
        assert len(data["surfaceUpdate"]["components"]) == 1
        assert data["surfaceUpdate"]["components"][0]["id"] == "c1"

    def test_generate_data_model_update(self, a2ui):
        result = a2ui.generate_data_model_update("main", "/data", [{"key": "value"}])
        data = json.loads(result)
        assert data["dataModelUpdate"]["surfaceId"] == "main"
        assert data["dataModelUpdate"]["path"] == "/data"

    def test_generate_begin_rendering(self, a2ui):
        result = a2ui.generate_begin_rendering("main", "root")
        data = json.loads(result)
        assert data["beginRendering"]["surfaceId"] == "main"
        assert data["beginRendering"]["root"] == "root"

    def test_generate_end_rendering(self, a2ui):
        result = a2ui.generate_end_rendering("main")
        data = json.loads(result)
        assert data["endRendering"]["surfaceId"] == "main"

    def test_wrap_text_as_a2ui_short(self, a2ui):
        result = a2ui.wrap_text_as_a2ui("test", "Hello")
        lines = result.strip().split("\n")
        assert len(lines) >= 2
        for line in lines:
            data = json.loads(line)
            assert "surfaceUpdate" in data or "beginRendering" in data

    def test_wrap_text_as_a2ui_multiple_paragraphs(self, a2ui):
        result = a2ui.wrap_text_as_a2ui("test", "Para 1\n\nPara 2\n\nPara 3")
        lines = result.strip().split("\n")
        # Should have surfaceUpdate for each paragraph + root + beginRendering
        assert len(lines) >= 4

    def test_wrap_text_as_a2ui_empty(self, a2ui):
        result = a2ui.wrap_text_as_a2ui("test", "")
        assert result
        lines = result.strip().split("\n")
        assert len(lines) == 3  # root, para_0, beginRendering

    def test_extract_a2ui_from_text(self, a2ui):
        text = '```a2ui\n{"surfaceUpdate":{"surfaceId":"main","components":[{"id":"c1","component":{"Text":{"text":{"literalString":"Hi"}}}}]}}\n{"beginRendering":{"surfaceId":"main","root":"c1"}}\n```'
        lines = a2ui.extract_a2ui_from_text(text)
        assert len(lines) == 2
        assert "surfaceUpdate" in lines[0]

    def test_extract_a2ui_no_match(self, a2ui):
        lines = a2ui.extract_a2ui_from_text("Just plain text, no a2ui blocks")
        assert lines == []

    def test_generate_artifact_with_a2ui(self, a2ui):
        text = '```a2ui\n{"surfaceUpdate":{"surfaceId":"s1","components":[]}}\n{"beginRendering":{"surfaceId":"s1","root":"root"}}\n```'
        artifact = a2ui.generate_artifact("s1", text)
        assert artifact["name"] == "A2UI Interface"
        parts = artifact["parts"]
        assert len(parts) == 1
        assert "a2ui_stream" in parts[0]["data"]
        assert "application/x-a2ui+jsonl" in parts[0]["mediaType"]

    def test_generate_artifact_with_text(self, a2ui):
        artifact = a2ui.generate_artifact("s2", "Hello World")
        assert artifact["artifactId"] == "s2"
        parts = artifact["parts"]
        assert len(parts) == 1
        assert "a2ui_stream" in parts[0]["data"]
        # Should be wrapped as text
        stream = parts[0]["data"]["a2ui_stream"]
        assert "Hello World" in stream

    def test_get_client_capabilities(self, a2ui):
        caps = a2ui.get_client_capabilities()
        assert "a2uiClientCapabilities" in caps
        assert len(caps["a2uiClientCapabilities"]["supportedCatalogIds"]) >= 1

    def test_get_extension_declaration(self, a2ui):
        ext = a2ui.get_extension_declaration()
        assert "uri" in ext
        assert "a2ui" in ext["uri"]
        assert "params" in ext
        assert ext["params"]["acceptsInlineCatalogs"] is True
