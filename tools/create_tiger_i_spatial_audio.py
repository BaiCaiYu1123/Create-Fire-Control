"""Create positional derivatives once from unchanged originals; measure and repair outlier seams."""
import hashlib, json, pathlib, sys
sys.path.insert(0,str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf
r=pathlib.Path('reference/tiger_i')
records=json.loads((r/'audio_analysis.json').read_text())
out=pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/tiger_i')
out.mkdir(parents=True,exist_ok=True)
loops={'idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load'}
manifest={}
for source in (r/'original').glob('*.ogg'):
    if source.stem=='idle_not_load':continue
    data,rate=sf.read(source,always_2d=True)
    mono=np.clip(data,-1,1).mean(axis=1)
    before=float(abs(mono[-1]-mono[0]))
    ordinary=float(np.quantile(abs(np.diff(mono)),.999))
    repair=None
    if source.stem in loops and before>1.5*ordinary:
        size=int(rate*.008);t=np.linspace(0,1,size);blend=t*t*(3-2*t)
        midpoint=(mono[0]+mono[-1])/2
        mono[:size]=midpoint+(mono[:size]-midpoint)*blend
        mono[-size:]=midpoint+(mono[-size:]-midpoint)*blend[::-1]
        repair=dict(millisecondsPerEnd=8,before=before,ordinaryP999=ordinary,
            method='smoothstep endpoint-to-common-midpoint repair in the mono derivative; no extra runtime voice')
    target=out/source.name
    sf.write(target,mono,rate,format='OGG',subtype='VORBIS',compression_level=0.0)
    decoded,decoded_rate=sf.read(target)
    assert decoded_rate==rate and len(decoded)==len(mono) and sf.info(target).channels==1
    record=records[source.stem]
    record['spatialRms']=float(np.sqrt(np.mean(decoded**2)))
    record['spatialPeak']=float(abs(decoded).max())
    record['spatialSeamDelta']=float(abs(decoded[-1]-decoded[0]))
    record['ordinaryDeltaP999']=float(np.quantile(abs(np.diff(decoded)),.999))
    if repair:
        repair['after']=record['spatialSeamDelta'];assert repair['after']<before*.2
        print(source.stem,'measured seam',before,'->',repair['after'])
    record['seamRepair']=repair
    manifest[source.stem]=dict(source=hashlib.sha256(source.read_bytes()).hexdigest(),
        monoSha256=hashlib.sha256(target.read_bytes()).hexdigest(),channels=1,frames=len(decoded),rate=rate,
        method='Minecraft-range PCM, arithmetic channel average, Vorbis quality 10; no loudness normalization; original preserved',seamRepair=repair)
(r/'audio_analysis.json').write_text(json.dumps(records,indent=2)+'\n')
(out/'spatial_manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
p=pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/TigerIAudioCalibration.java')
s=p.read_text()
for method,key in [('peak','spatialPeak'),('rms','spatialRms')]:
    begin=s.index(f'    public static float {method}(');end=s.index('    }; }',begin)+8
    block=f'    public static float {method}(String sample) {{ return switch(sample) {{\n'
    for name,record in records.items():block+=f'        case "{name}" -> {record.get(key,record[method]):.8f}F;\n'
    block+='        default -> throw new IllegalArgumentException(sample);\n    }; }'
    s=s[:begin]+block+s[end:]
p.write_text(s)
pathlib.Path('docs/tiger_i_reference/audio_analysis.json').write_text(json.dumps(records,indent=2)+'\n')
