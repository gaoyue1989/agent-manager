.PHONY: run dev test test-unit test-integration test-e2e docker-build docker-up docker-down clean

run:
	python -m server.main

dev:
	uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8000 --reload

test:
	pytest tests/unit/ tests/integration/ -v

test-unit:
	pytest tests/unit/ -v

test-integration:
	pytest tests/integration/ -v

test-e2e:
	pytest tests/e2e/ -v

test-e2e-tool:
	pytest tests/e2e/test_tool_bash.py -v

test-e2e-mcp:
	pytest tests/e2e/test_mcp_e2e.py -v

test-e2e-skill:
	pytest tests/e2e/test_skill_e2e.py -v

test-e2e-streaming:
	pytest tests/e2e/test_streaming.py -v

test-cov:
	pytest tests/unit/ tests/integration/ -v --cov=server --cov-report=term-missing

docker-build:
	docker build -t agent-framework:latest .

docker-up:
	docker compose up -d

docker-down:
	docker compose down

clean:
	rm -rf __pycache__ .pytest_cache tests/__pycache__ tests/**/__pycache__ server/__pycache__ server/**/__pycache__ .coverage
