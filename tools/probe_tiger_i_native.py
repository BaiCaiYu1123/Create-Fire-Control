"""Sample Tiger I exterior native instrument fades; divide channel audibility by its parent bus.

Usage: python tools/probe_tiger_i_native.py STUDIO_DLL ENGINE_BANK
This does not modify or redistribute the bank. XML remains the authoritative reference.
"""
import argparse, ctypes as c, json, math, os, pathlib, uuid, hashlib
from list_fmod_events import bind, check
from render_fmod_event import Vector, Attributes3D

parser=argparse.ArgumentParser();parser.add_argument('studio_dll');parser.add_argument('bank')
parser.add_argument('--extra-bank',action='append',default=[])
args=parser.parse_args()
handle=os.add_dll_directory(os.path.dirname(os.path.abspath(args.studio_dll)))
d=c.CDLL(os.path.abspath(args.studio_dll))
S=c.c_void_p;U=c.c_uint;I=c.c_int;F=c.c_float;P=c.POINTER
def b(name,arguments):return bind(d,name,arguments)
create=b('FMOD_Studio_System_Create',[P(S),U]);init=b('FMOD_Studio_System_Initialize',[S,I,U,U,S])
getcore=b('FMOD_Studio_System_GetCoreSystem',[S,P(S)]);out=b('FMOD5_System_SetOutput',[S,I])
load=b('FMOD_Studio_System_LoadBankFile',[S,c.c_char_p,U,P(S)])
byid=b('FMOD_Studio_System_GetEventByID',[S,S,P(S)]);release=b('FMOD_Studio_System_Release',[S])
instance=b('FMOD_Studio_EventDescription_CreateInstance',[S,P(S)])
param=b('FMOD_Studio_EventInstance_SetParameterByName',[S,c.c_char_p,F,c.c_bool])
start=b('FMOD_Studio_EventInstance_Start',[S]);update=b('FMOD_Studio_System_Update',[S])
coreupdate=b('FMOD5_System_Update',[S]);flush=b('FMOD_Studio_System_FlushCommands',[S])
group=b('FMOD_Studio_EventInstance_GetChannelGroup',[S,P(S)])
ng=b('FMOD5_ChannelGroup_GetNumGroups',[S,P(I)]);gg=b('FMOD5_ChannelGroup_GetGroup',[S,I,P(S)])
gn=b('FMOD5_ChannelGroup_GetName',[S,c.c_char_p,I]);gv=b('FMOD5_ChannelGroup_GetVolume',[S,P(F)])
sample=b('FMOD_Studio_Bank_LoadSampleData',[S]);flushsamples=b('FMOD_Studio_System_FlushSampleLoading',[S])
attrs=b('FMOD_Studio_EventInstance_Set3DAttributes',[S,P(Attributes3D)])
listener=b('FMOD_Studio_System_SetListenerAttributes',[S,I,P(Attributes3D),P(Vector)])
s=S();check(create(c.byref(s),0x20211),'create')
try:
    core=S();check(getcore(s,c.byref(core)),'core');check(out(core,4),'NOSOUND_NRT')
    check(init(s,128,4,0,None),'synchronous init')
    for bank_path in args.extra_bank+[args.bank]:
        bank=S();check(load(s,os.path.abspath(bank_path).encode(),0,c.byref(bank)),'bank '+bank_path)
        check(sample(bank),'samples')
    check(flushsamples(s),'flush samples')
    desc=S();guid=c.create_string_buffer(uuid.UUID('e4b1917d-d838-4490-8cb1-b8f43f4b1a94').bytes_le)
    check(byid(s,guid,c.byref(desc)),'official event UUID')
    ev=S();check(instance(desc,c.byref(ev)),'instance')
    a=Attributes3D(Vector(0,0,0),Vector(0,0,0),Vector(0,0,1),Vector(0,1,0))
    check(attrs(ev,c.byref(a)),'event position');check(listener(s,0,c.byref(a),None),'listener position')
    for name,value in {'rpm':33,'broken':0,'climb':0,'load':0}.items():
        check(param(ev,name.encode(),value,True),name)
    check(start(ev),'start')
    def settle():
        for i in range(32):check(update(s),'update');check(coreupdate(core),'core update');check(flush(s),'flush')
    settle()
    root=S();check(group(ev,c.byref(root)),'channel group')
    groups={}
    def walk(g,path=''):
        name=c.create_string_buffer(256);check(gn(g,name,256),'name')
        groups[path+name.value.decode()]=g
        n=I();check(ng(g,c.byref(n)),'count')
        for j in range(n.value):
            child=S();check(gg(g,j,c.byref(child)),'child');walk(child,path+str(j)+'/')
    walk(root)

    nc=b('FMOD5_ChannelGroup_GetNumChannels',[S,P(I)]);gc=b('FMOD5_ChannelGroup_GetChannel',[S,I,P(S)])
    current=b('FMOD5_Channel_GetCurrentSound',[S,P(S)]);namefn=b('FMOD5_Sound_GetName',[S,c.c_char_p,I])
    aud=b('FMOD5_Channel_GetAudibility',[S,P(F)]);gaud=b('FMOD5_ChannelGroup_GetAudibility',[S,P(F)])
    def voices(rpm):
        check(param(ev,b'rpm',rpm,True),'rpm');settle();result={}
        for key,g in groups.items():
            parent=F();check(gaud(g,c.byref(parent)),'parent');n=I();check(nc(g,c.byref(n)),'channels')
            for index in range(n.value):
                ch=S();sound=S();nm=c.create_string_buffer(512);v=F()
                check(gc(g,index,c.byref(ch)),'channel')
                if current(ch,c.byref(sound)) or namefn(sound,nm,512) or aud(ch,c.byref(v)):continue
                result[nm.value.decode(errors='replace')]=v.value/max(.000001,parent.value)
        return result
    profile=json.loads(pathlib.Path('reference/tiger_i/profile_reference.json').read_text())
    tables={};plateaus={'idle_load':0,'slow_not_load':20,'med_not_load':60,'fast_not_load':90,'slow_load':20,'med_load':50,'fast_load':90}
    for track in profile['tracks']:
        sample=track['sample'];base=voices(plateaus[sample])[sample]
        for direction in ['fadeIn','fadeOut']:
            points=track[direction]
            if not points:continue
            lo,hi=points[0]['position'],points[-1]['position'];rows=[]
            for index in range(257):
                rpm=lo+(hi-lo)*index/256
                value=voices(rpm).get(sample,0)/base
                if index and (abs(value-rows[-1])>.1 or (value-rows[-1])*(points[-1]['value']-points[0]['value'])<-.00001):
                    value=voices(rpm).get(sample,0)/base
                rows.append(max(0,min(1,value)))
            rows[0]=points[0]['value'];rows[-1]=points[-1]['value']
            assert all((b-a)*(rows[-1]-rows[0])>=-.00002 and abs(b-a)<.1 for a,b in zip(rows,rows[1:])),(sample,direction)
            tables[sample+'_'+direction]=rows
            print(sample,direction,[round(v,6) for v in rows[::64]],flush=True)
    result={'eventId':profile['eventId'],'bankSha256':hashlib.sha256(pathlib.Path(args.bank).read_bytes()).hexdigest(),'dllSha256':hashlib.sha256(pathlib.Path(args.studio_dll).read_bytes()).hexdigest(),'method':'Native FMOD audibility / parent bus / same-instrument plateau; 257 points; XML endpoints, fixed broken=0, climb=0, load=0. Bank build can differ from source XML.','tables':tables}
    pathlib.Path('reference/tiger_i/native_fades.json').write_text(json.dumps(result,indent=2)+'\n')
    java=['package com.hooya.stabilizedturret.client;','/** Native Tiger I fade tables; reference/tiger_i/native_fades.json contains provenance. */','public final class TigerIFmodFade {']
    for index,(key,rows) in enumerate(tables.items()):
        java.append('    private static final float[] CURVE_'+str(index)+' = {')
        for offset in range(0,257,8):java.append('        '+', '.join(f'{v:.8f}F' for v in rows[offset:offset+8])+',')
        java.append('    };')
    java+=['    public static float value(int curve,float t) {','        float[] values=switch(curve) {']
    java+=['            case '+str(i)+' -> CURVE_'+str(i)+';' for i in range(len(tables))]
    java+=['            default -> throw new IllegalArgumentException();','        };','        float index=TigerISoundProfile.clamp(t,0,1)*256;','        int lo=Math.min((int)index,255);','        return values[lo]+(values[lo+1]-values[lo])*(index-lo);','    }','    private TigerIFmodFade() {}','}']
    pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/TigerIFmodFade.java').write_text('\n'.join(java)+'\n')
finally:release(s)
