import base64
import os
from langchain_core.tools import tool


@tool
def image_to_text(image_path: str) -> str:
    """Extract text from an image file using OCR or base64 text extraction.

    Supports common image formats (PNG, JPG, GIF, BMP).
    For invoice recognition and document scanning.

    Args:
        image_path: Path to the image file

    Returns:
        Extracted text content from the image.
    """
    try:
        if not os.path.exists(image_path):
            return f"Error: Image file not found: {image_path}"

        ext = os.path.splitext(image_path)[1].lower()
        if ext not in (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff", ".tif"):
            return f"Error: Unsupported image format: {ext}"

        try:
            from PIL import Image
            import pytesseract

            img = Image.open(image_path)
            text = pytesseract.image_to_string(img, lang="chi_sim+eng")
            if text.strip():
                return text.strip()
        except ImportError:
            pass

        try:
            import subprocess
            result = subprocess.run(
                ["tesseract", image_path, "stdout", "-l", "chi_sim+eng"],
                capture_output=True, text=True, timeout=60
            )
            if result.returncode == 0 and result.stdout.strip():
                return result.stdout.strip()
            if result.stderr:
                return f"Error: {result.stderr.strip()}"
        except FileNotFoundError:
            pass
        except Exception:
            pass

        with open(image_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode("utf-8")
        file_size = os.path.getsize(image_path)
        mime = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
                ".gif": "image/gif", ".bmp": "image/bmp", ".webp": "image/webp"}

        return (
            f"[Image: {image_path}]\n"
            f"Format: {ext}, Size: {file_size} bytes\n"
            f"Base64 data (first 500 chars): {b64[:500]}..."
        )

    except Exception as e:
        return f"Error processing image: {e}"
