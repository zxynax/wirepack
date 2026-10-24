package com.cdrware.wirepack.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler method as a wirepack RPC route. Can sit on the same method as
 * Spring {@code @PostMapping}: HTTP stays on the servlet stack; UDP uses this route name.
 *
 * <p>The method should be POJO in / POJO out. Servlet types ({@code HttpServletRequest},
 * {@code MultipartFile}, \u2026) are ignored on the UDP path.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WirepackMapping {
    /** RPC route, e.g. {@code order.create} or {@code echo}. */
    String value();
}
