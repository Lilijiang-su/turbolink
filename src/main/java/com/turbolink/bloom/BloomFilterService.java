package com.turbolink.bloom;

import com.turbolink.config.TurboLinkProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 基于 Redisson 的分布式布隆过滤器。
 *
 * <p>作用：在缓存之前拦掉「一定不存在」的短码请求，防止无效短码击穿到 DB。
 * 特性：判定「不存在」是 100% 准确的；判定「存在」有极低误判率（配置为 0.1%），
 * 误判只是让少部分无效请求多走一次 DB，不影响正确性。
 *
 * <p>注意：布隆过滤器不能删除元素，因此短链删除后应依赖缓存空值兜底，
 * 而不是从布隆中移除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    private static final String BLOOM_KEY = "shortlink:bloom";

    private final RedissonClient redissonClient;
    private final TurboLinkProperties props;

    private RBloomFilter<String> bloomFilter;

    @PostConstruct
    public void init() {
        bloomFilter = redissonClient.getBloomFilter(BLOOM_KEY);
        bloomFilter.tryInit(
                props.getBloom().getExpectedInsertions(),
                props.getBloom().getFalseProbability());
        log.info("[布隆过滤器] 初始化完成，预计容量={}, 误判率={}",
                props.getBloom().getExpectedInsertions(),
                props.getBloom().getFalseProbability());
    }

    /** 新增短码时写入 */
    public void add(String shortCode) {
        bloomFilter.add(shortCode);
    }

    /** 批量写入（预热用） */
    public void addAll(java.util.Collection<String> codes) {
        if (codes != null && !codes.isEmpty()) {
            bloomFilter.add(codes);
        }
    }

    /**
     * 判断短码是否可能存在。
     * 返回 false 表示一定不存在，可直接拦截；返回 true 表示可能存在，需继续查缓存/DB。
     */
    public boolean mightContain(String shortCode) {
        return bloomFilter.contains(shortCode);
    }
}
