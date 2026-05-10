#!/usr/bin/env python3
"""
Codegen 测试运行脚本 - 全量测试

Usage:
    python run_all_tests.py [--unit] [--integration] [--e2e] [--llm]
"""

import subprocess
import sys
import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
VENV_PYTHON = str(PROJECT_ROOT / "codegen" / "venv" / "bin" / "python")


def run_tests(test_path: str, args: list[str] = None):
    cmd = [VENV_PYTHON, "-m", "pytest", test_path, "-v", "--tb=short"]
    if args:
        cmd.extend(args)
    env = os.environ.copy()
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    subprocess.run(cmd, env=env, cwd=str(PROJECT_ROOT))


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Run codegen tests")
    parser.add_argument("--unit", action="store_true", default=True, help="Run unit tests")
    parser.add_argument("--integration", action="store_true", help="Run integration tests")
    parser.add_argument("--e2e", action="store_true", help="Run E2E tests (requires server)")
    parser.add_argument("--llm", action="store_true", help="Run LLM integration tests")
    parser.add_argument("--all", action="store_true", help="Run all tests")
    parser.add_argument("-x", action="store_true", help="Stop on first failure")
    parser.add_argument("--quiet", "-q", action="store_true", help="Quiet mode")

    args = parser.parse_args()

    if args.all:
        args.integration = True
        args.e2e = True
        args.llm = True

    pytest_args = []
    if args.x:
        pytest_args.append("-x")
    if args.quiet:
        pytest_args.append("-q")

    test_base = str(PROJECT_ROOT / "codegen" / "tests")

    if args.unit:
        print("\n=== Running Unit Tests ===")
        run_tests(f"{test_base}/unit/", pytest_args)

    if args.integration:
        print("\n=== Running Integration Tests ===")
        run_tests(f"{test_base}/integration/", pytest_args)

    if args.llm:
        print("\n=== Running LLM Integration Tests ===")
        run_tests(f"{test_base}/e2e/test_llm_integration.py", pytest_args)

    if args.e2e:
        print("\n=== Running E2E Tests ===")
        run_tests(f"{test_base}/e2e/test_research_agent.py", pytest_args)

    print("\n=== Test Complete ===")


if __name__ == "__main__":
    main()
