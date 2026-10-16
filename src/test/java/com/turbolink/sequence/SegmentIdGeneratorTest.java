package com.turbolink.sequence;

import com.turbolink.config.TurboLinkProperties;
import com.turbolink.entity.Sequence;
import com.turbolink.mapper.SequenceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class SegmentIdGeneratorTest {

    private SegmentIdGenerator generator;
    private final AtomicLong dbMaxId = new AtomicLong(0);
    private final AtomicLong dbVersion = new AtomicLong(0);
    private final Object dbLock = new Object();
    private final AtomicInteger fetchCount = new AtomicInteger();

    /**
     * @param step      号段长度（越小越频繁切换，越能暴露预取竞态）
     * @param dbDelayMs 模拟 DB 延迟（放大预取与切换之间的竞态窗口）
     */
    private void build(int step, long dbDelayMs, boolean singleThread) {
        SequenceMapper mapper = Mockito.mock(SequenceMapper.class);
        dbMaxId.set(0);
        dbVersion.set(0);
        fetchCount.set(0);

        when(mapper.selectByBizType(anyString())).thenAnswer(inv -> {
            Sequence s = new Sequence();
            s.setBizType("short_link");
            s.setMaxId(dbMaxId.get());
            s.setStep(step);
            s.setVersion(dbVersion.get());
            return s;
        });
        // 模拟乐观锁：version 一致才更新，并用延迟放大 fetch 的耗时窗口
        when(mapper.updateMaxId(anyString(), any())).thenAnswer(inv -> {
            Long passedVersion = inv.getArgument(1);
            if (dbDelayMs > 0) {
                try { Thread.sleep(dbDelayMs); } catch (InterruptedException ignored) {}
            }
            synchronized (dbLock) {
                if (!passedVersion.equals(dbVersion.get())) {
                    return 0;
                }
                dbMaxId.addAndGet(step);
                dbVersion.incrementAndGet();
                fetchCount.incrementAndGet();
                return 1;
            }
        });

        TurboLinkProperties props = new TurboLinkProperties();
        props.getSequence().setBizType("short_link");
        props.getSequence().setStep(step);
        props.getSequence().setPrefetchThreshold(0.2);

        generator = new SegmentIdGenerator(mapper, props);
        generator.init();
    }

    @AfterEach
    void tearDown() {
        if (generator != null) {
            generator.shutdown();
        }
    }

    @Test
    @DisplayName("单线程连续取号唯一且递增，跨号段无重复无丢失")
    void sequentialUnique() {
        build(1000, 0, true);
        int total = 5000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        long prev = -1;
        for (int i = 0; i < total; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "重复 ID: " + id);
            assertTrue(id > prev, "未递增: " + id + " <= " + prev);
            prev = id;
        }
        assertEquals(total, ids.size());
    }

    @Test
    @DisplayName("10 线程并发各取 1000 个号，全局无重复")
    void concurrentUnique() throws InterruptedException {
        build(1000, 0, false);
        Set<Long> ids = runConcurrent(10, 1000);
        assertEquals(10_000, ids.size(), "存在重复 ID");
    }

    @Test
    @DisplayName("高切换压力：小号段 + DB延迟，多线程取号仍全局无重复（回归号段复用竞态）")
    void concurrentUniqueUnderHeavySwitch() throws InterruptedException {
        // step=100 且每次 fetch 延迟 3ms，制造大量号段切换与预取竞态
        build(100, 3, false);
        int threads = 16;
        int perThread = 500;
        Set<Long> ids = runConcurrent(threads, perThread);

        assertEquals(threads * perThread, ids.size(), "存在重复 ID（号段被复用）");
        // fetch 次数应约等于 总取号数/step，允许预取导致的少量额外段，但不应出现翻倍(复用)
        int segmentsNeeded = (threads * perThread) / 100;
        assertTrue(fetchCount.get() >= segmentsNeeded,
                "取号段数不足: " + fetchCount.get());
        assertTrue(fetchCount.get() <= segmentsNeeded + threads + 10,
                "取号段数异常偏多(可能重复取段): " + fetchCount.get());
    }

    private Set<Long> runConcurrent(int threads, int perThread) throws InterruptedException {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发取号超时");
        pool.shutdownNow();
        return ids;
    }
}
