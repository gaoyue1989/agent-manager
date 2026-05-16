import uuid
from typing import Optional, AsyncGenerator, Any

from server.config import LLMConfig
from server.models.oaf_types import OAFConfig


class AgentRuntime:
    """DeepAgents 运行时封装

    负责创建和管理 DeepAgents Agent 实例，提供 invoke 和 stream 接口
    """

    def __init__(
        self,
        oaf_config: OAFConfig,
        llm_config: LLMConfig,
        loaded_skills: list[dict] = None,
        mcp_configs: list[dict] = None,
        mcp_client: Any = None,
    ):
        self.oaf = oaf_config
        self.llm = llm_config
        self.loaded_skills = loaded_skills or []
        self.mcp_configs = mcp_configs or []
        self.mcp_client = mcp_client
        self._agent = None
        self._chat_model = None

    @property
    def name(self) -> str:
        return self.oaf.name

    @property
    def description(self) -> str:
        return self.oaf.description

    @property
    def system_prompt(self) -> str:
        prompt = self.oaf.system_prompt
        if self.loaded_skills:
            prompt += self._build_skills_context()
        if self.mcp_configs:
            prompt += self._build_mcp_context()
        return prompt

    @property
    def tools_list(self) -> list[str]:
        return self.oaf.tools

    def _build_skills_context(self) -> str:
        lines = ["\n\n## Available Skills"]
        for skill in self.loaded_skills:
            meta = skill.get("metadata", {})
            desc = meta.get("description", "")
            lines.append(f"- **{skill['name']}**: {desc[:200]}")
        return "\n".join(lines)

    def _build_mcp_context(self) -> str:
        lines = ["\n\n## Available MCP Servers"]
        for mc in self.mcp_configs:
            conn = mc.get("connection", {})
            tools_cfg = mc.get("tools", {})
            selected = tools_cfg.get("selectedTools", [])
            tool_names = [t["name"] for t in selected if t.get("enabled", True)]
            lines.append(f"- **{mc.get('server', 'unknown')}** ({len(tool_names)} tools: {', '.join(tool_names[:10])})")
        return "\n".join(lines)

    def _get_chat_model(self):
        if self._chat_model is None and self.llm.is_valid():
            from server.services.chat_model import ChatOpenAIReasoning
            self._chat_model = ChatOpenAIReasoning(
                model=self.llm.model_id,
                openai_api_key=self.llm.api_key,
                openai_api_base=self.llm.base_url,
                temperature=self.llm.temperature,
                max_tokens=self.llm.max_tokens,
            )
        return self._chat_model

    def _build_messages(self, message: str, history: list[dict] = None) -> list[dict]:
        messages = [{"role": "system", "content": self.system_prompt}]
        if history:
            for h in history:
                role = h.get("role", "user")
                content = h.get("content", "")
                if content:
                    messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": message})
        return messages

    async def invoke(self, message: str, history: list[dict] = None) -> str:
        if not self.llm.is_valid():
            return f"[Mock Agent:{self.name}] {message}"

        model = self._get_chat_model()
        if model is None:
            return f"[Agent:{self.name}] LLM not configured properly"

        try:
            from deepagents import create_deep_agent

            tools = self._get_available_tools()

            agent = create_deep_agent(
                model=model,
                system_prompt=self.system_prompt,
                tools=tools if tools else None,
            )

            result = agent.invoke({
                "messages": self._build_messages(message, history),
            })

            last_msg = ""
            if "messages" in result:
                for m in reversed(result["messages"]):
                    if hasattr(m, "content"):
                        last_msg = m.content
                        break

            return last_msg or str(result)

        except ImportError:
            return await self._invoke_direct(message, history)
        except Exception as e:
            return await self._invoke_direct(message, history)

    async def invoke_stream(
        self,
        message: str,
        history: list[dict] = None,
    ) -> AsyncGenerator[str, None]:
        if not self.llm.is_valid():
            yield f"[Mock Agent:{self.name}] {message}"
            return

        model = self._get_chat_model()
        if model is None:
            yield f"[Agent:{self.name}] LLM not configured properly"
            return

        try:
            from deepagents import create_deep_agent

            tools = self._get_available_tools()

            agent = create_deep_agent(
                model=model,
                system_prompt=self.system_prompt,
                tools=tools if tools else None,
            )

            full_text = ""
            async for msg, metadata in agent.astream(
                {"messages": self._build_messages(message, history)},
                stream_mode="messages",
            ):
                chunk = msg
                if isinstance(msg, tuple):
                    chunk = msg[0]
                content = self._get_message_content(chunk)
                if content and len(content) > len(full_text):
                    new_text = content[len(full_text):]
                    full_text = content
                    yield new_text
                elif content:
                    new_text = content
                    if new_text:
                        full_text += new_text
                        yield new_text

            if not full_text:
                result = agent.invoke({
                    "messages": self._build_messages(message, history),
                })
                if "messages" in result:
                    for m in reversed(result["messages"]):
                        text = self._get_message_content(m)
                        if text:
                            yield text
                            return

        except (ImportError, Exception):
            async for token in self._invoke_direct_stream(message, history):
                yield token

    def _extract_chunk_text(self, chunk, full_text: str) -> str:
        """从 DeepAgents stream chunk 中提取增量文本"""
        if isinstance(chunk, dict):
            if "messages" in chunk:
                for m in chunk["messages"]:
                    content = self._get_message_content(m)
                    if content and len(content) > len(full_text):
                        return content[len(full_text):]
            elif "agent" in chunk:
                for m in getattr(chunk["agent"], "messages", []):
                    content = self._get_message_content(m)
                    if content and len(content) > len(full_text):
                        return content[len(full_text):]
        elif hasattr(chunk, "content"):
            content = self._get_message_content(chunk)
            if content and len(content) > len(full_text):
                return content[len(full_text):]
        return ""

    def _get_message_content(self, msg) -> str:
        """从消息对象中提取内容，优先 content，fallback 到 reasoning_content"""
        if hasattr(msg, "content") and msg.content:
            return msg.content
        if hasattr(msg, "additional_kwargs") and isinstance(msg.additional_kwargs, dict):
            rc = msg.additional_kwargs.get("reasoning_content", "")
            if rc:
                return rc
        if hasattr(msg, "reasoning_content") and msg.reasoning_content:
            return msg.reasoning_content
        return ""

    async def _invoke_direct(self, message: str, history: list[dict] = None) -> str:
        """直接调用 OpenAI 兼容 API（不使用 DeepAgents）"""
        import httpx

        if not self.llm.is_valid():
            return f"[Mock Agent:{self.name}] {message}"

        messages = self._build_messages(message, history)

        headers = {
            "Authorization": f"Bearer {self.llm.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.llm.model_id,
            "messages": messages,
            "max_tokens": self.llm.max_tokens,
            "temperature": self.llm.temperature,
        }

        async with httpx.AsyncClient(timeout=self.llm.timeout) as client:
            resp = await client.post(
                f"{self.llm.base_url}/chat/completions",
                headers=headers,
                json=payload,
            )
            resp.raise_for_status()
            data = resp.json()
            msg = data["choices"][0]["message"]
            return msg.get("content", "") or msg.get("reasoning_content", "")

    async def _invoke_direct_stream(
        self,
        message: str,
        history: list[dict] = None,
    ) -> AsyncGenerator[str, None]:
        """直接流式调用 OpenAI 兼容 API"""
        import httpx

        if not self.llm.is_valid():
            yield f"[Mock Agent:{self.name}] {message}"
            return

        messages = self._build_messages(message, history)

        headers = {
            "Authorization": f"Bearer {self.llm.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.llm.model_id,
            "messages": messages,
            "max_tokens": self.llm.max_tokens,
            "temperature": self.llm.temperature,
            "stream": True,
        }

        async with httpx.AsyncClient(timeout=self.llm.timeout) as client:
            async with client.stream(
                "POST",
                f"{self.llm.base_url}/chat/completions",
                headers=headers,
                json=payload,
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if line.startswith("data: "):
                        data_str = line[6:]
                        if data_str == "[DONE]":
                            break
                        try:
                            import json as _json
                            data = _json.loads(data_str)
                            delta = data["choices"][0].get("delta", {})
                            content = delta.get("content", "")
                            reasoning = delta.get("reasoning_content", "")
                            text = content or reasoning
                            if text:
                                yield text
                        except Exception:
                            pass

    def _get_available_tools(self) -> list:
        tools = []
        tool_names = [t.lower() for t in self.oaf.tools]

        if "bash" in tool_names or "execute" in tool_names:
            from langchain_core.tools import tool

            @tool
            def bash_execute(command: str) -> str:
                """Execute a bash command and return the output. Use for shell operations."""
                import subprocess
                try:
                    result = subprocess.run(
                        command, shell=True, capture_output=True, text=True, timeout=30,
                    )
                    output = result.stdout.strip()
                    if result.stderr.strip():
                        output += "\n" + result.stderr.strip()
                    return output or "(no output)"
                except subprocess.TimeoutExpired:
                    return "Command timed out after 30s"
                except Exception as e:
                    return f"Error: {e}"

            tools.append(bash_execute)

        if "read" in tool_names:
            from langchain_core.tools import tool

            @tool
            def read_file(path: str) -> str:
                """Read the contents of a file at the given path."""
                from pathlib import Path
                p = Path(path)
                if not p.exists():
                    return f"File not found: {path}"
                try:
                    return p.read_text(encoding="utf-8")[:50000]
                except Exception as e:
                    return f"Error reading file: {e}"

            tools.append(read_file)

        if "edit" in tool_names:
            from langchain_core.tools import tool

            @tool
            def edit_file(path: str, old_string: str, new_string: str) -> str:
                """Replace old_string with new_string in file at path."""
                from pathlib import Path
                p = Path(path)
                if not p.exists():
                    return f"File not found: {path}"
                try:
                    content = p.read_text(encoding="utf-8")
                    if old_string not in content:
                        return f"old_string not found in {path}"
                    content = content.replace(old_string, new_string, 1)
                    p.write_text(content, encoding="utf-8")
                    return f"Successfully edited {path}"
                except Exception as e:
                    return f"Error editing file: {e}"

            tools.append(edit_file)

        if "grep" in tool_names:
            from langchain_core.tools import tool

            @tool
            def grep_search(pattern: str, path: str = ".") -> str:
                """Search for pattern in files under path. Returns matching lines."""
                import subprocess
                try:
                    result = subprocess.run(
                        f'grep -rn "{pattern}" {path} 2>/dev/null | head -50',
                        shell=True, capture_output=True, text=True, timeout=30,
                    )
                    return result.stdout.strip() or "No matches found"
                except Exception as e:
                    return f"Error: {e}"

            tools.append(grep_search)

        if self.mcp_client:
            try:
                import asyncio
                loop = asyncio.get_event_loop()
                if not loop.is_running():
                    mcp_tools = loop.run_until_complete(self.mcp_client.get_tools())
                    tools.extend(mcp_tools)
            except Exception:
                pass

        return tools

    async def invoke_skill(self, skill_name: str, input_data: str) -> str:
        for skill in self.loaded_skills:
            if skill["name"] == skill_name:
                module = skill.get("module")
                if module and hasattr(module, "main"):
                    try:
                        result = module.main(input_data)
                        return str(result)
                    except Exception as e:
                        return f"[Skill:{skill_name}] Error: {e}"
                return f"[Skill:{skill_name}] Loaded but no main() entry point"
        return f"[Skill:{skill_name}] Not found"
