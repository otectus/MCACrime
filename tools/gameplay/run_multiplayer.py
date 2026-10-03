#!/usr/bin/env python3
"""Use an isolated loopback server and two real client sessions to test a normal save/restart."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid

p = argparse.ArgumentParser(description=__doc__)
for name in ('work', 'server-install', 'server-args', 'eula', 'install', 'crime', 'harness', 'mca', 'xvfb'):
    p.add_argument('--' + name, type=Path, required=True)
for name in ('loader', 'minecraft', 'java'):
    p.add_argument('--' + name, required=True)
p.add_argument('--architectury', type=Path)
p.add_argument('--geckolib', type=Path)
p.add_argument('--extra-mod', type=Path, action='append', default=[])
p.add_argument('--display', default=':93')
p.add_argument('--law', action='store_true')
a = p.parse_args()
work = a.work.resolve()
if work.exists(): p.error('Refusing to reuse a previous test directory')
if 'eula=true' not in a.eula.read_text().lower(): p.error('Existing EULA acceptance is required')
server = work / 'server'
(server / 'mods').mkdir(parents=True)
(server / 'libraries').symlink_to((a.server_install / 'libraries').resolve(strict=True), target_is_directory=True)
shutil.copy2(a.eula, server / 'eula.txt')
(server / 'config').mkdir()
(server / 'config/mca.json').write_text(json.dumps({'version': 1 if a.minecraft == '1.20.1' else 2, 'launchIntoDestiny': False}))
mods = [a.crime, a.mca, a.architectury, a.geckolib, *a.extra_mod]
for jar in filter(None, mods): shutil.copy2(jar.resolve(strict=True), server / 'mods' / jar.name)
(server / 'artifacts.json').write_text(json.dumps({f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in (server / 'mods').iterdir()}, indent=2))
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
(server / 'server.properties').write_text(
    f'online-mode=false\nserver-ip=127.0.0.1\nserver-port={port}\n'
    'level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n'
    'spawn-protection=0\nenforce-secure-profile=false\ndifficulty=peaceful\n'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
identity = str(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:CrimeQa').digest(), version=3))
(server / 'ops.json').write_text(json.dumps([{'uuid': identity, 'name': 'CrimeQa', 'level': 4, 'bypassesPlayerLimit': False}]))
print('Multiplayer evidence:', work, flush=True)
for phase in range(2):
    logpath = server / f'console-{phase}.log'
    with logpath.open('w') as log:
        process = subprocess.Popen([a.java, '-Xmx2G', '@' + str(a.server_args), 'nogui'], cwd=server,
                                   stdin=subprocess.PIPE, stdout=log, stderr=log, text=True)
        try:
            deadline = time.monotonic() + 180
            while 'Done (' not in logpath.read_text(errors='replace'):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Server failed to start: ' + str(logpath))
                time.sleep(0.25)
            command = [sys.executable, str(Path(__file__).with_name('run_client.py').resolve()),
                       '--work', str(work / f'client-{phase}'), '--server', f'127.0.0.1:{port}', '--display', a.display]
            for key in ('install', 'crime', 'harness', 'mca', 'xvfb', 'loader', 'minecraft', 'java', 'architectury', 'geckolib'):
                value = getattr(a, key)
                if value: command += ['--' + key, str(value.resolve() if isinstance(value, Path) else value)]
            for jar in a.extra_mod: command += ['--extra-mod', str(jar.resolve())]
            if phase: command += ['--resume']
            elif a.law: command += ['--law']
            subprocess.run(command, check=True)
            process.stdin.write('stop\n'); process.stdin.flush()
            if process.wait(timeout=60) != 0: raise RuntimeError('Server shutdown failed')
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
print('PASS real multiplayer commands, synchronization, reconnect and server restart', flush=True)
