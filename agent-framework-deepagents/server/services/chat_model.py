from typing import Any, AsyncIterator, Iterator, List, Optional
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.outputs import ChatGenerationChunk
from langchain_openai import ChatOpenAI
import json


def _clean_usage_metadata(usage: dict) -> dict:
    """递归移除 usage_metadata 中的 None 值
    
    GLM-5 等 API 返回的 usage 包含 None 值，会导致 langchain merge 时报错:
    ValueError: Unknown value types: [<class 'NoneType'>]. Only dict and int values are supported.
    """
    if not isinstance(usage, dict):
        return usage
    result = {}
    for k, v in usage.items():
        if v is None:
            continue
        if isinstance(v, dict):
            cleaned = _clean_usage_metadata(v)
            if cleaned:
                result[k] = cleaned
        else:
            result[k] = v
    return result


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
        choices = chunk.get("choices", [])
        if not choices:
            usage = chunk.get("usage")
            if usage:
                cleaned_usage = _clean_usage_metadata(usage)
                result = super()._convert_chunk_to_generation_chunk(
                    {"choices": []}, default_chunk_class, base_generation_info
                )
                if result is None:
                    result = ChatGenerationChunk(
                        message=default_chunk_class(content=""),
                    )
                if cleaned_usage:
                    result.message.usage_metadata = cleaned_usage
                return result
            return super()._convert_chunk_to_generation_chunk(
                chunk, default_chunk_class, base_generation_info
            )

        choice = choices[0]
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
