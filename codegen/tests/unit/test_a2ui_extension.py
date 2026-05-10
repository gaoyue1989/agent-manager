"""A2UI Extension 测试"""

import pytest
import json

from codegen.frameworks.deepagents.a2ui_extension import A2UIExtension


class TestA2UIExtension:
    """A2UI Extension 测试"""
    
    @pytest.fixture
    def a2ui(self):
        return A2UIExtension(
            catalog_id="https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
            supported_catalogs=[
                "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
            ],
            accepts_inline_catalogs=True,
        )
    
    def test_wrap_text_as_a2ui(self, a2ui):
        """测试将文本包装为 A2UI JSONL"""
        text = "Hello\n\nWorld"
        jsonl = a2ui._wrap_text_as_a2ui("test-surface", text)
        
        lines = [l for l in jsonl.split("\n") if l.strip()]
        assert len(lines) >= 2
        
        first = json.loads(lines[0])
        assert "surfaceUpdate" in first
        assert first["surfaceUpdate"]["surfaceId"] == "test-surface"
        
        last = json.loads(lines[-1])
        assert "beginRendering" in last
    
    def test_extract_a2ui_from_text(self, a2ui):
        """测试从 LLM 响应中提取 A2UI"""
        response = '''这是响应
        
```a2ui
{"surfaceUpdate": {"surfaceId": "main", "components": []}}
{"beginRendering": {"surfaceId": "main", "root": "root"}}
```
        '''
        
        lines = a2ui._extract_a2ui_from_text(response)
        assert len(lines) == 2
        assert "surfaceUpdate" in lines[0]
        assert "beginRendering" in lines[1]
    
    def test_extract_a2ui_from_text_no_block(self, a2ui):
        """测试没有 A2UI 代码块时的提取"""
        response = "这是普通文本响应"
        
        lines = a2ui._extract_a2ui_from_text(response)
        assert len(lines) == 0
    
    def test_generate_artifact_with_a2ui(self, a2ui):
        """测试生成 A2UI Artifact"""
        artifact = a2ui.generate_artifact(
            surface_id="test",
            response_text="Hello World",
        )
        
        assert "artifactId" in artifact
        assert artifact["artifactId"] == "test"
        assert "parts" in artifact
        assert len(artifact["parts"]) == 1
        assert artifact["parts"][0]["mediaType"] == "application/x-a2ui+jsonl"
        assert "a2ui_stream" in artifact["parts"][0]["data"]
    
    def test_generate_artifact_with_embedded_a2ui(self, a2ui):
        """测试从嵌入的 A2UI 生成 Artifact"""
        response = '''响应文本
        
```a2ui
{"surfaceUpdate": {"surfaceId": "main", "components": [{"id": "root", "component": {"Text": {"text": {"literalString": "Hello"}}}}]}}
{"beginRendering": {"surfaceId": "main", "root": "root"}}
```
        '''
        
        artifact = a2ui.generate_artifact(
            surface_id="test",
            response_text=response,
        )
        
        a2ui_stream = artifact["parts"][0]["data"]["a2ui_stream"]
        assert "surfaceUpdate" in a2ui_stream
        assert "beginRendering" in a2ui_stream
    
    def test_generate_simple_text_response(self, a2ui):
        """测试生成简单文本响应"""
        artifact = a2ui.generate_simple_text_response(
            surface_id="test",
            text="Hello World",
        )
        
        assert "artifactId" in artifact
        assert "parts" in artifact
        assert artifact["parts"][0]["text"] == "Hello World"
    
    def test_get_client_capabilities(self, a2ui):
        """测试获取客户端能力声明"""
        caps = a2ui.get_client_capabilities()
        
        assert "a2uiClientCapabilities" in caps
        assert "supportedCatalogIds" in caps["a2uiClientCapabilities"]
        assert len(caps["a2uiClientCapabilities"]["supportedCatalogIds"]) > 0
