import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压测数据生成器 + 写路径压测。
 * 并发调用 POST /api/shorten 生成短链，收集返回的短码写入文件，
 * 同时输出写路径的 QPS / 延迟（生成过程本身就是写路径压测）。
 *
 * <pre>
 * 用法: java DataGen &lt;apiUrl&gt; &lt;threads&gt; &lt;total&gt; &lt;outFile&gt;
 *   例: java DataGen http://localhost:8080/api/shorten 50 30000 codes.txt
 * </pre>
 */
public class DataGen {

    private static final Pattern SHORT = Pattern.compile("\"shortUrl\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("用法: java DataGen <apiUrl> <threads> <total> <outFile>");
            return;
        }
        String apiUrl = args[0];
        int threads = Integer.parseInt(args[1]);
        int total = Integer.parseInt(args[2]);
        String outFile = args[3];

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();

        AtomicInteger issued = new AtomicInteger();
        List<String> codes = java.util.Collections.synchronizedList(new ArrayList<>(total));
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>(total));
        CountDownLatch done = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        System.out.printf(">>> 生成 %d 条短链，%d 线程...%n", total, threads);
        long t0 = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    while (true) {
                        int i = issued.getAndIncrement();
                        if (i >= total) return;
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(apiUrl))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"longUrl\":\"https://www.example.com/bench/" + i + "\"}"))
                                .build();
                        long ts = System.nanoTime();
                        try {
                            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                            latencies.add((System.nanoTime() - ts) / 1000);
                            Matcher m = SHORT.matcher(resp.body());
                            if (m.find()) {
                                String url = m.group(1);
                                codes.add(url.substring(url.lastIndexOf('/') + 1));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "gen-" + t).start();
        }

        start.countDown();
        done.await();
        double sec = (System.nanoTime() - t0) / 1_000_000_000.0;

        java.nio.file.Files.write(java.nio.file.Path.of(outFile), codes);

        long[] lat = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.println("\n=========== 写路径压测报告 (POST /api/shorten) ===========");
        System.out.printf("生成成功 : %d 条%n", codes.size());
        System.out.printf("耗时     : %.2fs%n", sec);
        System.out.printf("写 QPS   : %.0f%n", codes.size() / sec);
        if (lat.length > 0) {
            System.out.printf("平均延迟 : %d us%n", avg(lat));
            System.out.printf("P50      : %d us%n", pct(lat, 0.50));
            System.out.printf("P99      : %d us%n", pct(lat, 0.99));
            System.out.printf("最大延迟 : %d us%n", lat[lat.length - 1]);
        }
        System.out.printf("短码写入 : %s%n", outFile);
        System.out.println("=========================================================");
    }

    private static long avg(long[] a) {
        long s = 0;
        for (long v : a) s += v;
        return s / a.length;
    }

    private static long pct(long[] a, double p) {
        int i = (int) Math.ceil(p * a.length) - 1;
        return a[Math.max(0, i)];
    }
}
