"""Optional native FMOD check against an existing War Thunder bank (no device audio).

Usage: python tools/sample_ztz99a_fmod_gain.py STUDIO_DLL ENGINE_BANK
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
        for i in range(10):check(update(s),'update');check(coreupdate(core),'core update');check(flush(s),'flush')
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
    rows=[]
    for index in range(1001):
        check(param(ev,b'load',index/1000,True),'load');settle()
        row={'load':index/1000}
        for name,g in groups.items():
            v=F();check(gv(g,c.byref(v)),'volume');row[name]=v.value
        rows.append(row)
    target=pathlib.Path('reference/ztz99a/fmod_native_gain.json')
    target.write_text(json.dumps(rows,indent=2)+'\n')
    for row in rows[::250]:print(row)
    # Identify the only decreasing and increasing volume buses with other event inputs fixed.
    decreasing=[k for k in groups if rows[0][k]>1 and rows[-1][k]==0]
    increasing=[k for k in groups if abs(rows[0][k]-1)<.000001 and abs(20*math.log10(rows[-1][k])-6)<.001]
    assert len(decreasing)==len(increasing)==1, 'Unexpected bank graph; do not guess bus identities'
    idle,loaded=decreasing[0],increasing[0]
    def db(value):return max(-80,20*math.log10(value)) if value>0 else -80
    # Remove the idle bus's load-independent baseline and map its response to the XML endpoints.
    # Silence in the native bus is represented by the XML -80 dB endpoint.
    idle_start=db(rows[0][idle]);idle_response=[2+(db(r[idle])-idle_start)*82/(80+idle_start) for r in rows]
    loaded_response=[db(r[loaded]) for r in rows]
    assert idle_response[0]==2 and abs(idle_response[-1]+80)<.000001
    assert abs(loaded_response[0])<.000001 and abs(loaded_response[-1]-6)<.000001
    result=dict(eventId='63385eb2-f16b-4574-9740-3f308964a659',
                bankSha256=hashlib.sha256(pathlib.Path(args.bank).read_bytes()).hexdigest(),
                dllSha256=hashlib.sha256(pathlib.Path(args.studio_dll).read_bytes()).hexdigest(),
                fixedInputs=dict(rpm=33,speed=0,building_density=0,broken=0,climb=0,volume_ducking=0),
                idleNativeStartDb=idle_start,idleBus=idle,loadedBus=loaded,
                method='1001 native response points; idle baseline/range normalized to XML +2/-80 dB; loaded 0/+6 dB. Piecewise dB interpolation; not an XML event rebuild.',
                layer00=idle_response,layer06=loaded_response)
    resource=pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/ztz99a/native_load_response.json')
    resource.write_text(json.dumps(result,indent=2)+'\n')
    java=['package com.hooya.stabilizedturret.client;',
          '/** Generated native FMOD load response, normalized to the authoritative XML endpoints. */',
          'public final class Ztz99aFmodLoadResponse {']
    for name,values in [('IDLE',idle_response),('LOADED',loaded_response)]:
        java.append('    private static final float[] '+name+' = {')
        for offset in range(0,len(values),8):java.append('        '+', '.join(f'{v:.8f}F' for v in values[offset:offset+8])+',')
        java.append('    };')
    java.extend(['    public static float fraction(boolean idle, float parameter) {',
                 '        float[] values = idle ? IDLE : LOADED;',
                 '        float index = Ztz99aDieselSoundProfile.clamp(parameter, 0, 1)*(values.length-1);',
                 '        int lo = Math.min((int)index,values.length-2);',
                 '        float db = values[lo]+(values[lo+1]-values[lo])*(index-lo);',
                 '        return idle ? (2-db)/82 : db/6;',
                 '    }','    private Ztz99aFmodLoadResponse() {}','}'])
    pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/Ztz99aFmodLoadResponse.java').write_text('\n'.join(java)+'\n')
    print(target.resolve())
finally:release(s)
