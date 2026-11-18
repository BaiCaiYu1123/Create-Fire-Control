package com.hooya.stabilizedturret.content.remote;

import java.util.*;

/** Stable device-qualified channels; physical key codes never depend on translated labels. */
public record ControllerInput(boolean mouse, int code) {
    public static final int EXIT_KEY = 258;
    public static final String DEFAULT_EXIT = "key_" + EXIT_KEY;
    public static final int SCROLL_UP = 8, SCROLL_DOWN = 9;
    public static final List<ControllerInput> ALL;
    public static final List<String> CHANNELS;
    private static final Map<String, ControllerInput> BY_CHANNEL;
    static {
        List<ControllerInput> inputs = new ArrayList<>();
        int[] punctuation = {32, 39, 44, 45, 46, 47, 59, 61, 91, 92, 93, 96, 161, 162};
        for (int code : punctuation) inputs.add(new ControllerInput(false, code));
        for (int code = 48; code <= 57; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 65; code <= 90; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 256; code <= 269; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 280; code <= 284; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 290; code <= 314; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 320; code <= 336; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 340; code <= 348; code++) inputs.add(new ControllerInput(false, code));
        for (int code = 0; code <= 7; code++) inputs.add(new ControllerInput(true, code));
        inputs.add(new ControllerInput(true, SCROLL_UP));inputs.add(new ControllerInput(true, SCROLL_DOWN));
        ALL = List.copyOf(inputs);
        Map<String, ControllerInput> channels = new LinkedHashMap<>();
        for (ControllerInput input : ALL) channels.put(input.channel(), input);
        BY_CHANNEL = Collections.unmodifiableMap(channels);
        CHANNELS = List.copyOf(channels.keySet());
    }
    public static final Set<String> DEFAULT_MOUSE_BUTTONS=Set.of("mouse_0","mouse_1");
    public static List<String> chordInputs(String value) {
        if(value==null||value.isEmpty())return List.of();
        List<String> keys=List.of(value.split("\\+",-1));
        return keys.size()<=2 && new HashSet<>(keys).size()==keys.size() && keys.stream().allMatch(BY_CHANNEL::containsKey) ? keys : List.of();
    }
    /** Native signal/chord evaluation shared by keyboard mappings and legacy controller consumers. */
    public static int signal(Map<?, ?> signals, String key) {
        if (key == null || key.isEmpty()) return 0;
        List<String> inputs = chordInputs(key);
        if (inputs.size() == 2) return Math.min(signal(signals, inputs.get(0)), signal(signals, inputs.get(1)));
        Object value = signals.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
    public boolean scroll() { return mouse && (code == SCROLL_UP || code == SCROLL_DOWN); }
    public String channel() { return scroll() ? code==SCROLL_UP?"scroll_up":"scroll_down" : (mouse ? "mouse_" : "key_") + code; }
    public static String scrollChannel(double delta) {
        return !Double.isFinite(delta)||Math.abs(delta)<1.0e-6?"":delta>0?"scroll_up":"scroll_down";
    }
    public static int scrollSteps(double delta) {
        return scrollChannel(delta).isEmpty()?0:(int)Math.clamp(Math.abs(delta),1,16);
    }
    public static ControllerInput fromChannel(String channel) { return BY_CHANNEL.get(channel); }
    public static String channel(boolean mouse, int code) {
        String channel = new ControllerInput(mouse, code).channel();
        return BY_CHANNEL.containsKey(channel) ? channel : "";
    }
    public static Set<String> effective(Collection<String> wireless, Collection<String> wired,
                                        Collection<String> actions) {
        return effective(wireless, wired, actions, DEFAULT_EXIT);
    }
    public static String exitChannel(String channel) {
        return BY_CHANNEL.containsKey(channel) ? channel : DEFAULT_EXIT;
    }
    public static Set<String> effective(Collection<String> wireless, Collection<String> wired,
                                        Collection<String> actions, String exit) {
        Set<String> result = new LinkedHashSet<>();
        for (Collection<String> source : List.of(wireless, wired, actions))
            for (String channel : source) if (BY_CHANNEL.containsKey(channel)) result.add(channel);
        result.remove(exitChannel(exit));
        return Set.copyOf(result);
    }
}
