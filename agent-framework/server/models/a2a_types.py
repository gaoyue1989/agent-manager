from typing import Optional, Any
from pydantic import BaseModel, Field


class Part(BaseModel):
    text: Optional[str] = None
    data: Optional[dict] = None
    mediaType: Optional[str] = None


class Message(BaseModel):
    role: str = "user"
    parts: list[Part] = Field(default_factory=list)
    metadata: dict = Field(default_factory=dict)


class TaskStatus(BaseModel):
    state: str = "working"
    message: Optional[str] = None
    timestamp: Optional[str] = None


class Artifact(BaseModel):
    artifactId: str = ""
    name: str = ""
    parts: list[Part] = Field(default_factory=list)


class Task(BaseModel):
    id: str
    status: TaskStatus = Field(default_factory=lambda: TaskStatus(state="working"))
    artifacts: list[Artifact] = Field(default_factory=list)
    history: list[dict] = Field(default_factory=list)


class JSONRPCRequest(BaseModel):
    jsonrpc: str = "2.0"
    method: str
    params: dict = Field(default_factory=dict)
    id: Optional[str] = None


class JSONRPCResponse(BaseModel):
    jsonrpc: str = "2.0"
    result: Optional[Any] = None
    error: Optional[dict] = None
    id: Optional[str] = None


class StreamingEvent(BaseModel):
    kind: str = "task"
    task_id: str = ""
    data: Any = None


def extract_user_text(message: dict) -> str:
    parts = message.get("parts", [])
    for part in parts:
        if isinstance(part, dict) and "text" in part:
            return part["text"]
    return ""


def build_response_artifact(artifact_id: str, text: str) -> Artifact:
    return Artifact(
        artifactId=artifact_id,
        name="response",
        parts=[Part(text=text)],
    )


def build_a2ui_artifact(artifact_id: str, jsonl_content: str) -> Artifact:
    return Artifact(
        artifactId=artifact_id,
        name="A2UI Interface",
        parts=[Part(
            data={"a2ui_stream": jsonl_content},
            mediaType="application/x-a2ui+jsonl",
        )],
    )
