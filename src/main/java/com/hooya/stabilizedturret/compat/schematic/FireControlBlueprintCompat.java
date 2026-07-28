package com.hooya.stabilizedturret.compat.schematic;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.List;

/** Makes controller bindings portable across Sable blueprint placement. */
public final class FireControlBlueprintCompat {
    private static boolean registered;

    public static void register() {
        if (registered) {
            return;
        }
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.STABILIZER_CONTROLLER.get(),
                new ControllerMapper()
        );
        registered = true;
    }

    private static final class ControllerMapper implements SableBlueprintBlockMapper {
        private static final String PORTABLE_PREFIX = "stabilized_turret:portable_binding_";
        private static final String MISSILE_BINDINGS = "MissileLauncherBindings";
        private static final String CANNON_BINDINGS = "CannonMountBindings";
        private static final String PORTABLE_CANNON_BINDINGS =
                "stabilized_turret:portable_cannon_mounts";
        private static final String PORTABLE_MISSILE_BINDINGS = "stabilized_turret:portable_missile_launchers";
        private static final String AA_MISSILE_BINDINGS = "AaMissileLauncherBindings";
        private static final String PORTABLE_AA_MISSILE_BINDINGS =
                "stabilized_turret:portable_aa_missile_launchers";
        private static final String AIRCRAFT_RACK_BINDINGS = "AircraftMissileRackBindings";
        private static final String PORTABLE_AIRCRAFT_RACK_BINDINGS =
                "stabilized_turret:portable_aircraft_missile_racks";

        @Override
        public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            if (!(context.blockEntity() instanceof StabilizerControllerBlockEntity controller)) {
                return original;
            }
            CompoundTag result = original.copy();
            for (BindingSlot slot : BindingSlot.values()) {
                if (slot == BindingSlot.MISSILE_LAUNCHER
                        || slot == BindingSlot.AA_MISSILE_LAUNCHER
                        || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                        || slot == BindingSlot.CANNON_MOUNT) {
                    continue;
                }
                String bindingKey = "Binding_" + slot.name();
                BindingRef binding = controller.getBinding(slot);
                result.remove(PORTABLE_PREFIX + slot.name());
                if (binding == null) {
                    result.remove(bindingKey);
                    continue;
                }

                Optional<BlueprintBlockRef> portableRef = context.blockRef(binding.pos());
                if (portableRef.isEmpty()) {
                    // Never retain an absolute plot coordinate when the target
                    // is outside the saved blueprint.
                    result.remove(bindingKey);
                    continue;
                }

                BlueprintBlockRef ref = portableRef.get();
                CompoundTag portable = new CompoundTag();
                portable.putInt("SubLevelId", ref.subLevelId());
                portable.putLong("LocalPos", ref.localPos().asLong());
                if (result.contains(bindingKey)) {
                    CompoundTag savedBinding = result.getCompound(bindingKey);
                    if (savedBinding.contains("Block")) {
                        portable.putString("Block", savedBinding.getString("Block"));
                    }
                    portable.putBoolean("HasBlockEntity", savedBinding.getBoolean("HasBlockEntity"));
                }
                result.put(PORTABLE_PREFIX + slot.name(), portable);
                result.remove(bindingKey);
            }

            result.remove(MISSILE_BINDINGS);
            result.remove("Binding_" + BindingSlot.MISSILE_LAUNCHER.name());
            result.remove(PORTABLE_MISSILE_BINDINGS);
            ListTag portableLaunchers = new ListTag();
            for (BindingRef binding : controller.getMissileLaunchers()) {
                Optional<BlueprintBlockRef> portableRef = context.blockRef(binding.pos());
                if (portableRef.isEmpty()) {
                    continue;
                }
                BlueprintBlockRef ref = portableRef.get();
                CompoundTag portable = new CompoundTag();
                portable.putInt("SubLevelId", ref.subLevelId());
                portable.putLong("LocalPos", ref.localPos().asLong());
                CompoundTag savedBinding = binding.save();
                if (savedBinding.contains("Block")) {
                    portable.putString("Block", savedBinding.getString("Block"));
                }
                portable.putBoolean("HasBlockEntity", savedBinding.getBoolean("HasBlockEntity"));
                portableLaunchers.add(portable);
            }
            if (!portableLaunchers.isEmpty()) {
                result.put(PORTABLE_MISSILE_BINDINGS, portableLaunchers);
            }
            savePortableLauncherList(context, result, CANNON_BINDINGS,
                    PORTABLE_CANNON_BINDINGS, controller.getCannonMountBindings());
            savePortableLauncherList(context, result, AA_MISSILE_BINDINGS,
                    PORTABLE_AA_MISSILE_BINDINGS, controller.getAaMissileLaunchers());
            savePortableLauncherList(context, result, AIRCRAFT_RACK_BINDINGS,
                    PORTABLE_AIRCRAFT_RACK_BINDINGS, controller.getAircraftMissileRacks());
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            for (BindingSlot slot : BindingSlot.values()) {
                if (slot == BindingSlot.MISSILE_LAUNCHER
                        || slot == BindingSlot.AA_MISSILE_LAUNCHER
                        || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                        || slot == BindingSlot.CANNON_MOUNT) {
                    continue;
                }
                String portableKey = PORTABLE_PREFIX + slot.name();
                String bindingKey = "Binding_" + slot.name();
                tag.remove(bindingKey);
                if (!tag.contains(portableKey)) {
                    continue;
                }

                CompoundTag portable = tag.getCompound(portableKey);
                int subLevelId = portable.getInt("SubLevelId");
                BlueprintBlockRef ref = new BlueprintBlockRef(
                        subLevelId,
                        BlockPos.of(portable.getLong("LocalPos"))
                );
                BlockPos mappedPos = context.mapBlock(ref);
                if (mappedPos == null) {
                    continue;
                }

                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mappedPos.asLong());
                if (portable.contains("Block")) {
                    binding.putString("Block", portable.getString("Block"));
                }
                binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
                ServerSubLevel placedSubLevel = context.session().placedSubLevel(subLevelId);
                if (placedSubLevel != null) {
                    binding.putUUID("SubLevel", placedSubLevel.getUniqueId());
                }
                tag.put(bindingKey, binding);
                tag.remove(portableKey);
            }

            tag.remove(MISSILE_BINDINGS);
            tag.remove("Binding_" + BindingSlot.MISSILE_LAUNCHER.name());
            ListTag mappedLaunchers = new ListTag();
            ListTag portableLaunchers = tag.getList(PORTABLE_MISSILE_BINDINGS, Tag.TAG_COMPOUND);
            for (int index = 0; index < portableLaunchers.size(); index++) {
                CompoundTag portable = portableLaunchers.getCompound(index);
                int subLevelId = portable.getInt("SubLevelId");
                BlueprintBlockRef ref = new BlueprintBlockRef(
                        subLevelId,
                        BlockPos.of(portable.getLong("LocalPos"))
                );
                BlockPos mappedPos = context.mapBlock(ref);
                if (mappedPos == null) {
                    continue;
                }
                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mappedPos.asLong());
                if (portable.contains("Block")) {
                    binding.putString("Block", portable.getString("Block"));
                }
                binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
                ServerSubLevel placedSubLevel = context.session().placedSubLevel(subLevelId);
                if (placedSubLevel != null) {
                    binding.putUUID("SubLevel", placedSubLevel.getUniqueId());
                }
                mappedLaunchers.add(binding);
            }
            if (!mappedLaunchers.isEmpty()) {
                tag.put(MISSILE_BINDINGS, mappedLaunchers);
            }
            tag.remove(PORTABLE_MISSILE_BINDINGS);
            loadPortableLauncherList(context, tag, AA_MISSILE_BINDINGS,
                    PORTABLE_AA_MISSILE_BINDINGS);
            loadPortableLauncherList(context, tag, AIRCRAFT_RACK_BINDINGS,
                    PORTABLE_AIRCRAFT_RACK_BINDINGS);
            loadPortableLauncherList(context, tag, CANNON_BINDINGS,
                    PORTABLE_CANNON_BINDINGS);
        }

        private static void savePortableLauncherList(
                BlueprintBlockSaveContext context,
                CompoundTag result,
                String bindingKey,
                String portableKey,
                List<BindingRef> launchers
        ) {
            result.remove(bindingKey);
            result.remove(portableKey);
            ListTag portable = new ListTag();
            for (BindingRef binding : launchers) {
                Optional<BlueprintBlockRef> portableRef = context.blockRef(binding.pos());
                if (portableRef.isEmpty()) continue;
                BlueprintBlockRef ref = portableRef.get();
                CompoundTag entry = new CompoundTag();
                entry.putInt("SubLevelId", ref.subLevelId());
                entry.putLong("LocalPos", ref.localPos().asLong());
                CompoundTag saved = binding.save();
                if (saved.contains("Block")) entry.putString("Block", saved.getString("Block"));
                entry.putBoolean("HasBlockEntity", saved.getBoolean("HasBlockEntity"));
                portable.add(entry);
            }
            if (!portable.isEmpty()) result.put(portableKey, portable);
        }

        private static void loadPortableLauncherList(
                BlueprintBlockPlaceContext context,
                CompoundTag tag,
                String bindingKey,
                String portableKey
        ) {
            tag.remove(bindingKey);
            ListTag mapped = new ListTag();
            ListTag portable = tag.getList(portableKey, Tag.TAG_COMPOUND);
            for (int index = 0; index < portable.size(); index++) {
                CompoundTag entry = portable.getCompound(index);
                int subLevelId = entry.getInt("SubLevelId");
                BlockPos mappedPos = context.mapBlock(new BlueprintBlockRef(
                        subLevelId, BlockPos.of(entry.getLong("LocalPos"))));
                if (mappedPos == null) continue;
                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mappedPos.asLong());
                if (entry.contains("Block")) binding.putString("Block", entry.getString("Block"));
                binding.putBoolean("HasBlockEntity", entry.getBoolean("HasBlockEntity"));
                ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
                if (placed != null) binding.putUUID("SubLevel", placed.getUniqueId());
                mapped.add(binding);
            }
            if (!mapped.isEmpty()) tag.put(bindingKey, mapped);
            tag.remove(portableKey);
        }
    }

    private FireControlBlueprintCompat() {
    }
}
