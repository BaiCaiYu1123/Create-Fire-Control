package com.hooya.stabilizedturret.content.armor;

import com.google.gson.*;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import rbasamoyai.createbigcannons.munitions.autocannon.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Measures the launched projectile's body, never the cartridge used to load the gun. */
public final class ArmorProjectileBores {
    private record Access(Method block,Method item) {}
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) { return new Access(method(type,"getRenderedBlockState"),method(type,"getItem")); }
    };
    private record Model(ResourceLocation id,int axis) {}
    private static final Map<Model,Double> MODELS = new ConcurrentHashMap<>();

    public static double width(Entity projectile) {
        if (projectile instanceof StabilizedApfsdsProjectile) return 2.0/16;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        // This round uses a dedicated flying partial; its inventory block includes the sabot.
        if (id.toString().equals("cbcmodernwarfare:apds_shot")) return modelWidth(ResourceLocation.parse("cbcmodernwarfare:block/apds_shot_flying"),2,6.0/16);
        var access = ACCESS.get(projectile.getClass());
        Object block = invoke(access.block,projectile);
        if (block instanceof BlockState state && !state.isAir()) {
            var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            int axis = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING).getAxis().ordinal() : 1;
            // Projectile block models are authored pointing up, independently of their placed rotation.
            double modeled = modelWidth(ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(),"block/"+blockId.getPath()),1,0);
            if (modeled>0) return modeled;
            var shape = state.getShape(projectile.level(),BlockPos.ZERO);
            if (!shape.isEmpty()) {
                var bounds = shape.bounds();
                double[] size = {bounds.getXsize(),bounds.getYsize(),bounds.getZsize()};
                return Math.clamp(Math.min(size[(axis+1)%3],size[(axis+2)%3]),1.0/256,4);
            }
        }
        Object item = invoke(access.item,projectile);
        if (item instanceof ItemStack stack && !stack.isEmpty()) {
            var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            double modeled = modelWidth(ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(),"item/"+itemId.getPath()),2,0);
            if (modeled>0) return modeled;
        }
        // CBC's sprite/tracer bullets do not have a solid model. Use the body class,
        // not their common collision box or the glowing tracer's outer halo.
        String name = projectile.getClass().getName().toLowerCase(Locale.ROOT);
        if (isAutocannon(projectile)) {
            double full = name.contains("heavyautocannon") || name.contains("heavy_autocannon") ? 4 :
                    projectile instanceof AbstractAutocannonProjectile auto && auto.getAutocannonRoundType()==AutocannonAmmoType.MACHINE_GUN ? 1 : 2;
            String kind = id.getPath();
            double core = kind.contains("apfsds") || kind.contains("apdsfs") ? .375 : kind.contains("apds") ? .5 : kind.contains("hvap") ? .75 : 1;
            return full*core/16;
        }
        return Math.clamp(projectile.getBbWidth(),1.0/256,4);
    }

    public static boolean isAutocannon(Entity projectile) {
        if (projectile instanceof AbstractAutocannonProjectile) return true;
        for (Class<?> type=projectile.getClass();type!=null;type=type.getSuperclass())
            if (type.getSimpleName().equals("AbstractHeavyAutocannonProjectile")) return true;
        return false;
    }

    private static double modelWidth(ResourceLocation id,int axis,double fallback) {
        double result = MODELS.computeIfAbsent(new Model(id,axis),key -> readModel(key.id,key.axis,new HashSet<>()));
        return result>0 ? result : fallback;
    }
    private static double readModel(ResourceLocation id,int axis,Set<ResourceLocation> visited) {
        if (!visited.add(id) || visited.size()>16) return 0;
        var owner = ModList.get().getModFileById(id.getNamespace());
        if (owner==null) return 0;
        var path = owner.getFile().findResource("assets",id.getNamespace(),"models",id.getPath()+".json");
        if (!Files.isRegularFile(path)) return 0;
        try (var reader=Files.newBufferedReader(path)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            double width=0;
            if (json.has("elements")) for (var entry:json.getAsJsonArray("elements")) {
                var element=entry.getAsJsonObject();
                if (!element.has("from")||!element.has("to")) continue;
                var from=element.getAsJsonArray("from");var to=element.getAsJsonArray("to");
                int u=(axis+1)%3,v=(axis+2)%3;
                double a=Math.abs(to.get(u).getAsDouble()-from.get(u).getAsDouble()), b=Math.abs(to.get(v).getAsDouble()-from.get(v).getAsDouble());
                // Zero-thickness fins and decorative planes do not enlarge the penetrator.
                if (a>1e-6&&b>1e-6) width=Math.max(width,Math.min(a,b)/16);
            }
            if (width>0) return Math.min(width,4);
            return json.has("parent") ? readModel(ResourceLocation.parse(json.get("parent").getAsString()),axis,visited) : 0;
        } catch (java.io.IOException|RuntimeException invalid) { return 0; }
    }
    private static Method method(Class<?> type,String name) { try { return type.getMethod(name); } catch (NoSuchMethodException absent) { return null; } }
    private static Object invoke(Method method,Object receiver) { if(method==null)return null;try{return method.invoke(receiver);}catch(ReflectiveOperationException ignored){return null;} }
    private ArmorProjectileBores() {}
}
