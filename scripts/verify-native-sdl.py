#!/usr/bin/env python3
"""Compile the actual SDL platform and package a distinct native client acceptance mod.
This checks SDL platform ownership independently of the ImGui renderer/full mod startup.
"""
from pathlib import Path
import argparse, hashlib, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
options = parser.parse_args()
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-sdl-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
args = shlex.split((root / 'build/native-compile/javac.args').read_text())
compile_cp = args[args.index('-cp') + 1]
sources = [p for folder in ('common', 'fabric') for p in sorted((root / folder / 'src/main/java').rglob('*.java'))]
sources.append(root / 'port-tests/NativeSdlPlatformAcceptance.java')
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', compile_cp, '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
print('Complete genuine ImGuiMC module plus SDL acceptance javac:', result.returncode, flush=True)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)

jar = work / 'native-sdl-acceptance.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    target.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'imguimc_native_sdl_acceptance', 'version': '1.0.0', 'environment': 'client', 'name': 'ImGuiMC actual SDL3 platform acceptance (test only)', 'entrypoints': {'client': ['NativeSdlPlatformAcceptance']}, 'mixins': ['native-sdl.mixins.json'], 'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
    target.writestr('native-sdl.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.imgui.mixin', 'compatibilityLevel': 'JAVA_25', 'client': ['RenderSystemBackendAccessor'], 'injectors': {'defaultRequire': 1}}))
    for p in classes.rglob('*.class'):
        target.write(p, str(p.relative_to(classes)))
manifest = {'scope': 'Actual SDL3 platform/native ImGui/context/input/native surface ownership; native renderer and full production initialization not claimed', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, Path(__file__).resolve()]}, 'libraries': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root / 'libraries').glob('*.jar'))}, 'acceptance_jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Prepared native SDL platform fixture:', jar, flush=True)
if not options.client:
    raise SystemExit(0)

native = repo / 'ports/veil/build/rendertype-verification'
launcher = shlex.split((native / 'client.args').read_text())
index = launcher.index('-classpath') + 1
classpath = [entry for entry in launcher[index].split(os.pathsep) if entry != str(native / 'native-rendertype-acceptance.jar') and 'imgui-java-' not in entry]
libraries = [root / 'libraries/imgui-java-binding-1.92.7.1.jar', root / 'libraries/imgui-java-natives-macos-1.92.7.1.jar']
launcher[index] = os.pathsep.join(map(str, [*libraries, *classpath, jar]))
launcher = [entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')]
run = work / 'run'
run.mkdir(exist_ok=True)
launcher[launcher.index('--gameDir') + 1] = str(run)
report = work / 'runtime.json'
report.unlink(missing_ok=True)
launcher.insert(0, '-Dimguimc.sdl.report=' + str(report))
argfile = work / 'client.args'
argfile.write_text('\n'.join('"' + entry.replace('\\', '\\\\').replace('"', '\\"') + '"' for entry in launcher))
print('Launching only actual native SDL3 platform acceptance.', flush=True)
result = subprocess.run([str(java / 'java'), '@' + str(argfile)], cwd=run, timeout=180)
if result.returncode:
    raise SystemExit(result.returncode)
if not report.is_file():
    raise SystemExit('Actual SDL fixture produced no report')
data = json.loads(report.read_text())
print('Actual SDL platform result:', data)
if data.get('status') != 'passed':
    raise SystemExit(1)
