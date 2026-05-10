#!/usr/bin/env python3
"""
Agent Card 生成器

生成符合 A2A v1.0.0 规范的 Agent Card
"""

import json
from typing import Optional


class AgentCardGenerator:
    """A2A Agent Card 生成器"""
    
    def __init__(
        self,
        name: str,
        description: str,
        host: str = "localhost",
        port: int = 8000,
        version: str = "1.0.0",
        provider_org: str = "local",
        provider_url: str = "",
        streaming: bool = True,
        push_notifications: bool = False,
        state_transition_history: bool = True,
        a2ui_enabled: bool = True,
        a2ui_catalog_ids: Optional[list[str]] = None,
        a2ui_accepts_inline: bool = True,
        skills: Optional[list[dict]] = None,
        security_schemes: Optional[dict] = None,
    ):
        """
        初始化 Agent Card 生成器
        
        Args:
            name: Agent 名称
            description: Agent 描述
            host: 服务主机
            port: 服务端口
            version: Agent 版本
            provider_org: 提供者组织
            provider_url: 提供者 URL
            streaming: 是否支持流式
            push_notifications: 是否支持推送通知
            state_transition_history: 是否支持状态转换历史
            a2ui_enabled: 是否启用 A2UI
            a2ui_catalog_ids: A2UI catalog ID 列表
            a2ui_accepts_inline: 是否接受内联 catalog
            skills: Agent 技能列表
            security_schemes: 安全方案
        """
        self.name = name
        self.description = description
        self.host = host
        self.port = port
        self.version = version
        self.provider_org = provider_org
        self.provider_url = provider_url
        self.streaming = streaming
        self.push_notifications = push_notifications
        self.state_transition_history = state_transition_history
        self.a2ui_enabled = a2ui_enabled
        self.a2ui_catalog_ids = a2ui_catalog_ids or [
            "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
        ]
        self.a2ui_accepts_inline = a2ui_accepts_inline
        self.skills = skills or []
        self.security_schemes = security_schemes
    
    def generate(self) -> dict:
        """
        生成 Agent Card
        
        Returns:
            Agent Card 字典
        """
        url = f"http://{self.host}:{self.port}/"
        
        card = {
            "name": self.name,
            "description": self.description,
            "url": url,
            "version": self.version,
            "provider": {
                "organization": self.provider_org,
            },
        }
        
        if self.provider_url:
            card["provider"]["url"] = self.provider_url
        
        card["capabilities"] = {
            "streaming": self.streaming,
            "pushNotifications": self.push_notifications,
            "stateTransitionHistory": self.state_transition_history,
        }
        
        card["defaultInputModes"] = ["text", "text/plain"]
        
        output_modes = ["text", "text/plain"]
        if self.a2ui_enabled:
            output_modes.append("a2ui/v0.8")
        card["defaultOutputModes"] = output_modes
        
        if self.skills:
            card["skills"] = self.skills
        else:
            card["skills"] = [
                {
                    "id": "default",
                    "name": "General",
                    "description": self.description,
                    "tags": [],
                    "examples": [],
                    "inputModes": ["text"],
                    "outputModes": output_modes,
                }
            ]
        
        if self.security_schemes:
            card["securitySchemes"] = self.security_schemes
        else:
            card["securitySchemes"] = {
                "bearer": {
                    "scheme": "bearer",
                    "description": "Bearer token authentication",
                }
            }
        
        extensions = []
        
        if self.a2ui_enabled:
            extensions.append({
                "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
                "params": {
                    "supportedCatalogIds": self.a2ui_catalog_ids,
                    "acceptsInlineCatalogs": self.a2ui_accepts_inline,
                },
            })
        
        if extensions:
            card["extensions"] = extensions
        
        return card
    
    def to_json(self, indent: int = 2) -> str:
        """
        转换为 JSON 字符串
        
        Args:
            indent: 缩进空格数
        
        Returns:
            JSON 字符串
        """
        return json.dumps(self.generate(), indent=indent, ensure_ascii=False)
    
    def save(self, filepath: str) -> None:
        """
        保存到文件
        
        Args:
            filepath: 文件路径
        """
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(self.to_json())
