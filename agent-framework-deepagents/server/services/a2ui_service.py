import json
import re
import uuid
from typing import Optional


class A2UIService:
    """A2UI v0.8 服务

    将 Agent 文本响应转换为 A2UI JSONL 流
    支持 surfaceUpdate / dataModelUpdate / beginRendering / endRendering
    """

    A2UI_STANDARD_CATALOG = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"

    STANDARD_COMPONENTS = [
        "Text", "Button", "TextField", "Column", "Row", "Card",
        "List", "Image", "Divider", "CheckBox", "Slider", "DateTimeInput",
        "Tabs", "Modal", "Video",
    ]

    def __init__(
        self,
        catalog_id: Optional[str] = None,
        supported_catalogs: Optional[list[str]] = None,
        accepts_inline_catalogs: bool = True,
    ):
        self.catalog_id = catalog_id or self.A2UI_STANDARD_CATALOG
        self.supported_catalogs = supported_catalogs or [self.catalog_id]
        self.accepts_inline_catalogs = accepts_inline_catalogs

    def generate_surface_update(self, surface_id: str, components: list[dict]) -> str:
        return json.dumps({
            "surfaceUpdate": {
                "surfaceId": surface_id,
                "components": components,
            },
        }, ensure_ascii=False)

    def generate_data_model_update(self, surface_id: str, path: str, contents: list) -> str:
        return json.dumps({
            "dataModelUpdate": {
                "surfaceId": surface_id,
                "path": path,
                "contents": contents,
            },
        }, ensure_ascii=False)

    def generate_begin_rendering(self, surface_id: str, root: str = "root") -> str:
        return json.dumps({
            "beginRendering": {
                "surfaceId": surface_id,
                "root": root,
            },
        }, ensure_ascii=False)

    def generate_end_rendering(self, surface_id: str) -> str:
        return json.dumps({
            "endRendering": {
                "surfaceId": surface_id,
            },
        }, ensure_ascii=False)

    def wrap_text_as_a2ui(self, surface_id: str, text: str) -> str:
        paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
        if not paragraphs:
            paragraphs = [text]

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

        lines = []
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

    def extract_a2ui_from_text(self, text: str) -> list[str]:
        match = re.search(r'```a2ui\n(.*?)\n```', text, re.DOTALL)
        if match:
            return [line for line in match.group(1).split("\n") if line.strip()]
        return []

    def generate_artifact(
        self,
        surface_id: str,
        response_text: str,
        catalog_id: Optional[str] = None,
    ) -> dict:
        used_catalog = catalog_id or self.catalog_id
        a2ui_lines = self.extract_a2ui_from_text(response_text)

        if a2ui_lines:
            jsonl_content = "\n".join(a2ui_lines)
        else:
            jsonl_content = self.wrap_text_as_a2ui(surface_id, response_text)

        return {
            "artifactId": surface_id,
            "name": "A2UI Interface",
            "parts": [{
                "data": {"a2ui_stream": jsonl_content},
                "mediaType": "application/x-a2ui+jsonl",
            }],
        }

    def generate_simple_text_response(self, surface_id: str, text: str) -> dict:
        return {
            "artifactId": f"{surface_id}-text",
            "name": "Response",
            "parts": [{"text": text}],
        }

    def get_client_capabilities(self) -> dict:
        return {
            "a2uiClientCapabilities": {
                "supportedCatalogIds": self.supported_catalogs,
                "acceptsInlineCatalogs": self.accepts_inline_catalogs,
            },
        }

    def get_extension_declaration(self) -> dict:
        return {
            "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8",
            "params": {
                "supportedCatalogIds": self.supported_catalogs,
                "acceptsInlineCatalogs": self.accepts_inline_catalogs,
            },
        }
