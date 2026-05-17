from langchain_core.tools import tool


@tool
def echo(message: str) -> str:
    """Echo the input message back. Use for testing or repeating text."""
    return message
