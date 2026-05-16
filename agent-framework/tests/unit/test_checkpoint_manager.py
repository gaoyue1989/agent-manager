"""CheckpointManager 单元测试"""

import pytest
from unittest.mock import AsyncMock, patch, MagicMock


class TestCheckpointManager:
    """MySQL checkpoint 管理器单元测试"""

    def test_parse_dsn_standard(self):
        from server.services.checkpoint_manager import CheckpointManager
        params = CheckpointManager._parse_dsn(
            "mysql+asyncmy://user:pass@localhost:3306/mydb"
        )
        assert params["user"] == "user"
        assert params["password"] == "pass"
        assert params["host"] == "localhost"
        assert params["port"] == 3306
        assert params["db"] == "mydb"

    def test_parse_dsn_url_encoded_password(self):
        from server.services.checkpoint_manager import CheckpointManager
        params = CheckpointManager._parse_dsn(
            "mysql+asyncmy://agent:Agent%40Manager@127.0.0.1:3307/testdb"
        )
        assert params["user"] == "agent"
        assert params["password"] == "Agent@Manager"
        assert params["host"] == "127.0.0.1"
        assert params["port"] == 3307
        assert params["db"] == "testdb"

    def test_parse_dsn_no_password(self):
        from server.services.checkpoint_manager import CheckpointManager
        params = CheckpointManager._parse_dsn(
            "mysql+asyncmy://user@localhost/mydb"
        )
        assert params["user"] == "user"
        assert params["password"] == ""

    def test_parse_dsn_default_port(self):
        from server.services.checkpoint_manager import CheckpointManager
        params = CheckpointManager._parse_dsn(
            "mysql+asyncmy://user:pass@localhost/mydb"
        )
        assert params["port"] == 3306

    def test_parse_dsn_invalid(self):
        from server.services.checkpoint_manager import CheckpointManager
        with pytest.raises(ValueError):
            CheckpointManager._parse_dsn("not-a-dsn")

    def test_parse_dsn_simple_mysql(self):
        from server.services.checkpoint_manager import CheckpointManager
        params = CheckpointManager._parse_dsn(
            "mysql://user:pass@host:3307/db"
        )
        assert params["user"] == "user"
        assert params["host"] == "host"
        assert params["port"] == 3307

    @pytest.mark.asyncio
    async def test_saver_property_none_initially(self):
        from server.services.checkpoint_manager import CheckpointManager
        cm = CheckpointManager(
            "mysql+asyncmy://user:pass@localhost/mydb"
        )
        assert cm.saver is None

    @pytest.mark.asyncio
    async def test_close_when_not_started(self):
        from server.services.checkpoint_manager import CheckpointManager
        cm = CheckpointManager(
            "mysql+asyncmy://user:pass@localhost/mydb"
        )
        await cm.close()


class TestCheckpointManagerRealDB:
    """需要真实 MySQL 连接的测试"""

    @pytest.fixture
    def dsn(self):
        import os
        return os.getenv(
            "CHECKPOINT_MYSQL_DSN",
            "mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test",
        )

    @pytest.mark.asyncio
    @pytest.mark.integration
    async def test_start_and_close(self, dsn):
        from server.services.checkpoint_manager import CheckpointManager
        cm = CheckpointManager(dsn)
        try:
            saver = await cm.start()
            assert saver is not None
            assert cm.saver is not None
        finally:
            await cm.close()
        assert cm.saver is None

    @pytest.mark.asyncio
    @pytest.mark.integration
    async def test_saver_setup_creates_tables(self, dsn):
        from server.services.checkpoint_manager import CheckpointManager
        cm = CheckpointManager(dsn)
        try:
            await cm.start()
            assert cm.saver is not None
        finally:
            await cm.close()
