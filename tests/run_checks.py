"""Convenience entry point; Gradle resolves the compiler and Java runtime."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
raise SystemExit(subprocess.run([str(wrapper), '--no-daemon', 'cryptoChecks'], cwd=root).returncode)
