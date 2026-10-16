package com.turbolink.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.turbolink.config.TurboLinkProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 一级缓存：Caffeine 本地缓存。
 * 抗热点 key，减少 Redis 网络往返；单机内存级，命中后纳秒返回。
 *
 * <p>用空字符串表示「已确认不存在」，与未命中（null）区分：
 * 避免无效短码在本地反复回源。
 */
@Component
@RequiredArgsConstructor
public class LocalCacheManager {

    private final TurboLinkProperties props;
    private Cache<String, String> cache;

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.getCache().getLocalMaxSize())
                .expireAfterWrite(Duration.ofSeconds(props.getCache().getLocalTtlSeconds()))
                .build();
    }

    public String get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, String value) {
        cache.put(key, value);
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }
}
