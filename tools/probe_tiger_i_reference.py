"""Read-only native bank audit. Never modifies audio; records full RPM rather than guessed fade windows."""
import ctypes as c
import hashlib
import json
import os
import time
from pathlib import Path
import uuid
from list_fmod_events import bind, check
from render_fmod_event import Vector, Attributes3D

ROOT = Path(__file__).resolve().parents[1]
DLL = ROOT / '.tools/fmodstudio/fmodstudio.dll'
BANKS = Path('E:/SteamLibrary/steamapps/common/War Thunder/sound')
LOOPS = ['idle_load','slow_not_load','med_not_load','fast_not_load','slow_load','med_load','fast_load']
S=c.c_void_p; I=c.c_int; F=c.c_float; U=c.c_uint; P=c.POINTER

class NativeTiger:
    def __init__(self, output=None):
        self.directory=os.add_dll_directory(str(DLL.parent))
        self.d=c.CDLL(str(DLL))
        def b(name, types): return bind(self.d,name,types)
        self.call=b
        self.update=b('FMOD_Studio_System_Update',[S])
        self.coreupdate=b('FMOD5_System_Update',[S])
        self.flush=b('FMOD_Studio_System_FlushCommands',[S])
        self.param=b('FMOD_Studio_EventInstance_SetParameterByName',[S,c.c_char_p,F,c.c_bool])
        self.attrs=b('FMOD_Studio_EventInstance_Set3DAttributes',[S,P(Attributes3D)])
        self.system=S();check(b('FMOD_Studio_System_Create',[P(S),U])(c.byref(self.system),0x20211),'create')
        self.core=S();check(b('FMOD_Studio_System_GetCoreSystem',[S,P(S)])(self.system,c.byref(self.core)),'core')
        check(b('FMOD5_System_SetOutput',[S,I])(self.core,5 if output else 4),'output')
        check(b('FMOD5_System_SetSoftwareFormat',[S,I,I,I])(self.core,48000,3,0),'format')
        buffer=c.create_string_buffer(str(Path(output).resolve()).encode()) if output else None
        check(b('FMOD_Studio_System_Initialize',[S,I,U,U,S])(self.system,128,4,0,buffer),'init')
        self.hashes={str(DLL.relative_to(ROOT)):hashlib.sha256(DLL.read_bytes()).hexdigest()}
        for name in ['masterbank.bank','masterbank.assets.bank','tanks_engines.bank','tanks_engines.assets.bank']:
            path=BANKS/name;bank=S()
            check(b('FMOD_Studio_System_LoadBankFile',[S,c.c_char_p,U,P(S)])(self.system,str(path).encode(),0,c.byref(bank)),name)
            check(b('FMOD_Studio_Bank_LoadSampleData',[S])(bank),'samples')
            self.hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest()
        check(b('FMOD_Studio_System_FlushSampleLoading',[S])(self.system),'flushsamples')
        desc=S();guid=c.create_string_buffer(uuid.UUID('e4b1917d-d838-4490-8cb1-b8f43f4b1a94').bytes_le)
        check(b('FMOD_Studio_System_GetEventByID',[S,S,P(S)])(self.system,guid,c.byref(desc)),'GUID')
        self.ev=S();check(b('FMOD_Studio_EventDescription_CreateInstance',[S,P(S)])(desc,c.byref(self.ev)),'instance')
        a=Attributes3D(Vector(0,0,0),Vector(0,0,0),Vector(0,0,1),Vector(0,1,0))
        check(self.attrs(self.ev,c.byref(a)),'position')
        check(b('FMOD_Studio_System_SetListenerAttributes',[S,I,P(Attributes3D),P(Vector)])(self.system,0,c.byref(a),None),'listener')
        for name,value in {'rpm':33,'broken':0,'climb':0,'load':0}.items(): self.set(name,value)
        check(b('FMOD_Studio_EventInstance_SetVolume',[S,F])(self.ev,1),'unity volume')
        check(b('FMOD_Studio_EventInstance_Start',[S])(self.ev),'start')
        # Studio ADSR initialization uses elapsed update time even with NRT core output.
        for _ in range(70): self.settle(1); time.sleep(.01)
    def set(self,name,value,immediate=True): check(self.param(self.ev,name.encode(),value,immediate),name)
    def settle(self,count=32):
        for _ in range(count):
            check(self.update(self.system),'update');check(self.flush(self.system),'flush')
    def groups(self):
        root=S();check(self.call('FMOD_Studio_EventInstance_GetChannelGroup',[S,P(S)])(self.ev,c.byref(root)),'group')
        result=[]
        def walk(g,path=''):
            nm=c.create_string_buffer(512);n=I();volume=F();pitch=F();aud=F()
            check(self.call('FMOD5_ChannelGroup_GetName',[S,c.c_char_p,I])(g,nm,512),'name')
            key=path+nm.value.decode(errors='replace')
            for suffix,value in [('Volume',volume),('Pitch',pitch),('Audibility',aud)]:
                check(self.call('FMOD5_ChannelGroup_Get'+suffix,[S,P(F)])(g,c.byref(value)),suffix)
            nc=I();check(self.call('FMOD5_ChannelGroup_GetNumChannels',[S,P(I)])(g,c.byref(nc)),'channels')
            voices=[]
            for j in range(nc.value):
                ch=S();sound=S();sn=c.create_string_buffer(512);v=F();p=F();a=F()
                check(self.call('FMOD5_ChannelGroup_GetChannel',[S,I,P(S)])(g,j,c.byref(ch)),'channel')
                if self.call('FMOD5_Channel_GetCurrentSound',[S,P(S)])(ch,c.byref(sound)):continue
                if self.call('FMOD5_Sound_GetName',[S,c.c_char_p,I])(sound,sn,512):continue
                for suffix,value in [('Volume',v),('Pitch',p),('Audibility',a)]:
                    self.call('FMOD5_Channel_Get'+suffix,[S,P(F)])(ch,c.byref(value))
                voices.append(dict(sample=sn.value.decode(errors='replace'),volume=v.value,pitch=p.value,audibility=a.value,
                                   relativeAudibility=a.value/max(1e-8,aud.value)))
            result.append(dict(path=key,volume=volume.value,pitch=pitch.value,audibility=aud.value,voices=voices))
            check(self.call('FMOD5_ChannelGroup_GetNumGroups',[S,P(I)])(g,c.byref(n)),'children')
            for j in range(n.value):
                child=S();check(self.call('FMOD5_ChannelGroup_GetGroup',[S,I,P(S)])(g,j,c.byref(child)),'child')
                walk(child,key+'/')
        walk(root);return result
    def close(self):
        check(self.call('FMOD_Studio_EventInstance_Stop',[S,I])(self.ev,1),'stop')
        check(self.call('FMOD_Studio_EventInstance_Release',[S])(self.ev),'event release')
        self.settle(4)
        # Installed banks return ERR_INTERNAL in Studio cleanup; explicitly close owned core output first.
        check(self.call('FMOD5_System_Close',[S])(self.core),'close core output')
        result=self.call('FMOD_Studio_System_Release',[S])(self.system)
        if result: print('FMOD system cleanup status',result)

def main():
    native=NativeTiger()
    try:
        rows=[]
        for k in range(401):
            rpm=k/4;native.set('rpm',rpm);native.settle()
            groups=native.groups()
            rows.append(dict(rpm=rpm,voices=[v for g in groups for v in g['voices']]))
        snapshots=[]
        for broken in [0,.1,.265275,.4,.53055,1]:
            native.set('broken',broken);native.set('rpm',50);native.settle()
            snapshots.append(dict(broken=broken,groups=native.groups()))
        native.set('broken',0)
        for climb in [0,.5,1]:
            native.set('climb',climb);native.settle()
            snapshots.append(dict(climb=climb,groups=native.groups()))
        out=ROOT/'reference/tiger_i/native_reference_sweep.json'
        out.write_text(json.dumps(dict(hashes=native.hashes,method='Compiled installed WT bank; 0.25 RPM full sweep, unity event gain, immediate parameters; no forced endpoints. Not a Studio rebuild of pinned XML.',rows=rows,snapshots=snapshots),indent=2)+'\n')
        # Divide by the SAME instrument at its full authored plateau to isolate fade only.
        # This calibrates a control curve, not an audio file's RMS or its final loudness.
        # Retain XML static dB separately because the installed bank can differ from XML.
        plateaus=[1,20,60,90,20,50,90]
        bases=[next(v['relativeAudibility'] for v in rows[int(r*4)]['voices'] if v['sample']==name)
               for name,r in zip(LOOPS,plateaus)]
        table=[]
        for row in rows:
            voices={v['sample']:v for v in row['voices']}
            table.append([voices.get(name,{}).get('relativeAudibility',0)/base for name,base in zip(LOOPS,bases)])
        java=['package com.hooya.stabilizedturret.client;',
              '/** Full native RPM sweep; provenance and raw observations: native_reference_sweep.json. */',
              'public final class TigerIReferenceFade {','    private static final float[][] ROWS = {']
        java+=['        {'+', '.join(f'{v:.9f}F' for v in row)+'},' for row in table]
        java+=['    };','    public static float value(int layer, float rpm) {',
               '        float index=TigerISoundProfile.clamp(rpm,0,100)*4;',
               '        int lo=Math.min((int)index,399);',
               '        return ROWS[lo][layer]+(ROWS[lo+1][layer]-ROWS[lo][layer])*(index-lo);',
               '    }','    private TigerIReferenceFade() {}','}']
        (ROOT/'src/main/java/com/hooya/stabilizedturret/client/TigerIReferenceFade.java').write_text('\n'.join(java)+'\n')
        for k in [0,60,100,200,300,400]: print(rows[k]['rpm'],table[k])
        print('native full sweep saved:',out)
    finally:native.close()

if __name__=='__main__':main()
