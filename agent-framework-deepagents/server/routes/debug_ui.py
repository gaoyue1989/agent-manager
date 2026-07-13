from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse

TEMPLATE_DIR = Path(__file__).parent.parent / "templates"


def register_debug_ui(app: FastAPI):
    """注册内嵌调试页面"""

    @app.get("/debug", response_class=HTMLResponse)
    async def debug_page():
        html_path = TEMPLATE_DIR / "debug_page.html"
        if html_path.exists():
            return html_path.read_text(encoding="utf-8")
        return "<h1>Debug page not found</h1>"
