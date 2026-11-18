package com.hooya.stabilizedturret.compat.schematic;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.loader.CannonAutoLoader;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEvent;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

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
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.CONNECTED_DISPLAY.get(),
                new DisplayMapper()
        );
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.MZ_LOADER.get(),
                new LoaderMapper()
        );
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.TYPE_10_LOADER.get(),
                new LoaderMapper()
        );
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.AZ_LOADER.get(),
                new LoaderMapper()
        );
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.GAS_TURBINE.get(),
                new TurbineMapper()
        );
        SableBlueprintMapperRegistry.register(
                ModBlockEntities.ZTZ99A_DIESEL_ENGINE.get(),
                new TurbineMapper()
        );
        SableBlueprintMapperRegistry.register(ModBlockEntities.TIGER_I_ENGINE.get(),new TurbineMapper());
        SableBlueprintMapperRegistry.register(ModBlockEntities.WIRELESS_SIGNAL_PROCESSOR.get(),new ProcessorMapper());
        SableBlueprintMapperRegistry.register(ModBlockEntities.WIRELESS_LECTERN_CONTROLLER.get(),new LecternMapper());
        SableBlueprintEventRegistry.register(new ControllerConnectionBlueprintEvent());
        SableBlueprintEventRegistry.register(new PlacementStabilityEvent());
        ResourceLocation swivelBearingId = ResourceLocation.fromNamespaceAndPath(
                "simulated", "swivel_bearing");
        BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(swivelBearingId)
                .ifPresent(type -> {
                    // Photomancy already installs a mapper for simulated
                    // swivel bearings. Wrap it instead of replacing it: its
                    // save/after-load hooks rebuild the bearing's physical
                    // plate connection.
                    SableBlueprintBlockMapper existing =
                            SableBlueprintMapperRegistry.get(type);
                    SableBlueprintMapperRegistry.register(
                            type, new SwivelBearingMapper(existing));
                });
        registered = true;
    }

    /** A placed terminal gets an identity distinct from the source blueprint. */
    private static final class ProcessorMapper implements SableBlueprintBlockMapper {
        private static final String WIRES = "create_fire_control:portable_processor_wires";
        private record WireKey(long pos, int side, String channel) {}

        @Override public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            CompoundTag result = original.copy();
            result.remove(WIRES);
            result.remove(com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity.PORTABLE_WIRES);
            ListTag wires = com.hooya.stabilizedturret.content.remote.ControllerWireBridge.snapshot(
                    context.session().level(), context.blockEntity().getBlockPos());
            ListTag pending = original.getList(
                    com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity.PORTABLE_WIRES,
                    Tag.TAG_COMPOUND);
            // A second export can happen while only some endpoints have loaded.
            // Preserve unresolved endpoints alongside the already restored graph.
            for (int index = 0; index < pending.size(); index++) wires.add(pending.getCompound(index).copy());
            ListTag portable = new ListTag();
            Set<WireKey> saved = new HashSet<>();
            for (int index = 0; index < wires.size(); index++) {
                CompoundTag entry = wires.getCompound(index).copy();
                CompoundTag sink = entry.getCompound("Sink");
                if (!sink.contains("Pos", Tag.TAG_LONG) || !entry.contains("Channel", Tag.TAG_STRING)
                        || !entry.contains("Side", Tag.TAG_ANY_NUMERIC) || entry.getInt("Side") < 0
                        || entry.getInt("Side") > 5 || entry.getString("Channel").isEmpty()) continue;
                if (!saved.add(new WireKey(sink.getLong("Pos"), entry.getInt("Side"), entry.getString("Channel")))) continue;
                var ref = context.blockRef(BlockPos.of(sink.getLong("Pos")));
                if (ref.isEmpty()) continue;
                sink.remove("Pos"); sink.remove("SubLevel"); sink.remove("Dimension");
                sink.putInt("SubLevelId", ref.get().subLevelId());
                sink.putLong("LocalPos", ref.get().localPos().asLong());
                portable.add(entry);
            }
            if (!portable.isEmpty()) result.put(WIRES, portable);
            return result;
        }

        @Override public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context,CompoundTag tag) {
            if(tag.hasUUID("ProcessorIdentity"))tag.putUUID("ProcessorIdentity",context.allocateMappedUuid(tag.getUUID("ProcessorIdentity")));
            String pendingKey = com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity.PORTABLE_WIRES;
            tag.remove(pendingKey);
            ListTag portable = tag.getList(WIRES, Tag.TAG_COMPOUND), wires = new ListTag();
            for (int index = 0; index < portable.size(); index++) {
                CompoundTag entry = portable.getCompound(index).copy(), sink = entry.getCompound("Sink");
                if (!sink.contains("SubLevelId", Tag.TAG_ANY_NUMERIC) || !sink.contains("LocalPos", Tag.TAG_LONG)) continue;
                int subLevelId = sink.getInt("SubLevelId");
                BlockPos mapped = context.mapBlock(new BlueprintBlockRef(subLevelId,
                        BlockPos.of(sink.getLong("LocalPos"))));
                if (mapped == null) continue;
                sink.remove("SubLevelId"); sink.remove("LocalPos");
                sink.putLong("Pos", mapped.asLong());
                var body = context.session().placedSubLevel(subLevelId);
                if (body != null) sink.putUUID("SubLevel", body.getUniqueId());
                remapProcessorIdentity(context, sink, sink);
                wires.add(entry);
            }
            if (!wires.isEmpty()) tag.put(pendingKey, wires);
            tag.remove(WIRES);
        }
    }
    /** The mounted controller remains an item, with its pairing remapped alongside the terminal. */
    private static final class LecternMapper implements SableBlueprintBlockMapper {
        private static final String PORTABLE="create_fire_control:portable_lectern_hub";
        private static CompoundTag settings(CompoundTag tag) {
            return tag.getCompound("Controller").getCompound("components").getCompound("minecraft:custom_data")
                    .getCompound(com.hooya.stabilizedturret.content.remote.ControllerSettings.ROOT);
        }
        @Override public CompoundTag save(BlueprintBlockSaveContext context,CompoundTag original) {
            CompoundTag result=original.copy();result.remove(PORTABLE);
            CompoundTag data=settings(result);
            if(!data.contains("Hub"))return result;
            BindingRef hub=BindingRef.loadTag(data.getCompound("Hub"));data.remove("Hub");
            var ref=context.blockRef(hub.pos());
            if(ref.isEmpty()){data.remove("Processor");return result;}
            CompoundTag portable=hub.save();portable.remove("Pos");portable.remove("SubLevel");portable.remove("Dimension");
            portable.putInt("SubLevelId",ref.get().subLevelId());portable.putLong("LocalPos",ref.get().localPos().asLong());
            result.put(PORTABLE,portable);return result;
        }
        @Override public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context,CompoundTag tag) {
            CompoundTag data=settings(tag);
            if(data.hasUUID("Id"))data.putUUID("Id",context.allocateMappedUuid(data.getUUID("Id")));
            data.remove("Hub");
            if(!tag.contains(PORTABLE)){data.remove("Processor");return;}
            CompoundTag portable=tag.getCompound(PORTABLE);int subLevelId=portable.getInt("SubLevelId");
            BlockPos pos=context.mapBlock(new BlueprintBlockRef(subLevelId,BlockPos.of(portable.getLong("LocalPos"))));
            if(pos!=null) {
                CompoundTag hub=portable.copy();hub.remove("SubLevelId");hub.remove("LocalPos");hub.putLong("Pos",pos.asLong());
                remapProcessorIdentity(context,portable,hub);
                var placed=context.session().placedSubLevel(subLevelId);
                if(placed!=null)hub.putUUID("SubLevel",placed.getUniqueId());
                data.put("Hub",hub);
                if(data.hasUUID("Processor"))data.putUUID("Processor",context.allocateMappedUuid(data.getUUID("Processor")));
            } else data.remove("Processor");
            tag.remove(PORTABLE);
        }
    }
    /** Rewrites both supported engine terminal types through the blueprint map. */
    private static final class TurbineMapper implements SableBlueprintBlockMapper {
        private static final String BINDING = "AdvancedHub";
        private static final String PORTABLE =
                "create_fire_control:portable_gas_turbine_advanced_hub";

        @Override
        public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            if (!(context.blockEntity() instanceof GasTurbineBlockEntity turbine)) {
                return original;
            }
            CompoundTag result = original.copy();
            result.remove(PORTABLE);
            BindingRef binding = turbine.getAdvancedHub();
            result.remove(BINDING);
            if (binding == null) return result;

            Optional<BlueprintBlockRef> portableRef = context.blockRef(binding.pos());
            if (portableRef.isEmpty()) {
                // Never leave an absolute coordinate from the source world in
                // a reusable blueprint when the terminal was not saved with it.
                return result;
            }

            BlueprintBlockRef ref = portableRef.get();
            CompoundTag portable = new CompoundTag();
            portable.putInt("SubLevelId", ref.subLevelId());
            portable.putLong("LocalPos", ref.localPos().asLong());
            CompoundTag saved = binding.save();
            copyProcessorIdentity(saved, portable);
            if (saved.contains("Block")) {
                portable.putString("Block", saved.getString("Block"));
            }
            portable.putBoolean("HasBlockEntity", saved.getBoolean("HasBlockEntity"));
            result.put(PORTABLE, portable);
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            tag.remove(BINDING);
            if (!tag.contains(PORTABLE, Tag.TAG_COMPOUND)) return;

            CompoundTag portable = tag.getCompound(PORTABLE);
            int subLevelId = portable.getInt("SubLevelId");
            BlockPos mappedPos = context.mapBlock(new BlueprintBlockRef(
                    subLevelId, BlockPos.of(portable.getLong("LocalPos"))));
            if (mappedPos != null) {
                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mappedPos.asLong());
                if (portable.contains("Block")) {
                    binding.putString("Block", portable.getString("Block"));
                }
                binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
                remapProcessorIdentity(context, portable, binding);
                ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
                if (placed != null) binding.putUUID("SubLevel", placed.getUniqueId());
                tag.put(BINDING, binding);
            }
            tag.remove(PORTABLE);
        }
    }

    /** Prevents runtime physics helpers from targeting placement-temporary bodies. */
    private static final class PlacementStabilityEvent implements SableBlueprintEvent {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
                "create_fire_control", "placement_stability");
        private final Map<BlueprintSaveSession, List<BearingStabilizer.SuspendedNoContact>>
                suspendedBySession = new IdentityHashMap<>();

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void onSaveBeforeBlocks(
                BlueprintSaveSession session,
                CompoundTag extraData
        ) {
            List<BearingStabilizer.SuspendedNoContact> suspended =
                    BearingStabilizer.suspendOwnedNoContactsForBlueprint(
                            session.level());
            if (!suspended.isEmpty()) suspendedBySession.put(session, suspended);
        }

        @Override
        public void onSaveAfterEntities(
                BlueprintSaveSession session,
                CompoundTag extraData
        ) {
            List<BearingStabilizer.SuspendedNoContact> suspended =
                    suspendedBySession.remove(session);
            BearingStabilizer.resumeOwnedNoContactsAfterBlueprint(
                    session.level(), suspended);
        }

        @Override
        public void onPlaceAfterBlocks(
                BlueprintPlaceSession session,
                CompoundTag extraData
        ) {
            BearingStabilizer.refreshNoContactsNow(session.level());
        }
    }

    /**
     * Assembly pivots are cached in persistent block-entity data, but they are
     * local to the original Sable sub-level. Keeping them in a portable
     * blueprint makes a bearing reuse the source world's absolute pivot and
     * can trip Sable's extreme-coordinate removal guard after placement.
     */
    private static final class SwivelBearingMapper implements SableBlueprintBlockMapper {
        private static final String PIVOT_X = "CreateFireControlAssemblyPivotX";
        private static final String PIVOT_Y = "CreateFireControlAssemblyPivotY";
        private static final String PIVOT_Z = "CreateFireControlAssemblyPivotZ";
        private final SableBlueprintBlockMapper delegate;

        private SwivelBearingMapper(SableBlueprintBlockMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            CompoundTag result = delegate == null
                    ? original.copy()
                    : delegate.save(context, original);
            if (result == null) result = original.copy();
            else result = result.copy();
            removePivotData(result);
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            if (delegate != null) {
                delegate.beforeLoadBlockEntity(context, tag);
            }
            // Also sanitize blueprints created before this compatibility fix.
            removePivotData(tag);
        }

        @Override
        public void afterLoadBlockEntity(
                BlueprintBlockPlaceContext context,
                net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                CompoundTag tag
        ) {
            if (delegate != null) {
                delegate.afterLoadBlockEntity(context, blockEntity, tag);
            }
        }

        private static void removePivotData(CompoundTag tag) {
            removePivotFields(tag);
            // NeoForge serializes BlockEntity#getPersistentData() below this
            // wrapper, which is where these values are actually stored.
            if (tag.contains("NeoForgeData", Tag.TAG_COMPOUND)) {
                removePivotFields(tag.getCompound("NeoForgeData"));
            }
        }

        private static void removePivotFields(CompoundTag tag) {
            tag.remove(PIVOT_X);
            tag.remove(PIVOT_Y);
            tag.remove(PIVOT_Z);
        }
    }

    /** Rewrites the loader's absolute plot binding through Photomancy's block map. */
    private static final class LoaderMapper implements SableBlueprintBlockMapper {
        private static final String BINDING = "CannonMountBinding";
        private static final String PORTABLE =
                "stabilized_turret:portable_loader_cannon_mount";

        @Override
        public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            if (!(context.blockEntity() instanceof CannonAutoLoader loader)) {
                return original;
            }
            CompoundTag result = original.copy();
            result.remove(PORTABLE);
            // A blueprint keeps its rack contents and wheel position, but not
            // an in-flight cycle tied to an entity UUID and absolute game time.
            result.remove("PlayUntilTick");
            result.remove("ConsumePending");
            result.remove("AutomaticOpenPending");
            result.remove("AutomaticOpenAtTick");
            result.remove("AutomaticLoadPending");
            result.remove("AutomaticLoadAtTick");
            result.remove("AutomaticClosePending");
            result.remove("AutomaticCloseAtTick");
            result.remove("AutomaticCannon");
            result.remove("AutomaticBreechOpenedByLoader");
            BindingRef binding = loader.getCannonMountBinding();
            if (binding == null) {
                result.remove(BINDING);
                return result;
            }
            Optional<BlueprintBlockRef> mapped = context.blockRef(binding.pos());
            result.remove(BINDING);
            if (mapped.isEmpty()) return result;
            CompoundTag portable = new CompoundTag();
            portable.putInt("SubLevelId", mapped.get().subLevelId());
            portable.putLong("LocalPos", mapped.get().localPos().asLong());
            CompoundTag saved = binding.save();
            if (saved.contains("Block")) portable.putString("Block", saved.getString("Block"));
            portable.putBoolean("HasBlockEntity", saved.getBoolean("HasBlockEntity"));
            result.put(PORTABLE, portable);
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            tag.remove(BINDING);
            if (!tag.contains(PORTABLE)) return;
            CompoundTag portable = tag.getCompound(PORTABLE);
            int subLevelId = portable.getInt("SubLevelId");
            BlockPos mapped = context.mapBlock(new BlueprintBlockRef(
                    subLevelId, BlockPos.of(portable.getLong("LocalPos"))));
            if (mapped != null) {
                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mapped.asLong());
                if (portable.contains("Block")) {
                    binding.putString("Block", portable.getString("Block"));
                }
                binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
                ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
                if (placed != null) binding.putUUID("SubLevel", placed.getUniqueId());
                tag.put(BINDING, binding);
            }
            tag.remove(PORTABLE);
        }
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
        private static final String COUNTERMEASURE_BINDINGS = "CountermeasureLauncherBindings";
        private static final String PORTABLE_COUNTERMEASURE_BINDINGS =
                "stabilized_turret:portable_countermeasure_launchers";
        private static final String YAW_BEARING_BINDINGS = "YawBearingBindings";
        private static final String PITCH_BEARING_BINDINGS = "PitchBearingBindings";
        private static final String PORTABLE_YAW_BEARING_BINDINGS =
                "stabilized_turret:portable_yaw_bearings";
        private static final String PORTABLE_PITCH_BEARING_BINDINGS =
                "stabilized_turret:portable_pitch_bearings";
        private static final String REMOTE_WEAPON_STATION_BINDINGS =
                "RemoteWeaponStationBindings";
        private static final String PORTABLE_REMOTE_WEAPON_STATION_BINDINGS =
                "stabilized_turret:portable_remote_weapon_stations";
        private static final String CANNON_TURRET_POD_BINDINGS =
                "CannonTurretPodBindings";
        private static final String PORTABLE_CANNON_TURRET_POD_BINDINGS =
                "stabilized_turret:portable_cannon_turret_pods";
        private static final String IFF_TRANSPONDER_BINDINGS = "IffTransponderBindings";
        private static final String PORTABLE_IFF_TRANSPONDER_BINDINGS =
                "stabilized_turret:portable_iff_transponders";

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
                        || slot == BindingSlot.COUNTERMEASURE_LAUNCHER
                        || slot == BindingSlot.CANNON_MOUNT
                        || slot == BindingSlot.REMOTE_WEAPON_STATION
                        || slot == BindingSlot.CANNON_TURRET_POD
                        || slot == BindingSlot.IFF_TRANSPONDER
                        || slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
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
                    CompoundTag savedBinding = binding.save();
                    copyProcessorIdentity(savedBinding, portable);
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
            savePortableLauncherList(context, result, YAW_BEARING_BINDINGS,
                    PORTABLE_YAW_BEARING_BINDINGS, controller.getYawBearingBindings());
            savePortableLauncherList(context, result, PITCH_BEARING_BINDINGS,
                    PORTABLE_PITCH_BEARING_BINDINGS, controller.getPitchBearingBindings());
            savePortableBearingSettings(context, result);
            savePortableLauncherList(context, result, AA_MISSILE_BINDINGS,
                    PORTABLE_AA_MISSILE_BINDINGS, controller.getAaMissileLaunchers());
            savePortableLauncherList(context, result, AIRCRAFT_RACK_BINDINGS,
                    PORTABLE_AIRCRAFT_RACK_BINDINGS, controller.getAircraftMissileRacks());
            result.remove("Binding_" + BindingSlot.COUNTERMEASURE_LAUNCHER.name());
            savePortableLauncherList(context, result, COUNTERMEASURE_BINDINGS,
                    PORTABLE_COUNTERMEASURE_BINDINGS,
                    controller.getCountermeasureLaunchers());
            savePortableLauncherList(context, result, REMOTE_WEAPON_STATION_BINDINGS,
                    PORTABLE_REMOTE_WEAPON_STATION_BINDINGS,
                    controller.getRemoteWeaponStations());
            savePortableLauncherList(context, result, CANNON_TURRET_POD_BINDINGS,
                    PORTABLE_CANNON_TURRET_POD_BINDINGS,
                    controller.getCannonTurretPods());
            savePortableLauncherList(context, result, IFF_TRANSPONDER_BINDINGS,
                    PORTABLE_IFF_TRANSPONDER_BINDINGS,
                    controller.getIffTransponderBindings());
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            loadLegacyBearingBinding(context, tag, BindingSlot.YAW,
                    PORTABLE_YAW_BEARING_BINDINGS);
            loadLegacyBearingBinding(context, tag, BindingSlot.PITCH,
                    PORTABLE_PITCH_BEARING_BINDINGS);
            for (BindingSlot slot : BindingSlot.values()) {
                if (slot == BindingSlot.MISSILE_LAUNCHER
                        || slot == BindingSlot.AA_MISSILE_LAUNCHER
                        || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                        || slot == BindingSlot.COUNTERMEASURE_LAUNCHER
                        || slot == BindingSlot.CANNON_MOUNT
                        || slot == BindingSlot.REMOTE_WEAPON_STATION
                        || slot == BindingSlot.CANNON_TURRET_POD
                        || slot == BindingSlot.IFF_TRANSPONDER
                        || slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
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
                remapProcessorIdentity(context, portable, binding);
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
            loadPortableLauncherList(context, tag, YAW_BEARING_BINDINGS,
                    PORTABLE_YAW_BEARING_BINDINGS);
            loadPortableLauncherList(context, tag, PITCH_BEARING_BINDINGS,
                    PORTABLE_PITCH_BEARING_BINDINGS);
            loadPortableBearingSettings(context, tag);
            tag.remove("Binding_" + BindingSlot.COUNTERMEASURE_LAUNCHER.name());
            loadPortableLauncherList(context, tag, COUNTERMEASURE_BINDINGS,
                    PORTABLE_COUNTERMEASURE_BINDINGS);
            loadPortableLauncherList(context, tag, REMOTE_WEAPON_STATION_BINDINGS,
                    PORTABLE_REMOTE_WEAPON_STATION_BINDINGS);
            loadPortableLauncherList(context, tag, CANNON_TURRET_POD_BINDINGS,
                    PORTABLE_CANNON_TURRET_POD_BINDINGS);
            loadPortableLauncherList(context, tag, IFF_TRANSPONDER_BINDINGS,
                    PORTABLE_IFF_TRANSPONDER_BINDINGS);
        }

        /** Loads the single-bearing format written by earlier blueprint versions. */
        private static void loadLegacyBearingBinding(
                BlueprintBlockPlaceContext context,
                CompoundTag tag,
                BindingSlot slot,
                String listPortableKey
        ) {
            String bindingKey = "Binding_" + slot.name();
            String legacyPortableKey = PORTABLE_PREFIX + slot.name();
            tag.remove(bindingKey);

            // New blueprints use a list so a controller can bind multiple
            // bearings per axis. Prefer that data whenever it is present.
            if (tag.contains(listPortableKey, Tag.TAG_LIST)) {
                tag.remove(legacyPortableKey);
                return;
            }
            if (!tag.contains(legacyPortableKey, Tag.TAG_COMPOUND)) return;

            CompoundTag portable = tag.getCompound(legacyPortableKey);
            int subLevelId = portable.getInt("SubLevelId");
            BlockPos mappedPos = context.mapBlock(new BlueprintBlockRef(
                    subLevelId, BlockPos.of(portable.getLong("LocalPos"))));
            if (mappedPos != null) {
                CompoundTag binding = new CompoundTag();
                binding.putLong("Pos", mappedPos.asLong());
                if (portable.contains("Block")) {
                    binding.putString("Block", portable.getString("Block"));
                }
                binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
                ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
                if (placed != null) binding.putUUID("SubLevel", placed.getUniqueId());
                tag.put(bindingKey, binding);
            }
            tag.remove(legacyPortableKey);
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

        private static void savePortableBearingSettings(
                BlueprintBlockSaveContext context,
                CompoundTag tag
        ) {
            BearingSettingsBlueprintCodec.save(tag, pos -> context.blockRef(pos)
                    .map(ref -> new BearingSettingsBlueprintCodec.PortableRef(
                            ref.subLevelId(), ref.localPos()))
                    .orElse(null));
        }

        private static void loadPortableBearingSettings(
                BlueprintBlockPlaceContext context,
                CompoundTag tag
        ) {
            BearingSettingsBlueprintCodec.load(tag, ref -> {
                BlockPos mappedPos = context.mapBlock(new BlueprintBlockRef(
                        ref.subLevelId(), ref.localPos()));
                if (mappedPos == null) return null;
                ServerSubLevel placed = context.session().placedSubLevel(ref.subLevelId());
                return new BearingSettingsBlueprintCodec.PlacedRef(
                        mappedPos, placed == null ? null : placed.getUniqueId());
            });
        }
    }

    private static final class DisplayMapper implements SableBlueprintBlockMapper {
        // This is a serialized blueprint key, not a registry id. Keep the
        // legacy value so blueprints saved before the mod-id rename load
        // without a data upgrade.
        private static final String PREFIX = "stabilized_turret:portable_display_binding_";
        private static final String CANNON = "CannonMounts";
        private static final String YAW = "YawBearings";
        private static final String PITCH = "PitchBearings";
        private static final String AA = "AaLaunchers";
        private static final String CRUISE = "CruiseLaunchers";
        private static final String BALLISTIC = "BallisticLaunchers";
        private static final String IFF = "IffTransponders";
        private static final String PORTABLE_CANNON = PREFIX + "cannon_mounts";
        private static final String PORTABLE_YAW = PREFIX + "yaw_bearings";
        private static final String PORTABLE_PITCH = PREFIX + "pitch_bearings";
        private static final String PORTABLE_AA = PREFIX + "aa_launchers";
        private static final String PORTABLE_CRUISE = PREFIX + "cruise_launchers";
        private static final String PORTABLE_BALLISTIC = PREFIX + "ballistic_launchers";
        private static final String PORTABLE_IFF = PREFIX + "iff_transponders";
        private static final String SETTINGS = "PreviewBearingSettings";
        private static final String PORTABLE_SETTINGS = PREFIX + "bearing_settings";
        private static final String NEUTRAL_ANGLES = "BearingNeutralAngles";
        private static final String PORTABLE_NEUTRAL_ANGLES = PREFIX + "bearing_neutral_angles";

        @Override
        public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag original) {
            if (!(context.blockEntity() instanceof ConnectedDisplayBlockEntity display)) {
                return original;
            }
            CompoundTag result = original.copy();
            for (BindingSlot slot : List.of(BindingSlot.RADAR_BEARING)) {
                String absolute = "Binding_" + slot.name();
                result.remove(PREFIX + slot.name());
                result.remove(absolute);
                ConnectedDisplayBlockEntity.Device device = display.getDevices().stream()
                        .filter(value -> value.slot() == slot)
                        .findFirst().orElse(null);
                if (device != null) {
                    savePortableRef(context, result, PREFIX + slot.name(), device.ref());
                }
            }
            result.remove("Binding_" + BindingSlot.YAW.name());
            result.remove("Binding_" + BindingSlot.PITCH.name());
            result.remove(PREFIX + BindingSlot.YAW.name());
            result.remove(PREFIX + BindingSlot.PITCH.name());
            savePortableList(context, result, YAW, PORTABLE_YAW, display,
                    BindingSlot.YAW);
            savePortableList(context, result, PITCH, PORTABLE_PITCH, display,
                    BindingSlot.PITCH);
            savePortableList(context, result, CANNON, PORTABLE_CANNON, display,
                    BindingSlot.CANNON_MOUNT);
            savePortableList(context, result, AA, PORTABLE_AA, display,
                    BindingSlot.AA_MISSILE_LAUNCHER);
            savePortableList(context, result, CRUISE, PORTABLE_CRUISE, display,
                    BindingSlot.CRUISE_MISSILE_LAUNCHER);
            savePortableList(context, result, BALLISTIC, PORTABLE_BALLISTIC, display,
                    BindingSlot.BALLISTIC_MISSILE_LAUNCHER);
            savePortableList(context, result, IFF, PORTABLE_IFF, display,
                    BindingSlot.IFF_TRANSPONDER);
            BearingSettingsBlueprintCodec.save(result, SETTINGS, PORTABLE_SETTINGS, pos -> context.blockRef(pos)
                    .map(ref -> new BearingSettingsBlueprintCodec.PortableRef(ref.subLevelId(), ref.localPos())).orElse(null));
            BearingSettingsBlueprintCodec.save(result, NEUTRAL_ANGLES, PORTABLE_NEUTRAL_ANGLES, pos -> context.blockRef(pos)
                    .map(ref -> new BearingSettingsBlueprintCodec.PortableRef(ref.subLevelId(), ref.localPos())).orElse(null));

            // Radar contacts and in-flight telemetry are runtime state, not
            // part of a reusable Photomancy blueprint.
            result.remove("Contacts");
            result.remove("CruiseTelemetry");
            result.remove("SelectedTarget");
            result.remove("ManualAimX");
            result.remove("ManualAimY");
            result.remove("ManualAimZ");
            return result;
        }

        @Override
        public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
            for (BindingSlot slot : List.of(BindingSlot.RADAR_BEARING)) {
                String absolute = "Binding_" + slot.name();
                String portable = PREFIX + slot.name();
                tag.remove(absolute);
                if (tag.contains(portable)) {
                    CompoundTag mapped = mapPortableRef(context,
                            tag.getCompound(portable));
                    if (mapped != null) tag.put(absolute, mapped);
                }
                tag.remove(portable);
            }
            tag.remove("Binding_" + BindingSlot.YAW.name());
            tag.remove("Binding_" + BindingSlot.PITCH.name());
            tag.remove(PREFIX + BindingSlot.YAW.name());
            tag.remove(PREFIX + BindingSlot.PITCH.name());
            loadPortableList(context, tag, YAW, PORTABLE_YAW);
            loadPortableList(context, tag, PITCH, PORTABLE_PITCH);
            loadPortableList(context, tag, CANNON, PORTABLE_CANNON);
            loadPortableList(context, tag, AA, PORTABLE_AA);
            loadPortableList(context, tag, CRUISE, PORTABLE_CRUISE);
            loadPortableList(context, tag, BALLISTIC, PORTABLE_BALLISTIC);
            loadPortableList(context, tag, IFF, PORTABLE_IFF);
            BearingSettingsBlueprintCodec.load(tag, SETTINGS, PORTABLE_SETTINGS, ref -> {
                BlockPos pos = context.mapBlock(new BlueprintBlockRef(ref.subLevelId(), ref.localPos()));
                if (pos == null) return null;
                ServerSubLevel placed = context.session().placedSubLevel(ref.subLevelId());
                return new BearingSettingsBlueprintCodec.PlacedRef(pos, placed == null ? null : placed.getUniqueId());
            });
            BearingSettingsBlueprintCodec.load(tag, NEUTRAL_ANGLES, PORTABLE_NEUTRAL_ANGLES, ref -> {
                BlockPos pos = context.mapBlock(new BlueprintBlockRef(ref.subLevelId(), ref.localPos()));
                if (pos == null) return null;
                ServerSubLevel placed = context.session().placedSubLevel(ref.subLevelId());
                return new BearingSettingsBlueprintCodec.PlacedRef(pos, placed == null ? null : placed.getUniqueId());
            });
        }

        private static void savePortableList(
                BlueprintBlockSaveContext context,
                CompoundTag result,
                String absoluteKey,
                String portableKey,
                ConnectedDisplayBlockEntity display,
                BindingSlot slot
        ) {
            result.remove(absoluteKey);
            result.remove(portableKey);
            ListTag portable = new ListTag();
            for (ConnectedDisplayBlockEntity.Device device : display.getDevices()) {
                if (device.slot() != slot) continue;
                CompoundTag entry = portableRef(context, device.ref());
                if (entry != null) portable.add(entry);
            }
            if (!portable.isEmpty()) result.put(portableKey, portable);
        }

        private static void loadPortableList(
                BlueprintBlockPlaceContext context,
                CompoundTag tag,
                String absoluteKey,
                String portableKey
        ) {
            tag.remove(absoluteKey);
            ListTag mapped = new ListTag();
            ListTag portable = tag.getList(portableKey, Tag.TAG_COMPOUND);
            for (int index = 0; index < portable.size(); index++) {
                CompoundTag binding = mapPortableRef(context,
                        portable.getCompound(index));
                if (binding != null) mapped.add(binding);
            }
            if (!mapped.isEmpty()) tag.put(absoluteKey, mapped);
            tag.remove(portableKey);
        }

        private static void savePortableRef(
                BlueprintBlockSaveContext context,
                CompoundTag result,
                String key,
                BindingRef binding
        ) {
            CompoundTag portable = portableRef(context, binding);
            if (portable != null) result.put(key, portable);
        }

        private static CompoundTag portableRef(
                BlueprintBlockSaveContext context, BindingRef binding
        ) {
            Optional<BlueprintBlockRef> ref = context.blockRef(binding.pos());
            if (ref.isEmpty()) return null;
            CompoundTag portable = new CompoundTag();
            portable.putInt("SubLevelId", ref.get().subLevelId());
            portable.putLong("LocalPos", ref.get().localPos().asLong());
            CompoundTag saved = binding.save();
            if (saved.contains("Block")) portable.putString("Block", saved.getString("Block"));
            portable.putBoolean("HasBlockEntity", saved.getBoolean("HasBlockEntity"));
            return portable;
        }

        private static @org.jetbrains.annotations.Nullable CompoundTag mapPortableRef(
                BlueprintBlockPlaceContext context, CompoundTag portable
        ) {
            int subLevelId = portable.getInt("SubLevelId");
            BlueprintBlockRef ref = new BlueprintBlockRef(
                    subLevelId, BlockPos.of(portable.getLong("LocalPos")));
            BlockPos mappedPos = context.mapBlock(ref);
            if (mappedPos == null) return null;
            CompoundTag binding = new CompoundTag();
            binding.putLong("Pos", mappedPos.asLong());
            if (portable.contains("Block")) {
                binding.putString("Block", portable.getString("Block"));
            }
            binding.putBoolean("HasBlockEntity", portable.getBoolean("HasBlockEntity"));
            ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
            if (placed != null) binding.putUUID("SubLevel", placed.getUniqueId());
            return binding;
        }
    }

    private static void copyProcessorIdentity(CompoundTag source, CompoundTag target) {
        String key = BindingRef.WIRELESS_PROCESSOR_ID_TAG;
        if (source.hasUUID(key)) target.putUUID(key, source.getUUID(key));
    }

    private static void remapProcessorIdentity(
            BlueprintBlockPlaceContext context, CompoundTag source, CompoundTag target
    ) {
        String key = BindingRef.WIRELESS_PROCESSOR_ID_TAG;
        if (source.hasUUID(key)) target.putUUID(key, context.allocateMappedUuid(source.getUUID(key)));
    }

    private FireControlBlueprintCompat() {
    }
}
