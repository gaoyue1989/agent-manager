import pytest
import tempfile
from pathlib import Path

from server.services.custom_tool_manager import CustomToolManager


class TestCustomToolManager:
    """CustomToolManager 单元测试"""

    def test_init_with_path(self):
        """测试初始化"""
        manager = CustomToolManager("/tmp/custom-tools")
        assert manager.custom_tools_dir == Path("/tmp/custom-tools")

    def test_init_with_pathlib(self):
        """测试 Path 对象初始化"""
        manager = CustomToolManager(Path("/tmp/custom-tools"))
        assert manager.custom_tools_dir == Path("/tmp/custom-tools")

    def test_load_tools_empty_dir(self):
        """测试空目录"""
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["some_tool"])
            assert tools == []

    def test_load_tools_nonexistent_dir(self):
        """测试不存在的目录"""
        manager = CustomToolManager("/nonexistent/path")
        tools = manager.load_tools(["some_tool"])
        assert tools == []

    def test_load_tools_single_tool(self):
        """测试加载单个工具"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "echo.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["echo"])
            assert len(tools) == 1
            assert tools[0].name == "echo"
            assert "Echo" in tools[0].description

    def test_load_tools_multiple_tools(self):
        """测试加载多个工具"""
        with tempfile.TemporaryDirectory() as tmpdir:
            echo_file = Path(tmpdir) / "echo.py"
            echo_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            calc_file = Path(tmpdir) / "calculator.py"
            calc_file.write_text('''
from langchain_core.tools import tool

@tool
def add(a: int, b: int) -> int:
    """Add two numbers."""
    return a + b
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["echo", "calculator"])
            assert len(tools) == 2
            names = [t.name for t in tools]
            assert "echo" in names
            assert "add" in names

    def test_load_tools_file_with_multiple_tools(self):
        """测试单个文件包含多个工具"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "math_tools.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def add(a: int, b: int) -> int:
    """Add two numbers."""
    return a + b

@tool
def multiply(a: int, b: int) -> int:
    """Multiply two numbers."""
    return a * b
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["math_tools"])
            assert len(tools) >= 1

    def test_load_all_tools(self):
        """测试加载所有工具"""
        with tempfile.TemporaryDirectory() as tmpdir:
            echo_file = Path(tmpdir) / "echo.py"
            echo_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            calc_file = Path(tmpdir) / "calculator.py"
            calc_file.write_text('''
from langchain_core.tools import tool

@tool
def add(a: int, b: int) -> int:
    """Add two numbers."""
    return a + b
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_all_tools()
            assert len(tools) == 2

    def test_load_tools_skip_underscore_files(self):
        """测试跳过下划线开头的文件"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "echo.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            init_file = Path(tmpdir) / "_init.py"
            init_file.write_text('''
from langchain_core.tools import tool

@tool
def internal_tool() -> str:
    """Internal tool."""
    return "internal"
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_all_tools()
            assert len(tools) == 1
            assert tools[0].name == "echo"

    def test_load_tools_syntax_error(self):
        """测试语法错误处理"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "broken.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def broken_tool(  # syntax error
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["broken"])
            assert tools == []
            assert "broken" in manager.get_load_errors()

    def test_load_tools_non_tool_function(self):
        """测试非工具函数"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "helpers.py"
            tool_file.write_text('''
def regular_function(x: int) -> int:
    """A regular function, not a tool."""
    return x * 2
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["helpers"])
            assert tools == []

    def test_get_available_tool_names(self):
        """测试获取可用工具名"""
        with tempfile.TemporaryDirectory() as tmpdir:
            echo_file = Path(tmpdir) / "echo.py"
            echo_file.write_text("pass")
            calc_file = Path(tmpdir) / "calculator.py"
            calc_file.write_text("pass")
            manager = CustomToolManager(tmpdir)
            names = manager.get_available_tool_names()
            assert "echo" in names
            assert "calculator" in names

    def test_get_tool_summaries(self):
        """测试获取工具摘要"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "echo.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["echo"])
            summaries = manager.get_tool_summaries(tools)
            assert len(summaries) == 1
            assert summaries[0]["name"] == "echo"
            assert summaries[0]["loaded"] is True
            assert "Echo" in summaries[0]["description"]

    def test_load_tools_cached(self):
        """测试工具缓存"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tool_file = Path(tmpdir) / "echo.py"
            tool_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            manager = CustomToolManager(tmpdir)
            tools1 = manager.load_tools(["echo"])
            tools2 = manager.load_tools(["echo"])
            assert tools1[0] is tools2[0]

    def test_load_tools_partial_success(self):
        """测试部分加载成功"""
        with tempfile.TemporaryDirectory() as tmpdir:
            echo_file = Path(tmpdir) / "echo.py"
            echo_file.write_text('''
from langchain_core.tools import tool

@tool
def echo(message: str) -> str:
    """Echo the input message back."""
    return message
''')
            manager = CustomToolManager(tmpdir)
            tools = manager.load_tools(["echo", "nonexistent"])
            assert len(tools) == 1
            assert tools[0].name == "echo"
