package com.turbolink.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "turbolink")
public class TurboLinkProperties {

    private String domain = "http://tl.cn/";
    private Sequence sequence = new Sequence();
    private Cache cache = new Cache();
    private Bloom bloom = new Bloom();

    @Data
    public static class Sequence {
        private String bizType = "short_link";
        private int step = 1000;
        /** 剩余号段低于该比例时触发异步预取，0.2 = 20% */
        private double prefetchThreshold = 0.2;
    }

    @Data
    public static class Cache {
        private long localMaxSize = 100_000;
        private long localTtlSeconds = 300;
        private long redisTtlSeconds = 1800;
        private long nullTtlSeconds = 60;
    }

    @Data
    public static class Bloom {
        private long expectedInsertions = 10_000_000L;
        private double falseProbability = 0.001;
    }
}
