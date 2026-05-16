import asyncio
import asyncmy
from langgraph.checkpoint.mysql.asyncmy import AsyncMySaver


class CheckpointManager:
    """MySQL checkpoint 生命周期管理

    管理 asyncmy 连接和 AsyncMySaver 实例的创建、初始化和关闭
    """

    def __init__(self, dsn: str):
        self._dsn = dsn
        self._conn = None
        self._saver = None

    async def start(self):
        params = self._parse_dsn(self._dsn)
        self._conn = await asyncmy.connect(**params, autocommit=True)
        self._saver = AsyncMySaver(conn=self._conn)
        await self._saver.setup()
        return self._saver

    async def close(self):
        if self._conn:
            await self._conn.ensure_closed()
            self._conn = None
            self._saver = None

    @property
    def saver(self) -> AsyncMySaver:
        return self._saver

    @staticmethod
    def _parse_dsn(dsn: str) -> dict:
        import urllib.parse

        if "://" not in dsn:
            raise ValueError(f"Invalid DSN: {dsn}")

        scheme, rest = dsn.split("://", 1)
        if scheme.startswith("mysql+asyncmy"):
            scheme = "mysql+asyncmy"
        elif scheme.startswith("mysql"):
            scheme = "mysql"
        else:
            raise ValueError(f"Unsupported scheme: {scheme}")

        auth_host, _, path_query = rest.partition("/")
        query_params = {}
        if "?" in path_query:
            path_query, qs = path_query.split("?", 1)
            query_params = dict(urllib.parse.parse_qsl(qs))

        user_pass, _, host_port = auth_host.rpartition("@")
        if not host_port:
            host_port = user_pass
            user_pass = ""
            password = ""
        else:
            user, _, password = user_pass.partition(":")
            password = urllib.parse.unquote(password)

        host, _, port = host_port.partition(":")
        db = path_query or None

        return {
            "user": user,
            "password": password,
            "host": host or "localhost",
            "port": int(port) if port else 3306,
            "db": db,
            "unix_socket": query_params.get("unix_socket"),
        }
