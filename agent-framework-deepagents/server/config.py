import os
from pathlib import Path
from pydantic import BaseModel, Field, ConfigDict


class ServerConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    host: str = Field(default="0.0.0.0", alias="SERVER_HOST")
    port: int = Field(default=8100, alias="SERVER_PORT")
    reload: bool = Field(default=False, alias="SERVER_RELOAD")


class LLMConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    api_key: str = Field(default="", alias="LLM_API_KEY")
    model_id: str = Field(default="", alias="LLM_MODEL_ID")
    base_url: str = Field(default="", alias="LLM_BASE_URL")
    provider: str = Field(default="openai", alias="LLM_PROVIDER")
    temperature: float = Field(default=0.7, alias="LLM_TEMPERATURE")
    max_tokens: int = Field(default=4096, alias="LLM_MAX_TOKENS")
    timeout: int = Field(default=120, alias="LLM_TIMEOUT")

    def to_openai_config(self) -> dict:
        return {
            "model": self.model_id,
            "model_provider": "openai",
            "openai_api_key": self.api_key,
            "openai_api_base": self.base_url,
        }

    def to_langchain_chat(self):
        from langchain.chat_models import init_chat_model
        return init_chat_model(
            model=self.model_id,
            model_provider="openai",
            openai_api_key=self.api_key,
            openai_api_base=self.base_url,
        )

    def is_valid(self) -> bool:
        return bool(self.api_key and self.model_id and self.base_url)

    def validate_with_errors(self) -> list[str]:
        errors = []
        if not self.api_key:
            errors.append("LLM_API_KEY is not set")
        if not self.model_id:
            errors.append("LLM_MODEL_ID is not set")
        if not self.base_url:
            errors.append("LLM_BASE_URL is not set")
        return errors


class MySQLCheckpointConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    dsn: str = Field(
        default="mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test",
        alias="CHECKPOINT_MYSQL_DSN",
    )


class AppConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    config_dir: str = Field(default="/config", alias="AGENT_CONFIG_DIR")
    server: ServerConfig = ServerConfig()
    llm: LLMConfig = LLMConfig()
    checkpoint: MySQLCheckpointConfig = MySQLCheckpointConfig()

    @property
    def config_path(self) -> Path:
        return Path(self.config_dir)

    @property
    def agents_md_path(self) -> Path:
        return self.config_path / "AGENTS.md"

    @property
    def skills_dir(self) -> Path:
        return self.config_path / "skills"

    @property
    def mcp_configs_dir(self) -> Path:
        return self.config_path

    @property
    def custom_tools_dir(self) -> Path:
        return self.config_path / "custom-tools"


def load_config() -> AppConfig:
    from dotenv import load_dotenv
    load_dotenv()

    return AppConfig(
        config_dir=os.getenv("AGENT_CONFIG_DIR", "/config"),
        server=ServerConfig(
            host=os.getenv("SERVER_HOST", "0.0.0.0"),
            port=int(os.getenv("SERVER_PORT", "8000")),
            reload=os.getenv("SERVER_RELOAD", "").lower() == "true",
        ),
        llm=LLMConfig(
            api_key=os.getenv("LLM_API_KEY", ""),
            model_id=os.getenv("LLM_MODEL_ID", ""),
            base_url=os.getenv("LLM_BASE_URL", ""),
            provider=os.getenv("LLM_PROVIDER", "openai"),
            temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
            max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
            timeout=int(os.getenv("LLM_TIMEOUT", "120")),
        ),
        checkpoint=MySQLCheckpointConfig(
            dsn=os.getenv(
                "CHECKPOINT_MYSQL_DSN",
                "mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test",
            ),
        ),
    )
