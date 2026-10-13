package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.WirepackException;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bind domain objects to/from {@link KvTreeCodec} trees ({@code Map}/{@code List} graphs).
 *
 * <p>Supports records, public fields, and JavaBean getters/setters. Nested objects, lists,
 * maps, primitives, {@link String}, {@code byte[]}, and {@code null}.
 */
public final class ObjectBinder {
    private ObjectBinder() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toTree(Object bean) {
        if (bean == null) {
            throw new WirepackException("bean is null");
        }
        if (bean instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), toValue(e.getValue()));
            }
            return out;
        }
        Class<?> type = bean.getClass();
        if (type.isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : type.getRecordComponents()) {
                try {
                    out.put(rc.getName(), toValue(rc.getAccessor().invoke(bean)));
                } catch (ReflectiveOperationException e) {
                    throw new WirepackException("record access failed: " + rc.getName(), e);
                }
            }
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : type.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                out.put(f.getName(), toValue(f.get(bean)));
            } catch (IllegalAccessException e) {
                throw new WirepackException("field access failed: " + f.getName(), e);
            }
        }
        // Bean getters without a matching public field already added
        for (var m : type.getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName();
            String prop = null;
            if (name.startsWith("get") && name.length() > 3) {
                prop = decapitalize(name.substring(3));
            } else if (name.startsWith("is") && name.length() > 2
                    && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                prop = decapitalize(name.substring(2));
            }
            if (prop == null || out.containsKey(prop)) {
                continue;
            }
            try {
                out.put(prop, toValue(m.invoke(bean)));
            } catch (ReflectiveOperationException e) {
                throw new WirepackException("getter failed: " + name, e);
            }
        }
        return out;
    }

    public static <T> T fromTree(Map<String, ?> tree, Class<T> type) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(type, "type");
        return type.cast(fromValue(tree, type, type));
    }

    private static Object toValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String || v instanceof Boolean || v instanceof byte[]) {
            return v instanceof byte[] b ? b.clone() : v;
        }
        if (v instanceof Float || v instanceof Double) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Map<?, ?> || v.getClass().isRecord() || hasBeanShape(v.getClass())) {
            if (v instanceof Collection<?> || v.getClass().isArray()) {
                // fall through
            } else if (!(v instanceof Map<?, ?>)) {
                return toTree(v);
            }
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), toValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) {
                out.add(toValue(o));
            }
            return out;
        }
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            if (v.getClass().getComponentType() == byte.class) {
                return ((byte[]) v).clone();
            }
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(toValue(Array.get(v, i)));
            }
            return out;
        }
        if (hasBeanShape(v.getClass()) || v.getClass().isRecord()) {
            return toTree(v);
        }
        throw new WirepackException("unsupported property type: " + v.getClass().getName());
    }

    private static boolean hasBeanShape(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()
                || type.getName().startsWith("java.")) {
            return false;
        }
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            for (Field f : type.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Object fromValue(Object raw, Class<?> rawType, Type generic) {
        if (raw == null) {
            return null;
        }
        Class<?> type = rawType;
        if (type == Object.class && generic instanceof Class<?> c) {
            type = c;
        }
        if (type == String.class) {
            return String.valueOf(raw);
        }
        if (type == boolean.class || type == Boolean.class) {
            return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
        }
        if (type == byte[].class) {
            if (raw instanceof byte[] b) {
                return b.clone();
            }
            throw new WirepackException("expected byte[]");
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(box(type))) {
            return coerceNumber(raw, type);
        }
        if (List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
            if (!(raw instanceof List<?> list)) {
                throw new WirepackException("expected list for " + type.getName());
            }
            Class<?> elem = Object.class;
            if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
                Type a = pt.getActualTypeArguments()[0];
                if (a instanceof Class<?> c) {
                    elem = c;
                }
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(fromValue(o, elem, elem));
            }
            return out;
        }
        if (Map.class.isAssignableFrom(type)) {
            if (!(raw instanceof Map<?, ?> m)) {
                throw new WirepackException("expected map for " + type.getName());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new WirepackException("expected object map for " + type.getName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) m;
        return instantiate(type, tree);
    }

    private static Object instantiate(Class<?> type, Map<String, Object> tree) {
        try {
            if (type.isRecord()) {
                RecordComponent[] comps = type.getRecordComponents();
                Class<?>[] ctorTypes = new Class<?>[comps.length];
                Object[] args = new Object[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    ctorTypes[i] = comps[i].getType();
                    Object raw = tree.get(comps[i].getName());
                    args[i] = fromValue(raw, comps[i].getType(), comps[i].getGenericType());
                }
                Constructor<?> ctor = type.getDeclaredConstructor(ctorTypes);
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
            Object bean;
            try {
                Constructor<?> ctor = type.getDeclaredConstructor();
                ctor.setAccessible(true);
                bean = ctor.newInstance();
            } catch (NoSuchMethodException e) {
                throw new WirepackException("no no-arg constructor for " + type.getName(), e);
            }
            for (Map.Entry<String, Object> e : tree.entrySet()) {
                if (!setProperty(bean, e.getKey(), e.getValue())) {
                    // ignore unknown
                }
            }
            return bean;
        } catch (WirepackException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new WirepackException("bind failed for " + type.getName(), e);
        }
    }

    private static boolean setProperty(Object bean, String name, Object raw) throws ReflectiveOperationException {
        Class<?> type = bean.getClass();
        try {
            Field f = type.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                f.set(bean, fromValue(raw, f.getType(), f.getGenericType()));
                return true;
            }
        } catch (NoSuchFieldException ignored) {
            // try setter
        }
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (var m : type.getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterCount() != 1) {
                continue;
            }
            m.invoke(bean, fromValue(raw, m.getParameterTypes()[0], m.getGenericParameterTypes()[0]));
            return true;
        }
        return false;
    }

    private static Object coerceNumber(Object raw, Class<?> type) {
        Number n;
        if (raw instanceof Number num) {
            n = num;
        } else {
            n = Double.parseDouble(String.valueOf(raw));
        }
        Class<?> t = box(type);
        if (t == Long.class || type == long.class) {
            return n.longValue();
        }
        if (t == Integer.class || type == int.class) {
            return n.intValue();
        }
        if (t == Short.class || type == short.class) {
            return n.shortValue();
        }
        if (t == Byte.class || type == byte.class) {
            return n.byteValue();
        }
        if (t == Double.class || type == double.class) {
            return n.doubleValue();
        }
        if (t == Float.class || type == float.class) {
            return n.floatValue();
        }
        return n.longValue();
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            default -> type;
        };
    }

    private static String decapitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
