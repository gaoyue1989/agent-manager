from server.services.a2ui_service import A2UIService
from server.models.oaf_types import OAFConfig


def generate_agent_card(
    oaf_config: OAFConfig,
    a2ui_service: A2UIService,
    host: str = "localhost",
    port: int = 8000,
) -> dict:
    url = f"http://{host}:{port}/"

    card = {
        "name": oaf_config.name,
        "description": oaf_config.description,
        "url": url,
        "version": oaf_config.version,
        "provider": {
            "organization": oaf_config.vendor_key,
        },
        "capabilities": {
            "streaming": True,
            "pushNotifications": False,
            "stateTransitionHistory": True,
        },
        "defaultInputModes": ["text", "text/plain"],
        "defaultOutputModes": ["text", "text/plain"],
        "skills": [],
        "securitySchemes": {
            "bearer": {
                "scheme": "bearer",
                "description": "Bearer token authentication",
            },
        },
    }

    if a2ui_service:
        output_modes = card["defaultOutputModes"]
        output_modes.append("a2ui/v0.8")
        card["defaultOutputModes"] = output_modes

        card["extensions"] = [
            a2ui_service.get_extension_declaration(),
        ]

    if oaf_config.skills:
        card["skills"] = [
            {
                "id": s.name,
                "name": s.name,
                "description": s.description or f"Skill: {s.name}",
                "tags": [],
                "examples": [],
                "inputModes": ["text"],
                "outputModes": card["defaultOutputModes"],
            }
            for s in oaf_config.skills
        ]
    else:
        card["skills"] = [
            {
                "id": "default",
                "name": "General",
                "description": oaf_config.description,
                "tags": oaf_config.tags,
                "examples": [],
                "inputModes": ["text"],
                "outputModes": card["defaultOutputModes"],
            }
        ]

    return card
