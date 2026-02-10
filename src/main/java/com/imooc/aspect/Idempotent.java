package com.imooc.aspect;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    String key() default "";           // 幂等键前缀
    long expireTime() default 5000;    // 过期时间(毫秒)
    String message() default "请勿重复提交"; // 重复提交提示
}
