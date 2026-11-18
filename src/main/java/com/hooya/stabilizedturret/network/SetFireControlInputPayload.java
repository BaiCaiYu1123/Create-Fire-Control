package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.remote.ControllerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** kind -1 edits both frequency items; 0/1/2 map normal/advanced/wireless terminals. */
public record SetFireControlInputPayload(BlockPos pos, int action, int kind, String key,
                                         ItemStack first, ItemStack second) implements CustomPacketPayload {
    public static final Type<SetFireControlInputPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"set_fire_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SetFireControlInputPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBlockPos(p.pos);b.writeVarInt(p.action);b.writeVarInt(p.kind);b.writeUtf(p.key,64);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b,p.first);ItemStack.OPTIONAL_STREAM_CODEC.encode(b,p.second);},
        b->new SetFireControlInputPayload(b.readBlockPos(),b.readVarInt(),b.readVarInt(),b.readUtf(64),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(b),ItemStack.OPTIONAL_STREAM_CODEC.decode(b)));
    public static void handle(SetFireControlInputPayload p,IPayloadContext context) {
        context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player)||!C2SWriteAuthorization.canEditNearby(player,p.pos)
                    ||!(player.level().getBlockEntity(p.pos) instanceof StabilizerControllerBlockEntity controller))return;
            boolean accepted=false;
            if(p.action>=0&&p.action<StabilizerControllerBlockEntity.INPUT_ACTION_COUNT) {
                if(p.kind==-1) {
                    controller.setFrequency(p.action,true,ControllerSettings.normalize(p.first));
                    controller.setFrequency(p.action,false,ControllerSettings.normalize(p.second));accepted=true;
                } else if(p.kind>=0&&p.kind<RemoteControllerCompat.Type.values().length) {
                    var type=RemoteControllerCompat.Type.values()[p.kind];
                    if(p.key.isEmpty()||RemoteTerminalDetector.hasTerminal(controller,player.level(),type))
                        accepted=controller.setRemoteKey(type,p.action,p.key);
                }
            }
            PacketDistributor.sendToPlayer(player,new FireControlInputsStatePayload(p.pos,controller.inputUiState(),accepted));
        });
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
