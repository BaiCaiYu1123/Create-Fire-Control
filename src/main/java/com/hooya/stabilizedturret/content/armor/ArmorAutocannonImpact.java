package com.hooya.stabilizedturret.content.armor;

import com.hooya.stabilizedturret.mixin.PolyArmorProjectileAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;
import rbasamoyai.createbigcannons.config.*;
import rbasamoyai.createbigcannons.munitions.*;
import rbasamoyai.createbigcannons.network.ClientboundPlayBlockHitEffectPacket;
import rbasamoyai.createbigcannons.utils.CBCUtils;
import static rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult.KinematicOutcome.*;

/** CBC momentum/material rules for a destructible solid whose whole block must stay in place. */
final class ArmorAutocannonImpact {
    static AbstractCannonProjectile.ImpactResult calculate(AbstractCannonProjectile projectile,ProjectileContext context,BlockState state,BlockHitResult hit) {
        var access=(PolyArmorProjectileAccessor)projectile;
        var ballistic=access.fireControl$ballistics();var level=projectile.level();var pos=hit.getBlockPos();
        var velocity=projectile.getDeltaMovement().add(access.fireControl$forces(projectile.position(),projectile.getDeltaMovement()));
        double speed=velocity.length();var normal=CBCUtils.getSurfaceNormalVector(level,hit);
        double incidence=Math.max(0,velocity.normalize().dot(normal.reverse()));
        var material=BlockArmorPropertiesHandler.getProperties(state);
        double resistance=material.toughness(level,state,pos,true);
        double hardness=Math.max(0,material.hardness(level,state,pos,true)-ballistic.penetration());
        double mass=projectile.getProjectileMass();
        double bonus=1+Math.max(0,(speed-CBCConfigs.server().munitions.minVelocityForPenetrationBonus.getF())*CBCConfigs.server().munitions.penetrationBonusScale.getF());
        double momentum=mass*speed*incidence*bonus;
        if(hardness>.01)momentum*=ballistic.toughness()<.01?0:Math.max(.25,1-hardness/ballistic.toughness());
        boolean surface=access.fireControl$canHitSurface();double deflection=ballistic.deflection();
        double bounce=deflection<.01||incidence>deflection?0:Math.max(CBCConfigs.server().munitions.baseProjectileBounceChance.getF(),1-incidence/deflection);
        boolean unbreakable=context.griefState()==CBCCfgMunitions.GriefState.NO_DAMAGE||state.getDestroySpeed(level,pos)==-1||!ProjectileDamageHooks.canDamageTerrain(level,pos);
        var outcome=surface&&CBCConfigs.server().munitions.projectilesCanBounce.get()&&level.random.nextDouble()<bounce?BOUNCE:
                !unbreakable&&momentum>resistance?PENETRATE:STOP;
        boolean shattered=surface&&outcome!=BOUNCE&&hardness>ballistic.toughness();
        if(outcome==PENETRATE)projectile.setProjectileMass((float)Math.max(0,mass-resistance*(hardness+1)/Math.max(1e-4,speed*incidence)));
        else if(outcome==STOP)projectile.setProjectileMass(0);
        projectile.massLost=(float)((mass-projectile.getProjectileMass())/Math.max(1e-6,ballistic.durabilityMass()));
        if(!level.isClientSide){
            var effect=outcome==BOUNCE?velocity.subtract(normal.scale(normal.dot(velocity)*1.7)):velocity.reverse();var location=hit.getLocation();
            for(var contained:material.containedBlockStates(level,state,pos,true))context.addPlayedEffect(new ClientboundPlayBlockHitEffectPacket(
                    contained,projectile.getType(),outcome==BOUNCE,true,location.x,location.y,location.z,(float)effect.x,(float)effect.y,(float)effect.z));
        }
        state.onProjectileHit(level,state,hit,projectile);
        if(outcome==PENETRATE&&!level.isClientSide&&!(state.getBlock() instanceof com.caca.copypoly.PolyBlock))level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),11);
        var impact=new AbstractCannonProjectile.ImpactResult(outcome,shattered);
        boolean detonated=access.fireControl$onImpact(hit,impact,context);
        return new AbstractCannonProjectile.ImpactResult(outcome,shattered||detonated||outcome==STOP);
    }
    private ArmorAutocannonImpact() {}
}
