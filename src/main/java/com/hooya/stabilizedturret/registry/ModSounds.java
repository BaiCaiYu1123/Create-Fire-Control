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

    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_START =
            register("gas_turbine_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_IDLE =
            register("gas_turbine_idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_ACCEL =
            register("gas_turbine_accel");
    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_RUN =
            register("gas_turbine_run");
    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_DECEL =
            register("gas_turbine_decel");
    public static final DeferredHolder<SoundEvent, SoundEvent> GAS_TURBINE_STOP =
            register("gas_turbine_stop");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, name);
        return REGISTER.register(name, () -> SoundEvent.createFixedRangeEvent(id, 64.0F));
    }

    private ModSounds() {
    }
}
