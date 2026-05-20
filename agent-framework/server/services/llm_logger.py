import time
from typing import Any


class LLMLogger:
    """内存 LLM 请求/响应日志器，按 thread_id 存储每轮 LLM 调用的完整信息"""

    def __init__(self, max_calls_per_thread: int = 50):
        self._storage: dict[str, list[dict]] = {}
        self._max = max_calls_per_thread

    def log_call(
        self,
        thread_id: str,
        request_info: dict,
        response_info: dict,
    ) -> str:
        """记录一次 LLM 调用，返回 call_id"""
        if thread_id not in self._storage:
            self._storage[thread_id] = []

        call_id = f"call-{int(time.time() * 1000)}-{len(self._storage[thread_id])}"
        entry = {
            "call_id": call_id,
            "timestamp": time.time(),
            "request": request_info,
            "response": response_info,
        }
        self._storage[thread_id].append(entry)

        if len(self._storage[thread_id]) > self._max:
            self._storage[thread_id] = self._storage[thread_id][-self._max:]

        return call_id

    def get_calls(self, thread_id: str) -> list[dict]:
        """获取某 thread 的所有 LLM 调用记录"""
        return self._storage.get(thread_id, [])

    def clear_thread(self, thread_id: str):
        """清除某 thread 的所有日志"""
        self._storage.pop(thread_id, None)

    @property
    def thread_count(self) -> int:
        return len(self._storage)
