"""Launch project Codex without unrelated user MCP servers; preserve user config."""

import json
import os
import re
import subprocess
import sys

cli = os.environ.get("HETERODYNE_CODEX_BIN", "codex")
servers = json.loads(subprocess.check_output([cli, "mcp", "list", "--json"], text=True))
overrides = []
for server in servers:
    name = server["name"]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        raise SystemExit("MCP name cannot be addressed safely by this CLI; review project integrations before launch.")
    overrides.extend(["--config", f"mcp_servers.{name}.enabled=false"])
os.execvpe(cli, [cli, *overrides, *sys.argv[1:]], os.environ)
