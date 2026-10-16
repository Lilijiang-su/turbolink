import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 零依赖 HTTP 压测工具（仅用 JDK 自带 API，无需 JMeter/wrk）。
 *
 * <pre>
 * 编译: javac LoadTester.java
 * 运行: java LoadTester &lt;url&gt; &lt;mode&gt; &lt;threads&gt; &lt;durationSec&gt; [warmupSec]
 *   url       目标地址, 如 http://localhost:8080/1
 *   mode      get | post   (post 需 url 指向 /api/shorten)
 *   例:  java LoadTester http://localhost:8080/1 get 50 30 5
 * </pre>
 *
 * <p>输出: 总请求数 / 成功 / 失败 / QPS / 平均 / P50 / P90 / P95 / P99 / P999 / 最大延迟。
 */
public class LoadTester {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("用法: java LoadTester <url|@file> <get|post|getlist> <threads> <durationSec> [warmupSec]");
            System.out.println("  getlist 模式: 第一个参数为文件路径, 每行一个完整URL, 所有线程共享游标各读一次(强制冷读)");
            return;
        }
        String url = args[0];
        String mode = args[1].toLowerCase();
        int threads = Integer.parseInt(args[2]);
        int durationSec = Integer.parseInt(args[3]);
        int warmupSec = args.length >= 5 ? Integer.parseInt(args[4]) : 3;

        System.out.printf("目标: %s%n模式: %s  线程: %d  压测: %ds  预热: %ds%n%n",
                url, mode, threads, durationSec, warmupSec);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String[] urlList = null;
        if (mode.equals("getlist")) {
            urlList = java.nio.file.Files.readAllLines(java.nio.file.Path.of(url)).stream()
                    .filter(l -> !l.isBlank()).toArray(String[]::new);
            System.out.printf(">>> 载入 %d 个 URL，将各读取一次（冷读）%n", urlList.length);
        } else {
            System.out.println(">>> 预热中...");
            run(client, url, mode, Math.max(4, threads / 4), warmupSec, null);
        }

        System.out.println(">>> 正式压测中...");
        Stats stats = run(client, url, mode, threads, durationSec, urlList);
        printReport(stats);
    }

    private static Stats run(HttpClient client, String url, String mode,
                             int threads, int durationSec, String[] urlList) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long deadline = System.currentTimeMillis() + durationSec * 1000L;

        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        AtomicLong cursor = new AtomicLong(); // getlist 模式共享游标
        List<long[]> perThread = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final HttpRequest fixedRequest = mode.equals("getlist") ? null : buildRequest(url, mode);
            new Thread(() -> {
                List<Long> latencies = new ArrayList<>(1 << 16);
                ready.countDown();
                try {
                    start.await();
                    while (System.currentTimeMillis() < deadline) {
                        HttpRequest request = fixedRequest;
                        if (mode.equals("getlist")) {
                            long i = cursor.getAndIncrement();
                            if (i >= urlList.length) break; // 每个 URL 只读一次，读完即止
                            request = HttpRequest.newBuilder().uri(URI.create(urlList[(int) i]))
                                    .timeout(Duration.ofSeconds(10)).GET().build();
                        }
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
                            long us = (System.nanoTime() - t0) / 1000;
                            latencies.add(us);
                            int code = resp.statusCode();
                            if (code >= 200 && code < 400) {
                                success.incrementAndGet();
                            } else {
                                fail.incrementAndGet();
                            }
                        } catch (Exception e) {
                            fail.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long[] arr = latencies.stream().mapToLong(Long::longValue).toArray();
                perThread.add(arr);
                done.countDown();
            }, "load-" + t).start();
        }

        ready.await();
        long wallStart = System.nanoTime();
        start.countDown();
        done.await();
        long wallNs = System.nanoTime() - wallStart;

        long[] all = perThread.stream().flatMapToLong(Arrays::stream).toArray();
        Arrays.sort(all);
        Stats s = new Stats();
        s.total = all.length;
        s.success = success.get();
        s.fail = fail.get();
        s.wallSeconds = wallNs / 1_000_000_000.0;
        s.latencies = all;
        return s;
    }

    private static HttpRequest buildRequest(String url, String mode) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10));
        if ("post".equals(mode)) {
            b.header("Content-Type", "application/json")
             .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"https://example.com/load-test\"}"));
        } else {
            b.GET();
        }
        return b.build();
    }

    private static void printReport(Stats s) {
        System.out.println("\n================ 压测报告 ================");
        System.out.printf("总请求数 : %d%n", s.total);
        System.out.printf("成功     : %d%n", s.success);
        System.out.printf("失败     : %d%n", s.fail);
        double qps = s.total / s.wallSeconds;
        System.out.printf("QPS      : %.0f  (实际耗时 %.2fs)%n", qps, s.wallSeconds);
        if (s.total == 0) { System.out.println("无有效数据"); return; }
        System.out.printf("平均延迟 : %d us%n", avg(s.latencies));
        System.out.printf("P50      : %d us%n", pct(s.latencies, 0.50));
        System.out.printf("P90      : %d us%n", pct(s.latencies, 0.90));
        System.out.printf("P95      : %d us%n", pct(s.latencies, 0.95));
        System.out.printf("P99      : %d us%n", pct(s.latencies, 0.99));
        System.out.printf("P999     : %d us%n", pct(s.latencies, 0.999));
        System.out.printf("最大延迟 : %d us%n", s.latencies[s.latencies.length - 1]);
        System.out.printf("成功率   : %.2f%%%n", 100.0 * s.success / s.total);
        System.out.println("==========================================");
    }

    private static long avg(long[] a) {
        long sum = 0;
        for (long v : a) sum += v;
        return sum / a.length;
    }

    private static long pct(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        return sorted[idx];
    }

    static class Stats {
        long total, success, fail;
        double wallSeconds;
        long[] latencies;
    }
}
