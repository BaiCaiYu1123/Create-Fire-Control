package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.client.ClientBindingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.UUID;

public final class AimCameraBindingChecks {
    public static void main(String[] args) {
        Vec3 eye=new Vec3(55,148,-900),offset=new Vec3(56.652053833,98.482566833,-240.238342285);
        require(offset.length()>128,"live-log orbit exceeds the former rejection distance");
        for(Vec3 displacement:new Vec3[]{offset,new Vec3(0,20,-1024),new Vec3(0,0,-4096)}) {
            require(AimCameraGeometry.matches(eye,eye,eye.add(displacement),displacement),"authorized distant orbit remains valid");
            var frame=new AimDirectionPayload.CameraFrame(new UUID(1,2),new Vec3(20481032,129,20481032),new Vec3(0,1,0),displacement);
            require(frame.worldOffset().equals(displacement),"camera frame supports distant hit and empty-sky packets without a constructor failure");
        }
        require(!AimCameraGeometry.matches(eye,eye.add(1000,0,0),eye.add(offset),offset),"a distant forged physical anchor is rejected");
        require(!AimCameraGeometry.matches(eye,eye,eye.add(800,0,0),offset),"camera must match its declared orbit offset");
        require(!AimCameraGeometry.validOffset(new Vec3(Double.NaN,0,0)),"nonfinite orbit is rejected");
        require(!AimCameraGeometry.validOffset(new Vec3(AimCameraGeometry.MAX_OFFSET+1,0,0)),"orbit has a finite protocol sanity bound");
        Vec3 localSeat=new Vec3(20483080.5,977.6,20524134.5);
        require(AimCameraGeometry.matchesSeat(localSeat,localSeat,eye.add(240,0,0),eye.add(offset),offset),
                "a verified seat eye tolerates moving-ship render/server pose skew");
        require(!AimCameraGeometry.matchesSeat(localSeat,localSeat.add(100,0,0),eye,eye.add(offset),offset),
                "world pose tolerance never grants a distant forged seat anchor");
        var devices=new ArrayList<BindingSessionPayload.Candidate>();
        for(int i=0;i<3000;i++)devices.add(new BindingSessionPayload.Candidate(new BlockPos(i,70,-i),new UUID(1,i/100),i%3==0));
        var payload=new BindingSessionPayload(true,devices);int pageCount=0;
        for(var page:payload.pages()) {
            require(page.candidates().size()<=1024,"each packet stays small");
            require(page.append()==(pageCount++>0),"only continuation pages append");
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try {
                BindingSessionPayload.STREAM_CODEC.encode(buffer,page);
                var decoded=BindingSessionPayload.STREAM_CODEC.decode(buffer);
                require(decoded.equals(page)&&buffer.readableBytes()==0,"page is losslessly decoded");
                ClientBindingState.accept(decoded.active(),decoded.append(),decoded.candidates());
            } finally {buffer.release();}
        }
        require(pageCount==3&&ClientBindingState.getCandidates().equals(devices),"all 3000 device boxes arrive without truncation");
        ClientBindingState.accept(false,java.util.List.of());
        require(!ClientBindingState.isActive()&&ClientBindingState.getCandidates().isEmpty(),"ending binding clears all pages");
        System.out.println("PASS: distant authorized cameras, forged-frame rejection, 3000 paged binding candidates and session cleanup");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
