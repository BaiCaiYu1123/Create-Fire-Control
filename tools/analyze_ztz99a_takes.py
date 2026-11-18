"""Measure tonal ridges in original OGG, without rewriting samples.

Harmonic-comb estimates are matching cues, not asserted physical engine RPM/F0.
Confidence and trajectory are retained so noise-heavy release takes aren't forced
to follow a spurious single FFT peak. All processing is offline and reproducible.
"""
import pathlib, sys, json
sys.path.insert(0,str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf

root=pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/ztz99a')
records={}
for path in sorted(root.glob('*.ogg')):
    data,rate=sf.read(path,always_2d=True)
    mono=data.mean(axis=1)
    trajectory=[];conf=[]
    for fraction in [.12,.3,.5,.7,.88]:
        center=int(len(mono)*fraction);size=min(16384,len(mono))
        begin=max(0,min(len(mono)-size,center-size//2))
        spectrum=abs(np.fft.rfft(mono[begin:begin+size]*np.hanning(size),65536))
        freqs=np.fft.rfftfreq(65536,1/rate)
        # Engines have multiple strong harmonics. Scoring four harmonics reduces
        # octave errors; a continuity prior only breaks nearly equivalent peaks.
        candidates=np.arange(38,116,.25)
        scores=sum(np.interp(candidates*h,freqs,spectrum)/h for h in range(1,5))
        if trajectory:
            scores*=np.exp(-.18*abs(np.log2(candidates/trajectory[-1])))
        best=int(np.argmax(scores));trajectory.append(float(candidates[best]))
        conf.append(float(np.clip((scores[best]/max(1e-9,np.median(scores))-1)/3,0,1)))
    records[path.stem]=dict(seconds=len(data)/rate,tonalCueHz=trajectory,
                           confidence=conf,method='four-harmonic comb 38–116 Hz, five windows; cue, not verified F0')

target=pathlib.Path('reference/ztz99a/take_analysis.json')
target.write_text(json.dumps(records,indent=2)+'\n')
java=['package com.hooya.stabilizedturret.client;',
      '/** Measured harmonic cues; full trajectories/confidence in reference/ztz99a/take_analysis.json. */',
      'final class Ztz99aTakeCalibration {',
      '    static float[] cue(String sample) { return switch(sample) {']
for name,r in records.items():
    java.append('        case "'+name+'" -> new float[]{'+','.join(f'{v:.2f}F' for v in r['tonalCueHz'])+'};')
java+=['        default -> throw new IllegalArgumentException(sample);','    }; }',
       '    static float confidence(String sample) { return switch(sample) {']
for name,r in records.items():
    java.append(f'        case "{name}" -> {np.mean(r["confidence"]):.5f}F;')
java+=['        default -> throw new IllegalArgumentException(sample);','    }; }',
       '    private Ztz99aTakeCalibration() {}','}']
pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/Ztz99aTakeCalibration.java').write_text('\n'.join(java)+'\n')
for name,r in records.items():print(name,r['tonalCueHz'],round(np.mean(r['confidence']),3))
