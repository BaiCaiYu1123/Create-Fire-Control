package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(Registries.SOUND_EVENT, StabilizedTurret.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> CONTROLLER_PLUG_IN =
            register("controller.plug_in", 16.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> CONTROLLER_PLUG_OUT =
            register("controller.plug_out", 16.0F);

    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_00 =
            register("engine.abrams.engine_00");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_33 =
            register("engine.abrams.engine_33");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_66 =
            register("engine.abrams.engine_66");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_99 =
            register("engine.abrams.engine_99");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_00_LOAD =
            register("engine.abrams.engine_00_load");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_33_LOAD =
            register("engine.abrams.engine_33_load");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_66_LOAD =
            register("engine.abrams.engine_66_load");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_99_LOAD =
            register("engine.abrams.engine_99_load");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_START =
            register("engine.abrams.engine_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> ABRAMS_ENGINE_STOP =
            register("engine.abrams.engine_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> MZ_LOADER =
            register("mz_loader");
    public static final DeferredHolder<SoundEvent, SoundEvent> TYPE_10_LOADER =
            register("type_10_loader");

    public static final DeferredHolder<SoundEvent, SoundEvent> AZ_LOADER =
            register("az_loader");
    public static final DeferredHolder<SoundEvent, SoundEvent> RWR_SCAN =
            register("rwr_scan");
    public static final DeferredHolder<SoundEvent, SoundEvent> CONNECTED_DISPLAY_GOD_MODE =
            register("connected_display_god_mode", 64.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> MAID_LOADING_COMPLETE =
            register("maid_loading_complete", 32.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> MIANBAO_AUTOCANNON_FIRE_HALF =
            register("mianbao_autocannon_fire_half", 64.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> MIANBAO_AUTOCANNON_FIRE_FAR_HALF =
            register("mianbao_autocannon_fire_far_half", 512.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> MIANBAO_MACHINE_GUN_FIRE_HALF =
            register("mianbao_machine_gun_fire_half", 128.0F);
    public static final DeferredHolder<SoundEvent, SoundEvent> MIANBAO_MACHINE_GUN_FIRE_FAR_HALF =
            register("mianbao_machine_gun_fire_far_half", 512.0F);

    public static final java.util.Map<String, DeferredHolder<SoundEvent, SoundEvent>> ZTZ99A = registerDieselSounds();
    private static java.util.Map<String, DeferredHolder<SoundEvent, SoundEvent>> registerDieselSounds() {
        var events = new java.util.LinkedHashMap<String, DeferredHolder<SoundEvent, SoundEvent>>();
        for (String name : new String[]{"engine_00", "engine_33_load", "engine_66_load", "engine_99_load", "engine_start", "engine_stop"})
            events.put(name, register("engine.ztz99a." + name));
        for (String family : new String[]{"revv", "release", "blips"}) {
            int count = family.equals("revv") ? 5 : family.equals("release") ? 7 : 8;
            for (int i = 1; i <= count; i++) {
                String name = "engine_" + family + String.format(java.util.Locale.ROOT, "-%03d", i);
                events.put(name, register("engine.ztz99a." + name));
            }
        }
        return java.util.Collections.unmodifiableMap(events);
    }

    public static final java.util.Map<String, DeferredHolder<SoundEvent, SoundEvent>> TIGER_I = registerTigerSounds();
    private static java.util.Map<String, DeferredHolder<SoundEvent, SoundEvent>> registerTigerSounds() {
        var events = new java.util.LinkedHashMap<String, DeferredHolder<SoundEvent, SoundEvent>>();
        for (String name : new String[]{"idle_load", "slow_not_load", "med_not_load", "fast_not_load",
                "slow_load", "med_load", "fast_load", "start", "stop"}) events.put(name, register("engine.tiger_i." + name));
        for (String family : new String[]{"revv", "release", "blips"}) {
            int count = family.equals("revv") ? 5 : family.equals("release") ? 7 : 9;
            for (int i = 1; i <= count; i++) {
                String name = family + String.format(java.util.Locale.ROOT, "-%03d", i);
                events.put(name, register("engine.tiger_i." + name));
            }
        }
        return java.util.Collections.unmodifiableMap(events);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return register(name, 64.0F);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, float range) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, name);
        return REGISTER.register(name, () -> SoundEvent.createFixedRangeEvent(id, range));
    }

    private ModSounds() {
    }
}
