package com.hooya.stabilizedturret.mixin;

import com.simibubi.create.foundation.advancement.CreateAdvancement;
import com.tterrag.registrate.util.entry.ItemProviderEntry;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Backports Create PR #10534 for Create 6.0.10.
 *
 * <p>Large modpacks can dispatch the trigger-type RegisterEvent while an item entry used by
 * AllAdvancements is not bound yet. Create 6.0.10 eagerly calls ItemProviderEntry.asStack()
 * from AllAdvancements' static initializer, which makes startup fail intermittently. Store the
 * provider instead and resolve it from CreateAdvancement.save(), after registries are frozen.</p>
 */
@Mixin(targets = "com.simibubi.create.foundation.advancement.CreateAdvancement$Builder", remap = false)
public abstract class CreateAdvancementBuilderMixin {
    @Shadow
    private boolean externalTrigger;

    @Shadow
    private int keyIndex;

    @Shadow
    private ItemStack icon;

    @Shadow
    private Function<HolderLookup.Provider, ItemStack> func;

    @Shadow
    @Final
    private CreateAdvancement this$0;

    @Unique
    private boolean stabilizedTurret$awardOnIconCollected;

    @Inject(
            method = "icon(Lcom/tterrag/registrate/util/entry/ItemProviderEntry;)Lcom/simibubi/create/foundation/advancement/CreateAdvancement$Builder;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void stabilizedTurret$resolveEntryIconLazily(
            ItemProviderEntry<?, ?> item,
            CallbackInfoReturnable<Object> cir
    ) {
        func = registries -> item.asStack();
        cir.setReturnValue(this);
    }

    @Inject(
            method = "whenIconCollected()Lcom/simibubi/create/foundation/advancement/CreateAdvancement$Builder;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void stabilizedTurret$deferIconCriterion(CallbackInfoReturnable<Object> cir) {
        externalTrigger = true;
        stabilizedTurret$awardOnIconCollected = true;
        cir.setReturnValue(this);
    }

    @Inject(
            method = "icon(Lnet/minecraft/world/item/ItemStack;)Lcom/simibubi/create/foundation/advancement/CreateAdvancement$Builder;",
            at = @At("RETURN")
    )
    private void stabilizedTurret$appendDeferredIconCriterion(
            ItemStack stack,
            CallbackInfoReturnable<Object> cir
    ) {
        if (!stabilizedTurret$awardOnIconCollected) return;
        Advancement.Builder minecraftBuilder =
                ((CreateAdvancementAccessor) (Object) this$0).stabilizedTurret$getMinecraftBuilder();
        minecraftBuilder.addCriterion(
                String.valueOf(keyIndex++),
                InventoryChangeTrigger.TriggerInstance.hasItems(icon.getItem())
        );
        stabilizedTurret$awardOnIconCollected = false;
    }
}
