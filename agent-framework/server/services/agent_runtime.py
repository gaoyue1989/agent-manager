import uuid
from typing import Optional, AsyncGenerator, Any

from langchain_core.messages import HumanMessage

from server.config import LLMConfig
from server.models.oaf_types import OAFConfig


class AgentRuntime:
    """DeepAgents 运行时封装

    生命周期内复用同一个 agent（带 checkpointer），thread_id 驱动会话隔离
    """

    def __init__(
        self,
        oaf_config: OAFConfig,
        llm_config: LLMConfig,
        checkpointer: Any = None,
        loaded_skills: list[dict] = None,
        mcp_configs: list[dict] = None,
        mcp_client: Any = None,
    ):
        self.oaf = oaf_config
        self.llm = llm_config
        self._checkpointer = checkpointer
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

    def _ensure_agent(self):
        if self._agent is None and self.llm.is_valid():
            from deepagents import create_deep_agent
            model = self._get_chat_model()
            if model is None:
                return None
            tools = self._get_available_tools()
            self._agent = create_deep_agent(
                model=model,
                system_prompt=self.system_prompt,
                tools=tools if tools else None,
                checkpointer=self._checkpointer,
            )
        return self._agent

    async def invoke(self, message: str, thread_id: str = None) -> tuple[str, str]:
        """同步风格（异步实现），返回 (response_text, thread_id)"""
        if thread_id is None:
            thread_id = str(uuid.uuid4())

        if not self.llm.is_valid():
            return f"[Mock Agent:{self.name}] {message}", thread_id

        agent = self._ensure_agent()
        if agent is None:
            return f"[Agent:{self.name}] LLM not configured", thread_id

        config = {"configurable": {"thread_id": thread_id}}

        try:
            result = await agent.ainvoke(
                {"messages": [HumanMessage(content=message)]},
                config=config,
            )

            last_msg = ""
            if "messages" in result:
                for m in reversed(result["messages"]):
                    if hasattr(m, "content") and m.content:
                        last_msg = m.content
                        break

            return last_msg or str(result), thread_id

        except Exception:
            return await self._invoke_direct(message, None), thread_id

    async def invoke_stream(
        self,
        message: str,
        thread_id: str = None,
    ) -> AsyncGenerator[dict, None]:
        """流式调用，yields 事件 dict：
        {"type": "token", "token": "..."}
        {"type": "tool_call", "name": "...", "args": {...}}
        {"type": "tool_result", "name": "...", "result": "..."}
        {"type": "done"}
        """
        if thread_id is None:
            thread_id = str(uuid.uuid4())

        if not self.llm.is_valid():
            yield {"type": "token", "token": f"[Mock Agent:{self.name}] {message}"}
            yield {"type": "done"}
            return

        agent = self._ensure_agent()
        if agent is None:
            yield {"type": "token", "token": f"[Agent:{self.name}] LLM not configured"}
            yield {"type": "done"}
            return

        config = {"configurable": {"thread_id": thread_id}}

        try:
            full_text = ""
            pending_tool_calls = {}

            async for msg, metadata in agent.astream(
                {"messages": [HumanMessage(content=message)]},
                config=config,
                stream_mode="messages",
            ):
                chunk = msg
                if isinstance(msg, tuple):
                    chunk = msg[0]

                msg_type = getattr(chunk, "type", "")

                if msg_type == "tool":
                    tool_call_id = getattr(chunk, "tool_call_id", "")
                    result = self._get_message_content(chunk)
                    tc = pending_tool_calls.get(tool_call_id, {})
                    yield {"type": "tool_result", "name": tc.get("name", "tool"), "result": result, "tool_call_id": tool_call_id}
                elif hasattr(chunk, "tool_calls") and chunk.tool_calls:
                    for tc in chunk.tool_calls:
                        tc_id = tc.get("id", "")
                        tc_name = tc.get("name", "")
                        tc_args = tc.get("args", {})
                        if tc_id and tc_name:
                            pending_tool_calls[tc_id] = {"name": tc_name, "args": tc_args}
                            yield {"type": "tool_call", "name": tc_name, "args": tc_args, "tool_call_id": tc_id}
                        elif tc_args and tc_id in pending_tool_calls:
                            pending_tool_calls[tc_id]["args"] = tc_args
                            yield {"type": "tool_call", "name": pending_tool_calls[tc_id]["name"], "args": tc_args, "tool_call_id": tc_id}
                else:
                    content = self._get_message_content(chunk)
                    if content and len(content) > len(full_text):
                        new_text = content[len(full_text):]
                        full_text = content
                        if new_text:
                            yield {"type": "token", "token": new_text}
                    elif content:
                        new_text = content
                        if new_text:
                            full_text += new_text
                            yield {"type": "token", "token": new_text}

            if not full_text and not pending_tool_calls:
                result = await agent.ainvoke(
                    {"messages": [HumanMessage(content=message)]},
                    config=config,
                )
                if "messages" in result:
                    for m in reversed(result["messages"]):
                        text = self._get_message_content(m)
                        if text:
                            yield {"type": "token", "token": text}
                            break

            yield {"type": "done"}

        except Exception:
            async for token in self._invoke_direct_stream(message, None):
                yield {"type": "token", "token": token}
            yield {"type": "done"}

    async def get_thread_state(self, thread_id: str) -> dict:
        """获取 thread 的完整对话历史，包含工具调用信息"""
        agent = self._ensure_agent()
        if agent is None:
            return {"thread_id": thread_id, "messages": [], "error": "Agent not initialized"}

        config = {"configurable": {"thread_id": thread_id}}
        try:
            state = await agent.aget_state(config)
            if state is None or state.values is None:
                return {"thread_id": thread_id, "messages": [], "error": "Thread not found"}

            messages = state.values.get("messages", [])
            if not messages:
                # 没有消息说明 thread 不存在或已被删除
                # 但为了区分真正不存在的 thread（无 checkpoint）,
                # 检查 checkpoint 是否确实存在过
                if self._checkpointer:
                    cpt = await self._checkpointer.aget_tuple(config)
                    if cpt is None:
                        return {"thread_id": thread_id, "messages": [], "error": "Thread not found"}
                return {"thread_id": thread_id, "messages": [], "error": "Thread deleted or empty"}
            result = []
            tool_call_map = {}

            for m in messages:
                msg_type = getattr(m, "type", "")

                if msg_type == "human":
                    content = self._get_message_content(m)
                    if content:
                        result.append({"role": "user", "content": content})
                elif msg_type == "ai":
                    content = self._get_message_content(m)
                    tool_calls = getattr(m, "tool_calls", []) or []
                    if tool_calls:
                        for tc in tool_calls:
                            tc_id = tc.get("id", "")
                            tc_name = tc.get("name", "")
                            tc_args = tc.get("args", {})
                            tool_call_map[tc_id] = tc_name
                            result.append({
                                "role": "assistant",
                                "type": "tool_call",
                                "tool_name": tc_name,
                                "tool_args": tc_args,
                                "tool_call_id": tc_id,
                                "content": content if content else None,
                            })
                    elif content:
                        result.append({"role": "assistant", "content": content})
                elif msg_type == "tool":
                    tool_call_id = getattr(m, "tool_call_id", "")
                    tool_name = tool_call_map.get(tool_call_id, "tool")
                    content = self._get_message_content(m)
                    result.append({
                        "role": "tool",
                        "type": "tool_result",
                        "tool_name": tool_name,
                        "tool_call_id": tool_call_id,
                        "content": content,
                    })
                elif msg_type == "system":
                    content = self._get_message_content(m)
                    if content:
                        result.append({"role": "system", "content": content})

            return {"thread_id": thread_id, "messages": result}

        except Exception as e:
            return {"thread_id": thread_id, "messages": [], "error": str(e)}

    async def delete_thread(self, thread_id: str) -> bool:
        """删除 thread 的所有 checkpoint 数据"""
        if self._checkpointer is None:
            return False
        try:
            await self._checkpointer.adelete_thread(thread_id)
            return True
        except Exception:
            return False

    async def list_threads(self) -> list[dict]:
        """列出所有 thread（从 checkpoint 聚合）"""
        if self._checkpointer is None:
            return []

        try:
            seen = {}
            async for cp in self._checkpointer.alist(None, limit=1000):
                cfg = cp.config.get("configurable", {})
                tid = cfg.get("thread_id", "")
                if not tid or tid in seen:
                    continue
                step = cp.metadata.get("step", 0)
                source = cp.metadata.get("source", "")
                seen[tid] = {
                    "thread_id": tid,
                    "checkpoint_count": 1,
                    "last_step": step,
                    "last_source": source,
                }
            else:
                # count checkpoints per thread
                async for cp in self._checkpointer.alist(None, limit=1000):
                    tid = cp.config.get("configurable", {}).get("thread_id", "")
                    if not tid or tid not in seen:
                        continue
                    step = cp.metadata.get("step", 0)
                    source = cp.metadata.get("source", "")
                    seen[tid]["checkpoint_count"] += 1
                    if step > seen[tid]["last_step"]:
                        seen[tid]["last_step"] = step
                        seen[tid]["last_source"] = source

            return sorted(seen.values(), key=lambda t: t["last_step"], reverse=True)

        except Exception:
            return []

    def _get_message_content(self, msg) -> str:
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
        import httpx

        if not self.llm.is_valid():
            return f"[Mock Agent:{self.name}] {message}"

        messages = [{"role": "system", "content": self.system_prompt}]
        if history:
            for h in history:
                role = h.get("role", "user")
                content = h.get("content", "")
                if content:
                    messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": message})

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

        try:
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
        except Exception:
            return f"[Agent:{self.name}] LLM API unavailable — check configuration"

    async def _invoke_direct_stream(
        self,
        message: str,
        history: list[dict] = None,
    ) -> AsyncGenerator[str, None]:
        import httpx

        if not self.llm.is_valid():
            yield f"[Mock Agent:{self.name}] {message}"
            return

        messages = [{"role": "system", "content": self.system_prompt}]
        if history:
            for h in history:
                role = h.get("role", "user")
                content = h.get("content", "")
                if content:
                    messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": message})

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

        try:
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
        except Exception:
            yield f"[Agent:{self.name}] LLM API unavailable — check configuration"

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
