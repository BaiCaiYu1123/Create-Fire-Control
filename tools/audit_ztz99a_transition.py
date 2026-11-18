"""Check the request's scope against the per-file pre-fix snapshot, and record provenance."""
import pathlib,json,hashlib,zipfile,xml.etree.ElementTree as ET,math

root=pathlib.Path('.');out=root/'build/dist/ztz99a-transition'
baseline=json.loads((out/'rollback/source-sha256.json').read_text())
allowed={f'src/main/java/com/hooya/stabilizedturret/client/{name}.java' for name in [
    'Ztz99aDieselSoundProfile','Ztz99aDieselSoundManager','Ztz99aDieselGain','Ztz99aDieselSoundConfig',
    'Ztz99aFmodFade','Ztz99aDieselTakes','Ztz99aTakeCalibration']}
allowed|={'src/main/resources/assets/create_fire_control/sounds.json',
          'src/main/resources/assets/create_fire_control/sounds/engine/ztz99a/transition_reference.json'}
current={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'src/main').rglob('*') if p.is_file()}
changed=[p for p in baseline if baseline[p]!=current.get(p)];added=[p for p in current if p not in baseline]
external={'src/main/java/com/hooya/stabilizedturret/client/AbramsFmodReference.java',
          'src/main/java/com/hooya/stabilizedturret/client/GasTurbineSoundManager.java'}
assert not (set(changed+added)-allowed-external),set(changed+added)-allowed-external
with zipfile.ZipFile(out/'rollback/99a-audio-before.zip') as z:
    name=next(n for n in z.namelist() if n.endswith('sounds.json'))
    old=json.loads(z.read(name))
new=json.loads((root/'src/main/resources/assets/create_fire_control/sounds.json').read_text())
assert old.keys()==new.keys()
for k in old:
    if not k.startswith('engine.ztz99a.'):assert old[k]==new[k],k
    else:
        expected=json.loads(json.dumps(old[k]))
        for s in expected['sounds']:s['stream']=False;s['preload']=True
        assert expected==new[k],k
audit=dict(passOwnScope=True,changed99a=[p for p in changed if p in allowed],added99a=added,
           concurrentExternalChanges={p:dict(before=baseline[p],current=current[p]) for p in changed if p in external},
           unchangedMainFiles=len(baseline)-len(changed),
           originalOggUnchanged=True,m1a2SourceChangedExternally=bool(set(changed)&external),mechanicalModelGuiRemoteUnchanged=True,
           otherSoundsJsonEventsUnchanged=True)
(out/'scope_audit.json').write_text(json.dumps(audit,indent=2)+'\n')

manifest=root/'src/main/resources/assets/create_fire_control/sounds/engine/ztz99a/transition_reference.json'
j=json.loads(manifest.read_text())
xml=ET.parse(root/'reference/ztz99a/fmod_event.xml')
mods=[]
for obj in xml.findall('.//object[@class="AutopitchModulator"]'):
    props={p.attrib['name']:[v.text for v in p.findall('value')] for p in obj.findall('property')}
    mods.append(dict(id=obj.attrib['id'],properties=props))
j['xmlAutopitchModulators']=mods
j['audioFileMapping']={
    'engine_33_load':dict(audioFile='187dc9e9-cf51-41de-ae09-80bb48da88cd',instrument='421d8cd8-0828-4032-9c72-2b655ee354cc',root=30,pitchAtMinimum=.704999983,baseSemitones=-2),
    'engine_66_load':dict(audioFile='89d58b7c-9320-479e-b2cd-3cf76df34755',instrument='0c01a15a-75f8-470c-bcd0-fc21e1b96bf0',root=55,pitchAtMinimum=.779999971,baseSemitones=0),
    'engine_99_load':dict(audioFile='2c2e1ca8-ad55-4176-93b2-ea155cfb02a9',instrument='9991133a-4ddf-4cf4-8bda-b70153713696',root=100,pitchAtMinimum=.104999997,baseSemitones=0),
    'engine_00':dict(instrument='4db1a340-5358-4627-acf1-bb694a3c83ae',autoPitch=False,rpmFade=False,pitch=1)}
j['semantics']='AutoPitch = (minimumRate + (1-minimumRate)*rpm_result/root)*2^(baseSemitones/12); verified against audible native bank voices, not inferred from instrument display names.'
j['rpmResult']=dict(globalMinimum=.14124293785310735,globalMaximum=100,derivedRange=[0,100],seekUnitsPerSecond=50)
j['fadeSpec']=[dict(name=n,fromRpm=a,toRpm=b,curveShape=s) for n,a,b,s in [
    ('lowIn',1,10,-.357493967),('lowOut',30,45,.19546476),('midIn',30,45,-.362325013),
    ('midOut',65,80.00000762939453,.398425162),('highIn',65,80.00000762939453,-.340737462)]]
j['differencesFromFmod']=['257-point interpolation of native bank fades; bank version can differ from current official XML.',
    'Additional common dB load-group energy correction and 20 Hz presentation smoothing.',
    'Muted instruments prepared at audible boundary to stay within Minecraft 0.5–2.0 pitch; audible native ranges are unaffected.',
    'climb master pitch 0–3 semitones remains at default 0, because no independent climb audio parameter is exposed.',
    'Broken-layer pitch automation is not applied to normal engine loops; no normal-loop pitch LFO in this XML.',
    'Random transient pitch amount5 semitones replaced by bounded state-matched rate and confidence trim.',
    'Continuous OpenAL static looping, original sample edges retained without re-encoding.']
manifest.write_text(json.dumps(j,indent=2)+'\n')
print(json.dumps(audit,indent=2))
