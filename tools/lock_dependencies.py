#!/usr/bin/env python3
from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parents[1];cpp=root/'app/src/main/cpp'
paths=[cpp/'llama.cpp']+[x.parent for x in sorted((cpp/'vendor').glob('*/.git'))]
commits={str(x.relative_to(root)):subprocess.check_output(['git','-C',str(x),'rev-parse','HEAD'],text=True).strip() for x in paths}
lock=root/'native-dependencies.lock.json'
if lock.exists() and json.loads(lock.read_text())!=commits:
    raise SystemExit('Dependency revisions differ from the lock. Restore recorded commits or consciously regenerate the lock.')
lock.write_text(json.dumps(commits,indent=2)+'\n')
