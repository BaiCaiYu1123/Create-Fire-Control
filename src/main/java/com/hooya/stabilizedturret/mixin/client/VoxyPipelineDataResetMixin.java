package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.StabilizedTurret;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Releases stale Iris/Voxy pipeline ownership before Voxy rebuilds. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.RenderPipelineFactory", remap = false)
public abstract class VoxyPipelineDataResetMixin {
    private static boolean createFireControl$logged;

    @Inject(method = "createIrisPipeline", at = @At("HEAD"), require = 0)
    private static void createFireControl$resetStaleData(
            CallbackInfoReturnable<Object> cir
    ) {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null) {
                return;
            }
            Object data = pipeline.getClass()
                    .getMethod("voxy$getPipelineData")
                    .invoke(pipeline);
            if (data == null) {
                return;
            }
            java.lang.reflect.Field owner = data.getClass().getField("thePipeline");
            Object stalePipeline = owner.get(data);
            if (stalePipeline == null) {
                return;
            }
            try {
                java.lang.reflect.Method free = createFireControl$findMethod(
                        stalePipeline.getClass(),
                        "free0"
                );
                if (free == null) {
                    throw new NoSuchMethodException("free0");
                }
                free.setAccessible(true);
                free.invoke(stalePipeline);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // The old Voxy renderer may already have released its GL data.
            }
            owner.set(data, null);
            if (!createFireControl$logged) {
                createFireControl$logged = true;
                StabilizedTurret.LOGGER.info("Released stale Voxy/Iris pipeline data");
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep compatibility with Voxy/Iris builds without this bridge.
        }
    }

    private static java.lang.reflect.Method createFireControl$findMethod(
            Class<?> type,
            String name
    ) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Continue through Voxy's inheritance tree.
            }
        }
        return null;
    }
}
