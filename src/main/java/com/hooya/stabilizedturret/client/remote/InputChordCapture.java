package com.hooya.stabilizedturret.client.remote;

import java.util.*;

/** A binding completes when every captured physical input has been released. */
public final class InputChordCapture {
    private final LinkedHashSet<String> keys=new LinkedHashSet<>();
    private final Set<String> held=new HashSet<>();
    public void clear(){keys.clear();held.clear();}
    public boolean press(String key) {
        if(key==null||key.isEmpty()||(!keys.contains(key)&&keys.size()==2))return false;
        keys.add(key);held.add(key);return true;
    }
    public List<String> release(String key) {
        if(!held.remove(key)||!held.isEmpty()||keys.isEmpty())return List.of();
        return List.copyOf(keys);
    }
    public List<String> keys(){return List.copyOf(keys);}
    public net.minecraft.network.chat.Component preview(net.minecraft.network.chat.Component waiting) {
        if(keys.isEmpty())return waiting;
        var result=net.minecraft.network.chat.Component.empty();
        for(String key:keys) {
            if(!result.getString().isEmpty())result.append(" + ");
            result.append(WirelessControllerClient.name(key));
        }
        if(keys.size()==1&&!held.isEmpty())result.append("+");
        return result;
    }
}
