package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Bridges a loaded MZ/AZ loader to Mianbao's patched HE shell-rack detonation. */
public final class MzLoaderDetonation {
    public static final int SPALL_DETONATION_BLOCK_EVENT = 19042;
    private static final String MIANBAO_MOD_ID = "mianbaos_modernwarfare";
    private static final String SHELL_RACK_PROCEDURE =
            "net.mcreator.myfirstmod.procedures.ShellrackexplodeProcedure";
    private static final String SHELL_RACK_EXPLOSION_PROCEDURE =
            "net.mcreator.myfirstmod.procedures.ShellrackexplodebyexplodeProcedure";
    private static volatile Method directDetonationProcedure;
    private static volatile Method explosionDetonationProcedure;
    private static volatile boolean directProcedureResolved;
    private static volatile boolean explosionProcedureResolved;

    public static boolean isArmed(Level level, BlockPos pos) {
        return level != null && pos != null && ModList.get().isLoaded(MIANBAO_MOD_ID)
                && level.getBlockEntity(pos) instanceof CannonAutoLoader loader
                && loader.getSlotCount() > 0;
    }

    public static boolean tryDetonate(Level level, BlockPos pos) {
        return tryDetonate(level, pos, false);
    }

    public static boolean tryDetonateByExplosion(Level level, BlockPos pos) {
        return tryDetonate(level, pos, true);
    }

    private static boolean tryDetonate(Level level, BlockPos pos, boolean causedByExplosion) {
        if (!(level instanceof ServerLevel) || pos == null
                || !ModList.get().isLoaded(MIANBAO_MOD_ID)
                || !(level.getBlockEntity(pos) instanceof CannonAutoLoader loader)
                || !loader.beginDetonation()) {
            return false;
        }
        Method procedure = resolveProcedure(causedByExplosion);
        if (procedure == null) {
            loader.cancelDetonation();
            return false;
        }
        try {
            procedure.invoke(null, level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
            // Patched Mianbao removes the rack itself. Keep compatibility with older builds
            // whose procedure only emitted the blast but left a directly-hit foreign block behind.
            if (level.getBlockEntity(pos) == loader) level.removeBlock(pos, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            loader.cancelDetonation();
            StabilizedTurret.LOGGER.error("Unable to trigger Mianbao shell-rack detonation at {}", pos, exception);
            return false;
        }
    }

    public static boolean isCbcProjectile(Projectile projectile) {
        if (projectile == null) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        String namespace = id.getNamespace();
        if ("createbigcannons".equals(namespace) || "cbcmodernwarfare".equals(namespace)) return true;
        for (Class<?> type = projectile.getClass(); type != null; type = type.getSuperclass()) {
            if ("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Method resolveProcedure(boolean causedByExplosion) {
        if (causedByExplosion ? explosionProcedureResolved : directProcedureResolved) {
            return causedByExplosion ? explosionDetonationProcedure : directDetonationProcedure;
        }
        synchronized (MzLoaderDetonation.class) {
            if (causedByExplosion ? explosionProcedureResolved : directProcedureResolved) {
                return causedByExplosion ? explosionDetonationProcedure : directDetonationProcedure;
            }
            String className = causedByExplosion
                    ? SHELL_RACK_EXPLOSION_PROCEDURE : SHELL_RACK_PROCEDURE;
            Method resolved = null;
            try {
                Class<?> owner = Class.forName(className);
                for (Method method : owner.getMethods()) {
                    if ("execute".equals(method.getName()) && Modifier.isStatic(method.getModifiers())
                            && method.getParameterCount() == 4) {
                        resolved = method;
                        break;
                    }
                }
            } catch (ClassNotFoundException | RuntimeException | LinkageError exception) {
                StabilizedTurret.LOGGER.error("Mianbao shell-rack procedure {} is unavailable", className, exception);
            }
            if (causedByExplosion) {
                explosionDetonationProcedure = resolved;
                explosionProcedureResolved = true;
            } else {
                directDetonationProcedure = resolved;
                directProcedureResolved = true;
            }
            return resolved;
        }
    }

    private MzLoaderDetonation() {
    }
}
