import os
from langchain_core.tools import tool


@tool
def doc_to_md(file_path: str) -> str:
    """Convert a document file to Markdown text.

    Supports: .txt, .md, .json, .csv, .html, .xml, .yaml, .yml, .log

    Args:
        file_path: Path to the document file

    Returns:
        Converted Markdown content.
    """
    try:
        if not os.path.exists(file_path):
            return f"Error: File not found: {file_path}"

        ext = os.path.splitext(file_path)[1].lower()
        max_size = 50000

        with open(file_path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read(max_size)

        if ext in (".txt", ".md", ".log"):
            return content

        if ext == ".json":
            import json
            try:
                data = json.loads(content)
                formatted = json.dumps(data, ensure_ascii=False, indent=2)
                return f"```json\n{formatted[:max_size]}\n```"
            except json.JSONDecodeError:
                return content

        if ext == ".csv":
            lines = content.strip().split("\n")
            if not lines:
                return "(empty CSV)"
            headers = lines[0].split(",")
            md = "| " + " | ".join(headers) + " |\n"
            md += "| " + " | ".join(["---"] * len(headers)) + " |\n"
            for line in lines[1:21]:
                cells = line.split(",")
                md += "| " + " | ".join(cells) + " |\n"
            if len(lines) > 21:
                md += f"\n... ({len(lines)-1} rows total, showing first 20)"
            return md

        if ext in (".html", ".htm"):
            from html.parser import HTMLParser

            class TextExtractor(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.text = []

                def handle_data(self, data):
                    text = data.strip()
                    if text:
                        self.text.append(text)

            extractor = TextExtractor()
            extractor.feed(content)
            return "\n".join(extractor.text)[:max_size]

        if ext in (".yaml", ".yml"):
            return f"```yaml\n{content[:max_size]}\n```"

        if ext == ".xml":
            return f"```xml\n{content[:max_size]}\n```"

        return content

    except Exception as e:
        return f"Error converting document: {e}"
