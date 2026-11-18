package com.hooya.stabilizedturret.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Ztz99aDieselResourceChecks {
    private static final String ROOT="/assets/create_fire_control/";
    public static void main(String[] args) throws Exception {
        Ztz99aDieselGainChecks.run();
        Ztz99aDieselTransitionChecks.run();
        for(float rpm=0;rpm<=100;rpm+=.01F) {
            float[] w=Ztz99aDieselSoundProfile.weights(rpm);
            float power=0;int active=0;
            for(int i=0;i<4;i++) { float weight=w[i]; require(Float.isFinite(weight)&&weight>=0&&weight<=1,"bounded mix");if(i>0)power+=weight*weight;if(weight>0)active++; }
            require(w[0]==1 && power<=1.0001F && active<=3,"official idle bed and adjacent load overlaps");
            if(Ztz99aDieselSoundProfile.instrumentRpm(rpm)>=10) require(Math.abs(power-1)<.0001F,"corrected load-group power");
            for(int i=0;i<4;i++) {
                float pitch=Ztz99aDieselSoundProfile.pitch(i,rpm,.6F);
                require(Float.isFinite(pitch)&&pitch>=.5F&&pitch<=2,"Minecraft playback-rate range");
            }
        }
        for(float boundary:new float[]{1,10,30,45,65,80}) {
            float[] a=Ztz99aDieselSoundProfile.weights(boundary-.001F),b=Ztz99aDieselSoundProfile.weights(boundary+.001F);
            for(int i=0;i<4;i++) require(Math.abs(a[i]-b[i])<.001F,"continuous boundary");
        }
        require(Ztz99aDieselSoundProfile.normalizedRpm(256,256)==100,"Create RPM normalization");
        require(Ztz99aDieselSoundProfile.normalizedRpm(Float.NaN,256)==0,"invalid sound input");
        require(Ztz99aAudioCalibration.duration("engine_start")>3.64F && Ztz99aAudioCalibration.duration("engine_start")<3.65F,"measured startup handoff");
        float startLength=Ztz99aAudioCalibration.duration("engine_start");
        float fadeBegin=startLength-Ztz99aDieselSoundProfile.HANDOFF_SECONDS-1;
        require(Ztz99aDieselSoundProfile.startupEnvelope(fadeBegin,startLength)==0,"idle starts one second earlier");
        require(Math.abs(Ztz99aDieselSoundProfile.startupEnvelope(fadeBegin+.375F,startLength)-.5F)<.00001F,"preserved startup fade midpoint");
        require(Ztz99aDieselSoundProfile.startupEnvelope(startLength-1,startLength)==1,"idle fade completes one second earlier");
        for(float elapsed=0;elapsed<=startLength+.05F;elapsed+=.01F) {
            float oldShifted=Ztz99aDieselSoundProfile.clamp((elapsed+1-(startLength-Ztz99aDieselSoundProfile.HANDOFF_SECONDS))/Ztz99aDieselSoundProfile.HANDOFF_SECONDS,0,1);
            require(Math.abs(Ztz99aDieselSoundProfile.startupEnvelope(elapsed,startLength)-oldShifted)<.00001F,"one second startup advance without changing fade shape");
        }
        JsonObject states=json("blockstates/ztz99a_diesel_engine.json").getAsJsonObject("variants");
        require(Ztz99aDieselResourceChecks.class.getResource(ROOT+
                "models/block/ztz99a_diesel_engine/geometry_manifest.json")==null,
                "geometry metadata must not be parsed as a block model");
        require(json("ztz99a/geometry_manifest.json").get("elements").getAsInt()==155,
                "preserved source geometry metadata");
        try(var input=resource("textures/block/ztz99a_diesel_engine.png")) {
            require(Arrays.equals(input.readNBytes(8),new byte[]{(byte)137,80,78,71,13,10,26,10}),
                    "packaged PNG texture");
        }
        require(states.size()==72,"18 slices across four directions");
        for(var entry:states.entrySet()) {
            JsonObject model=entry.getValue().getAsJsonObject();
            String name=model.get("model").getAsString().replace("create_fire_control:block/", "models/block/");
            JsonObject mesh=json(name+".json");
            String path=mesh.get("model").getAsString().replace("create_fire_control:", "");
            require(mesh.getAsJsonObject("textures").get("layer0").getAsString()
                    .equals("create_fire_control:block/ztz99a_diesel_engine"),"independent texture binding");
            try(var input=resource(path)) {
                String obj=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                String material=Arrays.stream(obj.split("\\R")).filter(line->line.startsWith("mtllib "))
                        .findFirst().orElseThrow().substring(7);
                try(var mtl=resource(path.substring(0,path.lastIndexOf('/')+1)+material)) {
                    require(new String(mtl.readAllBytes(),StandardCharsets.UTF_8).contains("map_Kd #layer0"),
                            "packaged OBJ material binding");
                }
                int vertices=0;
                for(String line:obj.split("\\R")) if(line.startsWith("v ")) {
                    String[] v=line.split(" ");
                    for(int i=1;i<=3;i++) { float value=Float.parseFloat(v[i]);require(value>=-.000001F&&value<=1.000001F,"cell-clipped mesh"); }
                    vertices++;
                }
                require(vertices>0,"nonempty slice");
                require(!obj.contains("gas_turbine"),"independent mesh");
            }
        }
        JsonObject sounds=json("sounds.json");int count=0;
        for(var entry:sounds.entrySet()) if(entry.getKey().startsWith("engine.ztz99a.")) {
            count++;
            String sample=entry.getKey().substring("engine.ztz99a.".length());
            var definition=entry.getValue().getAsJsonObject().getAsJsonArray("sounds").get(0).getAsJsonObject();
            require(!definition.get("stream").getAsBoolean() && definition.get("preload").getAsBoolean(),"static preloaded 99A buffers");
            require(Ztz99aAudioCalibration.duration(sample)>0,"sample timing");
            try(var input=resource("sounds/engine/ztz99a/"+sample+".ogg")) {
                byte[] bytes=input.readNBytes(256);
                require(new String(bytes,0,4,StandardCharsets.US_ASCII).equals("OggS"),"Ogg container");
                require(new String(bytes,StandardCharsets.ISO_8859_1).contains("vorbis"),"Vorbis identification");
            }
        }
        require(count==26,"all independent samples registered");
        require(json("models/item/ztz99a_diesel_engine.json").get("model").getAsString().contains("ztz99a_diesel_engine/item.obj"),"inventory mesh");
        System.out.println("PASS: diesel mix continuity, 72 model variants, cell bounds and 26 Vorbis resources");
    }
    private static InputStream resource(String name) { var in=Ztz99aDieselResourceChecks.class.getResourceAsStream(ROOT+name);require(in!=null,"resource "+name);return in; }
    private static JsonObject json(String name) throws Exception { try(var in=resource(name)) { return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject(); } }
    private static void require(boolean value,String label) { if(!value)throw new AssertionError(label); }
}
