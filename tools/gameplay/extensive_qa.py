#!/usr/bin/env python3
"""Record isolated, reproducible runs of the existing two-loader QA fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


ROOT = Path('/home/otectus/Projects')
QA = ROOT / '.mcacrime-qa'
CACHE = Path('/home/otectus/.gradle/caches/modules-2/files-2.1')
TREES = {'forge': ROOT / 'MCACrime', 'neo': ROOT / '1.21.1 Ports/MCACrime_1.21.1'}


def cached(group, artifact, version):
    matches = list((CACHE / group / artifact / version).glob('*/*.jar'))
    matches = [p for p in matches if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if len(matches) != 1:
        raise RuntimeError(f'Expected one cached {artifact} {version}: {matches}')
    return matches[0]


def environment(session):
    result = {}
    forge_fleet = ['7.6.20', '7.6.26', '7.7.0-beta.2', '7.7.1-alpha.2', '7.7.1-beta.1', '7.7.1-beta.2']
    neo_fleet = ['7.7.13', '7.7.22', '7.7.33', '7.7.36-beta.3']
    for label, tree in TREES.items():
        forge = label == 'forge'
        siblings = ROOT if forge else ROOT / '1.21.1 Ports'
        suffix = '' if forge else '_1.21.1'
        companions = {
            'reputation': siblings / f'MCAReputation{suffix}/build/libs/mcareputation-0.6.1.jar',
            'quests': siblings / f'MCAQuests{suffix}/build/libs/mcaquests-1.7.1.jar',
            'conversations': siblings / f'MCAConversations{suffix}/build/libs' / ('mcaconversations-1.8.0.jar' if forge else 'mcaconversations-neoforge-1.8.0+1.21.1.jar'),
            'mobcompat': Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/mcamobcompat-forge-0.2.2+1.20.1.jar') if forge else siblings / 'MCAMobCompatibility_1.21.1/build/libs/mcamobcompat-neoforge-0.2.2+1.21.1.jar',
            'townstead': Path('/home/otectus/Documents/curseforge/minecraft/Instances/MCA Addons/mods/townstead-0.7.6+1.20.1.jar') if forge else siblings / 'MCAQuests_1.21.1/run/companions-1.6.5/townstead-0.7.7+1.21.1.jar',
            'patchouli': cached('maven.modrinth', 'patchouli', '1.20.1-85-forge') if forge else cached('vazkii.patchouli', 'Patchouli', '1.21.1-93-NEOFORGE'),
            'locks': Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/locks_reforged-1.7.5.jar') if forge else siblings / 'Locks_Reforged_1.21.1/build/libs/locks_reforged-neoforge-1.21.1-1.7.5.jar',
            'doors': QA / 'env/mods' / ('forge/DramaticDoors-QuiFabrge-1.20.1-3.3.3.jar' if forge else 'neoforge/DramaticDoors-NeoQuiFab-1.21.1-3.3.2.jar'),
        }
        fleet = {v: cached('maven.modrinth', 'minecraft-comes-alive-reborn', v + '+1.20.1') for v in forge_fleet} if forge else {'7.7.0': cached('maven.modrinth', 'minecraft-comes-alive-reborn', 'EGdYukBI'), **{v: cached('net.conczin.mca', 'mca-neoforge', v + '+1.21.1') for v in neo_fleet}}
        loader = 'forge-47.4.23' if forge else 'neoforge-21.1.250'
        server = QA / 'env/servers' / loader
        result[label] = {
            'tree': str(tree), 'minecraft': '1.20.1' if forge else '1.21.1', 'loader': loader,
            'java': '/usr/lib/jvm/java-17-openjdk/bin/java' if forge else '/home/otectus/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/java',
            'server-install': str(server),
            'server-args': str(server / 'libraries' / ('net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt' if forge else 'net/neoforged/neoforge/21.1.250/unix_args.txt')),
            'eula': str(tree / ('run/eula.txt' if forge else 'run-server/eula.txt')),
            'mca': str(fleet['7.7.1-beta.2' if forge else '7.7.36-beta.3']),
            'fleet': {k: str(v) for k, v in fleet.items()},
            'companions': {k: str(v) for k, v in companions.items()},
        }
        if forge:
            result[label].update(architectury=str(cached('dev.architectury', 'architectury-forge', '9.2.14')), geckolib=str(cached('software.bernie.geckolib', 'geckolib-forge-1.20.1', '4.8.3')))
    manifest = {}
    def record(value):
        if isinstance(value, dict):
            for v in value.values(): record(v)
        elif isinstance(value, str) and value.endswith('.jar'):
            p = Path(value)
            manifest[value] = {'exists': p.is_file(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None}
    record(result)
    (session / 'environment.json').write_text(json.dumps(result, indent=2))
    (session / 'input-jars.json').write_text(json.dumps(manifest, indent=2))
    missing = [p for p, v in manifest.items() if not v['exists']]
    print(f'Environment: {len(manifest)} jars; missing={missing}', flush=True)
    if missing: raise RuntimeError('Missing staged dependencies')
    return result


def run(session, label, name, command, cwd):
    evidence = session / label / 'commands'
    evidence.mkdir(exist_ok=True)
    base = evidence / name
    record_path = Path(str(base) + '.json')
    log_path = Path(str(base) + '.log')
    if record_path.exists(): raise RuntimeError(f'Refusing to overwrite {base}')
    record = {'name': name, 'loader': label, 'command': [str(c) for c in command], 'cwd': str(cwd), 'started': time.time()}
    record_path.write_text(json.dumps(record, indent=2))
    print(f'START {label} {name}', flush=True)
    with log_path.open('w') as log:
        result = subprocess.run(record['command'], cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
    record.update(returncode=result.returncode, seconds=round(time.time()-record['started'], 2))
    record_path.write_text(json.dumps(record, indent=2))
    print(f'END {label} {name}: exit={result.returncode} {record["seconds"]}s {log_path}', flush=True)
    return result.returncode


def client(session, label, cfg, name, mode='sp', extras=(), flags=(), mca=None):
    tree = Path(cfg['tree']); libs = session / label / 'artifacts'
    command = ['python3', tree / 'tools/gameplay' / ('run_client.py' if mode == 'sp' else 'run_multiplayer.py'),
               '--work', session / label / name, '--install', '/home/otectus/Documents/curseforge/minecraft/Install',
               '--crime', libs / 'mcacrime-0.7.7.jar', '--harness', libs / 'crime-gameplay-checks-0.7.7.jar',
               '--xvfb', QA / 'env/xvfb/Xvfb', '--display', ':85' if label == 'forge' else ':87']
    for key in ['loader', 'minecraft', 'java', 'mca', 'architectury', 'geckolib']:
        if key in cfg: command += ['--' + key, mca if key == 'mca' and mca else cfg[key]]
    if mode == 'mp':
        for key in ['server-install', 'server-args', 'eula']: command += ['--' + key, cfg[key]]
        command += ['--display2', ':84' if label == 'forge' else ':86']
    for extra in extras: command += ['--extra-mod', cfg['companions'][extra]]
    command += list(flags)
    return run(session, label, name, command, tree)


def servers(session, label, cfg):
    tree = Path(cfg['tree']); cwd = session / label / 'server-harness-cwd'; libs = session / label / 'artifacts'
    if not (cwd / 'build/libs').exists(): (cwd / 'build/libs').symlink_to(libs, target_is_directory=True)
    common = ['--mca', cfg['mca'], '--java', cfg['java']]
    for key in ['architectury', 'geckolib']:
        if key in cfg: common += ['--' + key, cfg[key]]
    platform = ['--forge-server', cfg['server-install']] if label == 'forge' else ['--neoforge-server', cfg['server-install'], '--neoforge-version', '21.1.250']
    if label == 'forge':
        run(session, label, 'B3-review', ['python3', tree / 'tools/review/run_runtime.py', *platform, *common, '--crime', libs / 'mcacrime-0.7.7.jar', '--harness', libs / 'crime-review-runtime-checks-0.7.7.jar', '--timeout', '300'], cwd)
        for present in [False, True]:
            cmd = ['python3', tree / 'tools/justice/run_runtime.py', *platform, '--mca', cfg['mca'], '--architectury', cfg['architectury'], '--geckolib', cfg['geckolib'], '--restart']
            if present: cmd += ['--reputation', cfg['companions']['reputation']]
            run(session, label, 'B4-justice-' + ('reputation' if present else 'alone'), cmd, cwd)
    for variant in ['absent', 'present', 'disabled', 'control']:
        cmd = ['python3', tree / 'tools/townstead/run_runtime.py', *platform, *common, '--crime', libs / 'mcacrime-0.7.7.jar', '--harness', libs / 'townstead-runtime-checks-0.7.7.jar', '--name', variant]
        if variant != 'absent':
            for key in ['townstead', 'patchouli']: cmd += ['--' + key, cfg['companions'][key]]
        if variant in ['disabled', 'control']: cmd += ['--' + variant]
        run(session, label, 'B5-townstead-' + variant, cmd, cwd)
    if label == 'forge': run(session, label, 'B10-static', ['python3', ROOT / '.mcmod-tools/check_mod.py', tree], tree)


def baseline_clients(session, label, cfg):
    if label == 'forge':
        for kind, jar in [('masks', 'crime-mask-runtime-checks'), ('justice-client', 'crime-justice-client-checks')]:
            cmd = ['python3', Path(cfg['tree']) / f'tools/{kind}/run_client.py', '--work', session / label / ('B6-' + kind), '--install', '/home/otectus/Documents/curseforge/minecraft/Install', '--crime', session / label / 'artifacts/mcacrime-0.7.7.jar', '--harness', session / label / 'artifacts' / f'{jar}-0.7.7.jar', '--xvfb', QA / 'env/xvfb/Xvfb', '--forge', cfg['loader'], '--display', ':87']
            for key in ['mca', 'architectury', 'geckolib', 'java']: cmd += ['--' + key, cfg[key]]
            run(session, label, 'B6-' + kind, cmd, cfg['tree'])
    for role, flags in [('default', []), ('law', ['--law']), ('smoke', ['--role', 'smoke'])]:
        client(session, label, cfg, 'B7-sp-' + role, flags=flags)
    client(session, label, cfg, 'B8-dramatic-doors', extras=['doors'], flags=['--role', 'smoke'])
    for role, flags in [('plain', []), ('law', ['--law']), ('duo', ['--duo'])]:
        client(session, label, cfg, 'B9-mp-' + role, mode='mp', flags=flags)


def matrix_clients(session, label, cfg):
    family = ['reputation', 'quests', 'conversations', 'mobcompat']
    for name, extras, modes in [('family', family, ['sp', 'mp']), ('townstead-family', family + ['townstead', 'patchouli'], ['mp']), ('locks', ['locks'], ['sp', 'mp']), ('doors', ['doors'], ['mp'])]:
        for mode in modes:
            for role, flags in ([('default', []), ('law', ['--law']), ('smoke', ['--role', 'smoke'])] if mode == 'sp' else [('plain', []), ('law', ['--law']), ('duo', ['--duo'])]):
                # The staged Forge Townstead 0.7.6 names MCA's old forge.net.mca package.
                # Its newest-MCA startup failure is retained separately; exercise its verified tuple.
                compat_mca = cfg['fleet']['7.6.26'] if label == 'forge' and name == 'townstead-family' else None
                client(session, label, cfg, f'matrix-{name}-{mode}-{role}', mode, extras, flags, mca=compat_mca)


def server_probe(session, label, cfg, name, version, extras):
    work = session / label / name
    if work.exists(): raise RuntimeError('Refusing to reuse ' + str(work))
    (work / 'mods').mkdir(parents=True)
    (work / 'config').mkdir()
    (work / 'libraries').symlink_to(Path(cfg['server-install']) / 'libraries', target_is_directory=True)
    shutil.copy2(cfg['eula'], work / 'eula.txt')
    jars = [session / label / 'artifacts/mcacrime-0.7.7.jar', Path(cfg['fleet'][version])]
    jars += [Path(cfg[k]) for k in ('architectury', 'geckolib') if k in cfg]
    jars += [Path(cfg['companions'][k]) for k in extras]
    for jar in jars: shutil.copy2(jar, work / 'mods' / jar.name)
    (work / 'artifacts.json').write_text(json.dumps({str(jar): hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars}, indent=2))
    (work / 'config/mca.json').write_text(json.dumps({'version': 1 if label == 'forge' else 2, 'launchIntoDestiny': False}))
    (work / 'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    command = [cfg['java'], '-Xmx2G', '@' + cfg['server-args'], 'nogui']
    (work / 'launch-command.json').write_text(json.dumps(command, indent=2))
    logfile = work / 'console.log'
    with logfile.open('w') as log:
        proc = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=log, stderr=log, text=True)
        try:
            deadline = time.monotonic() + 180
            while 'Done (' not in logfile.read_text(errors='replace'):
                if proc.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Server failed to start')
                time.sleep(0.25)
            commands = ['crime validate', 'crime debug integrations', 'crime debug townstead']
            if label == 'forge': commands += ['crime rules status']
            commands += ['reload']
            (work / 'console-commands.txt').write_text('\n'.join(commands) + '\nstop\n')
            proc.stdin.write('\n'.join(commands) + '\n'); proc.stdin.flush()
            deadline = time.monotonic() + 60
            while logfile.read_text(errors='replace').count('Loaded 15 crime type(s) with 0 error(s)') < 2:
                if proc.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Reload did not complete')
                time.sleep(0.25)
            time.sleep(1)
            proc.stdin.write('stop\n'); proc.stdin.flush()
            if proc.wait(timeout=60) != 0: raise RuntimeError('Server exit failed')
        finally:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=20)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
    text = logfile.read_text(errors='replace')
    for expected in ['Loaded 12 fence price(s) with 0 error(s)', 'Loaded 39 dialogue event(s) with 0 error(s)', 'Loaded 15 crime type(s) with 0 error(s)', 'guard alert gated', 'Config is valid.', 'locks reforged: installed=', 'mca quests: installed=']:
        if expected not in text: raise AssertionError('Missing expected console evidence: ' + expected)
    (work / 'PASS.txt').write_text('PASS A1/A3/A4 startup data counts, default config, native guard hook and integration command responses\nPASS A5 server resource reload completed\n')
    print((work / 'PASS.txt').read_text(), end='')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('session', type=Path)
    p.add_argument('phase', choices=['prepare', 'baseline-servers', 'baseline-clients', 'matrix-clients', 'version-clients', 'server-probes', 'server-probe-one'])
    p.add_argument('--loader', choices=['forge', 'neo', 'both'], default='both')
    p.add_argument('--probe-name')
    p.add_argument('--mca-version')
    p.add_argument('--extras', default='')
    a = p.parse_args(); session = a.session.resolve()
    if a.phase == 'prepare': environment(session); return
    configs = json.loads((session / 'environment.json').read_text())
    for label, cfg in configs.items():
        if a.loader not in ['both', label]: continue
        if a.phase == 'server-probe-one':
            server_probe(session, label, cfg, a.probe_name, a.mca_version, a.extras.split(',') if a.extras else [])
        if a.phase == 'server-probes':
            jobs = [('probe-mca-' + version, version, []) for version in cfg['fleet']]
            family = ['reputation', 'quests', 'conversations', 'mobcompat']
            current = '7.7.1-beta.2' if label == 'forge' else '7.7.36-beta.3'
            jobs += [('probe-family', current, family), ('probe-townstead-family', '7.6.26' if label == 'forge' else current, family + ['townstead', 'patchouli']), ('probe-locks', current, ['locks']), ('probe-doors', current, ['doors'])]
            jobs += [('probe-without-' + missing, current, [k for k in family if k != missing]) for missing in family]
            for name, version, extras in jobs:
                run(session, label, name, ['python3', Path(__file__).resolve(), session, 'server-probe-one', '--loader', label, '--probe-name', name, '--mca-version', version, '--extras', ','.join(extras)], cfg['tree'])
        if a.phase == 'baseline-servers': servers(session, label, cfg)
        if a.phase == 'baseline-clients': baseline_clients(session, label, cfg)
        if a.phase == 'matrix-clients': matrix_clients(session, label, cfg)
        if a.phase == 'version-clients':
            for version, jar in cfg['fleet'].items():
                if jar == cfg['mca']: continue
                client(session, label, cfg, 'fleet-' + version + '-sp-smoke', mca=jar, flags=['--role', 'smoke'])
                client(session, label, cfg, 'fleet-' + version + '-mp', mode='mp', mca=jar)


if __name__ == '__main__':
    main()
