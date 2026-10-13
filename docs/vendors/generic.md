# Generic HTTP endpoint

With `vendor="generic"` the appender POSTs batches of log events as **newline-delimited JSON**
(`Content-Type: application/x-ndjson`, one flat JSON object per line) to the exact
[`url`](../configuration.md#opt-url) you configure: an in-house collector or a log-shipper HTTP
input. The appender adds no path, no vendor-specific fields and no vendor-specific headers.

Authentication is optional: when [`apiKey`](../configuration.md#opt-apikey) is set, it is sent as
`Authorization: Bearer <apiKey>`. Generic is also the **fallback** sink, so a missing `vendor`
attribute or an unrecognized value (a typo such as `datdog`) builds this sink too.

> [!NOTE]
> **Sample Mule project: none yet.** The A/B pairs under [`demo/mule`](../../demo/mule) cover
> Datadog, New Relic, Elasticsearch and Dynatrace. The reference config is the `generic` appender
> in the Java example
> [`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml). The
> same `<BatchHttp vendor="generic">` element works in a Mule app's `log4j2.xml` once the token
> lookup matches how the runtime injects secrets; see [Mule application](#mule-application).

| At a glance | Generic HTTP endpoint |
| --- | --- |
| Endpoint | The exact `url` you configure; no path is appended |
| Authentication | Optional: [`apiKey`](../configuration.md#opt-apikey), sent as `Authorization: Bearer` |
| Required options | [`url`](../configuration.md#opt-url) |
| Sample Mule project | None yet; see the [Java example config](../../demo/java/benchmark/src/main/resources/log4j2-example.xml) |
| Benchmarked on CloudHub | No (local mock-intake benchmark only) |

On this page:

- [Set up the generic HTTP endpoint](#set-up-the-generic-http-endpoint)
- [Configuration](#configuration)
- [What gets sent](#what-gets-sent)
- [Verify delivery](#verify-delivery)
- [Benchmark on CloudHub](#benchmark-on-cloudhub)
- [Troubleshooting](#troubleshooting)

<a id="set-up-your-endpoint"></a>
## Set up the generic HTTP endpoint

There is no vendor account to create. You need:

1. **An HTTP(S) endpoint that accepts a POST of NDJSON** and answers `2xx` on success. Ideally it
   also accepts `Content-Encoding: gzip` request bodies; if it does not, set
   [`gzip`](../configuration.md#opt-gzip) to `false`.
2. **A bearer token, if the endpoint is authenticated.** Supply it as
   [`apiKey`](../configuration.md#opt-apikey) through a `${env:...}` or `${sys:...}` lookup. From
   `log4j2.xml` the only auth the appender can send is `Authorization: Bearer`. Any other scheme,
   or extra headers such as a tenant id, needs the programmatic builder (see
   [Builder-only options](#builder-only-options)).
3. **A trusted certificate, if the endpoint is TLS with a private CA.** Either add the CA to the
   JVM trust store, or use the insecure escape hatches in
   [Self-signed or internal collector](#self-signed-or-internal-collector).

The receiver decides what the appender does with a failed batch, so its status codes matter
(details in [Which responses retry or drop](#which-responses-retry-or-drop)):

| Situation at the receiver | Answer with | What the appender does |
| --- | --- | --- |
| Transient trouble | `408`, `429` or `5xx` (with `Retry-After` if it rate-limits) | Retries, then spills to disk |
| Permanent rejection | Any other `4xx` (only for this case) | Drops the batch at once |

The receiver must also map the flat fields itself (see [Field mapping](#field-mapping)).

> [!NOTE]
> **Loki and other push APIs with their own schema.** The Java example config
> ([`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml))
> mentions "Loki HTTP". Loki's native push API (`/loki/api/v1/push`) expects its own `streams`
> JSON (or protobuf), not these NDJSON lines. Put an NDJSON-accepting collector in front of Loki,
> or check the receiver's documentation before relying on direct ingestion.

### Self-signed or internal collector

If the collector's certificate is self-signed or issued by a private CA, the better fix is to
import that CA into the JVM trust store. As an escape hatch on a network you control:

- [`trustAllCertificates`](../configuration.md#opt-trustallcertificates) accepts any certificate,
  for this appender only.
- [`verifyHostName`](../configuration.md#opt-verifyhostname) `false` skips the hostname check.

```xml
<BatchHttp name="internal" vendor="generic"
           url="https://collector.internal.example.com/ingest"
           apiKey="${env:INTERNAL_LOG_TOKEN}"
           trustAllCertificates="true" verifyHostName="false">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

> [!WARNING]
> Both flags are insecure by design: they remove protection against a man-in-the-middle, and the
> appender logs a startup WARN when either is on. The JDK HttpClient can disable hostname
> verification only through a JVM-wide system property, so `verifyHostName="false"` affects
> **every HttpClient in the JVM**. On Mule, set it as the JVM argument
> `-M-Djdk.internal.httpclient.disableHostnameVerification=true` instead.

[`verifyHostName` in the configuration reference](../configuration.md#opt-verifyhostname) and
[Troubleshooting](../troubleshooting.md) have the details.

## Configuration

### Java application

The minimal config is a `url`. Add `apiKey` only if the collector needs a bearer token; leave the
attribute off entirely for an unauthenticated collector.

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <!-- Any HTTP intake that accepts newline-delimited JSON. apiKey is optional:
             when set it is sent as an "Authorization: Bearer" header; omit the
             attribute entirely for an unauthenticated collector. -->
        <BatchHttp name="collector" vendor="generic"
                   url="https://logs.internal.example.com/ingest"
                   apiKey="${env:INTERNAL_LOG_TOKEN}">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="collector"/>
        </Root>
    </Loggers>
</Configuration>
```

> [!IMPORTANT]
> Set `vendor="generic"` explicitly even though it is the fallback, so the intent is clear to the
> next reader. It does not guard against a typo elsewhere: the appender cannot tell an intended
> generic appender from a misspelled vendor, and logs no warning (see
> [Troubleshooting](#troubleshooting)).

### Mule application

There is no generic sample Mule project. Start from the `log4j2.xml` of any batch demo, for
example
[`demo/mule/datadog-batch-http`](../../demo/mule/datadog-batch-http/src/main/resources/log4j2.xml),
and swap its `<BatchHttp>` attributes for the generic ones. Two rules apply in a Mule app:

- **Only Log4j2 lookups resolve in `log4j2.xml`.** Mule `${...}` placeholders and `secure::`
  properties do not, so pass the token as a JVM system property or an environment variable. On
  CloudHub 1.0, Runtime Manager application properties arrive as system properties, so a
  `${env:...}`-only lookup does not see them.
- `<Configuration packages="com.mulesoft.support.batchhttp.log4j2">` is required.

The token attribute for a Mule app typically looks like this. `MISSING_TOKEN` makes an unset
secret visible as a 401 from an authenticated collector, instead of the literal `${env:...}` text
being sent:

```xml
<BatchHttp name="collector" vendor="generic"
           url="https://logs.internal.example.com/ingest"
           apiKey="${sys:collector.token:-${env:INTERNAL_LOG_TOKEN:-MISSING_TOKEN}}">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

[Using it from a Mule app](../mule.md) covers the dependency and how to inject secrets on each
runtime.

### Recommended production settings

```xml
<BatchHttp name="collector" vendor="generic"
           url="https://logs.internal.example.com/ingest"
           apiKey="${env:INTERNAL_LOG_TOKEN}"
           maxBatchRecords="500"
           queueCapacity="20000"
           maxRetries="5"
           maxRetryBackoffMillis="30000"
           spillEnabled="true"
           spillDirectory="/var/lib/myapp/batch-http-spill/collector"
           diagnosticsLevel="INFO"
           statsIntervalMillis="60000">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

Why these values:

- [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) `500` uses the generic sink's full
  per-request ceiling, so a busy app sends fewer, fuller requests. Values above 500 have no effect
  for this sink. Lower it if the collector's body limit answers `413`.
- [`queueCapacity`](../configuration.md#opt-queuecapacity) `20000` gives an in-house collector more
  headroom for a slow spell before [`overflowPolicy`](../configuration.md#opt-overflowpolicy)
  applies. Each queued event costs heap, so size it to your burst, not to infinity.
- [`maxRetries`](../configuration.md#opt-maxretries) `5` gives about 6–7 s of exponential backoff
  with the default
  [`initialRetryBackoffMillis`](../configuration.md#opt-initialretrybackoffmillis) before a batch
  goes to disk: enough for a blip, not a restart.
- [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis) `30000` only lengthens a
  `Retry-After` wait from the collector, which is honoured up to this cap. To ride out a longer
  restart in memory, raise `initialRetryBackoffMillis` or `maxRetries`, or rely on the spill
  buffer.
- [`spillEnabled`](../configuration.md#opt-spillenabled) with an explicit
  [`spillDirectory`](../configuration.md#opt-spilldirectory): the default spill directory is
  derived from the sink name, which embeds the `url` host (`Generic_<host>_`). With an explicit
  path, a host change cannot strand the backlog, and it can point at persistent, writable storage
  instead of `java.io.tmpdir`. See [Resilience](../resilience.md).
- [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` with
  [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) `60000` prints a
  once-a-minute delivery heartbeat, provided `<Configuration status>` is also `INFO` or finer (see
  [Verify delivery](#verify-delivery)). Without a vendor UI, this heartbeat is your main signal
  that the collector is accepting batches.

<a id="options-with-a-generic-sink-meaning"></a>
### Options with a generic-specific meaning

| Option | Meaning for `generic` |
| --- | --- |
| [`vendor`](../configuration.md#opt-vendor) | `generic` selects this sink; an unset or unrecognized value also does (note 1) |
| [`url`](../configuration.md#opt-url) | **Required.** The full intake URL, POSTed to exactly as written (note 2) |
| [`apiKey`](../configuration.md#opt-apikey) | Optional. Sent as `Authorization: Bearer <apiKey>`; masked in status output |
| [`token`](../configuration.md#opt-token) | Ignored: no header is sent, so an authenticated collector answers `401`. Use `apiKey` |
| [`site`](../configuration.md#opt-site), [`host`](../configuration.md#opt-host), [`hostname`](../configuration.md#opt-hostname), [`service`](../configuration.md#opt-service), [`source`](../configuration.md#opt-source), [`index`](../configuration.md#opt-index), [`sourcetype`](../configuration.md#opt-sourcetype) | Ignored; none of them adds a field or header (note 3) |
| [`includeTimestamp`](../configuration.md#opt-includetimestamp) | Ignored; the generic sink always sends `timestamp` |
| [`includeStackTrace`](../configuration.md#opt-includestacktrace) | `true` + a Throwable: stack trace in `exception`. `false`: field omitted |
| [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords), [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) | Capped at the sink's own **500 records / 1 MiB uncompressed** (note 4) |
| [`gzip`](../configuration.md#opt-gzip) | Compresses the body and adds `Content-Encoding: gzip` (note 5) |
| [`trustAllCertificates`](../configuration.md#opt-trustallcertificates), [`verifyHostName`](../configuration.md#opt-verifyhostname) | Insecure TLS escape hatches for a private collector (note 6) |
| [`Layout`](../configuration.md#opt-layout) (element) | Renders `message`, one trailing newline stripped; default: the formatted message |
| [`maxMessageBytes`](../configuration.md#opt-maxmessagebytes) | Caps only `message`; MDC fields and `exception` are not capped |
| [`spillDirectory`](../configuration.md#opt-spilldirectory), [`spillInstanceId`](../configuration.md#opt-spillinstanceid) | Default directory embeds the `url` host (note 7) |

1. A misspelled vendor therefore fails with the generic sink's diagnostic
   (`could not build its 'datdog' sink: GenericNdjsonSink requires a url`) only when `url` is
   unset. When `url` is set, it silently ships generic NDJSON (with a Bearer header from `apiKey`)
   to that URL.
2. The sink appends no path. It must be an absolute `http://` or `https://` URL. Redirects are not
   followed, so use the final URL.
3. Put static fields in ThreadContext/MDC (each entry becomes a top-level field) or render them
   into the message with the Layout.
4. The effective ceiling is the smaller of the configured value and the sink's limit. Values above
   the limit have no effect; only the builder can raise the sink limits.
5. The collector must accept gzip request bodies; otherwise set it to `false` (also handy for
   reading bodies in a mock or proxy).
6. Meant for exactly this case: a private collector with a self-signed or privately signed
   certificate. `verifyHostName="false"` is JVM-global; see
   [Self-signed or internal collector](#self-signed-or-internal-collector).
7. The default spill directory is `${java.io.tmpdir}/batch-http-spill/Generic_<url host>_/`, so
   changing the `url` host changes the directory and strands old segments unless `spillDirectory`
   is set. From XML, `spillInstanceId` defaults to the appender name.

Every other option (batching, queue, retries, spill, TLS, diagnostics) applies unchanged; see the
[configuration reference](../configuration.md).

#### Builder-only options

A few generic-sink settings have no `<BatchHttp>` attribute. You can reach them only by embedding
the engine and building the sink yourself. They set the body framing (NDJSON or one JSON array),
the sink's own record and byte ceilings, and arbitrary request headers.

<details>
<summary>Builder methods and an example</summary>

| Builder method | Purpose |
| --- | --- |
| `framing(Framing)` | `NDJSON` (newline-delimited, the default) or `JSON_ARRAY` (the whole body is one JSON array) (note 1) |
| `maxRecords(int)` | The sink's per-batch record ceiling (500 unless set), separate from the engine's `maxBatchRecords` |
| `maxBytes(int)` | The sink's per-batch uncompressed-byte ceiling (1 MiB unless set) |
| `header(String, String)` | Adds any request header, such as a non-Bearer auth scheme or a tenant id (note 2) |

1. `JSON_ARRAY` also switches the default `Content-Type` to `application/json`.
2. Call it repeatedly for several headers. An explicit `Content-Type` overrides the framing
   default.

```java
LogSink sink = GenericNdjsonSink.builder()
        .url("https://logs.internal.example.com/bulk")
        // one JSON array instead of NDJSON
        .framing(GenericNdjsonSink.Framing.JSON_ARRAY)
        // custom header the XML appender can't set
        .header("X-Scope-OrgID", "team-42")
        .maxRecords(1000)
        .build();
```

The local benchmark's
[`BenchmarkDemo.java`](../../demo/java/benchmark/src/main/java/com/mulesoft/support/batchhttp/demo/BenchmarkDemo.java)
is a working example of embedding the engine around this sink.

</details>

## What gets sent

Each log event becomes one flat JSON object: the fixed fields `timestamp`, `level`, `logger`,
`thread` and `message`, an optional `exception`, then one top-level string field per MDC entry. A
request body is NDJSON: one object per line, each line ending in `\n`, including the last.
The two objects below are pretty-printed for reading; on the wire each one is a single line.

```json
{
  "timestamp": 1700000000000,
  "level": "INFO",
  "logger": "demo",
  "thread": "main",
  "message": "order processed id=1",
  "env": "prod"
}
{
  "timestamp": 1700000000004,
  "level": "ERROR",
  "logger": "demo",
  "thread": "main",
  "message": "payment failed id=2",
  "exception": "java.lang.IllegalStateException\n\tat demo.Pay.run(Pay.java:42)\n",
  "env": "prod"
}
```

### Field mapping

<details>
<summary>Full Log4j2 event to JSON field mapping</summary>

| Log4j2 event | JSON field | Notes |
| --- | --- | --- |
| Event time (`getTimeMillis()`) | `timestamp` | A JSON **number** of epoch milliseconds; always present |
| Level | `level` | For example `"INFO"`, `"ERROR"` |
| Logger name (`""` if null) | `logger` | |
| Thread name (`""` if null) | `thread` | |
| Layout output, or the formatted message when there is no Layout | `message` | Truncated to [`maxMessageBytes`](../configuration.md#opt-maxmessagebytes) |
| Throwable, rendered with `printStackTrace()` | `exception` | Only when [`includeStackTrace`](../configuration.md#opt-includestacktrace) is true and the event has a Throwable; omitted otherwise |
| Each non-null ThreadContext/MDC entry | Named after the key | A top-level **string** field, no prefix or nesting, written after the fixed fields |

</details>

MDC keys are not reserved or de-duplicated. An MDC key named `timestamp`, `level`, `logger`,
`thread`, `message` or `exception` produces a **duplicate key** in the same object, and which value
wins depends on the receiver's JSON parser. Rename such keys.

### Timestamp

`timestamp` is always the Log4j event time, sent as epoch milliseconds:

- [`includeTimestamp`](../configuration.md#opt-includetimestamp) has no effect on this sink.
- Nothing is converted to ISO-8601.
- There is no "use arrival time" mode.

If the receiver wants a different field name or an ISO string, map it in the receiver or in a
collector in front of it.

### Stack traces

The full `printStackTrace()` text lands in the `exception` string field when
[`includeStackTrace`](../configuration.md#opt-includestacktrace) is on.
[`maxMessageBytes`](../configuration.md#opt-maxmessagebytes) does not cap it.

### Per-request limits and gzip

A batch is sent when the first of these happens:

- It reaches the record ceiling: the smaller of
  [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) and the sink's 500.
- It reaches the byte ceiling: the smaller of
  [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) and the sink's 1 MiB, measured on the
  **uncompressed** body.
- [`lingerMillis`](../configuration.md#opt-lingermillis) expires.

With [`gzip`](../configuration.md#opt-gzip) on, each body is compressed once and the same bytes are
reused on every retry. The request carries `Content-Encoding: gzip`, and `Content-Type` stays
`application/x-ndjson`.

The transport speaks HTTP/1.1 with a 10 s connect timeout;
[`requestTimeoutMillis`](../configuration.md#opt-requesttimeoutmillis) bounds each request.

### Which responses retry or drop

| Response | What the appender does |
| --- | --- |
| `2xx` | Success. The whole batch counts as sent; the response body is not read |
| `408`, `429`, `5xx`, or a network error (note 1) | **Retryable.** Backoff and retry, then spill or drop (note 2) |
| Any other status: other `4xx`, `1xx`, and `3xx` (redirects are not followed) | **Fatal.** Dropped at once, never retried and never spilled (note 3) |

1. Network errors include connection refused/reset, DNS, TLS handshake and timeout.
2. Retried up to [`maxRetries`](../configuration.md#opt-maxretries) times with exponential backoff
   and jitter, from [`initialRetryBackoffMillis`](../configuration.md#opt-initialretrybackoffmillis)
   up to [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis). A `Retry-After`
   header (seconds or HTTP-date) replaces the computed wait, clamped to `maxRetryBackoffMillis`.
   When the retries run out the batch is spilled to disk if
   [`spillEnabled`](../configuration.md#opt-spillenabled), otherwise dropped.
3. A spilled record that later gets a fatal answer during replay is deleted, so a poison record
   cannot block the backlog.

Delivery is **at-least-once**. A timeout after the collector already stored the body, or a spill
replay, can deliver the same records twice, and the generic sink adds no idempotency key.
De-duplicate in the receiver if that matters. The [Resilience](../resilience.md) page covers the
spill buffer and crash recovery.

## Verify delivery

There is no vendor UI, so check from both ends.

### The appender's own heartbeat

With [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO`,
[`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) set, and
`<Configuration status>` at `INFO` or finer, the Log4j2 StatusLogger prints a line like this for
every interval (wrapped here for width; in the log it is one line):

```text
batch-http[Generic(logs.internal.example.com)] stats accepted=1200 sentRecords=1200
    sentBatches=6 dropped=0 failedBatches=0 queueDepth=0 spill{...}
```

A rising `sentRecords` with `dropped=0` means the collector is answering `2xx`. At the default
`WARN` level, silence is good news. Two lines signal trouble:

- `endpoint rejected the batch (HTTP nnn)`: fatal drops.
- `spilled N record(s) to disk`: the endpoint is failing transiently.

Every line for this sink starts with `batch-http[Generic(<url host>)]`, so you can filter on that:

```bash
grep 'batch-http\[Generic(' app.log
```

### The receiver

Query the collector or its backing store for a known `message` text or MDC value that your app
just logged.

### The wire format, locally

Point `url` at a plain-http localhost mock with `gzip="false"` so the bodies are readable. This
minimal mock answers `204` and prints each NDJSON line:

<details>
<summary>Minimal Python NDJSON mock (<code>mock_ndjson.py</code>)</summary>

```python
# mock_ndjson.py: run with python3 mock_ndjson.py, then url="http://localhost:8080/ingest"
from http.server import BaseHTTPRequestHandler, HTTPServer

class Ingest(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        lines = body.decode("utf-8").splitlines()
        print(f"{self.headers.get('Content-Type')}: {len(lines)} record(s)")
        for line in lines:
            print("  " + line)
        self.send_response(204)
        self.end_headers()

HTTPServer(("127.0.0.1", 8080), Ingest).serve_forever()
```

</details>

The Datadog and New Relic stock-app READMEs
([datadog-stock-http](../../demo/mule/datadog-stock-http/README.md),
[newrelic-stock-http](../../demo/mule/newrelic-stock-http/README.md)) use the same trick for a
local A/B run: they switch the batch app to `vendor="generic"` with a localhost URL and count
NDJSON lines in a Node mock.

## Benchmark on CloudHub

**The generic sink has not been benchmarked on CloudHub**; there is no intake behind it to
measure. The cross-vendor CloudHub results for Datadog, New Relic, Elasticsearch and Dynatrace are
in [Benchmark results](../benchmark-results.md). Batching, queueing, retry and spill are the same
engine for every vendor, so the batched-versus-stock pattern there applies here too. What changes
is the collector's per-request cost.

The **local mock-intake benchmark** does run this sink.
[`BenchmarkDemo`](../../demo/java/benchmark/src/main/java/com/mulesoft/support/batchhttp/demo/BenchmarkDemo.java)
sends 5000 events to an in-process HTTP server that adds 5 ms of latency per request:

| Mode | HTTP requests | Time |
| --- | --- | --- |
| One request per event | 5000 | 32634 ms |
| Batched | 10 | 82 ms |

[Building and running](../building.md) explains how to run it.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Startup: `GenericNdjsonSink requires a url` (note 1) | `url` is missing or blank | Set `url` |
| The same message, but naming an odd vendor such as `'datdog'` | `vendor` is misspelled and `url` is unset (note 2) | Fix the vendor name |
| A vendor appender that sets `url` (Splunk, Dynatrace, Elasticsearch) gets `endpoint rejected the batch (HTTP 400)`, `401`, `404` or `405`, with prefix `batch-http[Generic(<host>)]` | `vendor` is misspelled (note 3) | Fix the vendor name |
| Startup ERROR `could not build its 'generic' sink: Illegal character in ...` (note 1) | Unresolved lookup or a space in `url` (note 4) | Set the variable, or fix the URL |
| Every batch dropped with `unexpected error while batching` | `url` has no `http://` or `https://` scheme; the JDK rejects it at send time | Use an absolute URL |
| `endpoint rejected the batch (HTTP 401)` or `(HTTP 403)` | Wrong or missing credential (note 5) | Set the token in `apiKey` and confirm the variable resolves (note 6) |
| `endpoint rejected the batch (HTTP 301)`, `302`, `307` or `308` | Redirects are not followed | Point `url` at the final URL, typically `https://` |
| `HTTP 415` or `400` | Collector rejects `application/x-ndjson` or gzip, or expects its own schema (note 7) | Try `gzip="false"` (note 8) |
| `HTTP 413` | The collector's body limit is below the batch size | Lower [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) and/or [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) |
| PKIX or handshake errors, and batches end up spilled | Self-signed or private-CA certificate (note 9) | Import the CA, or see [Self-signed or internal collector](#self-signed-or-internal-collector) |
| Duplicate keys in stored documents | An MDC key collides with a fixed field (note 10) | Rename the MDC key |
| An old spill backlog is not replayed after changing the `url` host | The default spill directory embeds the host (`Generic_<host>_`) | Set [`spillDirectory`](../configuration.md#opt-spilldirectory) explicitly |

1. Both startup messages end with `This appender is disabled and will ship no logs.` and appear
   only when `<Configuration status>` is not `OFF`. The full first one reads:
   `could not build its 'generic' sink: GenericNdjsonSink requires a url. Required log4j2.xml
   attributes for vendor 'generic': url. This appender is disabled and will ship no logs.`
2. Unrecognized `vendor` values fall back to the generic sink.
3. The appender silently built the generic sink and POSTs NDJSON with a Bearer header to the
   vendor's URL.
4. An unset env var or sysprop, which Log4j2 leaves as the literal `${env:...}` text. The app
   still starts; the appender ships nothing.
5. Common causes: the token was set in `token` (ignored for generic), or the env var behind
   `apiKey="${env:X}"` is unset, so the literal `Bearer ${env:X}` was sent.
6. If the collector needs a non-Bearer scheme or extra headers, use the builder's `header(...)`.
   For an unauthenticated collector, remove `apiKey`.
7. Loki's `streams`, for example.
8. A JSON-array body needs the builder's `framing(JSON_ARRAY)`. For a fixed schema, put an
   NDJSON-accepting collector in front.
9. TLS failures are network errors, so they are retried and then spilled rather than dropped.
10. The fixed fields are `timestamp`, `level`, `logger`, `thread`, `message` and `exception`.

For the StatusLogger, diagnostics levels and the stats heartbeat in
general, see [Troubleshooting](../troubleshooting.md).

---

[Docs index](../README.md) · [Configuration reference](../configuration.md) · Other vendors:
[Datadog](datadog.md) · [New Relic](newrelic.md) · [Elasticsearch](elasticsearch.md) ·
[Dynatrace](dynatrace.md) · [Splunk](splunk.md)
