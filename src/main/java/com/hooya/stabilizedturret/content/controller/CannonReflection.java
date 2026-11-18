package com.hooya.stabilizedturret.content.controller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Cached member metadata only; ammunition, geometry and invocation results stay live. */
final class CannonReflection {
    private record Entry(Method method, Class<?>[] parameters, boolean accessible) {}
    private static final Entry[] NONE = {};

    // ClassValue keeps optional mod classes tied to their own class loader. The
    // hierarchy and declared order are retained for CBC add-on overload fallback.
    private static final ClassValue<Map<String, Entry[]>> DECLARED = new ClassValue<>() {
        @Override protected Map<String, Entry[]> computeValue(Class<?> type) {
            Method[] methods = type.getDeclaredMethods();
            Map<String, ArrayList<Entry>> groups = new HashMap<>();
            for (Method method : methods) {
                boolean accessible;
                try { accessible = method.trySetAccessible(); }
                catch (RuntimeException | LinkageError ignored) { accessible = false; }
                Class<?>[] parameters = method.getParameterTypes();
                for (int p = 0; p < parameters.length; p++) parameters[p] = wrap(parameters[p]);
                groups.computeIfAbsent(method.getName(), ignored -> new ArrayList<>())
                        .add(new Entry(method, parameters, accessible));
            }
            Map<String, Entry[]> entries = new HashMap<>();
            groups.forEach((name, group) -> entries.put(name, group.toArray(Entry[]::new)));
            return Map.copyOf(entries);
        }
    };

    static Object invoke(Object owner, String name, Object... arguments) {
        return owner == null ? null : invoke(owner.getClass(), owner, name, arguments);
    }

    static Object invokeStatic(Class<?> owner, String name, Object... arguments) {
        return owner == null ? null : invoke(owner, null, name, arguments);
    }

    private static Object invoke(Class<?> type, Object receiver, String name, Object[] arguments) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Entry entry : DECLARED.get(current).getOrDefault(name, NONE)) {
                if (!entry.accessible || entry.parameters.length != arguments.length
                        || !accepts(entry.parameters, arguments)) continue;
                try { return entry.method.invoke(receiver, arguments); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // A transient invocation failure must not poison the metadata
                    // cache or prevent trying another overload or superclass.
                }
            }
        }
        return null;
    }

    static Method find(Class<?> type, String name, int argumentCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Entry entry : DECLARED.get(current).getOrDefault(name, NONE)) {
                if (entry.parameters.length == argumentCount) return entry.method;
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            // Keep the old null behavior: reflection rejects null for primitives,
            // then invocation continues with the remaining compatible candidates.
            if (arguments[i] != null && !parameters[i].isInstance(arguments[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private CannonReflection() {}
}
