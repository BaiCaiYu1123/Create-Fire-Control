package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.registry.*;
import com.hooya.stabilizedturret.network.WirelessControllerActionPayload;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.lang.reflect.Field;
import java.util.*;

final class LecternControllerIntegration {
    static void run(ServerLevel level)throws Exception {
        BlockPos pos=new BlockPos(6,240,12);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"LecternCheck"));
        player.setPos(6.5,240,11.5);
        var controller=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();var id=ControllerSettings.ensureId(controller);
        CompoundTag settings=ControllerSettings.data(controller);
        settings.putString("Color","green");
        ControllerSettings.putBindings(settings,List.of(new ControllerSettings.Binding("key_87","Lectern combo",
                new ItemStack(Items.GOLD_NUGGET),ItemStack.EMPTY,true,"mouse_3",6)),level.registryAccess());
        BlockPos hub=pos.east(2);
        if(ControllerWireBridge.available()) {
            level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
            settings.put("Hub",com.hooya.stabilizedturret.content.controller.BindingRef.capture(level,hub).save());
            settings.putUUID("Processor",((WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub)).identity());
        }
        ControllerSettings.write(controller,settings);player.setItemInHand(InteractionHand.MAIN_HAND,controller);
        var vanilla=Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING,Direction.EAST);
        level.setBlockAndUpdate(pos,vanilla.setValue(LecternBlock.HAS_BOOK,true));
        require(!WirelessLecternControllerBlock.placeController(player,InteractionHand.MAIN_HAND,pos),"occupied lectern is preserved");
        level.setBlockAndUpdate(pos,vanilla);
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,pos);
        require(level.getBlockState(pos).is(ModBlocks.WIRELESS_LECTERN_CONTROLLER.get()),"held controller mounts on empty lectern before activation");
        require(player.getMainHandItem().isEmpty(),"survival placement consumes exactly one controller");
        require(level.getBlockState(pos).getValue(WirelessLecternControllerBlock.FACING)==Direction.EAST,"lectern orientation is preserved");
        var mounted=(WirelessLecternControllerBlockEntity)level.getBlockEntity(pos);
        require(ControllerSettings.id(mounted.controller()).equals(id),"mounted item keeps its identity");
        require(ControllerSettings.data(mounted.controller()).equals(settings),"all controller settings survive placement");
        var saved=mounted.saveWithoutMetadata(level.registryAccess());
        var restored=new WirelessLecternControllerBlockEntity(pos,mounted.getBlockState());restored.loadWithComponents(saved,level.registryAccess());
        require(ControllerSettings.data(restored.controller()).equals(settings)&&!restored.inUse(),"world save restores configured controller in idle state");
        checkAnimation(restored);
        if(ControllerWireBridge.available()&&net.neoforged.fml.ModList.get().isLoaded("sable_schematic_api"))BlueprintChecks.run(level,mounted,hub);
        ControllerSessions.useLectern(player,mounted);Object session=session(player);
        require(session!=null&&mounted.inUse(),"empty hand activates the mounted controller");
        var frequency=Couple.create(Frequency.of(new ItemStack(Items.GOLD_NUGGET)),Frequency.EMPTY);
        input(player,session,Set.of("key_87"));require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"mounted combo waits for both keys");
        input(player,session,Set.of("key_87","mouse_3"));require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"mounted combo powers real Create wireless");
        require(mounted.visualPressed().equals(Set.of("key_87","mouse_3")),"mounted keycaps synchronize to observers");
        var other=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"LecternBusy"));other.setPos(6.5,240,11.5);
        ControllerSessions.useLectern(other,mounted);require(session(other)==null,"one mounted controller cannot be used by two players");
        player.setPos(14,240,12);input(player,session,Set.of());
        require(session(player)==null&&!mounted.inUse()&&!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"walking out of advanced-controller range releases every output");
        player.setPos(6.5,240,11.5);ControllerSessions.useLectern(player,mounted);session=session(player);
        input(player,session,Set.of("key_87","mouse_3"));
        player.setShiftKeyDown(true);
        level.getBlockState(pos).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        require(level.getBlockState(pos).is(Blocks.LECTERN),"sneak-use restores the original lectern");
        require(session(player)==null&&!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"retrieving controller stops its session");
        var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1));
        require(drops.stream().filter(e->e.getItem().is(ModItems.WIRELESS_CONTROLLER.get())).count()==1,"retrieving drops one configured controller");
        var recovered=drops.stream().filter(e->e.getItem().is(ModItems.WIRELESS_CONTROLLER.get())).findFirst().orElseThrow().getItem();
        require(ControllerSettings.data(recovered).equals(settings),"retrieved item keeps its channels and two-key binding");
        System.out.println("PASS: lectern placement, survival consumption, orientation, persisted item, stationary combo, exclusivity, range cleanup and retrieval");
    }
    // Optional blueprint verification must not prevent ordinary lectern/session checks from loading.
    private static final class BlueprintChecks {
    private static void run(ServerLevel level,WirelessLecternControllerBlockEntity mounted,BlockPos hub) {
        var save=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession(level,new org.joml.Vector3d(),
                new dev.ryanhcode.sable.companion.math.BoundingBox3d(0,230,0,16,250,16));
        var frame=new dev.rew1nd.sableschematicapi.api.blueprint.SubLevelSaveFrame(1,UUID.randomUUID(),null,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(0,230,0,16,250,16),BlockPos.ZERO,new dev.ryanhcode.sable.companion.math.Pose3d());save.addFrame(frame);
        var context=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext(save,frame,mounted.getBlockPos(),mounted.getBlockPos(),mounted.getBlockState(),mounted);
        var mapper=dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry.get(mounted.getType());
        require(mapper!=null,"mounted controllers register a blueprint mapper");
        CompoundTag tag=mapper.save(context,mounted.saveWithoutMetadata(level.registryAccess()));
        BlockPos offset=new BlockPos(240,0,0);
        var place=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession(level,new org.joml.Vector3d(),new CompoundTag()) {
            @Override public BlockPos mapBlock(dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef ref){return ref.localPos().offset(offset);}
        };
        var placed=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext(place,null,1,frame.sourceUuid(),mounted.getBlockPos(),mounted.getBlockPos().offset(offset),mounted.getBlockState());
        mapper.beforeLoadBlockEntity(placed,tag);
        var copied=new WirelessLecternControllerBlockEntity(mounted.getBlockPos().offset(offset),mounted.getBlockState());copied.loadWithComponents(tag,level.registryAccess());
        CompoundTag original=ControllerSettings.data(mounted.controller()),data=ControllerSettings.data(copied.controller());
        require(!data.getUUID("Id").equals(original.getUUID("Id")),"blueprint controller copy receives a new identity");
        require(com.hooya.stabilizedturret.content.controller.BindingRef.loadTag(data.getCompound("Hub")).pos().equals(hub.offset(offset)),"mounted pairing targets the copied terminal");
        require(data.getList("Bindings",net.minecraft.nbt.Tag.TAG_COMPOUND).equals(original.getList("Bindings",net.minecraft.nbt.Tag.TAG_COMPOUND)),"mounted blueprint keeps the two-key binding and frequencies");
        require(ControllerSettings.color(data).equals("green"),"blueprint keeps the chosen controller palette");
        var processor=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);var processorTag=processor.saveWithoutMetadata(level.registryAccess());
        dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry.get(processor.getType()).beforeLoadBlockEntity(placed,processorTag);
        require(data.getUUID("Processor").equals(processorTag.getUUID("ProcessorIdentity")),"controller and copied terminal use the same remapped UUID");
        require(data.getCompound("Hub").getUUID(com.hooya.stabilizedturret.content.controller.BindingRef.WIRELESS_PROCESSOR_ID_TAG)
                .equals(processorTag.getUUID("ProcessorIdentity")),"mounted binding reference remaps the same terminal identity");
        System.out.println("PASS: mounted-controller blueprint pairing, frequencies, chord and shared terminal UUID mapping");
    }
    }
    @SuppressWarnings("unchecked") private static Object session(ServerPlayer player)throws Exception {
        Field field=ControllerSessions.class.getDeclaredField("SESSIONS");field.setAccessible(true);
        return ((Map<UUID,Object>)field.get(null)).get(player.getUUID());
    }
    private static void checkAnimation(WirelessLecternControllerBlockEntity mounted) {
        UUID user=UUID.randomUUID();
        require(mounted.animationTime(0,1.5)==1.5,"idle lectern starts at the folded final frame");
        mounted.beginUsing(user);require(mounted.animationTime(10,1.5)==1.5,"activation starts unfolding from the idle pose");
        require(mounted.animationTime(20,1.5)==1,"lectern plays the original animation backwards");
        mounted.updateVisualInputs(Set.of("key_87"));
        require(mounted.animationTime(25,1.5)==.75,"key updates keep the opening animation time");
        mounted.endUsing(user);require(mounted.animationTime(25,1.5)==.75,"exit reverses smoothly from an unfinished opening");
        require(mounted.animationTime(35,1.5)==1.25,"lectern exit plays forwards");
        require(mounted.animationTime(55,1.5)==1.5,"lectern exit returns to the final frame");
        mounted.beginUsing(user);mounted.animationTime(60,1.5);
        require(mounted.animationTime(95,1.5)==0,"fully opened lectern reaches the independent keycap pose");
        mounted.endUsing(user);
        System.out.println("PASS: lectern opening, input updates, interrupted transition and closing animation");
    }
    private static void input(ServerPlayer player,Object session,Set<String> keys)throws Exception {
        Field nonce=session.getClass().getDeclaredField("nonce");nonce.setAccessible(true);
        CompoundTag data=new CompoundTag();data.putUUID("Session",(UUID)nonce.get(session));
        data.put("Pressed",ControllerSessions.strings(keys));data.put("VisualPressed",ControllerSessions.strings(keys));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT,data));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
