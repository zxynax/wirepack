package com.cdrware.wirepack.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional type-level marker for a bean that exposes {@link WirepackMapping} methods.
 * Scanning also finds mappings on {@code @RestController} beans without this annotation.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WirepackController {
}
