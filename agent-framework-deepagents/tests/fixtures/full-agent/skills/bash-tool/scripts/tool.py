#!/usr/bin/env python3
"""Bash Tool Skill - Execute shell commands"""

import subprocess
import sys


def main(input_data: str = None) -> str:
    """Execute a bash command and return output"""
    if not input_data:
        return "Usage: provide a bash command to execute"

    try:
        result = subprocess.run(
            input_data,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = result.stdout.strip()
        if result.stderr.strip():
            output += "\n" + result.stderr.strip()
        return output or "(no output)"
    except subprocess.TimeoutExpired:
        return "Command timed out after 30s"
    except Exception as e:
        return f"Error executing command: {e}"


if __name__ == "__main__":
    cmd = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else sys.stdin.read().strip()
    print(main(cmd))
