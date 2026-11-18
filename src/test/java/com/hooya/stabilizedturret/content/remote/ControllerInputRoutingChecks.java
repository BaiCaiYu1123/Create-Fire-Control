package com.hooya.stabilizedturret.content.remote;

import java.util.*;

public final class ControllerInputRoutingChecks {
    public static void main(String[] args) {
        String w=ControllerInput.channel(false,87),left=ControllerInput.channel(true,0),side=ControllerInput.channel(true,3);
        require(!w.isEmpty()&&!left.equals(w)&&!side.equals(left),"device-qualified inputs");
        String tab=ControllerInput.channel(false,258);
        require(tab.equals(ControllerInput.DEFAULT_EXIT),"Tab is a valid configurable input");
        require(ControllerInput.exitChannel("bad").equals(tab),"invalid exit falls back to Tab");
        require(ControllerInput.effective(Set.of(tab,w),Set.of(tab,w),Set.of(tab,w)).equals(Set.of(w)),"default exit excluded from every use");
        require(ControllerInput.effective(Set.of(tab,w),Set.of(tab,w),Set.of(tab,w),w).equals(Set.of(tab)),"custom exit frees Tab and excludes existing wireless/wire/action bindings");
        require(ControllerInput.effective(Set.of(left,tab),Set.of(left),Set.of(left),left).equals(Set.of(tab)),"mouse exit cannot drive outputs");
        require(ControllerInput.CHANNELS.size()==new HashSet<>(ControllerInput.CHANNELS).size(),"unique channels");
        var routing=new ControllerInputRouting();
        require(!routing.event(w,true),"unconfigured W must pass through");
        routing.updateUses(Set.of(w,left,side),Set.of(left));
        require(routing.event(left,true)&&routing.pressed().isEmpty(),"activation click cannot become a held output");
        routing.event(left,false);routing.event(left,true);routing.event(w,true);routing.event(side,true);
        require(routing.pressed().equals(Set.of(w,left,side)),"simultaneous keyboard and mouse");
        routing.updateUses(Set.of(w,left),Set.of(w,left,side));
        require(!routing.pressed().contains(side)&&routing.blocks(side),"removed use clears signal and waits for release");
        routing.event(side,false);require(!routing.event(side,true),"removed input passes after release");
        require(ControllerInput.effective(Set.of(w),Set.of(w,left),Set.of(side,"bad")).equals(Set.of(w,left,side)),"union of live uses");
        routing.updateUses(ControllerInput.effective(Set.of(w),Set.of(w),Set.of()),Set.of(w));
        require(routing.pressed().contains(w),"removing one use preserves a remaining use");
        routing.stop(Set.of(w));require(routing.pressed().isEmpty()&&routing.blocks(w),"stop releases output with held barrier");
        routing.reconcile(Set.of());require(!routing.blocks(w),"focus/physical release clears barrier");
        routing.updateUses(Set.of(w),Set.of(w));require(routing.event(w,true)&&routing.pressed().isEmpty(),"new binding during hold waits for fresh press");
        routing.event(w,false);routing.event(w,true);require(routing.pressed().equals(Set.of(w)),"fresh press works");
        var chords=Map.of(w,side);
        require(ControllerChords.physicalUses(Set.of(w),chords,tab).equals(Set.of(w,side)),"only live combos claim their secondary input");
        require(ControllerChords.outputs(Set.of(w),Set.of(w),chords,tab).isEmpty(),"primary alone does not trigger a combo");
        require(ControllerChords.outputs(Set.of(side),Set.of(w),chords,tab).isEmpty(),"secondary alone does not trigger a combo");
        require(ControllerChords.outputs(Set.of(side,w),Set.of(w),chords,tab).equals(Set.of(w)),"both press orders trigger one stable output");
        require(ControllerChords.outputs(Set.of(side,w),Set.of(w,side),chords,tab).equals(Set.of(w,side)),"combo secondary can retain its independent use");
        require(ControllerChords.physicalUses(Set.of(w),Map.of(w,tab),tab).isEmpty(),"exit cannot be claimed by a combo");
        require(ControllerChords.outputs(Set.of(w,tab),Set.of(w),Map.of(w,tab),tab).isEmpty(),"exit combos cannot emit output");
        require(ControllerInput.scrollChannel(.05).equals("scroll_up")&&ControllerInput.scrollChannel(-.05).equals("scroll_down"),"both high-resolution wheel directions have stable channels");
        require(ControllerInput.scrollChannel(Double.NaN).isEmpty()&&ControllerInput.scrollSteps(Double.POSITIVE_INFINITY)==0,"nonfinite wheel rejected");
        ControllerPulseQueue pulses=new ControllerPulseQueue();pulses.add(Set.of("scroll_down"),3);pulses.add(Set.of("scroll_up"),1);
        for(int tick=0;tick<8;tick++) {
            Set<String> expected=tick%2==1?Set.of():Set.of(tick<6?"scroll_down":"scroll_up");
            require(pulses.active(tick).equals(expected)&&pulses.active(tick).equals(expected),"notches keep order with one high and one low tick");
        }
        require(pulses.idle(),"all wheel pulses drain");pulses.add(Set.of(w),16);pulses.clear();require(pulses.active(20).isEmpty(),"stop clears queued wheel pulses");
        System.out.println("PASS: wheel directions, bounded ordered wheel pulses and clear; two-input chords, release, independent secondary, exit reservation and input routing");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
