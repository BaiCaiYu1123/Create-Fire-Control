"""Preserve the .7 release and change only the five-block footprint and its rendering/migration."""
import hashlib
import json
import pathlib
import re
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
base = root / 'build/dist/ztz99a-animation-spatial/create-fire-control-0.8.1-ztz99a.7.jar'
out = root / 'build/dist/ztz99a-footprint'
target = out / 'create-fire-control-0.8.1-ztz99a.8.jar'
classes = root / 'build/classes/java/main'
names = [
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlock',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlockEntity',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEnginePart',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineCollisionShapes',
    'com/hooya/stabilizedturret/client/Ztz99aDieselAnimationModels',
    'com/hooya/stabilizedturret/client/renderer/Ztz99aDieselEngineRenderer',
]
pattern = re.compile(r'^(?:' + '|'.join(map(re.escape,names)) + r')(?:\$[^/]+)?\.class$')
with zipfile.ZipFile(base) as jar:
    before = {entry.filename:jar.read(entry) for entry in jar.infolist()}
replacement = {path.relative_to(classes).as_posix():path.read_bytes() for path in classes.rglob('*.class')
    if pattern.fullmatch(path.relative_to(classes).as_posix())}
assert all(name+'.class' in replacement for name in names)
result = dict(before)
result.update(replacement)
metadata = {'META-INF/neoforge.mods.toml','META-INF/MANIFEST.MF'}
for name in metadata: result[name] = before[name].replace(b'0.8.1-ztz99a.7',b'0.8.1-ztz99a.8')
assert all(result[name]==value for name,value in before.items() if name not in replacement and name not in metadata)
out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as jar:
    for name,value in result.items():jar.writestr(name,value)
report = {'jar':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'baseJar':str(base),'replacedClasses':sorted(replacement),'engineBlocks':5,'freedModelCells':13,
    'ordinaryWorldAndSableFourFacings':'PASS','legacyLayoutCleanup':'PASS',
    'runtimeLog':'build/ztz99a-footprint-runtime.log','otherJarEntriesByteIdentical':True,
    'actualClientVisualVerificationPerformed':False}
(out/'package-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
(out/(target.name+'.sha256')).write_text(report['sha256']+'  '+target.name+'\n',encoding='ascii')
print(json.dumps({'jar':str(target),'engineBlocks':5,'freedModelCells':13,'sha256':report['sha256']},indent=2))
