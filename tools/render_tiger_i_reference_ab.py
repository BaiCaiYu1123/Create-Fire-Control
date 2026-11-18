"""Fixed-scale FLOAT PCM A/B from Java traces and optional genuine installed FMOD bank renders.
No source, Ogg, or per-file loudness changes. Offline files are explicitly not game recordings.
"""
import argparse, csv, json, math, sys, struct, subprocess
from pathlib import Path
sys.path.insert(0,str(Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf
from probe_tiger_i_reference import NativeTiger, Attributes3D, Vector, c, I, U, P, S, F, check

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/dist/tiger_i_fix/audio'
TRACES=ROOT/'build/reports/tiger_i_fix'
SOURCES=ROOT/'src/main/resources/assets/create_fire_control/sounds/engine/tiger_i'
LOOPS=['idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load']

def native_name(trace):
    scenario=trace.stem.removeprefix('fmod_reference_').replace('_tracks','')
    # This capture drives only the already-running engine event's parameters.
    # It does not render the Java start/stop cues or a native engine lifecycle.
    if scenario=='start_stop':scenario+='_'+'rpm_only'
    return 'native_bank_'+scenario+'.wav'

def metrics(pcm,rate):
    windows=[float(np.sqrt(np.mean(pcm[k:k+rate//10]**2))) for k in range(0,len(pcm),rate//10)]
    return dict(seconds=len(pcm)/rate,rms=float(np.sqrt(np.mean(pcm**2))),peak=float(np.max(np.abs(pcm))),
                rms100msMin=min(windows),rms100msMax=max(windows),rms100ms=windows)

def offline(trace):
    with trace.open() as file:rows=list(csv.DictReader(file))
    src={n:sf.read(SOURCES/(n+'.ogg'))[0] for n in LOOPS}
    rate=44100;block=2205;pcm=np.zeros(len(rows)*block,np.float32);positions=np.zeros(7);shot_data={}
    for k,row in enumerate(rows):
        frame=np.zeros(block);time=float(row['time'])
        for i,name in enumerate(LOOPS):
            data=src[name];pitch=float(row['p'+str(i)]);gain=float(row['g'+str(i)])
            coords=(positions[i]+np.arange(block)*pitch)%len(data);a=coords.astype(int);b=(a+1)%len(data)
            frame+=((1-(coords-a))*data[a]+(coords-a)*data[b])*gain
            positions[i]=(positions[i]+block*pitch)%len(data)
        for shot in row['shots'].split(';'):
            if not shot:continue
            name,pitch,db,began=shot.split(':');pitch=float(pitch);db=float(db);began=float(began)
            if name not in shot_data:shot_data[name]=sf.read(SOURCES/(name+'.ogg'))[0]
            data=shot_data[name];coords=(time-began+np.arange(block)/rate)*rate*pitch
            valid=(coords>=0)&(coords<len(data)-1);a=np.clip(coords.astype(int),0,len(data)-2)
            signal=(1-(coords-a))*data[a]+(coords-a)*data[a+1]
            if trace.name.startswith('legacy'):
                age=time-began;remain=(len(data)/rate)/pitch-age
                t=np.clip(min(age/.15,remain/.35),0,1);signal*=t*t*(3-2*t)
            frame+=signal*valid*10**(db/20)*float(row['headroom'])
        pcm[k*block:(k+1)*block]=frame*1.5
    path=OUT/(trace.stem.replace('_tracks','')+'_offline.wav');sf.write(path,pcm,rate,subtype='FLOAT')
    result=metrics(pcm,rate)
    result.update(method='Offline linear resampling of Java mixer traces, mono dry center, GUI gain=1.5; no individual normalization, no EFX/device/loop timing emulation.',
                  predictedAllocatedSourcesMax=max(int(r['predictedAllocatedSources']) for r in rows))
    return path.name,result

def native(trace):
    with trace.open() as file: rows=list(csv.DictReader(file))
    path=OUT/native_name(trace)
    n=NativeTiger(path)
    try:
        check(n.call('FMOD_Studio_EventInstance_SetVolume',[S,F])(n.ev,1.5),'same scalar 1.5')
        a=Attributes3D(Vector(0,0,3),Vector(0,0,0),Vector(0,0,1),Vector(0,1,0))
        check(n.attrs(n.ev,c.byref(a)),'3m reference position')
        block=U();count=I();check(n.call('FMOD5_System_GetDSPBufferSize',[S,P(U),P(I)])(n.core,c.byref(block),c.byref(count)),'buffer size')
        master=S();check(n.call('FMOD5_System_GetMasterChannelGroup',[S,P(S)])(n.core,c.byref(master)),'master')
        clock=n.call('FMOD5_ChannelGroup_GetDSPClock',[S,P(c.c_ulonglong),P(c.c_ulonglong)])
        def dsp_clock():
            now=c.c_ulonglong();parent=c.c_ulonglong();check(clock(master,c.byref(now),c.byref(parent)),'DSP clock');return now.value
        origin=dsp_clock();source_counts=[]
        for k,row in enumerate(rows):
            # Java applied the authored seek and the explicit MC climb mapping already.
            for name in ['rpm','climb','loadswitch']:n.set(name,float(row[name]))
            target=origin+round((k+1)*.05*48000)
            while dsp_clock()<target:check(n.update(n.system),'NRT Studio update')
            source_counts.append(sum(len(g['voices']) for g in n.groups()))
        hashes=n.hashes
    finally:n.close()
    # The installed FMOD runtime returns ERR_INTERNAL at cleanup and leaves zero RIFF sizes.
    # Finalize only the container length fields; every PCM byte remains the actual native output.
    raw=bytearray(path.read_bytes());offset=12
    while raw[offset:offset+4]!=b'data':offset+=8+struct.unpack_from('<I',raw,offset+4)[0]
    if struct.unpack_from('<I',raw,offset+4)[0]==0:
        struct.pack_into('<I',raw,4,len(raw)-8);struct.pack_into('<I',raw,offset+4,len(raw)-offset-8);path.write_bytes(raw)
    pcm,rate=sf.read(path)
    # Trim native setup/preroll with one clock-based offset, no loudness normalization.
    pcm=pcm[min(origin,len(pcm)):min(origin+round(len(rows)*.05*rate),len(pcm))]
    sf.write(path,pcm,rate,subtype='FLOAT');result=metrics(pcm,rate)
    result.update(method='Actual FMOD WAVWRITER_NRT, installed WT compiled engine event by GUID. Same scalar=1.5, listener distance=3m. DSP clock scheduling, setup preroll removed; 16-bit native PCM converted losslessly to FLOAT. Core output explicitly closed to finalize WAV; Studio cleanup returned ERR_INTERNAL. Not rebuilt pinned XML or WT gameplay. Engine event parameter playback only; start_stop drives RPM without native start/stop events.',
                  hashes=hashes,actualBankChannelsMax=max(source_counts),actualBankChannels=source_counts)
    return path.name,result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--native',action='store_true');parser.add_argument('--native-one',type=Path);args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);results={}
    if args.native_one:
        name,result=native(args.native_one)
        (OUT/(name+'.json')).write_text(json.dumps(result,indent=2)+'\n')
        print(name,result['seconds'],result['rms'],result['peak'],flush=True);return
    for trace in sorted(TRACES.glob('*_tracks.csv')):
        name,result=offline(trace);results[name]=result;print(name,result['rms'],result['peak'],flush=True)
        if args.native and trace.name.startswith('fmod_reference_'):
            # Installed runtime cannot fully release Studio systems; isolate each render in a process.
            subprocess.run([sys.executable,__file__,'--native-one',str(trace)],check=True)
            name=native_name(trace)
            results[name]=json.loads((OUT/(name+'.json')).read_text())
    (OUT/'measurements.json').write_text(json.dumps(results,indent=2)+'\n')

if __name__=='__main__':main()
