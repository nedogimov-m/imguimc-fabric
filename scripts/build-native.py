#!/usr/bin/env python3
"""Compile all pinned ImGuiMC shared/Fabric sources and package the genuine 26.3 mod with native libraries."""
from pathlib import Path
import hashlib,io,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-compile';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text())
binding=root/'libraries/imgui-java-binding-1.92.7.1.jar'
classpath=[str(binding)]+[entry for entry in args[args.index('-classpath')+1].split(os.pathsep) if 'imguimc-fabric-1.21.1' not in entry]
sources=sorted(p for side in ('common','fabric') for p in (root/side/'src/main/java').rglob('*.java'))
compile_args=['--release','25','-proc:none','-sourcepath','','-Xmaxerrs','10000','-cp',os.pathsep.join(classpath),'-d',str(classes),*map(str,sources)]
argfile=work/'javac.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in compile_args))
result=subprocess.run([str(java/'javac'),'@'+str(argfile)],capture_output=True,text=True)
(work/'javac.log').write_text(result.stdout+result.stderr);print(f'Complete pinned ImGuiMC native module: {len(sources)} sources; javac {result.returncode}',flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
resources=sorted(p for side in ('common','fabric') for p in (root/side/'src/main/resources').rglob('*') if p.is_file())
libraries=sorted((root/'libraries').glob('*.jar'))
inputs=[*sources,*resources,*libraries,Path(__file__).resolve(),root/'UPSTREAM.json']
(work/'inputs.json').write_text(json.dumps({'status':'compiled','scope':'All actual shared/Fabric production sources, no source exclusions or stub classes','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},'classpath':classpath},indent=2)+'\n')
version='2.0.5-port.1+mc26.3';jar=root/f'build/libs/imguimc-fabric-{version}.jar';jar.parent.mkdir(parents=True,exist_ok=True)
values={'id':'imguimc','version':version,'name':'ImGuiMC','description':'Native ImGui Minecraft bindings for Fabric 26.3 and RenderPearl/SDL3','author':'Ocelot; Fabric 26.3 port contributors','license':'LGPL-3.0-only','minecraft':'26.3','java':'25'}
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 for p in sorted(classes.rglob('*.class')):target.write(p,str(p.relative_to(classes)))
 seen=set()
 for side in ('common','fabric'):
  folder=root/side/'src/main/resources'
  for p in sorted(folder.rglob('*')):
   if not p.is_file():continue
   name=str(p.relative_to(folder))
   if name in seen:raise SystemExit('Duplicate actual ImGuiMC resource: '+name)
   seen.add(name)
   if name=='fabric.mod.json':
    text=p.read_text()
    for key,value in values.items():text=text.replace('${'+key+'}',value)
    metadata=json.loads(text);metadata['jars']=[{'file':'META-INF/jars/'+library.name} for library in libraries];target.writestr(name,json.dumps(metadata,indent=2)+'\n')
   elif name.endswith('.mixins.json'):
    text=p.read_text().replace('${id}','imguimc');data=json.loads(text);data['compatibilityLevel']='JAVA_25';data.pop('refmap',None);target.writestr(name,json.dumps(data,indent=2)+'\n')
   else:target.write(p,name)
 # Fabric nested libraries need the same generated metadata that Loom's include creates.
 # Every original class/native/resource byte remains unchanged; only metadata is added.
 for library in libraries:
  buffer=io.BytesIO()
  with zipfile.ZipFile(library) as original,zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as included:
   for item in original.infolist():included.writestr(item,original.read(item))
   included.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':library.stem.removesuffix('-1.92.7.1').replace('-','_'),'version':'1.92.7.1','name':library.stem,'description':'Genuine included ImGui Java library','custom':{'fabric-loom:generated':True}}))
  target.writestr('META-INF/jars/'+library.name,buffer.getvalue())
 for p in sorted(root.glob('LICENSE*')):target.write(p,'META-INF/'+p.name)
 target.write(root/'UPSTREAM.json','META-INF/imguimc-upstream.json')
print('Genuine native production artifact:',jar,flush=True)
(work/'artifact.json').write_text(json.dumps({'artifact':str(jar.relative_to(root)),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'sourceCount':len(sources),'java':25,'minecraft':'26.3','nativeLibraries':[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in libraries]},indent=2)+'\n')
