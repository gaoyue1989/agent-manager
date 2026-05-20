from typing import Any
from uuid import UUID

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.messages import BaseMessage

from server.services.llm_logger import LLMLogger


def _serialize_message(msg: BaseMessage) -> dict:
    """将 LangChain message 序列化为可 JSON 序列化的 dict"""
    result: dict[str, Any] = {
        "role": msg.type,
        "content": str(msg.content) if msg.content else "",
    }
    if hasattr(msg, "tool_calls") and msg.tool_calls:
        result["tool_calls"] = []
        for tc in msg.tool_calls:
            tc_info = {
                "id": tc.get("id", ""),
                "name": tc.get("name", ""),
                "args": tc.get("args", {}),
            }
            result["tool_calls"].append(tc_info)
    if hasattr(msg, "tool_call_id") and msg.tool_call_id:
        result["tool_call_id"] = msg.tool_call_id
    if hasattr(msg, "name") and msg.name:
        result["name"] = msg.name
    return result


class LLMLoggingCallback(BaseCallbackHandler):
    """LangChain 回调，捕获每次 LLM 调用的请求与响应"""

    def __init__(self, logger: LLMLogger, thread_id: str):
        super().__init__()
        self._logger = logger
        self._thread_id = thread_id
        self._pending_request: dict | None = None
        self._pending_start: float = 0

    def on_chat_model_start(
        self,
        serialized: dict[str, Any],
        messages: list[list[BaseMessage]],
        *,
        run_id: UUID,
        parent_run_id: UUID | None = None,
        tags: list[str] | None = None,
        metadata: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        import time as _time
        self._pending_start = _time.time()

        flat_messages = []
        for batch in messages:
            for msg in batch:
                flat_messages.append(_serialize_message(msg))

        model_name = ""
        if serialized and "kwargs" in serialized:
            model_name = serialized["kwargs"].get("model_name", "")
        elif serialized and "name" in serialized:
            model_name = serialized["name"]

        invocation_params = {}
        if kwargs.get("invocation_params"):
            invocation_params = dict(kwargs["invocation_params"])
            invocation_params.pop("_type", None)

        self._pending_request = {
            "model": model_name,
            "messages": flat_messages,
            "params": {
                "temperature": invocation_params.get("temperature"),
                "max_tokens": invocation_params.get("max_tokens"),
            },
        }

    def on_llm_end(
        self,
        response: Any,
        *,
        run_id: UUID,
        parent_run_id: UUID | None = None,
        **kwargs: Any,
    ) -> None:
        if self._pending_request is None:
            return

        import time as _time
        elapsed = round(_time.time() - self._pending_start, 3) if self._pending_start else 0

        response_info: dict[str, Any] = {
            "elapsed": elapsed,
            "content": "",
            "tool_calls": [],
            "usage": {},
        }

        try:
            usage = {}
            if hasattr(response, "llm_output") and response.llm_output:
                tu = response.llm_output.get("token_usage", {})
                if tu:
                    usage = {
                        "input_tokens": tu.get("input_tokens", tu.get("prompt_tokens", 0)),
                        "output_tokens": tu.get("output_tokens", tu.get("completion_tokens", 0)),
                        "total_tokens": tu.get("total_tokens", 0),
                    }
            elif hasattr(response, "generations") and response.generations:
                for gen_list in response.generations:
                    for gen in gen_list:
                        if hasattr(gen, "generation_info"):
                            tu = gen.generation_info.get("usage_metadata", {})
                            if tu:
                                usage = {
                                    "input_tokens": tu.get("input_tokens", 0),
                                    "output_tokens": tu.get("output_tokens", 0),
                                    "total_tokens": tu.get("total_tokens", 0),
                                }
                                break
            response_info["usage"] = usage
        except Exception:
            pass

        try:
            if hasattr(response, "generations") and response.generations:
                for gen_list in response.generations:
                    for gen in gen_list:
                        msg = getattr(gen, "message", None)
                        if msg is None:
                            continue
                        if hasattr(msg, "content") and msg.content:
                            response_info["content"] = str(msg.content)
                        if hasattr(msg, "tool_calls") and msg.tool_calls:
                            for tc in msg.tool_calls:
                                response_info["tool_calls"].append({
                                    "id": tc.get("id", ""),
                                    "name": tc.get("name", ""),
                                    "args": tc.get("args", {}),
                                })
                        if hasattr(msg, "usage_metadata") and msg.usage_metadata:
                            um = msg.usage_metadata
                            response_info["usage"] = {
                                "input_tokens": um.get("input_tokens", 0),
                                "output_tokens": um.get("output_tokens", 0),
                                "total_tokens": um.get("total_tokens", 0),
                            }
        except Exception:
            pass

        self._logger.log_call(
            thread_id=self._thread_id,
            request_info=self._pending_request,
            response_info=response_info,
        )
        self._pending_request = None
        self._pending_start = 0
