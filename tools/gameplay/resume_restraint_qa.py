#!/usr/bin/env python3
"""Reconnect to the failed restraint fixture's saved world and exercise removal."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('session', type=Path)
    parser.add_argument('loader', choices=['forge', 'neo'])
    args = parser.parse_args()
    session = args.session.resolve()
    cfg = json.loads((session / 'environment.json').read_text())[args.loader]
    work = session / args.loader
    previous = json.loads((work / 'commands/extended-mp-physical.json').read_text())
    if 'returncode' not in previous: raise RuntimeError('Original server is still running')
    server = work / 'extended-mp-physical/server'
    logpath = server / 'console-restraint-resume.log'
    if logpath.exists(): raise RuntimeError('Refusing to overwrite restart evidence')
    port = re.search(r'^server-port=(\d+)', (server / 'server.properties').read_text(), re.M)[1]
    artifacts = work / 'artifacts-resume'; artifacts.mkdir()
    harness = artifacts / 'crime-gameplay-checks-0.7.7.jar'
    shutil.copy2(Path(cfg['tree']) / 'build/libs' / harness.name, harness)
    launch = [cfg['java'], '-Xmx2G', '@' + cfg['server-args'], 'nogui']
    (server / 'launch-restraint-resume.json').write_text(json.dumps(launch, indent=2))
    with logpath.open('w') as log:
        process = subprocess.Popen(launch, cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=log, text=True)
        try:
            deadline = time.monotonic() + 180
            while 'Done (' not in logpath.read_text(errors='replace'):
                if process.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Restart failed')
                time.sleep(0.25)
            command = ['python3', str(Path(cfg['tree']) / 'tools/gameplay/run_client.py'), '--work', str(work / 'extended-restraint-resume-client'), '--install', '/home/otectus/Documents/curseforge/minecraft/Install', '--crime', str(work / 'artifacts/mcacrime-0.7.7.jar'), '--harness', str(harness), '--xvfb', '/home/otectus/Projects/.mcacrime-qa/env/xvfb/Xvfb', '--display', ':79', '--server', '127.0.0.1:' + port, '--role', 'extended-restraint-resume']
            for key in ('loader', 'minecraft', 'java', 'mca', 'architectury', 'geckolib'):
                if key in cfg: command += ['--' + key, cfg[key]]
            (server / 'client-restraint-resume-command.json').write_text(json.dumps(command, indent=2))
            result = subprocess.run(command, timeout=360)
            process.stdin.write('stop\n'); process.stdin.flush(); process.wait(timeout=60)
            return result.returncode
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__':
    raise SystemExit(main())
