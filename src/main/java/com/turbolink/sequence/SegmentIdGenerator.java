package com.turbolink.sequence;

import com.turbolink.config.TurboLinkProperties;
import com.turbolink.entity.Sequence;
import com.turbolink.mapper.SequenceMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模式发号器。
 *
 * <p>一次从 DB 领取一段 ID 缓存在内存，绝大多数取号零 DB 访问、无锁，
 * 是本项目高吞吐写入的关键。采用「双缓冲」：当前号段用到阈值时异步预取下一段，
 * 号码耗尽时 <b>必定消费预取的号段</b>，从而保证取号严格单调递增。
 *
 * <p>预取失败或未就绪则回退到同步取号，功能不受影响。
 * 号段由 DB 的乐观锁（version）保护，多实例部署天然不重复。
 */
@Slf4j
@Component
public class SegmentIdGenerator {

    private final SequenceMapper sequenceMapper;
    private final String bizType;
    private final int step;
    private final double prefetchThreshold;

    /** 当前使用中的号段 */
    private volatile Segment current;
    /** 预取中的下一号段（异步结果）。赋值与消费都在 switchLock 内，避免竞态重复取号 */
    private volatile CompletableFuture<Segment> next;

    private final Object switchLock = new Object();
    private final ExecutorService prefetchPool;

    public SegmentIdGenerator(SequenceMapper sequenceMapper, TurboLinkProperties props) {
        this.sequenceMapper = sequenceMapper;
        this.bizType = props.getSequence().getBizType();
        this.step = props.getSequence().getStep();
        this.prefetchThreshold = props.getSequence().getPrefetchThreshold();
        this.prefetchPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "segment-prefetch");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        this.current = fetchSegment();
        log.info("[发号器] 初始化，当前号段 [{}, {}]", current.start, current.end);
    }

    /** 取一个全局唯一 ID，严格单调递增。 */
    public long nextId() {
        Segment seg = current;
        long id = seg.cursor.getAndIncrement();
        if (id <= seg.end) {
            maybePrefetch(seg);
            return id;
        }
        return switchSegment();
    }

    /**
     * 当前号段耗尽：在锁内切换到预取号段，无序竞争。
     */
    private long switchSegment() {
        synchronized (switchLock) {
            while (true) {
                // 可能已被持锁线程切换，双重检查
                if (current.cursor.get() > current.end) {
                    current = takeNext();
                    resetPrefetchState();
                    log.info("[发号器] 已切换号段 [{}, {}]", current.start, current.end);
                }
                long id = current.cursor.getAndIncrement();
                if (id <= current.end) {
                    return id;
                }
            }
        }
    }

    /** 消费预取号段；若预取不存在或失败则同步补取 */
    private Segment takeNext() {
        CompletableFuture<Segment> f = next;
        if (f != null) {
            try {
                return f.join();
            } catch (CompletionException e) {
                log.warn("[发号器] 预取号段获取失败，改为同步取号", e.getCause());
            }
        }
        return fetchSegment();
    }

    private void resetPrefetchState() {
        next = null;
    }

    private void maybePrefetch(Segment seg) {
        if (seg.remaining() >= seg.size() * prefetchThreshold) {
            return;
        }
        // 先在锁内判重，确认需要预取后才提交任务，避免"提交后又丢弃"造成过度取号
        synchronized (switchLock) {
            // seg 必须仍是当前段且尚无预取：否则本次预取基于过期状态，直接跳过
            if (seg != current || next != null) {
                return;
            }
            next = CompletableFuture.supplyAsync(this::fetchSegment, prefetchPool);
        }
    }

    /** 从 DB 领取新号段，乐观锁冲突自旋重试 */
    private Segment fetchSegment() {
        while (true) {
            Sequence seq = sequenceMapper.selectByBizType(bizType);
            if (seq == null) {
                throw new IllegalStateException("号段记录不存在: " + bizType);
            }
            long oldMaxId = seq.getMaxId();
            if (sequenceMapper.updateMaxId(bizType, seq.getVersion()) > 0) {
                return new Segment(oldMaxId + 1, oldMaxId + seq.getStep());
            }
            log.debug("[发号器] 乐观锁冲突，重试");
        }
    }

    @PreDestroy
    public void shutdown() {
        prefetchPool.shutdownNow();
    }

    /** 号段：[start, end] 闭区间，cursor 原子自增分配 */
    private static class Segment {
        final long start;
        final long end;
        final AtomicLong cursor;

        Segment(long start, long end) {
            this.start = start;
            this.end = end;
            this.cursor = new AtomicLong(start);
        }

        long remaining() {
            return end - cursor.get() + 1;
        }

        long size() {
            return end - start + 1;
        }
    }
}
