"""Export the complete cached Tiger event graph and measured before/after audio facts."""
import hashlib,json,math
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
REF=ROOT/'reference/tiger_i'
objects={o.get('id'):(o,p) for p in REF.rglob('*.xml') for o in ET.parse(p).getroot()}
def properties(o):return {p.get('name'):p[0].text for p in o.findall('property') if len(p)}
def relations(o):return {r.get('name'):[d.text for d in r] for r in o.findall('relationship')}
names={r.text:properties(o).get('name') for o,p in objects.values() if o.get('class')=='ParameterPreset' for r in o.findall("relationship[@name='parameter']/destination")}
visited={};missing=set();queue=['{e4b1917d-d838-4490-8cb1-b8f43f4b1a94}']
while queue:
    id=queue.pop()
    if id in visited:continue
    if id not in objects:missing.add(id);continue
    o,p=objects[id];rel=relations(o)
    visited[id]=dict(type=o.get('class'),properties=properties(o),relations=rel,parameterName=names.get(id),source=p.relative_to(ROOT).as_posix())
    for kind,ids in rel.items():
        if kind not in ['folder','tags','banks','masterAssetFolder']:queue+=ids
analysis=json.loads((REF/'audio_analysis.json').read_text())
loops=['idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load']
static=[8,2,1,4,-4,-2,0]
old={n:20*math.log10((.12 if i<4 else .15)/(analysis[n]['spatialRms']*10**(static[i]/20))) for i,n in enumerate(loops)}
hashes=json.loads((ROOT/'backups/tiger_i_fix_before/audio_hashes.json').read_text())
for path,sha in hashes.items():
    assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==sha,path
out=ROOT/'docs/tiger_i_reference/audio_fix_audit.json';out.parent.mkdir(parents=True,exist_ok=True)
result=dict(commit='07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6',event='e4b1917d-d838-4490-8cb1-b8f43f4b1a94',
    graph=visited,unresolvedExternalObjects=sorted(missing),
    metadataHashes={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in REF.rglob('*.xml')},
    oldRmsCorrectionsDb=old,audioAnalysis=analysis,unchangedAudioFiles=len(hashes),
    knownDefaults={'broken':0,'climb':'explicit MC acceleration mapping','load':'unused by seven loop groups',
        'loadswitch':'positive audio-RPM target gap / 25; authored seek +2/-4; one entry trigger',
        'nearPremasterGainDb':6,'revvLayerGainDb':6,'is_visible':1,'occlusion':0,'headphones':0,'building_density':0},
    limitations=['Installed bank is a separate build from pinned XML; native sweeps are observations, not a Studio rebuild.',
        'Seven existing mono files and their old clipping/seam processing remain byte-identical.',
        'Full FMOD EQ, compression, snapshots, headphone return paths and gameplay occlusion are not cloned.',
        'Native render covers engine event only; Java start/stop handoff has no exact Studio-event counterpart here.'])
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('graph objects',len(visited),'unresolved external',len(missing),'unchanged Ogg',len(hashes))
print('old RMS correction dB',old)
