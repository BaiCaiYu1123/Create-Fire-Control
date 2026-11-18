"""Optional native FMOD check against an existing War Thunder bank (no device audio).

Usage: python tools/probe_ztz99a_native_pitch.py STUDIO_DLL ENGINE_BANK
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
    desc=S();guid=c.create_string_buffer(uuid.UUID('63385eb2-f16b-4574-9740-3f308964a659').bytes_le)
    check(byid(s,guid,c.byref(desc)),'official event UUID')
    ev=S();check(instance(desc,c.byref(ev)),'instance')
    a=Attributes3D(Vector(0,0,0),Vector(0,0,0),Vector(0,0,1),Vector(0,1,0))
    check(attrs(ev,c.byref(a)),'event position');check(listener(s,0,c.byref(a),None),'listener position')
    for name,value in {'rpm':33,'speed':0,'building_density':0,'broken':0,'climb':0,'load':0,'volume_ducking':0}.items():
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
    print('Native channel groups:',list(groups))
    cp=b('FMOD5_Channel_GetPitch',[S,P(F)])
    cv=b('FMOD5_Channel_GetVolume',[S,P(F)])
    cs=b('FMOD5_Channel_GetCurrentSound',[S,P(S)])
    sn=b('FMOD5_Sound_GetName',[S,c.c_char_p,I])
    gc=b('FMOD5_ChannelGroup_GetNumChannels',[S,P(I)])
    cc=b('FMOD5_ChannelGroup_GetChannel',[S,I,P(S)])
    rows=[]
    for rpm in [0,1,5,10,15,30,33,37.5,45,55,65,70,72.5,80,100]:
        check(param(ev,b'rpm',rpm,True),'rpm');settle()
        row={'rpm':rpm,'channels':[]}
        for name,g in groups.items():
            n=I();check(gc(g,c.byref(n)),'channels')
            for j in range(n.value):
                ch=S();check(cc(g,j,c.byref(ch)),'channel');p=F();v=F();sound=S();nm=c.create_string_buffer(512)
                if cp(ch,c.byref(p)) or cv(ch,c.byref(v)) or cs(ch,c.byref(sound)) or sn(sound,nm,512):continue
                row['channels'].append(dict(group=name,name=nm.value.decode(errors='replace'),pitch=p.value,volume=v.value))
        rows.append(row);print(row)
    pathlib.Path('reference/ztz99a/native_pitch_probe.json').write_text(json.dumps(rows,indent=2))
finally:release(s)
