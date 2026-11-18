package com.hooya.stabilizedturret.client.compat;

import net.minecraft.client.resources.model.BakedModel;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** No graphics context needed: exercise third-party model contracts rather than BuiltInModel. */
public final class YuushyaItemRenderPlanChecks {
    public static void main(String[] args) {
        BakedModel custom = model(new AtomicBoolean(true), null);
        YuushyaItemRenderPlan root = YuushyaItemRenderPlan.resolve(custom, null);
        require(root.cached().isEmpty() && root.live().equals(List.of(custom)),
                "custom root must render live even if it has no ordinary render passes");

        BakedModel ordinary = model(new AtomicBoolean(false), null);
        AtomicBoolean useCustomRenderer = new AtomicBoolean(true);
        BakedModel switchable = model(useCustomRenderer, null);
        BakedModel wrapper = model(new AtomicBoolean(false), List.of(ordinary, switchable));
        YuushyaItemRenderPlan mixed = YuushyaItemRenderPlan.resolve(wrapper, null);
        require(mixed.cached().equals(List.of(ordinary)) && mixed.live().equals(List.of(switchable)),
                "wrapped custom pass must stay out of the captured mesh without losing ordinary quads");

        useCustomRenderer.set(false);
        YuushyaItemRenderPlan changed = YuushyaItemRenderPlan.resolve(wrapper, null);
        require(changed.cached().equals(List.of(ordinary, switchable)) && changed.live().isEmpty(),
                "routing must update when a model changes behavior without changing identity");
        require(mixed.cached().equals(List.of(ordinary)) && mixed.live().equals(List.of(switchable)),
                "the frame's render plan must remain stable while the underlying model changes");
        System.out.println("Yuushya item render plan checks passed");
    }

    private static BakedModel model(AtomicBoolean custom, List<BakedModel> passes) {
        return (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isCustomRenderer" -> custom.get();
                    case "getRenderPasses" -> {
                        if (passes == null) throw new AssertionError("unexpected render pass enumeration");
                        yield passes;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Test model";
                    default -> throw new AssertionError("unexpected model call: " + method.getName());
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
