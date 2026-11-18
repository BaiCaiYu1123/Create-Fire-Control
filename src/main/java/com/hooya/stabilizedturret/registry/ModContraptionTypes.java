package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.mianbao.MianbaoMountedAutocannonContraption;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModContraptionTypes {
    public static final DeferredRegister<ContraptionType> REGISTER = DeferredRegister.create(
            CreateRegistries.CONTRAPTION_TYPE,
            StabilizedTurret.MOD_ID
    );

    public static final DeferredHolder<ContraptionType, ContraptionType> MIANBAO_AUTOCANNON = REGISTER.register(
            "mianbao_autocannon",
            () -> new ContraptionType(MianbaoMountedAutocannonContraption::new)
    );

    private ModContraptionTypes() {
    }
}
