"""Codegen 核心模块"""

from .scaffold_generator import ScaffoldGenerator
from .legacy_migrator import migrate_legacy_config

__all__ = ["ScaffoldGenerator", "migrate_legacy_config"]
