import os
import fnmatch
from langchain_core.tools import tool


@tool
def glob(pattern: str, path: str = ".") -> str:
    """Search for files matching a glob pattern.

    Use this tool to find files by pattern. Supports wildcards:
    - * matches any characters
    - ? matches any single character
    - ** recurses into subdirectories

    Args:
        pattern: Glob pattern to match (e.g., "*.py", "**/*.json")
        path: Directory to search in (default: current directory)

    Returns:
        Newline-separated list of matching file paths, limited to 200 results.
    """
    import glob as _glob

    try:
        search_path = os.path.join(path, pattern)
        results = _glob.glob(search_path, recursive=True)
        if not results:
            return f"No files matched pattern '{pattern}' in {path}"
        results.sort()
        if len(results) > 200:
            results = results[:200]
            results.append("... (truncated, showing first 200)")
        return "\n".join(results)
    except Exception as e:
        return f"Error: {e}"
