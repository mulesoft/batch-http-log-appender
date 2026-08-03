package com.mulesoft.support.batchhttp.demo;

import com.mulesoft.support.batchhttp.core.BatchConfig;
import com.mulesoft.support.batchhttp.core.BatchingLogShipper;
import com.mulesoft.support.batchhttp.core.LogRecord;
import com.mulesoft.support.batchhttp.core.sink.GenericNdjsonSink;
import com.mulesoft.support.batchhttp.core.sink.LogSink;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Head-to-head micro-benchmark against a local mock intake that simulates a
 * realistic per-request network latency. Demonstrates why batching wins:
 *
 *   - "naive"   : one synchronous HTTP request per event (what the stock
 *                 Log4j2 HttpAppender does).
 *   - "batched" : events enqueued non-blocking, shipped in batches by the engine.
 *
 * Run: cd demo/java/benchmark && mvn exec:java
 */
public final class BenchmarkDemo {

    private static final int EVENTS = 5000;
    private static final int SIMULATED_LATENCY_MS = 5; // per request, server-side

    public static void main(String[] args) throws Exception {
        AtomicInteger requests = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes(); // consume
            }
            requests.incrementAndGet();
            try {
                Thread.sleep(SIMULATED_LATENCY_MS); // simulate network + intake latency
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();

        String url = "http://127.0.0.1:" + port + "/intake";
        System.out.println("Mock intake at " + url + " (simulated " + SIMULATED_LATENCY_MS + "ms/request)\n");

        // ---- 1) Naive: one request per event, synchronous on the caller ----
        requests.set(0);
        long naiveStart = System.nanoTime();
        runNaive(url);
        long naiveMs = (System.nanoTime() - naiveStart) / 1_000_000L;
        int naiveRequests = requests.get();

        // ---- 2) Batched engine ----
        requests.set(0);
        LogSink sink = GenericNdjsonSink.builder().url(url).maxRecords(500).build();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(500)
                .lingerTime(Duration.ofMillis(200))
                .gzip(true)
                .build();

        long batchStart = System.nanoTime();
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink, config)) {
            for (int i = 0; i < EVENTS; i++) {
                shipper.offer(LogRecord.builder()
                        .timestampMillis(1700000000000L + i)
                        .level("INFO").loggerName("demo").threadName("main")
                        .message("order processed id=" + i + " status=OK latency_ms=" + (i % 50))
                        .attribute("env", "prod")
                        .build());
            }
            // close() drains and flushes the tail
        }
        long batchMs = (System.nanoTime() - batchStart) / 1_000_000L;
        int batchRequests = requests.get();

        server.stop(0);

        System.out.println("=== Results for " + EVENTS + " events ===");
        System.out.printf("naive    : %5d ms  across %5d HTTP requests%n", naiveMs, naiveRequests);
        System.out.printf("batched  : %5d ms  across %5d HTTP requests%n", batchMs, batchRequests);
        System.out.printf("speedup  : %.1fx wall-clock,  %.0fx fewer requests%n",
                naiveMs / (double) Math.max(1, batchMs),
                naiveRequests / (double) Math.max(1, batchRequests));
        System.out.println("\nNote: the naive caller blocks on every request; the batched engine");
        System.out.println("returns to the application immediately and ships in the background.");
    }

    /** Mimics the stock HttpAppender: one blocking POST per event on the calling thread. */
    private static void runNaive(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create(url);
        for (int i = 0; i < EVENTS; i++) {
            String body = "{\"message\":\"order processed id=" + i + "\",\"level\":\"INFO\"}\n";
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        }
    }
}
