"""Add the 150HB name, collider and earlier startup handoff to the stable 99A release.

Compile with -Pmod_version=0.8.1-ztz99a.4 first. Preserve all other JAR entries,
including concurrent M1A2 source work which is outside this release's scope.
"""
import hashlib
import json
import pathlib
import re
import shutil
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
out = root / 'build/dist/ztz99a-150hb'
base = root / 'build/dist/ztz99a-transition/create-fire-control-0.8.1-ztz99a.3.jar'
compiled = root / 'build/libs/create-fire-control-0.8.1-ztz99a.4.jar'
target = out / 'create-fire-control-0.8.1-ztz99a.4.jar'
classes = [
    'com/hooya/stabilizedturret/client/Ztz99aDieselSoundProfile',
    'com/hooya/stabilizedturret/client/Ztz99aDieselSoundManager',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlock',
    'com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineCollisionShapes',
]
pattern = re.compile(r'^(?:' + '|'.join(map(re.escape, classes)) + r')(?:\$[^/]+)?\.class$')
metadata = {'META-INF/neoforge.mods.toml', 'META-INF/MANIFEST.MF'}
lang_key = 'block.create_fire_control.ztz99a_diesel_engine'
names = {'zh_cn': '150HB柴油发动机', 'en_us': '150HB Diesel Engine'}

def entries(path):
    with zipfile.ZipFile(path) as jar:
        return {item.filename: jar.read(item) for item in jar.infolist()}

before = entries(base)
latest = entries(compiled)
replacement = {name: value for name, value in latest.items() if pattern.fullmatch(name)}
assert all(name + '.class' in replacement for name in classes)
release = {name: value for name, value in before.items() if not pattern.fullmatch(name)}
release.update(replacement)
languages = set()
for locale, display_name in names.items():
    name = f'assets/create_fire_control/lang/{locale}.json'
    original = json.loads(before[name])
    current = json.loads(latest[name])
    assert current[lang_key] == display_name
    original[lang_key] = display_name
    release[name] = (json.dumps(original, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    languages.add(name)
for name in metadata:
    release[name] = before[name].replace(b'0.8.1-ztz99a.3', b'0.8.1-ztz99a.4')
assert b'0.8.1-ztz99a.4' in release['META-INF/neoforge.mods.toml']

out.mkdir(parents=True, exist_ok=True)
rollback = out / 'rollback' / base.name
rollback.parent.mkdir(exist_ok=True)
if rollback.exists():
    assert rollback.read_bytes() == base.read_bytes(), 'Preserve the existing rollback'
else:
    shutil.copyfile(base, rollback)
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as jar:
    for name, value in release.items():
        jar.writestr(name, value)
actual = entries(target)
unchanged = [name for name in before if not pattern.fullmatch(name) and name not in metadata | languages]
assert all(actual[name] == before[name] for name in unchanged)
assert not any(name not in before and not pattern.fullmatch(name) for name in actual)
for name in languages:
    old_json, new_json = json.loads(before[name]), json.loads(actual[name])
    assert old_json.keys() == new_json.keys()
    assert all(old_json[key] == new_json[key] for key in old_json if key != lang_key)
report = {
    'jar': str(target),
    'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
    'baseSha256': hashlib.sha256(base.read_bytes()).hexdigest(),
    'rollbackJar': str(rollback),
    'replacedClasses': sorted(replacement),
    'changedLanguageKeys': [lang_key],
    'otherEntriesByteIdentical': len(unchanged),
    'm1a2ByteIdentical': True,
    'originalOggByteIdentical': True,
    'modelsTexturesKineticOutputsGuiRemoteByteIdentical': True,
}
(out / 'package-audit.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
(out / (target.name + '.sha256')).write_text(report['sha256'] + '  ' + target.name + '\n', encoding='ascii')
print(json.dumps(report, indent=2))
