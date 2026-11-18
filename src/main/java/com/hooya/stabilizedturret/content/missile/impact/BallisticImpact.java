package com.hooya.stabilizedturret.content.missile.impact;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.util.UUID;

/** Optional Mianbao native bridge. */
public final class BallisticImpact {
    private static final String CLASS="net.mcreator.myfirstmod.procedures.BallisticNuclearExplosion";
    private static volatile Method activeMethod;
    private static volatile boolean activeResolved;
    public static boolean detonate(ServerLevel level, UUID id, Vec3 position) {
        if(level==null||position==null||level.isClientSide())return false;
        MianbaoMissileEffects.broadcastDetonationVisual(level, position,
                MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR,
                level.getRandom().nextLong(), true);
        try {
            Class<?> c=Class.forName(CLASS);
            Method m=c.getMethod("detonate", net.minecraft.world.level.LevelAccessor.class, Vec3.class);
            m.invoke(null,level,position);
            return true;
        } catch (ReflectiveOperationException|RuntimeException|LinkageError failure) {
            StabilizedTurret.LOGGER.error("Unable to run Mianbao ballistic nuclear impact {}", id, failure);
            return false;
        }
    }

    /** True while Mianbao is still applying the paced native terrain effect. */
    public static boolean isTerrainWorkActive(ServerLevel level, Vec3 position) {
        if (level == null || position == null) return false;
        if (!activeResolved) {
            synchronized (BallisticImpact.class) {
                if (!activeResolved) {
                    try {
                        Class<?> type = Class.forName(
                                "net.mcreator.myfirstmod.procedures.NuclearTerrainScheduler");
                        activeMethod = type.getMethod("isActive", ServerLevel.class, BlockPos.class);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                        activeMethod = null;
                    }
                    activeResolved = true;
                }
            }
        }
        Method method = activeMethod;
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, level, BlockPos.containing(position)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
    private BallisticImpact(){}
}
