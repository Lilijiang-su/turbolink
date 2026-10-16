package com.turbolink.service;

import com.turbolink.bloom.BloomFilterService;
import com.turbolink.cache.LocalCacheManager;
import com.turbolink.config.TurboLinkProperties;
import com.turbolink.constant.CacheKeys;
import com.turbolink.entity.ShortLink;
import com.turbolink.exception.ShortLinkException;
import com.turbolink.mapper.ShortLinkMapper;
import com.turbolink.migration.MigrationCoordinator;
import com.turbolink.migration.MigrationTargetDataSource;
import com.turbolink.util.Base62Util;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 短链核心服务：生成 + 跳转。
 *
 * <p>读路径是本项目性能核心，按「本地缓存 → Redis → 布隆过滤器 → 互斥锁查库」逐级下沉，
 * 分别解决命中率、穿透、击穿三类问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private final ShortLinkMapper shortLinkMapper;
    private final LocalCacheManager localCache;
    private final StringRedisTemplate redis;
    private final BloomFilterService bloomFilter;
    private final RedissonClient redissonClient;
    private final TurboLinkProperties props;
    private final MigrationCoordinator migrationCoordinator;
    private final MigrationTargetDataSource migrationTargetDataSource;
    private final DomainBlacklistService domainBlacklistService;

    // ---------------------------------------------------------------- 跳转读路径

    /**
     * 短码 -> 长链。
     *
     * @return 长链 URL；不存在返回 null（调用方转 404）
     */
    public String redirect(String shortCode) {
        // ① 本地缓存
        String local = localCache.get(shortCode);
        if (local != null) {
            return emptyToNull(local);
        }

        // ② Redis 缓存（含空值缓存）
        String cached = redis.opsForValue().get(CacheKeys.url(shortCode));
        if (cached != null) {
            localCache.put(shortCode, cached);
            return emptyToNull(cached);
        }

        // ③ 布隆过滤器：判定不存在则直接拦截，保护 DB
        if (!bloomFilter.mightContain(shortCode)) {
            localCache.put(shortCode, CacheKeys.NULL_PLACEHOLDER);
            return null;
        }

        // ④ 缓存击穿互斥：只锁同一个短码，其它短码互不影响
        return loadFromDbWithLock(shortCode);
    }

    private String loadFromDbWithLock(String shortCode) {
        String lockKey = CacheKeys.lock(shortCode);
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 未拿到锁说明已有线程在回源，短暂等待后可命中其回填的缓存
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                log.warn("[跳转] 获取锁超时: {}", shortCode);
                return null;
            }
            // 双重检查：拿到锁后再看一眼 Redis，可能已被前一个持锁线程回填
            String again = redis.opsForValue().get(CacheKeys.url(shortCode));
            if (again != null) {
                return emptyToNull(again);
            }
            return queryAndBackfill(shortCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String queryAndBackfill(String shortCode) {
        // 迁移完成读新库：读流量切到扩容后的 4 库 16 片
        if (migrationCoordinator.isReadNew()) {
            String url = migrationTargetDataSource.queryFullUrl(shortCode);
            if (url != null) {
                writeCaches(shortCode, url, false);
                return url;
            }
            writeCaches(shortCode, CacheKeys.NULL_PLACEHOLDER, true);
            return null;
        }

        ShortLink link = shortLinkMapper.selectOne(
                Wrappers.<ShortLink>lambdaQuery()
                        .eq(ShortLink::getShortCode, shortCode)
                        .eq(ShortLink::getDelFlag, 0)
                        .eq(ShortLink::getStatus, 0));

        if (link != null && !isExpired(link)) {
            String url = link.getFullUrl();
            writeCaches(shortCode, url, false);
            return url;
        }
        // DB 也不存在（或已过期/禁用）：写空值缓存兜底，防止反复穿透
        writeCaches(shortCode, CacheKeys.NULL_PLACEHOLDER, true);
        return null;
    }

    // ---------------------------------------------------------------- 生成写路径

    /**
     * 生成短链（系统发号）。
     *
     * @param id      号段发号器生成的全局唯一 ID
     * @param longUrl 原始长链
     */
    public String create(Long id, String longUrl) {
        return create(id, longUrl, null);
    }

    /**
     * 生成短链，可指定自定义短码。
     *
     * @param id         号段发号器生成的全局唯一 ID
     * @param longUrl    原始长链
     * @param customCode 自定义短码（可空，为空则用系统生成的短码）
     */
    public String create(Long id, String longUrl, String customCode) {
        // ① 域名黑名单校验（生成前拦截，避免产生恶意短链）
        if (domainBlacklistService.isBlocked(longUrl)) {
            throw ShortLinkException.blockedDomain(extractDomain(longUrl));
        }

        // ② 确定短码：自定义优先，否则系统发号
        String shortCode;
        if (customCode != null && !customCode.isBlank()) {
            validateCustomCode(customCode);
            ensureCustomCodeAvailable(customCode);
            shortCode = customCode.trim();
        } else {
            shortCode = Base62Util.encode(id);
        }

        ShortLink link = new ShortLink();
        link.setId(id);
        link.setShortCode(shortCode);
        link.setFullUrl(longUrl);
        link.setGid("default");
        link.setUserId(0L);
        link.setOriginCode(customCode != null && !customCode.isBlank() ? shortCode : null);
        link.setStatus(0);
        link.setDelFlag(0);
        shortLinkMapper.insert(link);

        // 迁移双写：开启双写窗口时，同时写入扩容目标库（4库16片）。
        // 双写是旁路，失败只记日志，绝不影响主库写入结果。
        if (migrationCoordinator.isDualWrite()) {
            try {
                migrationTargetDataSource.insertShortLink(id, shortCode, longUrl);
            } catch (Exception e) {
                log.warn("[迁移] 双写失败(主库已写入，忽略): {}", shortCode);
            }
        }

        // 同步写布隆 + 预热缓存，避免刚生成就击穿
        bloomFilter.add(shortCode);
        writeCaches(shortCode, longUrl, false);

        return props.getDomain() + shortCode;
    }

    /** 自定义短码格式校验：4-16 位字母数字 */
    private void validateCustomCode(String code) {
        String trimmed = code.trim();
        if (trimmed.length() < 4 || trimmed.length() > 16
                || !trimmed.matches("[0-9a-zA-Z]+")) {
            throw ShortLinkException.invalidCustomCode(code);
        }
    }

    /** 自定义短码唯一性校验（含系统已生成的短码冲突） */
    private void ensureCustomCodeAvailable(String code) {
        Long exists = shortLinkMapper.selectCount(
                Wrappers.<ShortLink>lambdaQuery()
                        .eq(ShortLink::getShortCode, code)
                        .eq(ShortLink::getDelFlag, 0));
        if (exists != null && exists > 0) {
            throw ShortLinkException.codeOccupied(code);
        }
    }

    private String extractDomain(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private void writeCaches(String shortCode, String value, boolean isNull) {
        long ttl = isNull
                ? props.getCache().getNullTtlSeconds()
                : withJitter(props.getCache().getRedisTtlSeconds());

        redis.opsForValue().set(CacheKeys.url(shortCode), value, ttl, TimeUnit.SECONDS);
        localCache.put(shortCode, value);
    }

    /** TTL 加 ±10% 随机抖动，避免大规模 key 同时失效导致雪崩 */
    private long withJitter(long baseTtlSeconds) {
        long jitter = (long) (baseTtlSeconds * (Math.random() * 0.2 - 0.1));
        return baseTtlSeconds + jitter;
    }

    private boolean isExpired(ShortLink link) {
        return link.getExpireTime() != null
                && link.getExpireTime().isBefore(java.time.LocalDateTime.now());
    }

    private String emptyToNull(String v) {
        return v.isEmpty() ? null : v;
    }
}
