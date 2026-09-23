#!/usr/bin/env python3
"""Run the mask rendering fixture in a fresh Linux client using existing launcher files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


def allowed(rules):
    result = not rules
    for rule in rules or []:
        target = rule.get('os', {})
        if target.get('name', 'linux') == 'linux' and target.get('arch', 'amd64') in ('amd64', 'x86_64') and not rule.get('features'):
            result = rule['action'] == 'allow'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('work', 'install', 'crime', 'harness', 'mca', 'architectury', 'geckolib', 'xvfb'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--forge', required=True, help='Installed launcher profile, e.g. forge-47.4.23')
    parser.add_argument('--display', default=':96')
    parser.add_argument('--java', default='/usr/lib/jvm/java-17-openjdk/bin/java')
    args = parser.parse_args()
    work, install = args.work.resolve(), args.install.resolve()
    if work.exists():
        parser.error('Refusing to reuse an existing client directory')
    work.mkdir(parents=True)
    (work / 'mods').mkdir()
    (work / 'natives').mkdir()
    (work / 'config').mkdir()
    (work / 'config/mca.json').write_text(json.dumps({'enableVillagerPlayerModel': False, 'enablePlayerShaders': False}))
    artifacts = [getattr(args, key).resolve(strict=True) for key in ('crime', 'harness', 'mca', 'architectury', 'geckolib')]
    for artifact in artifacts:
        shutil.copy2(artifact, work / 'mods' / artifact.name)
    (work / 'artifacts.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}, indent=2))
    (work / 'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\nguiScale:1\n')
    vanilla = json.loads((install / 'versions/1.20.1/1.20.1.json').read_text())
    forge = json.loads((install / 'versions' / args.forge / (args.forge + '.json')).read_text())
    libraries = {}
    for library in vanilla['libraries'] + forge['libraries']:
        if allowed(library.get('rules')):
            parts = library['name'].split(':')
            libraries[':'.join(parts[:2]) + (':' + parts[3] if len(parts) > 3 else '')] = library
    classpath = []
    for library in libraries.values():
        artifact = library.get('downloads', {}).get('artifact')
        if artifact:
            classpath.append(str((install / 'libraries' / artifact['path']).resolve(strict=True)))
    classpath.append(str(install / 'versions/1.20.1/1.20.1.jar'))
    values = {
        'auth_player_name': 'MaskTest', 'version_name': args.forge, 'game_directory': str(work),
        'assets_root': str(install / 'assets'), 'assets_index_name': vanilla['assetIndex']['id'],
        'auth_uuid': uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:MaskTest').hex,
        'auth_access_token': '0', 'clientid': '', 'auth_xuid': '', 'user_type': 'legacy',
        'version_type': 'release', 'natives_directory': str(work / 'natives'),
        'launcher_name': 'MaskAcceptance', 'launcher_version': '1', 'classpath': ':'.join(classpath),
        'classpath_separator': ':', 'library_directory': str(install / 'libraries'),
    }

    def expand(arguments):
        result = []
        for arg in arguments:
            if isinstance(arg, dict):
                if not allowed(arg.get('rules')):
                    continue
                arg = arg['value']
            for value in arg if isinstance(arg, list) else [arg]:
                value = re.sub(r'\$\{([^}]+)\}', lambda match: values[match[1]], value)
                if value.startswith('-DignoreList='):
                    value += ',1.20.1.jar'
                result.append(value)
        return result

    command = [args.java, '-Xmx3G', '-Dcrime.maskTest.output=' + str(work),
               *expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm']), forge['mainClass'],
               *expand(vanilla['arguments']['game'] + forge['arguments']['game']), '--width', '1600', '--height', '1000']
    (work / 'launch-command.json').write_text(json.dumps(command, indent=2))
    environment = dict(os.environ, DISPLAY=args.display, LIBGL_ALWAYS_SOFTWARE='1')
    with (work / 'display.log').open('w') as display_log, (work / 'client.log').open('w') as log:
        display = subprocess.Popen([str(args.xvfb.resolve()), args.display, '-screen', '0', '1600x1000x24', '-nolisten', 'tcp', '-ac'], stdout=display_log, stderr=display_log)
        process = None
        try:
            time.sleep(1)
            process = subprocess.Popen(command, cwd=work, env=environment, stdout=log, stderr=subprocess.STDOUT)
            code = process.wait(timeout=300)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            display.terminate()
            display.wait(timeout=15)
    if code or not (work / 'PASS.txt').is_file() or (work / 'FAIL.txt').exists():
        raise SystemExit('Mask client failed; inspect ' + str(work / 'client.log'))
    print((work / 'PASS.txt').read_text(), end='')
    print('Logs, artifact hashes and screenshots: ' + str(work))


if __name__ == '__main__':
    main()
