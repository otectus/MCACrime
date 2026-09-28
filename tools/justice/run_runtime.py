#!/usr/bin/env python3
"""Disposable production Forge justice checks, optionally repeat the same world to prove persistence."""
import argparse,pathlib,shutil,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('--forge-server',type=pathlib.Path,required=True);p.add_argument('--mca',type=pathlib.Path,required=True);p.add_argument('--architectury',type=pathlib.Path,required=True);p.add_argument('--geckolib',type=pathlib.Path,required=True);p.add_argument('--reputation',type=pathlib.Path);p.add_argument('--restart',action='store_true');a=p.parse_args()
base=pathlib.Path('build/justice-runtime').resolve();base.mkdir(parents=True,exist_ok=True)
w=pathlib.Path(tempfile.mkdtemp(prefix='check-',dir=base)); libs=(a.forge_server/'libraries').resolve(strict=True);(w/'libraries').symlink_to(libs,target_is_directory=True);(w/'mods').mkdir()
version=next(line.split('=',1)[1] for line in pathlib.Path('gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
jars=[pathlib.Path(f'build/libs/mcacrime-{version}.jar'),pathlib.Path(f'build/libs/crime-justice-runtime-checks-{version}.jar'),a.mca,a.architectury,a.geckolib]
if a.reputation:jars.append(a.reputation)
for jar in jars:shutil.copy2(jar.resolve(strict=True),w/'mods'/jar.name)
eula=pathlib.Path('run/eula.txt');assert 'eula=true' in eula.read_text().lower();shutil.copy2(eula,w/'eula.txt')
(w/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
launch=list(libs.glob('net/minecraftforge/forge/1.20.1-*/unix_args.txt'));assert len(launch)==1
cmd=['/usr/lib/jvm/java-17-openjdk/bin/java','-Xmx2G','@libraries/'+str(launch[0].relative_to(libs)),'nogui']
print('Runtime artifacts:',w,flush=True)
for phase in range(2 if a.restart else 1):
 with (w/f'console-{phase}.log').open('w') as log:
  proc=subprocess.Popen(cmd,cwd=w,stdout=log,stderr=log)
  try:code=proc.wait(timeout=180)
  except subprocess.TimeoutExpired:
   proc.terminate()
   try:proc.wait(timeout=15)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
   raise SystemExit('Runtime timeout')
 result=w/'runtime-results.txt'
 if code or not result.exists():raise SystemExit(f'Runtime failed ({code}): {w}/console-{phase}.log')
 report=result.read_text();(w/f'results-{phase}.txt').write_text(report);print(report,flush=True)
 if 'FAIL ' in report or 'COMPLETE ' not in report:raise SystemExit(1)
