"""Render actual Java mixer traces at one fixed scale; no independent loudness normalization."""
import csv, json, pathlib, sys
sys.path.insert(0,str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf
root=pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/tiger_i')
names=['idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load']
sources={n:sf.read(root/(n+'.ogg'))[0] for n in names}
out=pathlib.Path('build/dist/tiger_i/audio');out.mkdir(parents=True,exist_ok=True)
metrics={}
for trace in pathlib.Path('build/reports/tiger_i').glob('*_tracks.csv'):
    with trace.open() as f: rows=list(csv.DictReader(f))
    block=2205;output=np.zeros(len(rows)*block,np.float32);positions=np.zeros(7)
    for k,row in enumerate(rows):
        for i,name in enumerate(names):
            data=sources[name];pitch=float(row['p'+str(i)]);gain=float(row['g'+str(i)])
            coords=(positions[i]+np.arange(block)*pitch)%len(data);a=coords.astype(np.int64);b=(a+1)%len(data)
            output[k*block:(k+1)*block]+=((1-(coords-a))*data[a]+(coords-a)*data[b])*gain*1.5
            positions[i]=(positions[i]+block*pitch)%len(data)
    # Both files use identical fixed scale 1.5 (the existing GUI default), float PCM retains peaks.
    sf.write(out/(trace.stem+'.wav'),output,44100,subtype='FLOAT')
    window=np.sqrt(np.mean(output.reshape(-1,4410)**2,axis=1))
    metrics[trace.stem]=dict(seconds=len(output)/44100,rms=float(np.sqrt(np.mean(output**2))),peak=float(abs(output).max()),
        rms100msMin=float(window.min()),rms100msMax=float(window.max()),scale=1.5,
        limitation='Offline linear resampling of Java trace; no OpenAL/device latency, shots, distance, limiter, or human listening verdict.')
(out/'measurements.json').write_text(json.dumps(metrics,indent=2)+'\n')
print(json.dumps(metrics,indent=2))
