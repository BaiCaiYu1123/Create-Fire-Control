package com.hooya.stabilizedturret.content.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import java.util.*;
import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentHashMap;

/** A penetrated contact is ignored by that projectile only; the block never disappears. */
public final class ArmorProjectilePassage {
    // Entity.equals compares numeric IDs. The client replica and integrated-server
    // projectile share that ID, but must never share their predicted contact history.
    private static final Map<Entity,Map<BlockPos,Long>> CONTACTS=new MapMaker().weakKeys().makeMap();
    public static void allow(Entity projectile,BlockPos pos){CONTACTS.computeIfAbsent(projectile,p->new ConcurrentHashMap<>()).put(pos.immutable(),projectile.level().getGameTime());}
    public static boolean ignores(Entity projectile,BlockPos pos){
        if(projectile==null)return false;var positions=CONTACTS.get(projectile);if(positions==null)return false;
        positions.values().removeIf(t->projectile.level().getGameTime()-t>1);return positions.containsKey(pos);
    }
    private ArmorProjectilePassage(){}
}
