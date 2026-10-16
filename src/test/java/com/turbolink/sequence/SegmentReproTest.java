package com.turbolink.sequence;

import com.turbolink.config.TurboLinkProperties;
import com.turbolink.entity.Sequence;
import com.turbolink.mapper.SequenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 用「生产参数」复现号段复用：
 * step=1000、阈值0.2、50线程、DB 延迟约 10ms。
 */
class SegmentReproTest {

    @Test
    @DisplayName("生产参数复现：50线程取2万号，检查是否出现重复号段")
    void reproduce() throws Exception {
        AtomicLong dbMaxId = new AtomicLong(0);
        AtomicLong dbVersion = new AtomicLong(0);
        Object dbLock = new Object();
        SequenceMapper mapper = Mockito.mock(SequenceMapper.class);

        when(mapper.selectByBizType(anyString())).thenAnswer(inv -> {
            Sequence s = new Sequence();
            s.setBizType("short_link");
            s.setMaxId(dbMaxId.get());
            s.setStep(1000);
            s.setVersion(dbVersion.get());
            return s;
        });
        when(mapper.updateMaxId(anyString(), any())).thenAnswer(inv -> {
            Long v = inv.getArgument(1);
            try { Thread.sleep(10); } catch (InterruptedException ignored) {} // 模拟 DB 延迟
            synchronized (dbLock) {
                if (!v.equals(dbVersion.get())) return 0;
                dbMaxId.addAndGet(1000);
                dbVersion.incrementAndGet();
                return 1;
            }
        });

        TurboLinkProperties props = new TurboLinkProperties();
        props.getSequence().setBizType("short_link");
        props.getSequence().setStep(1000);
        props.getSequence().setPrefetchThreshold(0.2);

        SegmentIdGenerator gen = new SegmentIdGenerator(mapper, props);
        gen.init();

        int threads = 50, perThread = 400; // 共 20000 号
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) ids.add(gen.nextId());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        gen.shutdown();

        int expected = threads * perThread;
        System.out.printf(">>> 取号 %d 个，唯一 %d 个，重复 %d 个，DB段数=%d%n",
                expected, ids.size(), expected - ids.size(), dbVersion.get());
        assertEquals(expected, ids.size(), "出现重复 ID！DB段数=" + dbVersion.get());
    }
}
