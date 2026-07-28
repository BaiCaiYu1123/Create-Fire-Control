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
    private static final String CONTROL_PITCH_COMPAT_MIXIN =
            "com.hooya.stabilizedturret.mixin.ControlPitchContraptionCompatMixin";
    private static final String CBC_MW_ROTARY_CANNON_MIXIN =
            "com.hooya.stabilizedturret.mixin.CbcModernWarfareRotaryCannonMixin";
    private static final String SHOULDER_SURFING_MOD_ID = "shouldersurfing";
    private static final String CBC_AT_MOD_ID = "cbc_at";
    private static final String MIANBAO_MOD_ID = "mianbaos_modernwarfare";
    private static final String POD_RENDERER_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.ElectroOpticalPodRendererMixin";
    private static final String YUUSHYA_MIXIN_PREFIX =
            "com.hooya.stabilizedturret.mixin.Yuushya";
    private static final String YUUSHYA_CLIENT_MIXIN_PREFIX =
            "com.hooya.stabilizedturret.mixin.client.Yuushya";

    private boolean hasThreeArgumentCallback;
    private boolean hasFourArgumentCallback;
    private boolean shoulderSurfingLoaded;
    private boolean cbcAtLoaded;
    private boolean mianbaoLoaded;
    private boolean cbcModernWarfareLoaded;
    private boolean yuushyaModellingLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        int mask = readCallbackSignatureMask();
        hasThreeArgumentCallback = (mask & 1) != 0;
        hasFourArgumentCallback = (mask & 2) != 0;
        shoulderSurfingLoaded = isModLoaded(SHOULDER_SURFING_MOD_ID);
        cbcAtLoaded = isModLoaded(CBC_AT_MOD_ID);
        mianbaoLoaded = isModLoaded(MIANBAO_MOD_ID);
        cbcModernWarfareLoaded = isModLoaded("cbcmodernwarfare");
        yuushyaModellingLoaded = isModLoaded("yuushya_modelling");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (NEW_BRIDGE.equals(mixinClassName)) {
            return hasThreeArgumentCallback && !hasFourArgumentCallback;
        }
        if (LEGACY_BRIDGE.equals(mixinClassName)) {
            return hasFourArgumentCallback && !hasThreeArgumentCallback;
        }
        if (OPTIONS_SET_CAMERA_TYPE_MIXIN.equals(mixinClassName)) {
            // Only needed when ShoulderSurfing is present; without it the
            // vanilla Options.setCameraType() call is safe.
            return shoulderSurfingLoaded;
        }
        if (CONTROL_PITCH_COMPAT_MIXIN.equals(mixinClassName)) {
            // Bridge for cbc_at's two-argument ControlPitchContraption.onRecoil call.
            return cbcAtLoaded;
        }
        if (CBC_MW_ROTARY_CANNON_MIXIN.equals(mixinClassName)) {
            return cbcModernWarfareLoaded;
        }
        if (POD_RENDERER_MIXIN.equals(mixinClassName)) {
            return mianbaoLoaded;
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
