#!/usr/bin/env python3
"""Run the test-only mod in a fresh dedicated server using supplied production jars."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile
import time
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--neoforge-server', type=pathlib.Path, required=True,
                        help='Installed NeoForge 1.21.1 server directory (libraries are read only)')
    parser.add_argument('--neoforge-version', required=True)
    parser.add_argument('--crime', type=pathlib.Path, required=True)
    parser.add_argument('--harness', type=pathlib.Path, required=True)
    parser.add_argument('--mca', type=pathlib.Path, required=True)
    parser.add_argument('--townstead', type=pathlib.Path)
    parser.add_argument('--patchouli', type=pathlib.Path)
    parser.add_argument('--architectury', type=pathlib.Path)
    parser.add_argument('--disabled', action='store_true')
    parser.add_argument('--control', action='store_true', help='Boot and stop the companion pair without Crime or the harness')
    parser.add_argument('--name', default='check')
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    libraries = (args.neoforge_server / 'libraries').resolve(strict=True)
    launchers = list(libraries.glob('net/neoforged/neoforge/' + args.neoforge_version + '/unix_args.txt'))
    if len(launchers) != 1:
        parser.error('Expected exactly one NeoForge 1.21.1 unix_args.txt')
    inputs = [args.mca, args.townstead, args.patchouli, args.architectury]
    if not args.control:
        inputs.extend([args.crime, args.harness])
    jars = [p.resolve(strict=True) for p in inputs if p]
    for jar in jars:
        if not zipfile.is_zipfile(jar):
            parser.error(f'Not a jar: {jar}')
    base = pathlib.Path('build/townstead-runtime').resolve()
    base.mkdir(parents=True, exist_ok=True)
    # A fresh directory prevents stale result files or worlds from passing a failed launch.
    work = pathlib.Path(tempfile.mkdtemp(prefix=args.name + '-', dir=base))
    (work / 'libraries').symlink_to(libraries, target_is_directory=True)
    (work / 'mods').mkdir()
    for jar in jars:
        shutil.copy2(jar, work / 'mods' / jar.name)
    # Reuse the developer's existing EULA acceptance, never accept one on their behalf.
    eula = pathlib.Path('run/eula.txt')
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower():
        parser.error('run/eula.txt must contain an existing eula=true acceptance')
    shutil.copy2(eula, work / 'eula.txt')
    (work / 'server.properties').write_text(
        'online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\n'
        'simulation-distance=2\nspawn-protection=0\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block",'
        '"height":1}],"biome":"minecraft:plains"}\n')
    if args.disabled:
        (work / 'config').mkdir()
        (work / 'config/mcacrime-common.toml').write_text('[townstead]\nenabled=false\n')
    launcher = launchers[0].relative_to(libraries)
    command = [args.java, '-Xmx2G', '@libraries/' + str(launcher), 'nogui']
    print(f'Runtime artifacts: {work}', flush=True)
    with (work / 'console.log').open('w') as log:
        process = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=log, stderr=log, text=True)
        try:
            if args.control:
                deadline = time.monotonic() + args.timeout
                while process.poll() is None and time.monotonic() < deadline:
                    if 'Done (' in (work / 'console.log').read_text(errors='replace'):
                        process.stdin.write('stop\n')
                        process.stdin.flush()
                        break
                    time.sleep(0.25)
                else:
                    raise subprocess.TimeoutExpired(command, args.timeout)
            code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise SystemExit(f'Server timed out; inspect {work / "console.log"}')
    if args.control:
        if code != 0 or 'Done (' not in (work / 'console.log').read_text(errors='replace'):
            raise SystemExit(f'Control server failed; inspect {work / "console.log"}')
        print('PASS companion-only control startup and shutdown')
        return
    result = work / 'runtime-results.txt'
    if code != 0 or not result.is_file():
        raise SystemExit(f'Server failed (exit {code}); inspect {work / "console.log"}')
    text = result.read_text()
    print(text, end='')
    if 'FAIL ' in text or not text.startswith('PASS bridge state'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
