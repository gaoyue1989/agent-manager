from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from server.config import AppConfig
from server.services.oaf_loader import OAFLoader
from server.services.skill_manager import SkillManager
from server.services.mcp_manager import MCPManager
from server.services.a2ui_service import A2UIService
from server.services.agent_runtime import AgentRuntime
from server.routes.agent_card import generate_agent_card
from server.routes.a2a_routes import A2ARoutes
from server.routes.debug_ui import register_debug_ui


def create_app(config: AppConfig = None) -> FastAPI:
    if config is None:
        from server.config import load_config
        config = load_config()

    app = FastAPI(
        title="Agent Framework",
        description="DeepAgents-based Agent Framework with OAF + A2A + A2UI",
        version="1.0.0",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    llm_issues = config.llm.validate_with_errors()
    if llm_issues:
        for issue in llm_issues:
            print(f"WARNING: {issue}")

    oaf_loader = OAFLoader(config.config_path)
    oaf_config = oaf_loader.load()
    print(f"Loaded OAF: {oaf_config.name} v{oaf_config.version}")
    print(f"  Skills: {len(oaf_config.skills)} - {[s.name for s in oaf_config.skills]}")
    print(f"  MCP: {len(oaf_config.mcp_servers)} - {[m.server for m in oaf_config.mcp_servers]}")
    print(f"  Tools: {oaf_config.tools}")

    skill_manager = SkillManager(config.skills_dir)
    loaded_skills = skill_manager.load_all(oaf_config.local_skills)

    mcp_manager = MCPManager(config.mcp_configs_dir)
    mcp_configs = mcp_manager.load_configs(oaf_config.mcp_servers)

    a2ui_service = A2UIService(
        catalog_id=oaf_config.get_catalog_id(),
    )

    agent_runtime = AgentRuntime(
        oaf_config=oaf_config,
        llm_config=config.llm,
        loaded_skills=loaded_skills,
        mcp_configs=mcp_configs,
    )

    agent_card_data = generate_agent_card(
        oaf_config=oaf_config,
        a2ui_service=a2ui_service,
        host=config.server.host,
        port=config.server.port,
    )

    @app.get("/.well-known/agent-card.json")
    async def agent_card():
        return agent_card_data

    @app.get("/health")
    async def health():
        return {
            "status": "healthy",
            "agent": oaf_config.name,
            "version": oaf_config.version,
            "skills": len(loaded_skills),
            "mcp_servers": len(mcp_configs),
            "llm_configured": config.llm.is_valid(),
        }

    @app.get("/")
    async def root():
        return {
            "agent": oaf_config.name,
            "description": oaf_config.description,
            "version": oaf_config.version,
            "protocols": {"a2a": "1.0.0", "a2ui": "v0.8", "oaf": "v0.8.0"},
            "oaf": {
                "tools": oaf_config.tools,
                "skills": len(oaf_config.skills),
                "mcp": len(oaf_config.mcp_servers),
                "sub_agents": len(oaf_config.sub_agents),
            },
            "endpoints": {
                "agent_card": "/.well-known/agent-card.json",
                "jsonrpc": "/",
                "tasks": "/tasks",
                "skills": "/skills",
                "mcp": "/mcp",
                "health": "/health",
                "debug": "/debug",
            },
        }

    @app.get("/skills")
    async def list_skills():
        return skill_manager.get_skill_summaries(loaded_skills)

    @app.get("/mcp")
    async def list_mcp():
        return mcp_manager.get_mcp_summaries(mcp_configs)

    A2ARoutes(
        app=app,
        config=config,
        agent_runtime=agent_runtime,
        a2ui_service=a2ui_service,
    )

    register_debug_ui(app)

    return app
