from typing import Any, AsyncIterator, Iterator, List, Optional
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.outputs import ChatGenerationChunk
from langchain_openai import ChatOpenAI
import json


class ChatOpenAIReasoning(ChatOpenAI):
    """支持 reasoning_content 的 ChatOpenAI 子类

    GLM-5 等模型将流式 token 放在 reasoning_content 而非 content 字段，
    此类从 reasoning_content 中提取内容。
    """

    def _convert_chunk_to_generation_chunk(
        self,
        chunk: dict,
        default_chunk_class: type,
        base_generation_info: Optional[dict],
    ) -> Optional[ChatGenerationChunk]:
        """重写：将 reasoning_content 合并到 content"""
        choice = chunk.get("choices", [{}])[0]
        delta = choice.get("delta", {})

        content = delta.get("content", "")
        reasoning = delta.get("reasoning_content", "")
        if reasoning and not content:
            delta = dict(delta)
            delta["content"] = reasoning
            choice = dict(choice)
            choice["delta"] = delta
            chunk = dict(chunk)
            chunk["choices"] = [choice]

        return super()._convert_chunk_to_generation_chunk(
            chunk, default_chunk_class, base_generation_info
        )
