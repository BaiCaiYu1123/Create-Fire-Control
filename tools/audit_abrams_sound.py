import sys, json, hashlib, argparse
from pathlib import Path
parser=argparse.ArgumentParser(description='Read-only Abrams PCM/loudness audit; never writes sound assets.')
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--reference', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--packages', type=Path, action='append', default=[])
args=parser.parse_args()
base=args.root.resolve()
sys.path[:0]=[str(p.resolve()) for p in args.packages]
import numpy as np
import soundfile as sf
import pyloudnorm as pyln
from scipy.signal import correlate, correlation_lags
def db(x): return float(20*np.log10(max(float(x),1e-15)))
def metrics(p):
    x,sr=sf.read(p,always_2d=True); mono=x.mean(axis=1)
    assert np.isfinite(x).all(), f'Non-finite decoded audio: {p}'
    rms=np.sqrt(np.mean(x*x)); peak=np.max(np.abs(x))
    n=int(.1*sr); blocks=mono[:len(mono)//n*n].reshape(-1,n)
    block_db=20*np.log10(np.maximum(np.sqrt(np.mean(blocks*blocks,axis=1)),1e-15))
    steps=np.abs(np.diff(mono)); seam=abs(mono[-1]-mono[0])
    info=sf.info(p)
    return x,sr,dict(seconds=len(x)/sr,rate=sr,channels=x.shape[1],format=info.format,subtype=info.subtype,
        rms_dbfs=db(rms),mono_rms_dbfs=db(np.sqrt(np.mean(mono*mono))),peak_dbfs=db(peak),
        lufs=pyln.Meter(sr).integrated_loudness(x),mono_lufs=pyln.Meter(sr).integrated_loudness(mono),
        mono_peak_dbfs=db(np.max(np.abs(mono))),mono_crest_db=db(np.max(np.abs(mono))/np.sqrt(np.mean(mono*mono))),
        crest_db=db(peak/rms),block_rms_p95_p10_db=float(np.percentile(block_db,95)-np.percentile(block_db,10)),
        seam_delta=float(seam),step_p99=float(np.percentile(steps,99)),step_max=float(steps.max()),
        seam_step_percentile=float(np.mean(steps<=seam)*100),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
records={}
for p in sorted((base/'src/main/resources/assets/create_fire_control/sounds/engine/abrams').glob('*.ogg')):
    y,sr,m=metrics(p); x,rs,o=metrics(args.reference/f'{p.stem}.wav'); x=x.mean(axis=1); y=y[:,0]
    if sr!=rs:
        from scipy.signal import resample_poly
        import math
        g=math.gcd(sr,rs);x=resample_poly(x,sr//g,rs//g)
    # Search a bounded delay; never fit a different cycle or arbitrarily crop a file.
    c=correlate(y,x,method='fft');lags=correlation_lags(len(y),len(x)); mask=abs(lags)<=sr//10
    lag=int(lags[mask][np.argmax(c[mask])]); xx=x[max(0,-lag):]; yy=y[max(0,lag):]; n=min(len(xx),len(yy));xx=xx[:n];yy=yy[:n]
    gain=float(np.dot(xx,yy)/np.dot(xx,xx)); residual=yy-gain*xx
    size=round(sr*.1);nblock=n//size
    xb=xx[:nblock*size].reshape(-1,size);yb=yy[:nblock*size].reshape(-1,size)
    fits=(xb*yb).sum(axis=1)/(xb*xb).sum(axis=1);valid=np.mean(xb*xb,axis=1)>1e-6
    compare=dict(lag_samples=lag,matched_samples=n,correlation=float(np.corrcoef(xx,yy)[0,1]),
        fitted_gain_db=db(gain),residual_relative_db=db(np.sqrt(np.mean(residual**2))/np.sqrt(np.mean(yy**2))),
        block_gain_p05_db=db(np.percentile(fits[valid],5)),block_gain_p95_db=db(np.percentile(fits[valid],95)),
        near_fullscale_samples=int(np.sum(abs(yy)>=.999)),
        extra_decoded_frames=len(y)-len(x),
        current_seam_at_official_length=float(abs(y[len(x)-1]-y[0])) if len(y)>=len(x) else None)
    records[p.stem]=dict(current=m,official=o,comparison=compare)
    print(p.stem, 'RMS',round(m['rms_dbfs'],3),'official mono',round(o['mono_rms_dbfs'],3),'fit dB',round(compare['fitted_gain_db'],4),'corr',round(compare['correlation'],6),'seam',round(m['seam_delta'],5))
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(records,indent=2)+'\n')
print('idle load RMS difference current/official:',records['engine_00_load']['current']['rms_dbfs']-records['engine_00']['current']['rms_dbfs'],records['engine_00_load']['official']['mono_rms_dbfs']-records['engine_00']['official']['mono_rms_dbfs'])

