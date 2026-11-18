"""Follow FMOD objects to original files, fades, modulation, and automation."""
import hashlib, json, pathlib, xml.etree.ElementTree as ET
root = pathlib.Path('reference/tiger_i')
objects = {}
for p in root.rglob('*.xml'):
    for o in ET.parse(p).getroot(): objects[o.get('id')] = o
def prop(o, name, default=None):
    v=o.find(f"property[@name='{name}']/value")
    return v.text if v is not None else default
def rel(o, name): return [v.text for v in o.findall(f"relationship[@name='{name}']/destination")]
def points(id):
    curve=objects[id]
    ids=rel(curve,'automationPoints') or rel(curve,'startPoint')+rel(curve,'endPoint')
    return sorted([dict(id=i,position=float(prop(objects[i],'position')),
        value=float(prop(objects[i],'value')),shape=float(prop(objects[i],'curveShape','0'))) for i in ids],key=lambda p:p['position'])
tracks=[]
for t in objects.values():
    if t.get('class')!='GroupTrack': continue
    group=objects[rel(t,'mixerGroup')[0]]
    for id in rel(t,'modules'):
        sound=objects[id]
        if sound.get('class')!='SingleSound' or prop(sound,'looping')!='true':continue
        file=objects[rel(sound,'audioFile')[0]]
        asset=prop(file,'assetPath')
        if '/panzer6/update/' not in asset:continue
        auto=objects[rel(sound,'modulators')[0]]
        tracks.append(dict(id=id,asset=asset,sample=pathlib.PurePosixPath(asset).stem,
            group=prop(group,'name'),groupVolumeDb=float(prop(group,'volume','0')),
            staticDb=float(prop(sound,'volume','0')),start=float(prop(sound,'start','0')),
            end=float(prop(sound,'start','0'))+float(prop(sound,'length','0')),
            root=float(prop(auto,'root')),pitchAtMinimum=float(prop(auto,'pitchAtMinimum')),
            fadeIn=points(rel(sound,'fadeInCurve')[0]) if rel(sound,'fadeInCurve') else [],
            fadeOut=points(rel(sound,'fadeOutCurve')[0]) if rel(sound,'fadeOutCurve') else []))
assert len(tracks)==7
order=['idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load']
tracks.sort(key=lambda t:order.index(t['sample']))
automation=[]
for o in objects.values():
    if o.get('class')!='Automator':continue
    for cid in rel(o,'automationCurves'):
        curve=objects[cid];pid=rel(curve,'parameter')[0]
        presets=[x for x in objects.values() if x.get('class')=='ParameterPreset' and rel(x,'parameter')==[pid]]
        automation.append(dict(id=o.get('id'),property=prop(o,'nameOfPropertyBeingAutomated'),
            parameter=prop(presets[0],'name') if presets else pid,points=points(cid)))
report=dict(commit='07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6',
    eventId='e4b1917d-d838-4490-8cb1-b8f43f4b1a94',
    eventSha256=hashlib.sha256((root/'fmod_event.xml').read_bytes()).hexdigest(),
    tracks=tracks,automation=automation,
    notes=['Group volume automators use broken, NOT load. Healthy broken=0: load=-2 dB, not_load=+6 dB.',
    'Tank_AI_rpm_Compensator_Gain is RPM automation (-3 dB at 0, 0 dB at 9.7457627).',
    'SU crossfade is a Minecraft adaptation, not an authored FMOD load curve.',
    'Climb automation endpoints .004237288 -> 0 st, .995762712 -> 6 st, on slow_load/med_load/fast_load only. MC acceleration mapping is an approximation.',
    'Revv MultiSound uses loadswitch, not load. MC maps positive audio-RPM target gap / 25, seek +2/-4, one trigger at entry >= .1.',
    'Revv randomizer serialized amount=5 is not assumed to be semitones: 240 native triggers measured about +/-1.2 st.',
    'Reference mode interpolates the full 401-row native RPM sweep without equal-power or RMS normalization.',
    'Serial gains: event +2, engines_enemies -8, enemy parent -16 dB; shared near/front Distance +4 and Direction approximately +2 dB.',
    'Revv Tank_AI_Load_Speed_Gain automated +8 replaces base +6; local Gain -2 dB. Group automations replace base values.',
    'Spatializer preset overrides range to 3..400 and Doppler multiplier to 0; event base max=1000 and scale=205 are not effective range/multiplier.',
    'Full EQ, compressor/limiter, snapshot, headphone return and WT gameplay occlusion chains are not emulated. Installed bank and pinned XML are separate builds.'])
(root/'profile_reference.json').write_text(json.dumps(report,indent=2)+'\n')
for t in tracks: print(t['sample'],t['group'],t['staticDb'],t['start'],t['end'],t['root'],t['pitchAtMinimum'])
