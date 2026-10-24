package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.RpcHandler;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.api.WirepackMapping;
import com.cdrware.wirepack.codec.ObjectBinder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bind {@link WirepackMapping} methods on a POJO to {@link Session#onRpc}.
 */
public final class WirepackMappings {
    private WirepackMappings() {}

    public static boolean hasMappings(Class<?> type) {
        if (type == null) {
            return false;
        }
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(WirepackMapping.class)) {
                return true;
            }
        }
        return false;
    }

    public static void install(Session session, Object... beans) {
        Objects.requireNonNull(session, "session");
        for (Object bean : beans) {
            if (bean == null) {
                continue;
            }
            for (Binding b : scan(bean)) {
                session.onRpc(b.route(), payload -> invoke(bean, b.method(), payload));
            }
        }
    }

    public static List<Binding> scan(Object bean) {
        Objects.requireNonNull(bean, "bean");
        Class<?> type = bean.getClass();
        List<Binding> out = new ArrayList<>();
        for (Method m : type.getMethods()) {
            WirepackMapping ann = m.getAnnotation(WirepackMapping.class);
            if (ann == null || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String route = ann.value();
            if (route == null || route.isBlank()) {
                throw new WirepackException("@WirepackMapping on " + m + " has empty route");
            }
            if (m.getReturnType().getName().startsWith("org.springframework.http.ResponseEntity")) {
                throw new WirepackException(
                        "@WirepackMapping methods must return a POJO, not ResponseEntity: " + m);
            }
            bindableParams(m);
            out.add(new Binding(route, m));
        }
        return out;
    }

    public static Object invoke(Object bean, Method method, Map<String, Object> payload) throws Exception {
        List<Parameter> bindable = bindableParams(method);
        method.setAccessible(true);
        if (bindable.isEmpty()) {
            return method.invoke(bean);
        }
        Class<?> pt = bindable.get(0).getType();
        Map<String, Object> tree = payload == null ? Map.of() : payload;
        Object arg = ObjectBinder.fromTree(tree, pt);
        return method.invoke(bean, arg);
    }

    static List<Parameter> bindableParams(Method method) {
        List<Parameter> bindable = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            if (isHttpOnly(p.getType())) {
                continue;
            }
            bindable.add(p);
        }
        if (bindable.size() > 1) {
            throw new WirepackException(
                    "@WirepackMapping method must have at most one POJO parameter: " + method);
        }
        return bindable;
    }

    static Object toPayload(Object result) {
        if (result == null) {
            return Map.of();
        }
        if (result instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return result;
    }

    static boolean isHttpOnly(Class<?> t) {
        String n = t.getName();
        return n.startsWith("jakarta.servlet.")
                || n.startsWith("javax.servlet.")
                || n.startsWith("org.springframework.http.")
                || n.startsWith("org.springframework.web.")
                || n.startsWith("org.springframework.ui.")
                || n.startsWith("org.springframework.validation.")
                || n.equals("java.security.Principal");
    }

    public record Binding(String route, Method method) {}
}
