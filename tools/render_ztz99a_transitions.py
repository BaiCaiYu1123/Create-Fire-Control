"""Render unchanged OGG with traces exported by the actual old/new Java mixers.

Identical fixed scale=1 and PCM16 output for all versions. No loudness normalization.
The old seam scheduler is idealized (zero decoder latency), so this does not measure
the old asynchronous startup gaps. OpenAL resampling/device latency is not simulated.
Optional takes use Java selector/envelope traces with idealized zero startup latency.
"""
import pathlib,sys,json,csv,math,hashlib
sys.path.insert(0,str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf

root=pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/ztz99a')
traces=pathlib.Path('build/reports/ztz99a-transition')
out=pathlib.Path('build/dist/ztz99a-transition/audio');out.mkdir(parents=True,exist_ok=True)
rate=44100;block=2205
names=['engine_00','engine_33_load','engine_66_load','engine_99_load']
sources={p.stem:np.clip(sf.read(p,always_2d=True)[0],-1,1) for p in root.glob('*.ogg')}
def rows(path):
    with path.open() as f:return [{k:float(v) for k,v in r.items()} for r in csv.DictReader(f)]
def resample(sample,pos,step,n,loop):
    data=sources[sample];coords=pos+np.arange(n)*step
    if loop: coords%=len(data)
    valid=coords<len(data);coords=np.clip(coords,0,len(data)-1)
    a=coords.astype(np.int64);b=(a+1)%len(data) if loop else np.minimum(a+1,len(data)-1)
    frac=(coords-a)[:,None]
    return ((1-frac)*data[a]+frac*data[b])*valid[:,None]
def render(trace,before):
    output=np.zeros((len(trace)*block,2),np.float32)
    # Old four independent seam clocks; actual buffer phase is accumulated, no resets
    # in the new renderer. This mirrors the removed scheduler in the rollback source.
    voices=[[] for _ in names];positions=np.zeros(4)
    for k,r in enumerate(trace):
        chunk=np.zeros((block,2))
        for i,name in enumerate(names):
            pitch=r[f'p{i}'];gain=r[f'g{i}']
            if before:
                v=voices[i]
                remaining=(len(sources[name])-v[-1][0])/rate/pitch if v else 0
                if not v or remaining<=.35:v.append([0.,0.])
                for voice in v:
                    pos,age=voice
                    coords=pos+np.arange(block)*pitch
                    ages=age+np.arange(block)/rate
                    remain=(len(sources[name])-coords)/rate/pitch
                    env=np.sin(np.clip(np.minimum(ages,remain)/.35,0,1)*math.pi/2)
                    chunk+=resample(name,pos,pitch,block,False)*gain*env[:,None]
                    voice[0]+=block*pitch;voice[1]+=.05
                voices[i]=[v for v in v if v[0]<len(sources[name])]
            else:
                t=np.clip((k*.05+np.arange(block)/rate)/.22,0,1)
                entrance=t*t*(3-2*t) # one-time live voice entrance, never at a loop seam
                chunk+=resample(name,positions[i],pitch,block,True)*gain*entrance[:,None]
                positions[i]=(positions[i]+block*pitch)%len(sources[name])
        output[k*block:(k+1)*block]=chunk
    return output
def metrics(data):
    windows=np.sqrt(np.mean(data[:len(data)//4410*4410].reshape(-1,4410,2)**2,axis=(1,2)))
    delta=abs(np.diff(data,axis=0))
    return dict(rms=float(np.sqrt(np.mean(data.astype(np.float64)**2))),peak=float(abs(data).max()),
                rms100msMin=float(windows.min()),rms100msMax=float(windows.max()),
                maxSampleDelta=float(delta.max()),p999SampleDelta=float(np.quantile(delta,.999)))
results={};seams={}
for name in names:
    data=sources[name];delta=abs(np.diff(data,axis=0)).max(axis=1)
    jump=float(abs(data[-1]-data[0]).max())
    seams[name]=dict(boundaryDelta=jump,ordinaryDeltaP99=float(np.quantile(delta,.99)),
                     ordinaryDeltaP999=float(np.quantile(delta,.999)),
                     boundaryPercentile=float(np.mean(delta<=jump)*100))
for path in sorted(traces.glob('*_after.csv')):
    scenario=path.stem.removesuffix('_after');new=render(rows(path),False)
    old=render(rows(traces/(scenario+'_before.csv')),True)
    for version,data in [('before',old),('after',new)]:
        dest=out/f'{scenario}_{version}.wav';sf.write(dest,data,rate,subtype='PCM_16')
        results[dest.name]=metrics(data)
    # Audition tracks share the exact same base loops and master gain. Takes are
    # exported from Java; all four mode files are written, including no-op cases.
    if scenario in ['fast_accel','fast_decel','low_mid']:
        with (traces/(scenario+'_takes.csv')).open() as file: takeRows=list(csv.DictReader(file))
        for mode in ['loops_only','loops_revv','loops_release','full']:
            mixed=new.copy();pos=0;previousSample='';previousTime=-1
            for r in takeRows:
                time=float(r['time']);sample=r['sample'];start=round(time*rate)
                if sample!=previousSample or abs(time-previousTime-.05)>.001:pos=0
                pitch=float(r['pitch']);gain=float(r['gain'])
                allowed=mode=='full' or mode=='loops_revv' and 'revv' in sample or mode=='loops_release' and 'release' in sample
                if allowed:mixed[start:start+block]+=resample(sample,pos,pitch,block,False)*gain
                pos+=block*pitch;previousSample=sample;previousTime=time
            dest=out/f'{scenario}_{mode}.wav';sf.write(dest,mixed,rate,subtype='PCM_16');results[dest.name]=metrics(mixed)
manifest=dict(scale=1,normalization='NONE, identical fixed scale for all files',rate=rate,
              sourceSha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob('*.ogg')},
              limitations=__doc__,seams=seams,rendered=results)
(out/'render_measurements.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(dict(seams=seams,rendered=results),indent=2))
