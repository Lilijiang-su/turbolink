package com.turbolink.bloom;

import com.turbolink.mapper.ShortLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时将存量短码预热进布隆过滤器。
 *
 * <p>布隆过滤器存放在 Redis 中。若 Redis 被清空或重建，所有已存在的短码会被误判为「不存在」，
 * 导致跳转全部 404。因此应用启动时用 id 游标分页把全量短码回填进布隆。
 *
 * <p>用 Redis 中的 {@code WARMED_KEY} 标记避免每次启动重复全表扫描：标记与布隆同生共死——
 * Redis 被清则标记与布隆一起消失，下次启动自然重新预热。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomWarmUpRunner implements ApplicationRunner {

    private static final String WARMED_KEY = "shortlink:bloom:warmed";

    private final ShortLinkMapper shortLinkMapper;
    private final BloomFilterService bloomFilter;
    private final StringRedisTemplate redis;

    @Override
    public void run(ApplicationArguments args) {
        if (Boolean.TRUE.equals(redis.hasKey(WARMED_KEY))) {
            log.info("[布隆预热] 已预热过，跳过");
            return;
        }
        long total = warmUp();
        redis.opsForValue().set(WARMED_KEY, String.valueOf(total));
        log.info("[布隆预热] 完成，回填 {} 个短码", total);
    }

    private long warmUp() {
        long start = System.currentTimeMillis();
        // 一次性全量拉取（无分片键，ShardingSphere 会广播合并）。当前数据量下可接受。
        List<String> codes = shortLinkMapper.selectAllCodes();
        if (codes != null && !codes.isEmpty()) {
            bloomFilter.addAll(codes);
        }
        long total = codes == null ? 0 : codes.size();
        log.info("[布隆预热] 扫描 {} 个短码，耗时 {}ms", total, System.currentTimeMillis() - start);
        return total;
    }
}
