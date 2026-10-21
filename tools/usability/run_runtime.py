#!/usr/bin/env python3
"""Run the 0.8.0 upgrade fixtures in a disposable production NeoForge server.

The NeoForge counterpart of the Forge build's tools/usability runner, carrying the one suite the GameTests
cannot: an upgrade. Phase 0 runs the previous release and seeds a world (keys, a ring, molds, masks, an
armour stand, an occupied pillory under a falling guillotine); phase 1 reopens the same world with the jar
named by --phase1-crime. Every run gets a fresh directory under build/usability-runtime/.

    python3 tools/usability/run_runtime.py --neoforge-server <dir> --neoforge-version 21.1.250 \\
        --crime <old jar> --harness <harness jar> --mca <mca jar> --geckolib <geckolib jar> \\
        --suite upgrade --restart --phase1-crime build/libs/mcacrime-<version>.jar
"""
import argparse
import pathlib
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--neoforge-server', type=pathlib.Path, required=True)
    parser.add_argument('--neoforge-version', required=True)
    parser.add_argument('--crime', type=pathlib.Path, required=True)
    parser.add_argument('--harness', type=pathlib.Path, required=True)
    parser.add_argument('--mca', type=pathlib.Path, required=True)
    parser.add_argument('--geckolib', type=pathlib.Path)
    parser.add_argument('--extra-mod', type=pathlib.Path, action='append', default=[])
    parser.add_argument('--suite', default='upgrade', choices=('upgrade',))
    parser.add_argument('--restart', action='store_true')
    parser.add_argument('--phase1-crime', type=pathlib.Path,
                        help='replace the MCA: Crime jar with this one before the restarted phase')
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    libraries = (args.neoforge_server / 'libraries').resolve(strict=True)
    launchers = list(libraries.glob('net/neoforged/neoforge/' + args.neoforge_version + '/unix_args.txt'))
    if len(launchers) != 1:
        parser.error('Expected exactly one NeoForge 1.21.1 unix_args.txt')
    eula = pathlib.Path('run-server/eula.txt')
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower():
        parser.error('run-server/eula.txt must contain an existing eula=true acceptance')
    jars = [p.resolve(strict=True) for p in (args.crime, args.harness, args.mca, args.geckolib, *args.extra_mod) if p]
    base = pathlib.Path('build/usability-runtime').resolve()
    base.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix=f'{args.suite}-', dir=base))
    (work / 'libraries').symlink_to(libraries, target_is_directory=True)
    (work / 'mods').mkdir()
    for jar in jars:
        shutil.copy2(jar, work / 'mods' / jar.name)
    shutil.copy2(eula, work / 'eula.txt')
    (work / 'config').mkdir()
    (work / 'config' / 'mca.json').write_text(
        '{"version": 2, "overwriteOriginalVillagers": false, "overwriteOriginalZombieVillagers": false}\n')
    (work / 'server.properties').write_text(
        'online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\n'
        'simulation-distance=2\nspawn-protection=0\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block",'
        '"height":1}],"biome":"minecraft:plains"}\n')
    command = [args.java, '-Xmx2G', '-Dcrime.usability.suite=' + args.suite,
               '@libraries/' + str(launchers[0].relative_to(libraries)), 'nogui']
    print(f'Runtime artifacts: {work}', flush=True)
    failed = False
    for phase in range(2 if args.restart else 1):
        if phase == 1 and args.phase1_crime:
            (work / 'mods' / args.crime.resolve().name).unlink()
            upgraded = args.phase1_crime.resolve(strict=True)
            shutil.copy2(upgraded, work / 'mods' / upgraded.name)
            print(f'Phase 1 runs {upgraded.name} in place of {args.crime.name}', flush=True)
        (work / 'usability-phase.txt').write_text(str(phase))
        result = work / 'runtime-results.txt'
        if result.exists():
            result.unlink()
        with (work / f'console-{phase}.log').open('w') as log:
            process = subprocess.Popen(command, cwd=work, stdout=log, stderr=log)
            try:
                code = process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise SystemExit(f'Server timed out: {work / f"console-{phase}.log"}')
        if code != 0 or not result.is_file():
            raise SystemExit(f'Server failed ({code}): {work / f"console-{phase}.log"}')
        report = result.read_text()
        (work / f'results-{phase}.txt').write_text(report)
        print(report, end='', flush=True)
        if 'FAIL ' in report or 'COMPLETE ' not in report:
            failed = True
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
