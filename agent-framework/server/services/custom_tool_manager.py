import importlib.util
from pathlib import Path
from typing import Any, Optional

from langchain_core.tools import StructuredTool


class CustomToolManager:
    """自定义工具管理器

    从 custom-tools/ 目录动态加载 @tool 装饰的 Python 脚本
    """

    def __init__(self, custom_tools_dir: Path | str):
        self.custom_tools_dir = Path(custom_tools_dir)
        self._loaded_tools: dict[str, StructuredTool] = {}
        self._load_errors: dict[str, str] = {}

    def load_tools(self, tool_names: list[str]) -> list[StructuredTool]:
        """按名称列表加载工具

        Args:
            tool_names: 工具名列表（来自 OAF 配置的 tools 字段）

        Returns:
            加载成功的 StructuredTool 列表
        """
        if not self.custom_tools_dir.exists():
            return []

        loaded = []
        for name in tool_names:
            tool = self._load_single_tool(name)
            if tool:
                loaded.append(tool)
        return loaded

    def load_all_tools(self) -> list[StructuredTool]:
        """加载目录下所有工具

        Returns:
            所有加载成功的 StructuredTool 列表
        """
        if not self.custom_tools_dir.exists():
            return []

        tools = []
        for py_file in self.custom_tools_dir.glob("*.py"):
            if py_file.name.startswith("_"):
                continue
            file_tools = self._load_tools_from_file(py_file)
            tools.extend(file_tools)
        return tools

    def _load_single_tool(self, name: str) -> Optional[StructuredTool]:
        """加载单个工具

        先检查缓存，再尝试从文件加载
        """
        if name in self._loaded_tools:
            return self._loaded_tools[name]

        py_file = self.custom_tools_dir / f"{name}.py"
        if not py_file.exists():
            return None

        tools = self._load_tools_from_file(py_file)
        for tool in tools:
            if tool.name == name:
                self._loaded_tools[name] = tool
                return tool

        if tools:
            self._loaded_tools[name] = tools[0]
            return tools[0]

        return None

    def _load_tools_from_file(self, py_file: Path) -> list[StructuredTool]:
        """从 Python 文件加载所有 @tool 装饰的函数

        Args:
            py_file: Python 文件路径

        Returns:
            文件中所有 StructuredTool 实例
        """
        tools = []
        try:
            module_name = f"custom_tool_{py_file.stem}"
            spec = importlib.util.spec_from_file_location(module_name, str(py_file))
            if not spec or not spec.loader:
                return tools

            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)

            for attr_name in dir(module):
                if attr_name.startswith("_"):
                    continue
                obj = getattr(module, attr_name)
                if isinstance(obj, StructuredTool):
                    tools.append(obj)

        except Exception as e:
            self._load_errors[py_file.stem] = str(e)

        return tools

    def get_available_tool_names(self) -> list[str]:
        """获取目录下所有可用工具名

        Returns:
            工具名列表（不含 .py 后缀）
        """
        if not self.custom_tools_dir.exists():
            return []

        names = []
        for py_file in self.custom_tools_dir.glob("*.py"):
            if not py_file.name.startswith("_"):
                names.append(py_file.stem)
        return sorted(names)

    def get_tool_summaries(self, loaded_tools: list[StructuredTool] = None) -> list[dict]:
        """获取工具摘要

        Args:
            loaded_tools: 已加载的工具列表（可选，用于获取运行时状态）

        Returns:
            工具摘要列表
        """
        summaries = []
        available_names = self.get_available_tool_names()
        loaded_names = set(t.name for t in (loaded_tools or []))

        for name in available_names:
            tool = self._loaded_tools.get(name)
            summary = {
                "name": name,
                "description": tool.description if tool else "",
                "loaded": name in loaded_names,
                "file": f"custom-tools/{name}.py",
            }
            if name in self._load_errors:
                summary["error"] = self._load_errors[name]
            summaries.append(summary)

        return summaries

    def get_load_errors(self) -> dict[str, str]:
        """获取加载错误

        Returns:
            工具名 -> 错误信息
        """
        return self._load_errors.copy()
