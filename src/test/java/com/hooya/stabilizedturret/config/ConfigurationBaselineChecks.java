package com.hooya.stabilizedturret.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.hooya.stabilizedturret.client.ClientScopeConfig;
import com.hooya.stabilizedturret.client.TigerISoundConfig;
import com.hooya.stabilizedturret.client.Ztz99aDieselSoundConfig;
import com.hooya.stabilizedturret.content.missile.MissilePhysicsConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Independent references: the shipped pre-menu fire-control JAR and native missile bytecode. */
public final class ConfigurationBaselineChecks {
    private static final String PREFIX = "com.hooya.stabilizedturret.";
    private static ClassLoader baseline;
    private static JarFile nativeJar;

    public static void main(String[] args) throws Exception {
        try (var loader = new BaselineLoader(Path.of(args[0]).toUri().toURL());
             var nativeFile = new JarFile(args[1])) {
            baseline = loader;
            nativeJar = nativeFile;
            verifyRequestedScopeLimits();
            int settings = compareSpec("client.TigerISoundConfig", TigerISoundConfig.SPEC)
                    + compareSpec("client.Ztz99aDieselSoundConfig", Ztz99aDieselSoundConfig.SPEC)
                    + compareSpec("content.missile.MissilePhysicsConfig", MissilePhysicsConfig.SPEC);
            for (var entry : MissileSettings.defaultsByModel().entrySet()) {
                String model = entry.getKey();
                var value = entry.getValue();
                double life, speed, burn;
                if (model.startsWith("antitank")) {
                    life = number("content.controller.MianbaoMissileGuidanceHandler", "MAX_LIFETIME_TICKS") / 20;
                    speed = 1; // Identity multiplier preserves the original variable native speed curve.
                    String procedure = model.contains("_2") ? "Antitankemissile2trailProcedure"
                            : model.contains("_5") ? "Antitankmissile5trailProcedure"
                            : model.contains("_6") ? "Antitankmissile6trailProcedure"
                            : model.contains("_7") ? "Antitankmissile_7tanshe_trailProcedure"
                            : "Antitankmissile1trailProcedure";
                    int lowerRead = model.contains("_5") ? 2 : 3;
                    burn = model.contains("_2") || model.contains("_5")
                            ? (tickComparison(procedure, lowerRead + 1) - tickComparison(procedure, lowerRead)) / 20.0
                            : tickComparison(procedure, 2) / 20.0;
                } else if (model.startsWith("antiair") || model.startsWith("portableantiair")) {
                    boolean ordinary = model.equals("portableantiairmissiletanshe");
                    life = model.equals("antiairmissiletanshe") ? 30 : 20;
                    if (ordinary) {
                        life = number("content.controller.OrdinaryAirDefenseGuidance", "LIFETIME_TICKS") / 20;
                        speed = number("content.controller.OrdinaryAirDefenseGuidance", "MAX_SPEED") * 20;
                        burn = number("content.controller.OrdinaryAirDefenseGuidance", "ACCELERATION_TICKS") / 20;
                    } else {
                        var owner = old("content.controller.MianbaoAirDefenseCompat");
                        Object profile = owner.getMethod("speedProfile", double.class).invoke(null, 1.0);
                        speed = (double) profile.getClass().getMethod("maximumSpeed").invoke(profile) * 20;
                        burn = (int) profile.getClass().getMethod("accelerationTicks").invoke(profile) / 20.0;
                    }
                } else if (model.contains("cruise") || model.startsWith("groundmissile") || model.startsWith("ballistic")) {
                    life = number("content.controller.LandAttackMissileFlight", "LIFETIME_TICKS") / 20;
                    burn = number("content.controller.LandAttackMissileFlight", "POWERED_TICKS") / 20;
                    speed = model.startsWith("ballistic")
                            ? number("content.display.BallisticMissileGuidance", "TERMINAL_SPEED") * 20
                            : number("content.display.CruiseMissileGuidance", model.startsWith("ground")
                            ? "GROUND_VLS_MAX_SPEED" : "MACH_1_SPEED") * 20;
                } else {
                    String family = model.startsWith("farmissile") ? "FAR"
                            : model.startsWith("closemissile") ? "CLOSE"
                            : model.equals("mica_em_tanshe") ? "MICA_EM"
                            : model.equals("aim_120_tanshe") ? "AIM_120D"
                            : model.equals("missile_2tanshe") || model.equals("medium_aim_missile_2tanshe") ? "MEDIUM"
                            : model.equals("antiradiationmissiletanshe") ? "ANTI_RADIATION"
                            : model.equals("opticalguidedmissiletanshe") ? "OPTICAL_GUIDED"
                            : model.equals("missiletanshe") ? "LASER_GUIDED"
                            : model.startsWith("agm_1") || model.startsWith("agm_3") || model.equals("agmmissile_3")
                            ? "AIR_TO_GROUND_DIRECT" : "AIR_TO_GROUND_TOP_ATTACK";
                    Class<?> owner = old("content.controller.MianbaoAircraftCompat");
                    Class<?> type = old("content.controller.MianbaoAircraftCompat$WeaponType");
                    Object weapon = type.getField(family).get(null);
                    life = (int) owner.getMethod("lifetimeTicks", type).invoke(null, weapon) / 20.0;
                    speed = (double) owner.getMethod("maximumSpeedBlocksPerTick", type).invoke(null, weapon) * 20;
                    int aamBurn = (int) owner.getMethod("airToAirBurnTicks", type).invoke(null, weapon);
                    burn = aamBurn > 0 ? aamBurn / 20.0 : family.equals("ANTI_RADIATION") ? life
                            : tickComparison(family.equals("LASER_GUIDED") ? "Missile3trailProcedure"
                            : "Agm1trailProcedure", 1) / 20.0;
                }
                near(value.lifetimeSeconds(), life, model + " lifetime differs from pre-menu code");
                near(value.speed(), speed, model + " speed differs from pre-menu code");
                near(value.poweredSeconds(), burn, model + " powered duration differs from pre-menu code");
            }
            System.out.println("PASS: " + settings + " retained configuration defaults and all "
                    + MissileSettings.defaultsByModel().size() + " registered missile lifetime/speed/burn defaults "
                    + "independently match the pre-menu JAR and native procedure bytecode; requested scope/pod limits are 1-32x, cap 100x");
        }
    }

    private static void verifyRequestedScopeLimits() {
        Map<String, Object> limits = flatten(ClientScopeConfig.SPEC.getValues());
        if (!limits.equals(Map.of("scope.minZoom", 1, "scope.maxZoom", 32,
                "scope.podMinZoom", 1, "scope.podMaxZoom", 32)))
            throw new AssertionError("Scope limits differ from the user's requested defaults: " + limits);
        for (String path : limits.keySet()) {
            ModConfigSpec.ValueSpec spec = ClientScopeConfig.SPEC.getSpec().get(path);
            if (!spec.test(100) || spec.test(101) || spec.test(0))
                throw new AssertionError(path + " must allow 1 through 100");
        }
    }

    private static Class<?> old(String name) throws ClassNotFoundException { return Class.forName(PREFIX + name, true, baseline); }
    private static double number(String owner, String name) throws Exception {
        var field = old(owner).getDeclaredField(name); field.setAccessible(true);
        return ((Number) field.get(null)).doubleValue();
    }

    private static int tickComparison(String procedure, int ordinal) throws Exception {
        var node = new ClassNode();
        try (var input = nativeJar.getInputStream(nativeJar.getJarEntry("net/mcreator/myfirstmod/procedures/" + procedure + ".class"))) {
            new ClassReader(input).accept(node, 0);
        }
        int seen = 0;
        for (var method : node.methods) if (method.name.equals("execute")) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || !field.name.equals("tickCount") || seen++ != ordinal) continue;
                var next = instruction.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof IntInsnNode integer) return integer.operand;
                if (next instanceof LdcInsnNode constant && constant.cst instanceof Number value) return value.intValue();
                if (next != null && next.getOpcode() >= Opcodes.ICONST_M1 && next.getOpcode() <= Opcodes.ICONST_5)
                    return next.getOpcode() - Opcodes.ICONST_0;
                throw new AssertionError("Native motor comparison changed: " + procedure + "/" + ordinal);
            }
        }
        throw new AssertionError("Native motor age read missing: " + procedure + "/" + ordinal);
    }

    private static int compareSpec(String name, ModConfigSpec current) throws Exception {
        var reference = flatten(((ModConfigSpec) old(name).getField("SPEC").get(null)).getValues());
        var actual = flatten(current.getValues());
        actual.forEach((path, value) -> {
            Object original = reference.get(path);
            if (!java.util.Objects.equals(value, original)) throw new AssertionError(name + "/" + path + ": " + value + " != " + original);
        });
        return actual.size();
    }
    private static Map<String, Object> flatten(UnmodifiableConfig group) {
        var result = new LinkedHashMap<String, Object>();
        for (var value : group.valueMap().values()) {
            if (value instanceof ModConfigSpec.ConfigValue<?> field) {
                Object entry = field.getDefault();
                result.put(String.join(".", field.getPath()), entry instanceof Enum<?> enumeration ? enumeration.name() : entry);
            } else if (value instanceof UnmodifiableConfig child) result.putAll(flatten(child));
        }
        return result;
    }
    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-7) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    private static final class BaselineLoader extends URLClassLoader {
        BaselineLoader(URL jar) { super(new URL[]{jar}, ConfigurationBaselineChecks.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && name.startsWith(PREFIX)) try { found = findClass(name); } catch (ClassNotFoundException ignored) { }
                if (found == null) found = super.loadClass(name, false);
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }
}
