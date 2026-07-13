from server.config import load_config
from server.app import create_app

config = load_config()
app = create_app(config)
