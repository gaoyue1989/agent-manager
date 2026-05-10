#!/usr/bin/env python3
"""
A2UI Extension Handler

处理 A2A 的 A2UI 扩展，生成 A2UI JSONL 流
"""

import json
import re
from typing import Optional


class A2UIExtension:
    """A2A 的 A2UI 扩展实现
    
    将 Agent 的文本响应转换为 A2UI JSONL 流 (Artifact 格式)
    """
    
    def __init__(
        self,
        catalog_id: str = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
        supported_catalogs: Optional[list[str]] = None,
        accepts_inline_catalogs: bool = True,
    ):
        """
        初始化 A2UI Extension
        
        Args:
            catalog_id: 默认 catalog ID
            supported_catalogs: 支持的 catalog 列表
            accepts_inline_catalogs: 是否接受内联 catalog
        """
        self.catalog_id = catalog_id
        self.supported_catalogs = supported_catalogs or [catalog_id]
        self.accepts_inline_catalogs = accepts_inline_catalogs
    
    def generate_artifact(
        self,
        surface_id: str,
        response_text: str,
        catalog_id: Optional[str] = None,
    ) -> dict:
        """
        从 Agent 响应中生成 A2UI Artifact
        
        Args:
            surface_id: Surface ID
            response_text: Agent 响应文本
            catalog_id: 使用的 catalog ID
        
        Returns:
            A2A Artifact 对象
        """
        used_catalog = catalog_id or self.catalog_id
        a2ui_lines = self._extract_a2ui_from_text(response_text)
        
        if a2ui_lines:
            jsonl_content = "\n".join(a2ui_lines)
        else:
            jsonl_content = self._wrap_text_as_a2ui(surface_id, response_text)
        
        return {
            "artifactId": surface_id,
            "name": "A2UI Interface",
            "parts": [{
                "data": {"a2ui_stream": jsonl_content},
                "mediaType": "application/x-a2ui+jsonl",
            }],
        }
    
    def _extract_a2ui_from_text(self, text: str) -> list[str]:
        """从 LLM 响应中提取 A2UI JSONL 代码块"""
        match = re.search(r'```a2ui\n(.*?)\n```', text, re.DOTALL)
        if match:
            return [line for line in match.group(1).split("\n") if line.strip()]
        return []
    
    def _wrap_text_as_a2ui(self, surface_id: str, text: str) -> str:
        """将纯文本包装为 A2UI Text 组件"""
        paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
        
        if not paragraphs:
            paragraphs = [text]
        
        lines = []
        components = []
        child_ids = []
        
        for i, para in enumerate(paragraphs):
            comp_id = f"para_{i}"
            child_ids.append(comp_id)
            components.append({
                "id": comp_id,
                "component": {
                    "Text": {
                        "text": {"literalString": para},
                    },
                },
            })
        
        components.insert(0, {
            "id": "root",
            "component": {
                "Column": {
                    "children": {"explicitList": child_ids},
                },
            },
        })
        
        for comp in components:
            lines.append(json.dumps({
                "surfaceUpdate": {
                    "surfaceId": surface_id,
                    "components": [comp],
                },
            }, ensure_ascii=False))
        
        lines.append(json.dumps({
            "beginRendering": {
                "surfaceId": surface_id,
                "root": "root",
            },
        }, ensure_ascii=False))
        
        return "\n".join(lines)
    
    def generate_simple_text_response(self, surface_id: str, text: str) -> dict:
        """生成简单的文本响应 Artifact"""
        return {
            "artifactId": f"{surface_id}-text",
            "name": "Response",
            "parts": [{"text": text}],
        }
    
    def get_client_capabilities(self) -> dict:
        """获取客户端能力声明"""
        return {
            "a2uiClientCapabilities": {
                "supportedCatalogIds": self.supported_catalogs,
                "acceptsInlineCatalogs": self.accepts_inline_catalogs,
            },
        }
