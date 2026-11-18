"""Build an audio-only release from the saved pre-fix JAR; preserve concurrent unrelated work.

Compile latest sources normally first. All classes/resources except the explicit 99A
audio allowlist and the internal version metadata must match the rollback archive.
"""
import pathlib,zipfile,re,json,hashlib

out=pathlib.Path('build/dist/ztz99a-transition')
old=out/'rollback/create-fire-control-0.8.1-ztz99a.2.jar'
compiled=pathlib.Path('build/libs/create-fire-control-0.8.1-ztz99a.3.jar')
target=out/'create-fire-control-0.8.1-ztz99a.3.jar'
names=['Ztz99aDieselSoundProfile','Ztz99aDieselSoundManager','Ztz99aDieselGain','Ztz99aDieselSoundConfig',
       'Ztz99aFmodFade','Ztz99aDieselTakes','Ztz99aTakeCalibration']
prefix='com/hooya/stabilizedturret/client/'
pattern=re.compile('^'+prefix+'(?:'+'|'.join(names)+r')(?:\$[^/]+)?\.class$')
allowedResources={'assets/create_fire_control/sounds.json',
                  'assets/create_fire_control/sounds/engine/ztz99a/transition_reference.json'}
metadata={'META-INF/neoforge.mods.toml','META-INF/MANIFEST.MF'}
with zipfile.ZipFile(old) as a,zipfile.ZipFile(compiled) as b:
    before={i.filename:a.read(i) for i in a.infolist()}
    replacements={i.filename:b.read(i) for i in b.infolist() if pattern.match(i.filename) or i.filename in allowedResources}
    assert all(prefix+n+'.class' in replacements for n in names)
    # General resource definition changes have been object-by-object scope checked.
    oldSounds=json.loads(before['assets/create_fire_control/sounds.json']);newSounds=json.loads(replacements['assets/create_fire_control/sounds.json'])
    assert oldSounds.keys()==newSounds.keys()
    assert all(oldSounds[k]==newSounds[k] for k in oldSounds if not k.startswith('engine.ztz99a.'))
    data={k:v for k,v in before.items() if not pattern.match(k)};data.update(replacements)
    for name in metadata:
        if name in data:
            data[name]=data[name].replace(b'0.8.1-ztz99a.2',b'0.8.1-ztz99a.3')
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
        for name,value in data.items():z.writestr(name,value)
with zipfile.ZipFile(target) as z:
    release={i.filename:z.read(i) for i in z.infolist()}
unchanged=[k for k,v in before.items() if k not in metadata and not pattern.match(k) and k not in allowedResources]
assert all(release[k]==before[k] for k in unchanged)
assert not any(k not in before and not pattern.match(k) and k not in allowedResources for k in release)
report=dict(jar=str(target.resolve()),sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
            baseSha256=hashlib.sha256(old.read_bytes()).hexdigest(),
            replacedAudioEntries=sorted(replacements),otherEntriesByteIdentical=len(unchanged),
            m1a2ByteIdentical=True,originalOggByteIdentical=True,modelsMechanicsGuiRemoteByteIdentical=True,
            reason='Concurrent external M1A2 source edits preserved in workspace, excluded from this audio-only release.')
(out/'package_audit.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
