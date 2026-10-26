"""Compute the APK version. Requires Python 3.12 and Git; never writes Git state."""
import logging
from pathlib import Path
import re
import subprocess
import sys

LOG = logging.getLogger('version_name')


def version_name(root: Path) -> str:
    root = root.resolve()

    def git(*args: str) -> str:
        return subprocess.run(
            ['git', '-C', str(root), *args], check=True,
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()

    try:
        # A worktree has a .git file; an ordinary checkout has a .git directory.
        if not (root / '.git').exists():
            raise ValueError('no .git at project root (source archive or nested copy)')
        if Path(git('rev-parse', '--show-toplevel')).resolve() != root:
            raise ValueError('Git repository root differs from project root')
        sha = git('rev-parse', '--verify', 'HEAD')
        if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', sha):
            raise ValueError('invalid Git HEAD')
        # Untracked local files are deliberately outside the version contract.
        dirty = bool(git('status', '--porcelain', '--untracked-files=no', '--ignore-submodules=none'))
        return '0.1.0+' + sha[:7] + ('-dirty' if dirty else '')
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        LOG.warning('APK version uses unknown: %s', detail)
        return '0.1.0+unknown'


if __name__ == '__main__':
    print(version_name(Path(sys.argv[1])))
