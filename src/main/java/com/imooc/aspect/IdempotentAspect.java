package com.imooc.aspect;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.imooc.enums.ResultEnum;
import com.imooc.exception.SellException;
import com.imooc.service.RedisLock;

import javassist.bytecode.SignatureAttribute.MethodSignature;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class IdempotentAspect {
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private RedisLock redisLock;

    @Around("@annotation(com.imooc.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent idempotent = method.getAnnotation(Idempotent.class);
        
        // 获取用户唯一标识（openid + 请求路径）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String openid = request.getParameter("openid");
        String requestUri = request.getRequestURI();
        
        // 构建幂等键
        String idempotentKey = buildIdempotentKey(idempotent.key(), openid, requestUri);
        String lockKey = "lock:" + idempotentKey;
        
        // 生成唯一token值
        String tokenValue = String.valueOf(System.currentTimeMillis());
        
        try {
            // 双重检查：先检查是否已存在
            String existingValue = redisTemplate.opsForValue().get(idempotentKey);
            if (existingValue != null) {
                log.warn("【幂等性拦截】重复提交，key={}", idempotentKey);
                throw new SellException(ResultEnum.REPEAT_REQUEST.getCode(), idempotent.message());
            }
            
            // 获取分布式锁
            boolean locked = redisLock.lock(lockKey, tokenValue);
            if (!locked) {
                log.error("【幂等性拦截】获取锁失败，key={}", lockKey);
                throw new SellException(ResultEnum.SYSTEM_ERROR);
            }
            
            // 双重检查：获取锁后再次验证
            existingValue = redisTemplate.opsForValue().get(idempotentKey);
            if (existingValue != null) {
                log.warn("【幂等性拦截】重复提交，key={}", idempotentKey);
                throw new SellException(ResultEnum.REPEAT_REQUEST.getCode(), idempotent.message());
            }
            
            // 设置幂等标记
            redisTemplate.opsForValue().set(idempotentKey, tokenValue, 
                    idempotent.expireTime(), TimeUnit.MILLISECONDS);
            
            // 执行业务方法
            return joinPoint.proceed();
            
        } finally {
            // 释放分布式锁
            redisLock.unlock(lockKey, tokenValue);
        }
    }
    
    private String buildIdempotentKey(String prefix, String openid, String uri) {
        return "idempotent:" + (prefix.isEmpty() ? "" : prefix + ":") + 
               openid + ":" + uri.replace("/", "_");
    }
}
