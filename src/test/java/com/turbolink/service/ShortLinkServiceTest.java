package com.turbolink.service;

import com.turbolink.bloom.BloomFilterService;
import com.turbolink.cache.LocalCacheManager;
import com.turbolink.config.TurboLinkProperties;
import com.turbolink.constant.CacheKeys;
import com.turbolink.entity.ShortLink;
import com.turbolink.mapper.ShortLinkMapper;
import com.turbolink.migration.MigrationCoordinator;
import com.turbolink.migration.MigrationTargetDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShortLinkServiceTest {

    private ShortLinkMapper mapper;
    private LocalCacheManager localCache;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private BloomFilterService bloom;
    private RedissonClient redisson;
    private RLock lock;
    private TurboLinkProperties props;
    private DomainBlacklistService blacklistService;
    private ShortLinkService service;

    private static final String CODE = "aB3xK9";
    private static final String URL = "https://example.com/very/long/path";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(ShortLinkMapper.class);
        bloom = mock(BloomFilterService.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        // 真实 Caffeine 本地缓存
        props = new TurboLinkProperties();
        localCache = new LocalCacheManager(props);
        localCache.init();

        // Redisson 锁默认可获取
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException ignored) {
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        MigrationCoordinator migrationCoordinator = new MigrationCoordinator();
        MigrationTargetDataSource migrationTarget = mock(MigrationTargetDataSource.class);
        blacklistService = mock(DomainBlacklistService.class);
        // 默认不拦截
        when(blacklistService.isBlocked(anyString())).thenReturn(false);

        service = new ShortLinkService(mapper, localCache, redis, bloom, redisson, props,
                migrationCoordinator, migrationTarget, blacklistService);
    }

    @Test
    @DisplayName("场景1：本地缓存命中，直接返回，不碰 Redis")
    void hitLocalCache() {
        localCache.put(CODE, URL);

        assertEquals(URL, service.redirect(CODE));
        verify(redis, never()).opsForValue();
        verify(bloom, never()).mightContain(anyString());
    }

    @Test
    @DisplayName("场景2：本地未命中、Redis 命中 → 回填本地缓存")
    void hitRedisCache() {
        when(valueOps.get(CacheKeys.url(CODE))).thenReturn(URL);

        assertEquals(URL, service.redirect(CODE));
        // 回填本地后二次访问应命中本地
        assertEquals(URL, service.redirect(CODE));
        verify(valueOps, times(1)).get(CacheKeys.url(CODE));
    }

    @Test
    @DisplayName("场景3：布隆过滤器判定不存在 → 直接返回 null，保护 DB")
    void blockedByBloomFilter() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(bloom.mightContain(CODE)).thenReturn(false);

        assertNull(service.redirect(CODE));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("场景4：缓存未命中但 DB 命中 → 返回并回填缓存")
    void loadFromDb() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(bloom.mightContain(CODE)).thenReturn(true);
        ShortLink link = new ShortLink();
        link.setShortCode(CODE);
        link.setFullUrl(URL);
        link.setStatus(0);
        link.setDelFlag(0);
        when(mapper.selectOne(any())).thenReturn(link);

        assertEquals(URL, service.redirect(CODE));
        verify(valueOps).set(eq(CacheKeys.url(CODE)), eq(URL), anyLong(), any(TimeUnit.class));
        verify(lock).unlock();
    }

    @Test
    @DisplayName("场景5：DB 也没有 → 返回 null 且写空值缓存（防穿透兜底）")
    void dbMissWritesNullCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(bloom.mightContain(CODE)).thenReturn(true); // 布隆误判为可能存在
        when(mapper.selectOne(any())).thenReturn(null);

        assertNull(service.redirect(CODE));
        // 空值缓存：value 为空串
        verify(valueOps).set(eq(CacheKeys.url(CODE)), eq(""), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("场景6：空值缓存命中 → 返回 null")
    void hitNullCache() {
        when(valueOps.get(CacheKeys.url(CODE))).thenReturn("");

        assertNull(service.redirect(CODE));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("场景7：生成短链 → 落库 + 写布隆 + 预热缓存")
    void createShortLink() {
        long id = 123456789L;
        String shortUrl = service.create(id, URL);

        assertTrue(shortUrl.startsWith(props.getDomain()));
        verify(mapper).insert(any(ShortLink.class));
        verify(bloom).add(anyString());
        verify(valueOps).set(anyString(), eq(URL), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("场景8：域名黑名单拦截 → 抛 403，不落库")
    void blockedDomain() {
        when(blacklistService.isBlocked(URL)).thenReturn(true);
        com.turbolink.exception.ShortLinkException e = assertThrows(
                com.turbolink.exception.ShortLinkException.class,
                () -> service.create(123L, URL));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, e.getStatus());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("场景9：自定义短码生成 → 用自定义码落库")
    void customCode() {
        when(mapper.selectCount(any())).thenReturn(0L);
        long id = 100L;
        String shortUrl = service.create(id, URL, "promo2024");

        assertTrue(shortUrl.endsWith("promo2024"));
        verify(mapper).insert(argThat(l -> "promo2024".equals(l.getShortCode())));
    }

    @Test
    @DisplayName("场景10：自定义短码被占用 → 抛 409")
    void customCodeOccupied() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThrows(com.turbolink.exception.ShortLinkException.class,
                () -> service.create(100L, URL, "promo2024"));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("场景11：自定义短码格式非法 → 抛 400")
    void customCodeInvalid() {
        assertThrows(com.turbolink.exception.ShortLinkException.class,
                () -> service.create(100L, URL, "a!"));
        verify(mapper, never()).insert(any());
    }
}
