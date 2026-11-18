package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.network.ScopeAmmoPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class ScopeAmmoChecks {
    public static void main(String[] args) {
        LoaderAmmunition ammunition = new LoaderAmmunition();
        CompoundTag tag = new CompoundTag();
        tag.putInt("AnnihilationSlots", 0b01010);
        ammunition.load(tag, 0b11111);
        equal(3, ammunition.standardCount(0b11111), "mixed rack counts only APFSDS");
        equal(0, ammunition.standardCount(0b01010), "piercing-only rack");
        equal(0, ammunition.standardCount(0), "empty rack");
        ammunition.retain(0b11100);
        equal(2, ammunition.standardCount(0b11100), "consumed slots disappear from count");
        CompoundTag saved = new CompoundTag();
        ammunition.save(saved);
        LoaderAmmunition restored = new LoaderAmmunition();
        restored.load(saved, 0b11100);
        equal(2, restored.standardCount(0b11100), "typed rack survives reload");
        restored.load(new CompoundTag(), 0b11111);
        equal(5, restored.standardCount(0b11111), "old untyped rack retains all original APFSDS");

        for (int count : new int[]{-1, 0, 1, 13, 22, 127, 128, 10000}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var original = new ScopeAmmoPayload(count);
                ScopeAmmoPayload.STREAM_CODEC.encode(buffer, original);
                require(original.equals(ScopeAmmoPayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(),
                        "scope count changed in transit");
            } finally { buffer.release(); }
        }
        var malformed = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            malformed.writeBoolean(true); malformed.writeVarInt(-1);
            try {
                ScopeAmmoPayload.STREAM_CODEC.decode(malformed);
                throw new AssertionError("negative visible count accepted");
            } catch (IllegalArgumentException expected) { /* rejected */ }
        } finally { malformed.release(); }
        System.out.println("PASS: APFSDS-only reserves, mixed/empty/reloaded racks and scoped ammunition protocol");
    }

    private static void equal(int expected, int actual, String message) { require(expected == actual, message); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
