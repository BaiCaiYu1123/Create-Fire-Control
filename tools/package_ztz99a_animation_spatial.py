"""Package only the requested 150HB outline, original animation and positional audio changes."""
import hashlib
import json
import pathlib
import re
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
base = root / 'build/dist/ztz99a-t-collision/create-fire-control-0.8.1-ztz99a.5.jar'
out = root / 'build/dist/ztz99a-animation-spatial'
target = out / 'create-fire-control-0.8.1-ztz99a.7.jar'
classes = root / 'build/classes/java/main'
resources = root / 'src/main/resources'
names = [
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlock',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlockEntity',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineCollisionShapes',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselModelAnimation',
    'com/hooya/stabilizedturret/client/renderer/Ztz99aDieselEngineRenderer',
    'com/hooya/stabilizedturret/client/Ztz99aDieselSoundManager',
    'com/hooya/stabilizedturret/client/Ztz99aDieselAnimationModels',
    'com/hooya/stabilizedturret/client/Ztz99aDieselSpatialAudio',
]
pattern = re.compile(r'^(?:' + '|'.join(map(re.escape, names)) + r')(?:\$[^/]+)?\.class$')
with zipfile.ZipFile(base) as jar:
    before = {entry.filename: jar.read(entry) for entry in jar.infolist()}
replacement = {path.relative_to(classes).as_posix(): path.read_bytes()
    for path in classes.rglob('*.class') if pattern.fullmatch(path.relative_to(classes).as_posix())}
assert all(name + '.class' in replacement for name in names)
asset_root = 'assets/create_fire_control/'
for directory in ['models/block/ztz99a_diesel_engine', 'sounds/engine/ztz99a/spatial']:
    for path in (resources / asset_root / directory).iterdir():
        if path.is_file(): replacement[path.relative_to(resources).as_posix()] = path.read_bytes()
for filename in ['animation_manifest.json', 'spatial_audio_manifest.json']:
    name = asset_root + 'ztz99a/' + filename
    replacement[name] = (resources / name).read_bytes()
sounds_name = asset_root + 'sounds.json'
sounds = json.loads(before[sounds_name])
latest_sounds = json.loads((resources / sounds_name).read_text(encoding='utf-8'))
for key in sounds:
    if key.startswith('engine.ztz99a.'): sounds[key] = latest_sounds[key]
replacement[sounds_name] = (json.dumps(sounds, indent=2, ensure_ascii=False)+'\n').encode('utf-8')
result = dict(before)
result.update(replacement)
metadata = {'META-INF/neoforge.mods.toml', 'META-INF/MANIFEST.MF'}
for name in metadata:
    result[name] = before[name].replace(b'0.8.1-ztz99a.5', b'0.8.1-ztz99a.7')
assert all(value == result[name] for name, value in before.items() if name not in replacement and name not in metadata)
assert all(json.loads(before[sounds_name])[key] == sounds[key] for key in sounds if not key.startswith('engine.ztz99a.'))
spatial_manifest = json.loads(replacement[asset_root + 'ztz99a/spatial_audio_manifest.json'])
for sample, info in spatial_manifest.items():
    name = asset_root + 'sounds/engine/ztz99a/' + sample + '.ogg'
    assert hashlib.sha256(result[name]).hexdigest() == info['originalSha256']
out.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as jar:
    for name, value in result.items(): jar.writestr(name, value)
report = {'jar': str(target), 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
    'baseJar': str(base), 'changedEntries': sorted(name for name, value in result.items() if before.get(name) != value),
    'm1a2Unchanged': True, 'originalOggUnchanged': True,
    'positionalMonoCopies': len(spatial_manifest), 'compileLog': 'build/ztz99a-animation-spatial-compile.log',
    'runtimeClientAuditionPerformed': False}
(out / 'package-audit.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
(out / (target.name+'.sha256')).write_text(report['sha256']+'  '+target.name+'\n', encoding='ascii')
print(json.dumps({'jar': str(target), 'changedEntries': len(report['changedEntries']),
    'positionalMonoCopies': len(spatial_manifest), 'sha256': report['sha256']}, indent=2))
