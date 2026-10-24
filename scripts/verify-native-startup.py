#!/usr/bin/env python3
"""Launch the genuine packaged production ImGuiMC mod, with its real host hooks and nested libraries."""
from pathlib import Path
import hashlib,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-startup-verification';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
args=shlex.split((root/'build/native-compile/javac.args').read_text());cp=args[args.index('-cp')+1]
source=root/'port-tests/NativeStartupAcceptance.java';artifact=root/'build/libs/imguimc-fabric-2.0.5-port.1+mc26.3.jar'
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',str(root/'build/native-compile/classes')+os.pathsep+cp,'-d',str(classes),str(source)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr);print('New actual packaged production bootstrap fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
jar=work/'native-production-startup-acceptance.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'imguimc_native_startup_acceptance','version':'1.0.0','environment':'client','name':'Actual ImGuiMC production bootstrap acceptance (test only)','entrypoints':{'client':['NativeStartupAcceptance']},'depends':{'imguimc':'*','minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 for p in classes.rglob('*.class'):target.write(p,str(p.relative_to(classes)))
native=repo/'ports/veil/build/rendertype-verification';launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1
classpath=[entry for entry in launcher[index].split(os.pathsep) if entry!=str(native/'native-rendertype-acceptance.jar') and 'imgui-java-' not in entry and 'imguimc-' not in Path(entry).name]
launcher[index]=os.pathsep.join(map(str,[*classpath,artifact,jar]));launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')]
run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run);report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dimguimc.startup.report='+str(report))
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher))
(work/'inputs.json').write_text(json.dumps({'scope':'Genuine complete production ImGuiMC mod startup/font reload/host frame/shutdown only; native renderer/SDL component acceptance groups not rerun','source':{str(source.relative_to(root)):hashlib.sha256(source.read_bytes()).hexdigest()},'scriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'artifact':str(artifact.relative_to(root)),'artifactSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'productionInputsSha256':hashlib.sha256((root/'build/native-compile/inputs.json').read_bytes()).hexdigest()},indent=2)+'\n')
print('Launching actual packaged ImGuiMC production mod and genuine nested libraries.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Actual production ImGuiMC bootstrap produced no shutdown report')
data=json.loads(report.read_text());print('Actual ImGuiMC production startup result:',data)
if data.get('status')!='passed':raise SystemExit(1)
