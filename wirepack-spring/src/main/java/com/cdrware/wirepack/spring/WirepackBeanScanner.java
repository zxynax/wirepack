package com.cdrware.wirepack.spring;

import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.WirepackMapping;
import com.cdrware.wirepack.core.WirepackMappings;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Find Spring beans with {@link WirepackMapping} methods and attach them to a session.
 */
public final class WirepackBeanScanner {
    private WirepackBeanScanner() {}

    public static List<Object> beansWithMappings(ListableBeanFactory factory) {
        List<Object> out = new ArrayList<>();
        for (String name : factory.getBeanDefinitionNames()) {
            if (name.startsWith("&")) {
                continue;
            }
            if (factory instanceof ConfigurableListableBeanFactory clbf) {
                BeanDefinition bd = clbf.getBeanDefinition(name);
                if (bd.isAbstract() || !bd.isSingleton()) {
                    continue;
                }
            }
            Class<?> type = factory.getType(name);
            if (type == null
                    || type.getName().startsWith("org.springframework.")
                    || !hasMappings(type)) {
                continue;
            }
            out.add(factory.getBean(name));
        }
        return out;
    }

    public static void install(Session session, List<Object> beans) {
        for (Object bean : beans) {
            Class<?> user = AopUtils.getTargetClass(bean);
            for (Method m : user.getMethods()) {
                WirepackMapping ann = AnnotationUtils.findAnnotation(m, WirepackMapping.class);
                if (ann == null) {
                    continue;
                }
                Method invocable = invocable(bean, m);
                session.onRpc(ann.value(), payload -> WirepackMappings.invoke(bean, invocable, payload));
            }
        }
    }

    static boolean hasMappings(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (AnnotationUtils.findAnnotation(m, WirepackMapping.class) != null) {
                return true;
            }
        }
        return false;
    }

    private static Method invocable(Object bean, Method mapped) {
        try {
            return bean.getClass().getMethod(mapped.getName(), mapped.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return mapped;
        }
    }
}
