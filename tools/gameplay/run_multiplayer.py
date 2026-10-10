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
# 0.7.7: two concurrent clients, an operator offender and a plain bystander, then a restart and the
# offender alone reconnecting. Reaction timers are shortened and debug logging is on, so the run is
# minutes rather than tens of minutes and the server log can show a line it refused to deliver.
p.add_argument('--duo', action='store_true')
p.add_argument('--display2', default=':92')
p.add_argument('--client-heap', default='3G')
p.add_argument('--server-heap', default='2G')
p.add_argument('--role', default='', help='Custom first-phase client scenario')
p.add_argument('--resume-role', default='', help='Custom reconnect scenario instead of the default saved-state check')
p.add_argument('--common-config', type=Path)
p.add_argument('--client-config', type=Path)
p.add_argument('--op-level', type=int, choices=range(5), default=4)
p.add_argument('--duo-roles', nargs=3, default=['offender', 'bystander', 'offender-resume'],
               metavar=('ACTOR', 'BYSTANDER', 'RESUME'))
a = p.parse_args()
if a.duo and (a.role or a.resume_role): p.error('Custom roles cannot be combined with --duo')
work = a.work.resolve()
if work.exists(): p.error('Refusing to reuse a previous test directory')
if 'eula=true' not in a.eula.read_text().lower(): p.error('Existing EULA acceptance is required')
server = work / 'server'
(server / 'mods').mkdir(parents=True)
(server / 'libraries').symlink_to((a.server_install / 'libraries').resolve(strict=True), target_is_directory=True)
shutil.copy2(a.eula, server / 'eula.txt')
(server / 'config').mkdir()
(server / 'config/mca.json').write_text(json.dumps({'version': 1 if a.minecraft == '1.20.1' else 2, 'launchIntoDestiny': False}))
if a.duo:
    # A partial file: the loader keeps these values and fills every other key with its default.
    (server / 'config/mcacrime-common.toml').write_text(
        '[reactions]\n\treactionFleeTicks = 40\n\treactionHideTicks = 40\n\treactionRecoveryTicks = 200\n'
        '[debug]\n\tdebugLogging = true\n')
if a.common_config:
    shutil.copy2(a.common_config.resolve(strict=True), server / 'config/mcacrime-common.toml')
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
(server / 'ops.json').write_text(json.dumps([{'uuid': identity, 'name': 'CrimeQa', 'level': a.op_level, 'bypassesPlayerLimit': False}] if a.op_level else []))
print('Multiplayer evidence:', work, flush=True)
for phase in range(2):
    logpath = server / f'console-{phase}.log'
    with logpath.open('w') as log:
        process = subprocess.Popen([a.java, '-Xmx' + a.server_heap, '@' + str(a.server_args), 'nogui'], cwd=server,
                                   stdin=subprocess.PIPE, stdout=log, stderr=log, text=True)
        try:
            deadline = time.monotonic() + 180
            while 'Done (' not in logpath.read_text(errors='replace'):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Server failed to start: ' + str(logpath))
                time.sleep(0.25)
            def client(name, display, extra):
                command = [sys.executable, str(Path(__file__).with_name('run_client.py').resolve()),
                           '--work', str(work / name), '--server', f'127.0.0.1:{port}', '--display', display,
                           '--heap', a.client_heap]
                for key in ('install', 'crime', 'harness', 'mca', 'xvfb', 'loader', 'minecraft', 'java', 'architectury', 'geckolib'):
                    value = getattr(a, key)
                    if value: command += ['--' + key, str(value.resolve() if isinstance(value, Path) else value)]
                for jar in a.extra_mod: command += ['--extra-mod', str(jar.resolve())]
                for key in ('common_config', 'client_config'):
                    value = getattr(a, key)
                    if value: command += ['--' + key.replace('_', '-'), str(value.resolve())]
                return command + extra
            if a.duo:
                sync = work / 'sync'
                sync.mkdir(exist_ok=True)
                common = ['--sync', str(sync), '--timeout', '900']
                if phase:
                    subprocess.run(client(f'client-{phase}-offender', a.display,
                                          common + ['--role', a.duo_roles[2]]), check=True)
                else:
                    pair = [subprocess.Popen(client('client-0-offender', a.display, common + ['--role', a.duo_roles[0]])),
                            subprocess.Popen(client('client-0-bystander', a.display2,
                                                    common + ['--role', a.duo_roles[1], '--username', 'CrimeBystander']))]
                    codes = [proc.wait() for proc in pair]
                    if any(codes):
                        raise RuntimeError(f'Concurrent clients failed: {codes}')
            else:
                command = client(f'client-{phase}', a.display, [])
                if phase: command += ['--role', a.resume_role] if a.resume_role else ['--resume']
                elif a.law: command += ['--law']
                elif a.role: command += ['--role', a.role]
                subprocess.run(command, check=True)
            process.stdin.write('stop\n'); process.stdin.flush()
            if process.wait(timeout=60) != 0: raise RuntimeError('Server shutdown failed')
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
if a.duo:
    # The server's own record of the lines it would not deliver, which is what makes the offender's
    # "heard nothing while away" more than an absence.
    suppressed = [line.strip() for line in (server / 'console-0.log').read_text(errors='replace').splitlines()
                  if 'not delivered: out of earshot' in line and 'CrimeQa' in line]
    (work / 'suppressed-lines.txt').write_text('\n'.join(suppressed) + '\n')
    print(f'EVIDENCE {len(suppressed)} villager line(s) to CrimeQa refused as out of earshot', flush=True)
    for line in suppressed[:5]:
        print('  ' + line, flush=True)
    print('PASS two concurrent clients, restart and offender reconnect', flush=True)
else:
    print('PASS real multiplayer commands, synchronization, reconnect and server restart', flush=True)
