#!/usr/bin/env python3
"""Run the MCA Capitals and Blueprint-prison fixtures (0.7.8) in a disposable production server.

Never touches an installed server or an existing world. With --restart the same world is started a
second time, so the checks that need a save and a reload in between can run.
"""
import argparse
import pathlib
import shutil
import subprocess
import tempfile


def run_server(command, work, timeout, label):
    with (work / f'console-{label}.log').open('w') as log:
        process = subprocess.Popen(command, cwd=work, stdout=log, stderr=log)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise SystemExit(f'Server timed out: {work / f"console-{label}.log"}')
    result = work / 'runtime-results.txt'
    if code != 0 or not result.is_file():
        raise SystemExit(f'Server failed ({code}): {work / f"console-{label}.log"}')
    kept = work / f'runtime-results-{label}.txt'
    result.replace(kept)
    return kept.read_text()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forge-server', type=pathlib.Path, required=True)
    parser.add_argument('--crime', type=pathlib.Path, required=True)
    parser.add_argument('--harness', type=pathlib.Path, required=True)
    parser.add_argument('--mca', type=pathlib.Path, required=True)
    parser.add_argument('--capitals', type=pathlib.Path, help='omit to check that everything degrades without it')
    parser.add_argument('--geckolib', type=pathlib.Path)
    parser.add_argument('--architectury', type=pathlib.Path)
    parser.add_argument('--restart', action='store_true', help='start the same world a second time')
    parser.add_argument('--java', default='/usr/lib/jvm/java-17-openjdk/bin/java')
    parser.add_argument('--timeout', type=int, default=240)
    args = parser.parse_args()
    libraries = (args.forge_server / 'libraries').resolve(strict=True)
    launchers = list(libraries.glob('net/minecraftforge/forge/1.20.1-*/unix_args.txt'))
    if len(launchers) != 1:
        parser.error('Expected exactly one Forge 1.20.1 launcher')
    eula = pathlib.Path('run/eula.txt')
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower():
        parser.error('Existing run/eula.txt acceptance is required')
    optional = [p for p in (args.capitals, args.geckolib, args.architectury) if p is not None]
    jars = [p.resolve(strict=True) for p in [args.crime, args.harness, args.mca, *optional]]
    base = pathlib.Path('build/capitals-runtime').resolve()
    base.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix='check-', dir=base))
    (work / 'libraries').symlink_to(libraries, target_is_directory=True)
    (work / 'mods').mkdir()
    for jar in jars:
        shutil.copy2(jar, work / 'mods' / jar.name)
    shutil.copy2(eula, work / 'eula.txt')
    (work / 'server.properties').write_text(
        'online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\n'
        'simulation-distance=2\nspawn-protection=0\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block",'
        '"height":1}],"biome":"minecraft:plains"}\n')
    command = [args.java, '-Xmx2G', '@libraries/' + str(launchers[0].relative_to(libraries)), 'nogui']
    print(f'Runtime artifacts: {work}', flush=True)
    reports = [('first start', run_server(command, work, args.timeout, 'phase0'))]
    if args.restart:
        reports.append(('after restart', run_server(command, work, args.timeout, 'phase1')))
    failed = False
    for label, report in reports:
        print(f'--- {label}')
        print(report, end='')
        failed |= 'FAIL ' in report or 'COMPLETE ' not in report
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
