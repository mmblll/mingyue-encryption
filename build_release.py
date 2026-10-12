"""Build a release using optional signing environment variables; never log secrets."""
import json
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parent
wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
subprocess.run([str(wrapper), '--no-daemon', ':app:assembleRelease'], cwd=root, check=True)
output = root / 'app/build/outputs/apk/release'
metadata = json.loads((output / 'output-metadata.json').read_text(encoding='utf-8'))
entry = metadata['elements'][0]
apk = output / entry['outputFile']
unsigned = '-unsigned' in apk.name
destination = root / 'dist' / f"mingyue-encryption-{entry['versionName']}{'-unsigned' if unsigned else ''}.apk"
destination.parent.mkdir(exist_ok=True)
shutil.copy2(apk, destination)
print('APK:', destination.name)
if unsigned:
    print('Unsigned APK: configure your own signing environment variables before installing or publishing.')
