package com.hooya.stabilizedturret.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** Selects the CBC/Sable collision bridge from the methods present in CBC. */
public final class StabilizedTurretMixinPlugin implements IMixinConfigPlugin {
    private static final String CBC_CALLBACK =
            "rbasamoyai.createbigcannons.compat.sable.ShellSubLevelImpactCallback";
    private static final String NEW_BRIDGE =
            "com.hooya.stabilizedturret.mixin.CbcSableCollisionCallbackMixin";
    private static final String LEGACY_BRIDGE =
            "com.hooya.stabilizedturret.mixin.CbcSableLegacyCollisionCallbackMixin";
    private static final String OPTIONS_SET_CAMERA_TYPE_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.OptionsSetCameraTypeMixin";
    private static final String SHOULDER_SURFING_PERSPECTIVE_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.ShoulderSurfingPerspectiveMixin";
    private static final String CONTROL_PITCH_COMPAT_MIXIN =
            "com.hooya.stabilizedturret.mixin.ControlPitchContraptionCompatMixin";
    private static final String CONTROL_PITCH_RECOIL_MIXIN =
            "com.hooya.stabilizedturret.mixin.ControlPitchContraptionRecoilMixin";
    private static final String CBC_PARTIAL_BLOCK_DAMAGE_MANAGER =
            "rbasamoyai.createbigcannons.base.PartialBlockDamageManager";
    private static final String CBC_PARTIAL_BLOCK_DAMAGE_INT_COMPAT_MIXIN =
            "com.hooya.stabilizedturret.mixin.CbcPartialBlockDamageIntCompatMixin";
    private static final String CBC_PARTIAL_BLOCK_DAMAGE_FLOAT_DESCRIPTOR =
            "(Lnet/minecraft/core/BlockPos;FLnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;)Z";
    private static final String CBC_PARTIAL_BLOCK_DAMAGE_INT_DESCRIPTOR =
            "(Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;)Z";
    private static final String CBC_MW_LOADER_MIXIN =
            "com.hooya.stabilizedturret.mixin.MountedMediumCannonLoaderMixin";
    private static final String CBC_MW_ROTARY_CANNON_MIXIN =
            "com.hooya.stabilizedturret.mixin.CbcModernWarfareRotaryCannonMixin";
    private static final String CBC_PROJECTILE_MZ_DETONATION_MIXIN =
            "com.hooya.stabilizedturret.mixin.CbcProjectileMzLoaderDetonationMixin";
    private static final String TERMINAL_BALLISTICS_MZ_DETONATION_MIXIN =
            "com.hooya.stabilizedturret.mixin.TerminalBallisticsMzLoaderDetonationMixin";
    private static final String TERMINAL_BALLISTICS_IMPACT_SERVICE =
            "com.cbc_terminal_ballistics.ballistics.TBImpactService";
    private static final String CBC_SCREEN_SHAKE_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.CbcScreenShakeHandlerMixin";
    private static final String CBC_APFSDS_TRAIL_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.CbcModernWarfareApfsdsTrailMixin";
    private static final String CBC_APFSDS_SYNC_MIXIN =
            "com.hooya.stabilizedturret.mixin.ApfsdsServerEntityMixin";
    private static final String CBC_APFSDS_CLIENT_SYNC_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.ApfsdsPreciseMotionMixin";
    private static final String SIMULATED_COASTERS_EARLY_CHUNK_LOAD_MIXIN =
            "com.hooya.stabilizedturret.mixin.SimulatedCoastersEarlyChunkLoadMixin";
    private static final String GAS_TURBINE_DESTROY_PROTECTION_MIXIN =
            "com.hooya.stabilizedturret.mixin.GasTurbineDestroyProtectionMixin";
    private static final String SIMULATED_COASTERS_KINETIC_CONFLICT_MIXIN =
            "com.hooya.stabilizedturret.mixin.SimulatedCoastersKineticConflictMixin";
    private static final String SIMULATED_COASTERS_KINETIC_CONFLICT_HANDLER =
            "dev.silvergold.simulatedcoasters.track.chainlift.ChainLiftKineticConflict";
    private static final String KINETIC_CONFLICT_HANDLER_DESCRIPTOR =
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)Z";
    private static final String SHOULDER_SURFING_MOD_ID = "shouldersurfing";
    /** Shoulder Surfing AERO uses its own NeoForge id instead of the upstream id. */
    private static final String SHOULDER_SURFING_AERO_MOD_ID = "shouldersurfingaero";
    private static final String CBC_AT_MOD_ID = "cbc_at";
    private static final String MIANBAO_MOD_ID = "mianbaos_modernwarfare";
    private static final String MIANBAO_CRUISE_EXPLOSION_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoCruiseExplosionProcedureMixin";
    private static final String MIANBAO_BALLISTIC_DETONATION_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoBallisticMissileDetonationMixin";
    private static final String MIANBAO_BALLISTIC_REMOTE_VISUAL_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoBallisticMissileRemoteVisualMixin";
    private static final String MIANBAO_ROCKET_EXPLOSION_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoRocketExplosionProcedureMixin";
    private static final String MIANBAO_CANNON_BLOCK_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoCannonBlockMixin";
    private static final String MIANBAO_AUTOCANNON_EXPLOSION_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoAutocannonExplosionProcedureMixin";
    private static final String MIANBAO_COUNTERMEASURE_TRAIL_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoCountermeasureTrailProcedureMixin";
    private static final String MIANBAO_COUNTERMEASURE_TRAIL_PROCEDURE =
            "net.mcreator.myfirstmod.procedures.CountermeasureTtrailProcedure";
    private static final String MIANBAO_REMOTE_WEAPON_STATION_PITCH_LIMIT_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoRemoteWeaponStationPitchLimitMixin";
    private static final String MIANBAO_REMOTE_WEAPON_STATION_SESSION_AIM_MIXIN =
            "com.hooya.stabilizedturret.mixin.MianbaoRemoteWeaponStationSessionAimMixin";
    private static final String MIANBAO_CLIENT_CONTRAPTION_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.MianbaoClientContraptionMixin";
    private static final Set<String> MOUNTED_HEAVY_MACHINE_GUN_MIXINS = Set.of(
            "com.hooya.stabilizedturret.mixin.CannonCarriageMountedHeavyMachineGunMixin",
            "com.hooya.stabilizedturret.mixin.ContraptionMountedHeavyMachineGunDropMixin",
            "com.hooya.stabilizedturret.mixin.MountedHeavyMachineGunContraptionMixin",
            "com.hooya.stabilizedturret.mixin.PitchOrientedContraptionMountedHeavyMachineGunMixin",
            "com.hooya.stabilizedturret.mixin.client.MountedHeavyMachineGunRendererMixin");
    private static final String MIANBAO_BALLISTIC_RENDERER_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.MianbaoBallisticMissileRendererMixin";
    private static final String MIANBAO_REMOTE_WEAPON_STATION_CLIENT_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.MianbaoRemoteWeaponStationRemoteClientMixin";
    private static final String POD_RENDERER_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.ElectroOpticalPodRendererMixin";
    private static final String YUUSHYA_MIXIN_PREFIX =
            "com.hooya.stabilizedturret.mixin.Yuushya";
    private static final String YUUSHYA_CLIENT_MIXIN_PREFIX =
            "com.hooya.stabilizedturret.mixin.client.Yuushya";
    private static final String YUUSHYA_SHOW_BLOCK_QUAD_SNAPSHOT_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.YuushyaShowBlockQuadSnapshotMixin";
    private static final String MOUSE_FLIGHT_HUD_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.MouseFlightHudMixin";
    private static final String DUPLICATE_CLIENT_EXTENSIONS_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.DuplicateClientExtensionsMixin";

    private boolean hasThreeArgumentCallback;
    private boolean hasFourArgumentCallback;
    private boolean shoulderSurfingLoaded;
    private boolean cbcAtLoaded;
    private boolean mianbaoLoaded;
    private boolean createBigCannonsLoaded;
    private boolean cbcModernWarfareLoaded;
    private boolean terminalBallisticsLoaded;
    private boolean terminalBallisticsMzHookAvailable;
    private boolean yuushyaModellingLoaded;
    private boolean qModelingLoaded;
    private boolean simulatedCoastersLoaded;
    private boolean simulatedCoastersKineticConflictHookAvailable;
    private boolean needsCbcPartialBlockDamageIntBridge;
    private boolean createFlightControlLoaded;
    private boolean azimuthLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        int mask = readCallbackSignatureMask();
        hasThreeArgumentCallback = (mask & 1) != 0;
        hasFourArgumentCallback = (mask & 2) != 0;
        // Support both the upstream mod id and the AERO fork used by the
        // pack.  The compatibility mixin must be selected from the actual
        // loading id; checking only "shouldersurfing" silently disables it
        // and lets AERO's Perspective.of switch crash on Sable camera types.
        shoulderSurfingLoaded = isModLoaded(SHOULDER_SURFING_MOD_ID)
                || isModLoaded(SHOULDER_SURFING_AERO_MOD_ID);
        cbcAtLoaded = isModLoaded(CBC_AT_MOD_ID);
        mianbaoLoaded = isModLoaded(MIANBAO_MOD_ID);
        createBigCannonsLoaded = isModLoaded("createbigcannons");
        cbcModernWarfareLoaded = isModLoaded("cbcmodernwarfare");
        terminalBallisticsLoaded = isModLoaded("cbc_terminal_ballistics");
        terminalBallisticsMzHookAvailable = terminalBallisticsLoaded
                && hasMethod(TERMINAL_BALLISTICS_IMPACT_SERVICE,
                "triggerExplosiveBlockHit");
        needsCbcPartialBlockDamageIntBridge = cbcAtLoaded
                && createBigCannonsLoaded
                && hasMethod(
                CBC_PARTIAL_BLOCK_DAMAGE_MANAGER,
                "damageBlock",
                CBC_PARTIAL_BLOCK_DAMAGE_FLOAT_DESCRIPTOR)
                && !hasMethod(
                CBC_PARTIAL_BLOCK_DAMAGE_MANAGER,
                "damageBlock",
                CBC_PARTIAL_BLOCK_DAMAGE_INT_DESCRIPTOR);
        yuushyaModellingLoaded = isModLoaded("yuushya_modelling");
        qModelingLoaded = isModLoaded("qmodeling");
        simulatedCoastersLoaded = isModLoaded("simulatedcoasters");
        simulatedCoastersKineticConflictHookAvailable = simulatedCoastersLoaded
                && hasMethod(
                SIMULATED_COASTERS_KINETIC_CONFLICT_HANDLER,
                "redirectDestroyAwayFromAnchor",
                KINETIC_CONFLICT_HANDLER_DESCRIPTOR);
        createFlightControlLoaded = isModLoaded("create_flight_control");
        azimuthLoaded = isModLoaded("azimuth");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".TerminalArmorInspectionOverlayMixin")
                || mixinClassName.endsWith(".PolyArmorTerminalRicochetMixin")
                || mixinClassName.endsWith(".EquivalentArmorTerminalInspectorMixin")
                || mixinClassName.endsWith(".PolyArmorTerminalImpactMixin")
                || mixinClassName.endsWith(".PolyArmorTerminalInspectionMixin")
                || mixinClassName.endsWith(".PolyArmorTerminalIntegrityMixin"))
            return terminalBallisticsLoaded;
        if (mixinClassName.endsWith(".EquivalentArmorInspectionMixin")
                || mixinClassName.endsWith(".EquivalentArmorProjectileMixin")
                || mixinClassName.endsWith(".EquivalentArmorBlockStateMixin")
                || mixinClassName.endsWith(".PolyArmorPropertiesMixin")
                || mixinClassName.endsWith(".PolyArmorProjectileAccessor")
                || mixinClassName.endsWith(".PolyArmorExplosionQueueMixin")
                || mixinClassName.endsWith(".PolyArmorSurfaceNormalMixin")
                || mixinClassName.endsWith(".PolyArmorCannonCollisionMixin")
                || mixinClassName.endsWith(".PolyArmorCannonImpactMixin")
                || mixinClassName.endsWith(".PolyArmorCommonImpactMixin")
                || mixinClassName.endsWith(".PolyArmorLevelImpactMixin")) return createBigCannonsLoaded;
        if (mixinClassName.endsWith(".PolyArmorNativeImpactMixin")) return cbcModernWarfareLoaded;
        if (mixinClassName.endsWith(".VirtualScopeColorwheelEmbeddingMixin")) {
            return isModLoaded("colorwheel");
        }
        if (mixinClassName.startsWith("com.hooya.stabilizedturret.mixin.client.QModeling")) {
            return qModelingLoaded;
        }
        if (mixinClassName.endsWith(".MianbaoGuidedMissileLifetimeMixin")) {
            return mianbaoLoaded && hasMethod(targetClassName, "tick", "()V");
        }
        if (mixinClassName.endsWith(".MianbaoOrdinaryAaInventoryMixin")) {
            return mianbaoLoaded && hasMethod(targetClassName, "execute", "(Lnet/minecraft/world/item/ItemStack;D)Z");
        }
        if (mixinClassName.endsWith(".MianbaoOrdinaryAaTrailMixin")
                || mixinClassName.endsWith(".MianbaoConfigurableEngineMixin")
                || mixinClassName.endsWith(".MianbaoConfigurableImmediateBoostMixin")
                || mixinClassName.endsWith(".MianbaoConfigurableDelayedBoostMixin")
                || mixinClassName.endsWith(".MianbaoAirToAirTrailTimingMixin")
                || mixinClassName.endsWith(".MianbaoAirToAirEngineSoundMixin")) {
            return mianbaoLoaded && hasMethod(targetClassName, "execute",
                    "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V");
        }
        if (mixinClassName.endsWith(".MianbaoConfigurableAt7BoostMixin")) return mianbaoLoaded && hasMethod(targetClassName, "execute",
                "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V");
        if (mixinClassName.endsWith(".MianbaoBombRackSignalMixin")) return mianbaoLoaded;
        if (mixinClassName.endsWith("WirelessProcessorChannelsMixin")
                || mixinClassName.endsWith("ControllerWireSelectionAccessor")) return isModLoaded("drivebywire");
        if ("com.hooya.stabilizedturret.mixin.MianbaoNativeStationGunfireMixin".equals(mixinClassName)
                || "com.hooya.stabilizedturret.mixin.MianbaoNativeMountedGunfireMixin".equals(mixinClassName)) {
            return mianbaoLoaded && hasMethod(targetClassName,
                    mixinClassName.endsWith("StationGunfireMixin") ? "tick" : "execute");
        }
        if (MOUNTED_HEAVY_MACHINE_GUN_MIXINS.contains(mixinClassName)) {
            return mianbaoLoaded && createBigCannonsLoaded;
        }
        if ("com.hooya.stabilizedturret.mixin.MianbaoLowAltitudeGuidanceMixin".equals(mixinClassName)) {
            return mianbaoLoaded && hasMethod(targetClassName, "execute",
                    "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V");
        }
        if (NEW_BRIDGE.equals(mixinClassName)) {
            return hasThreeArgumentCallback && !hasFourArgumentCallback;
        }
        if (LEGACY_BRIDGE.equals(mixinClassName)) {
            return hasFourArgumentCallback && !hasThreeArgumentCallback;
        }
        if (OPTIONS_SET_CAMERA_TYPE_MIXIN.equals(mixinClassName)) {
            // Always keep the generic bound-seat camera guard available. It
            // also bypasses ShoulderSurfing when that mod is installed.
            return true;
        }
        if (SHOULDER_SURFING_PERSPECTIVE_MIXIN.equals(mixinClassName)) {
            return shoulderSurfingLoaded;
        }
        if (CONTROL_PITCH_COMPAT_MIXIN.equals(mixinClassName)) {
            // Bridge for cbc_at's two-argument ControlPitchContraption.onRecoil call.
            return cbcAtLoaded;
        }
        if (CONTROL_PITCH_RECOIL_MIXIN.equals(mixinClassName)) return createBigCannonsLoaded;
        if (CBC_PARTIAL_BLOCK_DAMAGE_INT_COMPAT_MIXIN.equals(mixinClassName)) {
            return needsCbcPartialBlockDamageIntBridge;
        }
        if (CBC_SCREEN_SHAKE_MIXIN.equals(mixinClassName)) return createBigCannonsLoaded;
        if (mixinClassName.endsWith(".CbcFpsOptimizerParticleMixin")) {
            return createBigCannonsLoaded && isModLoaded("createfpsoptimizer")
                    && hasMethod("dev.noxora.createfpsoptimizer.OptimizationController", "allowParticle",
                    "(Lnet/minecraft/core/particles/ParticleOptions;DDD)Z");
        }
        if (CBC_PROJECTILE_MZ_DETONATION_MIXIN.equals(mixinClassName)) return createBigCannonsLoaded;
        if (TERMINAL_BALLISTICS_MZ_DETONATION_MIXIN.equals(mixinClassName)) {
            return terminalBallisticsMzHookAvailable;
        }
        if (CBC_MW_ROTARY_CANNON_MIXIN.equals(mixinClassName)) return cbcModernWarfareLoaded;
        if (CBC_MW_LOADER_MIXIN.equals(mixinClassName)
                || CBC_APFSDS_SYNC_MIXIN.equals(mixinClassName)
                || CBC_APFSDS_CLIENT_SYNC_MIXIN.equals(mixinClassName)
                || CBC_APFSDS_TRAIL_MIXIN.equals(mixinClassName)) {
            return cbcModernWarfareLoaded;
        }
        if (POD_RENDERER_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded;
        }
        if (MIANBAO_REMOTE_WEAPON_STATION_CLIENT_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded;
        }
        if (MIANBAO_BALLISTIC_RENDERER_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded;
        }
        if (MIANBAO_CRUISE_EXPLOSION_MIXIN.equals(mixinClassName)
                || MIANBAO_BALLISTIC_DETONATION_MIXIN.equals(mixinClassName)
                || MIANBAO_BALLISTIC_REMOTE_VISUAL_MIXIN.equals(mixinClassName)
                || MIANBAO_ROCKET_EXPLOSION_MIXIN.equals(mixinClassName)
                || MIANBAO_AUTOCANNON_EXPLOSION_MIXIN.equals(mixinClassName)
                || MIANBAO_REMOTE_WEAPON_STATION_SESSION_AIM_MIXIN.equals(mixinClassName)
                || MIANBAO_REMOTE_WEAPON_STATION_PITCH_LIMIT_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded;
        }
        if (MIANBAO_COUNTERMEASURE_TRAIL_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded
                    && hasMethod(MIANBAO_COUNTERMEASURE_TRAIL_PROCEDURE, "execute");
        }
        if (MIANBAO_CANNON_BLOCK_MIXIN.equals(mixinClassName)
                || MIANBAO_CLIENT_CONTRAPTION_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded && createBigCannonsLoaded;
        }
        if (SIMULATED_COASTERS_EARLY_CHUNK_LOAD_MIXIN.equals(mixinClassName)) {
            return simulatedCoastersLoaded;
        }
        if (GAS_TURBINE_DESTROY_PROTECTION_MIXIN.equals(mixinClassName)) {
            return !simulatedCoastersKineticConflictHookAvailable;
        }
        if (SIMULATED_COASTERS_KINETIC_CONFLICT_MIXIN.equals(mixinClassName)) {
            return simulatedCoastersKineticConflictHookAvailable;
        }
        if (MOUSE_FLIGHT_HUD_MIXIN.equals(mixinClassName)) {
            return createFlightControlLoaded;
        }
        if (DUPLICATE_CLIENT_EXTENSIONS_MIXIN.equals(mixinClassName)) {
            return azimuthLoaded;
        }
        if (YUUSHYA_SHOW_BLOCK_QUAD_SNAPSHOT_MIXIN.equals(mixinClassName)) {
            return yuushyaModellingLoaded;
        }
        if (mixinClassName.startsWith(YUUSHYA_MIXIN_PREFIX)
                || mixinClassName.startsWith(YUUSHYA_CLIENT_MIXIN_PREFIX)) {
            return cbcModernWarfareLoaded && yuushyaModellingLoaded;
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
            java.lang.reflect.Method getModList = modListClass.getMethod("getLoadingModList");
            Object modList = getModList.invoke(null);
            java.lang.reflect.Method getModFileById = modList.getClass()
                    .getMethod("getModFileById", String.class);
            Object modFile = getModFileById.invoke(modList, modId);
            return modFile != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int readCallbackSignatureMask() {
        String resource = CBC_CALLBACK.replace('.', '/') + ".class";
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                return 0;
            }
            int[] mask = {0};
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    if ("sable$onCollision".equals(name)) {
                        int argumentCount = Type.getArgumentTypes(descriptor).length;
                        if (argumentCount == 3) {
                            mask[0] |= 1;
                        } else if (argumentCount == 4) {
                            mask[0] |= 2;
                        }
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return mask[0];
        } catch (IOException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static boolean hasMethod(String className, String methodName) {
        return hasMethod(className, methodName, null);
    }

    private static boolean hasMethod(
            String className,
            String methodName,
            String expectedDescriptor
    ) {
        String resource = className.replace('.', '/') + ".class";
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) return false;
            boolean[] found = {false};
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    if (methodName.equals(name)
                            && (expectedDescriptor == null
                            || expectedDescriptor.equals(descriptor))) {
                        found[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (IOException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
