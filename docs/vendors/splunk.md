# Splunk HEC

With `vendor="splunk"`, the `<BatchHttp>` appender sends batches of log events to the Splunk
**HTTP Event Collector (HEC)**, on Splunk Enterprise or Splunk Cloud Platform. Each request
carries many events, up to the batch limits described below, so the app makes far fewer requests
than with one POST per log line.

> [!NOTE]
> **No sample Mule project yet.** Splunk has no demo pair and has not been benchmarked on
> CloudHub. The Java example config
> [`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml)
> contains a Splunk appender template (`name="splunk"`). Its `url` and `host` are placeholders:
> replace them with your HEC base URL and host before adding `<AppenderRef ref="splunk"/>`, or
> start from the [minimal config](#java-application) on this page. For a Mule app, see
> [Mule application](#mule-application).

| At a glance | Splunk HEC |
| --- | --- |
| Endpoint | `<url>/services/collector/event`, newline-delimited JSON events |
| Authentication | `Authorization: Splunk <token>`, where the token is an HEC token |
| Required options | [`url`](../configuration.md#opt-url), [`token`](../configuration.md#opt-token) |
| Sample Mule project | None yet; see the [Java example config](../../demo/java/benchmark/src/main/resources/log4j2-example.xml) |
| Benchmarked on CloudHub | No |

On this page:

- [Set up Splunk HEC](#set-up-splunk-hec)
- [Configuration](#configuration)
- [What gets sent](#what-gets-sent)
- [Verify delivery](#verify-delivery)
- [Benchmark on CloudHub](#benchmark-on-cloudhub)
- [Troubleshooting](#troubleshooting)

## Set up Splunk HEC

In Splunk Enterprise, go to **Settings > Data Inputs > HTTP Event Collector**. Splunk Cloud
Platform has the same HEC token settings.

1. **Enable HEC.** Under **Global Settings**, set "All Tokens" to enabled. Note whether SSL is
   on and which port HEC listens on (8088 by default on Splunk Enterprise).
2. **Create a token** with **New Token**. You can give it a default source and sourcetype.
3. **Pick the indexes.** Choose the allowed index(es) and a default index. Any
   [`index`](../configuration.md#opt-index) you set in `log4j2.xml` must be in the allowed list.
4. **Leave "Enable indexer acknowledgement" off** (see the note below).
5. **Store the token outside the config.** Put the token value in an environment variable or a
   JVM system property, for example `SPLUNK_HEC_TOKEN`, and reference it with `${env:...}` or
   `${sys:...}`. Never write it into `log4j2.xml` itself.
6. **Find the HEC base URL.** Use the HEC URL Splunk documents for your deployment, with
   `https`. Splunk Cloud Platform uses a dedicated HEC host and port that differ from the web UI
   address, so take them from Splunk's documentation.

> [!IMPORTANT]
> An ack-enabled token requires an `X-Splunk-Request-Channel` header, which this appender does
> not send. Such a token rejects every batch with HTTP 400.

Splunk Cloud presents publicly trusted certificates. For a self-signed Splunk Enterprise HEC
certificate, first import it into the JVM trust store. Only for an internal collector where that
is not possible, use [`trustAllCertificates`](../configuration.md#opt-trustallcertificates), and
[`verifyHostName`](../configuration.md#opt-verifyhostname)`="false"` if the name does not match;
turning off hostname verification affects the whole JVM (see the
[TLS troubleshooting row](#troubleshooting)).

> [!WARNING]
> [`trustAllCertificates`](../configuration.md#opt-trustallcertificates) and
> [`verifyHostName`](../configuration.md#opt-verifyhostname) are insecure. Use them for internal
> collectors only.

The appender has no region setting for Splunk: [`url`](../configuration.md#opt-url) alone
decides where events go.

## Configuration

Two attributes are required: [`url`](../configuration.md#opt-url) (the HEC base URL,
`scheme://host:port`) and [`token`](../configuration.md#opt-token). The sink adds
`/services/collector/event` to the URL itself, so do **not** put `/services/collector` in `url`.

### Java application

A minimal `log4j2.xml`. `SPLUNK_HEC_URL` holds the HEC base URL and `SPLUNK_HEC_TOKEN` the
token:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <!-- Splunk HTTP Event Collector. SPLUNK_HEC_URL is the HEC base URL
             (scheme://host:port); the sink appends /services/collector/event. -->
        <BatchHttp name="splunk" vendor="splunk"
                   url="${env:SPLUNK_HEC_URL}"
                   token="${env:SPLUNK_HEC_TOKEN}"
                   index="main"
                   sourcetype="mulesoft:app"
                   host="${env:HOSTNAME:-${hostName}}">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="splunk"/>
        </Root>
    </Loggers>
</Configuration>
```

- `packages` lets Log4j2 find the `<BatchHttp>` plugin.
- `status="WARN"` keeps the appender's own failure messages visible (see
  [Troubleshooting](#troubleshooting)).
- The `%m` layout makes the log message the `event.message` field.
- `index`, `sourcetype` and `host` are optional. Leave any of them out and the token's defaults
  apply.

### Mule application

There is no Splunk sample Mule project. This is a minimal Splunk `log4j2.xml` for a Mule app;
the batch demos, for example
[`demo/mule/datadog-batch-http`](../../demo/mule/datadog-batch-http/src/main/resources/log4j2.xml),
show a fuller file. Two rules apply in a Mule app:

- Only Log4j2 lookups resolve in `log4j2.xml`. Mule `${...}` placeholders and `secure::`
  properties do not, so pass the token as a JVM system property or an environment variable.
- `<Configuration packages="com.mulesoft.support.batchhttp.log4j2">` is required.

In a Mule app the appender typically looks like this:

```xml
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <!-- Keep the app's other appenders
             (for example the CloudHub 1.0 RollingFile "FILE") -->
        <BatchHttp name="splunk" vendor="splunk"
                   url="${sys:splunk.hecUrl:-${env:SPLUNK_HEC_URL}}"
                   token="${sys:splunk.hecToken:-${env:SPLUNK_HEC_TOKEN:-MISSING_TOKEN}}"
                   sourcetype="mulesoft:app">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <!-- Your application's own logger category ships to Splunk and the local file -->
        <AsyncLogger name="com.example.orders" level="INFO" additivity="false">
            <AppenderRef ref="splunk"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
        <AsyncRoot level="INFO">
            <AppenderRef ref="FILE"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```

`MISSING_TOKEN` makes an unset secret visible: HEC rejects it with 401/403 and the ERROR line
names the status, instead of the literal `${env:...}` text being sent. Point your own logger
category (here the placeholder `com.example.orders`) at the appender with
`<AppenderRef ref="splunk"/>`.

[Using it from a Mule app](../mule.md) explains how to add the dependency, how to get JVM system
properties to the Mule runtime on CloudHub, and how to inject secrets.

### Recommended production settings

The minimal config uses the built-in defaults for batching, queueing, retries and spill. The
example below changes a few of them on purpose:

```xml
<BatchHttp name="splunk" vendor="splunk"
           url="${env:SPLUNK_HEC_URL}"
           token="${env:SPLUNK_HEC_TOKEN}"
           index="main"
           sourcetype="mulesoft:app"
           host="${env:HOSTNAME:-${hostName}}"
           queueCapacity="25000"
           maxRetries="5"
           maxRetryBackoffMillis="30000"
           spillEnabled="true"
           spillDirectory="/var/lib/batch-http-spill"
           spillInstanceId="orders-api"
           diagnosticsLevel="INFO"
           statsIntervalMillis="60000">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

- [`queueCapacity`](../configuration.md#opt-queuecapacity) `25000` gives tens of seconds of
  buffer at a busy app's rate, and the heap it can take is still bounded (very roughly 50 MB at
  about 2 KB per queued record). Lower it on a small worker.
- [`maxRetries`](../configuration.md#opt-maxretries) `5` and
  [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis) `30000`: HEC answers
  503 when its queues are full. More retries with a longer backoff cap give the indexers time to
  recover before a batch goes to disk. A `Retry-After` header is honoured, clamped to the backoff
  cap.
- [`spillEnabled`](../configuration.md#opt-spillenabled) is spelled out so the durability choice
  is visible in review. With spill on, batches that fail every retry and records the queue cannot
  take go to disk and are replayed later.
- [`spillDirectory`](../configuration.md#opt-spilldirectory) points at real storage. Unset, the
  spill buffer lives under `${java.io.tmpdir}/batch-http-spill/SplunkHEC`. On container
  platforms where `/tmp` is memory-backed, that buffer uses RAM instead of disk.
- [`spillInstanceId`](../configuration.md#opt-spillinstanceid) is a stable id per running
  instance. A restart on the same host adopts its own backlog, and two apps sharing a spill
  directory never touch each other's files.
- [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` together with
  [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) `60000` prints a
  once-a-minute delivery heartbeat, so you can check that logs are still flowing by reading a log
  line. With spill on, watch `spilledRecords` in it: that counter, not `dropped`, shows overload.

See [Resilience and the disk-spill buffer](../resilience.md) for how spill, replay and crash
recovery behave.

### Options with a Splunk-specific meaning

| Option | Meaning for Splunk (HEC) |
| --- | --- |
| [`vendor`](../configuration.md#opt-vendor) | `splunk` (alias `splunkhec`, any case) selects the HEC sink; an unrecognised value silently falls back to `generic` |
| [`url`](../configuration.md#opt-url) | **Required.** HEC base URL, `scheme://host:port`; one trailing `/` is removed, then `/services/collector/event` is added |
| [`token`](../configuration.md#opt-token) | **Required.** HEC token, sent as `Authorization: Splunk <token>`; a sensitive attribute, masked in Log4j2's status output |
| [`index`](../configuration.md#opt-index) | Optional HEC `index` on every event; unset, the field is left out and the token's default index applies (note 1) |
| [`sourcetype`](../configuration.md#opt-sourcetype) | Optional HEC `sourcetype`; unset, the token's or input's default sourcetype applies |
| [`host`](../configuration.md#opt-host) | Optional HEC `host` (Splunk's host metadata); unset, Splunk assigns its default host (note 2) |
| [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) | Honoured, capped at the sink's 921600-byte (900 KiB) ceiling; lower it if your HEC `max_content_length` is smaller |
| [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) | Honoured as-is; HEC has no per-request record limit, so the sink sets no record cap |
| [`gzip`](../configuration.md#opt-gzip) | Honoured; the body is gzipped and sent with `Content-Encoding: gzip`, which HEC accepts |
| [`includeStackTrace`](../configuration.md#opt-includestacktrace) | Honoured; the rendered stack trace goes into `event.exception` |
| [`includeTimestamp`](../configuration.md#opt-includetimestamp) | Ignored; the HEC `time` field is always sent |
| [`source`](../configuration.md#opt-source) | Ignored; no HEC `source` field is sent, so the token's default source applies |
| [`service`](../configuration.md#opt-service) | Ignored; it appears nowhere in the event |
| [`apiKey`](../configuration.md#opt-apikey) | Ignored; use `token` (note 3) |
| [`site`](../configuration.md#opt-site), [`hostname`](../configuration.md#opt-hostname) | Ignored |
| [`Layout`](../configuration.md#opt-layout) | Its output becomes `event.message` |

1. The token must be allowed to write to this index.
2. For Splunk, `host` is a data field, **not** the intake address.
3. A token placed in `apiKey` leaves `token` empty, and the appender fails to build.

Every other option (batching, queue, retries, spill, TLS, diagnostics) applies unchanged. See
the [configuration reference](../configuration.md).

## What gets sent

Each log event becomes one HEC event object:

- **Top level:** `time` (always), plus `host`, `sourcetype` and `index` when those attributes
  are set.
- **Inside `event`:** `message`, `level`, `logger`, `thread`, `exception` (when there is a stack
  trace to include), then one key per MDC entry.

On the wire each event is a single line; it is wrapped here for reading:

```json
{"time": 1700000000.123, "host": "app-01", "sourcetype": "mulesoft:app", "index": "main",
 "event": {"message": "order processed", "level": "INFO", "logger": "com.example.Orders",
           "thread": "worker-1", "orderId": "42"}}
```

<a id="field-mapping"></a>

<details>
<summary>Full field mapping</summary>

| HEC field | Source |
| --- | --- |
| `time` | Log event timestamp: epoch seconds with a 3-digit millisecond fraction, as a JSON number. Always present. |
| `host` | `host` attribute, only when set |
| `sourcetype` | `sourcetype` attribute, only when set |
| `index` | `index` attribute, only when set |
| `event.message` | Layout output, or the formatted message when there is no layout (note 1) |
| `event.level` | Log level |
| `event.logger` | Logger name (empty string if none) |
| `event.thread` | Thread name (empty string if none) |
| `event.exception` | Throwable stack trace, when [`includeStackTrace`](../configuration.md#opt-includestacktrace) is on |
| `event.<key>` | Each MDC / ThreadContext entry with a non-null value, as a string (note 2) |

1. One trailing newline is removed from the layout output. The message is truncated at
   [`maxMessageBytes`](../configuration.md#opt-maxmessagebytes).
2. MDC entries become keys inside the `event` object, after the fixed fields. They are not sent
   as HEC indexed `fields`. They are not checked against the fixed names, so an MDC key called
   `message`, `level`, `logger`, `thread` or `exception` produces a duplicate JSON key.

</details>

### Timestamp

`time` always comes from the Log4j2 event's own timestamp, so Splunk's `_time` is the moment the
event was logged, not the moment it arrived. Because of that, `_time` does not show shipping
delay. To measure it, compare `_indextime` with `_time` (see
[Verify delivery](#verify-delivery)).
[`includeTimestamp`](../configuration.md#opt-includetimestamp) has no effect for Splunk.

### Stack traces

When an event carries a Throwable, the full `printStackTrace` text goes into `event.exception`
as a string. To leave it out, turn off
[`includeStackTrace`](../configuration.md#opt-includestacktrace).

### Per-request limits and gzip

A batch is flushed at whichever limit it reaches first:

- **Records:** [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords). The sink sets no
  record cap, so this option alone limits how many records go in a batch.
- **Bytes:** the smaller of [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) and
  921600 bytes (900 KiB). You cannot raise or lower that ceiling from `log4j2.xml`.
- **Time:** a partial batch is also flushed after
  [`lingerMillis`](../configuration.md#opt-lingermillis), so quiet periods do not hold logs back.

<details>
<summary>Why the real body can exceed the ceiling</summary>

The 900 KiB ceiling sits under HEC's default 1 MB `max_content_length`, but the batch size is an
estimate of the uncompressed UTF-8 record contents. It does not count the per-event envelope
(`time`, `host`, `sourcetype`, `index` and the field names) or JSON escaping, and those can push
the real body past the estimate:

- A message that is itself a JSON string has every quote escaped.
- Stack traces add escaped newlines and tabs.

For JSON-string messages, or wherever HEC enforces 1 MB, lower `maxBatchBytes` well below the
ceiling (for example `700000`). If your HEC `max_content_length` is below the ceiling, lower
`maxBatchBytes` too.

</details>

<details>
<summary>Request framing and gzip</summary>

- **Framing:** each event is one JSON object followed by a newline. Nothing wraps the events
  (no array). HEC accepts events concatenated back to back in one body.
- **gzip:** with [`gzip`](../configuration.md#opt-gzip) on, each batch is compressed once (and
  the same bytes are reused on retry) and sent with `Content-Encoding: gzip`. The size limits
  apply to the **uncompressed** body.

</details>

### Which responses retry or drop

HTTP responses are classified the same way for every vendor:

| Response | What happens |
| --- | --- |
| 2xx | Success: the whole batch counts as sent. HEC's response body is not read. |
| 408, 429, any 5xx (including HEC's 503 "server busy"), network errors, timeouts | Retried, then spilled or dropped (note 1) |
| Any other status (in practice a 4xx; also 1xx/3xx, since redirects are not followed) | Fatal: dropped at once and **not** spilled (note 2) |

1. Retried up to [`maxRetries`](../configuration.md#opt-maxretries) times with exponential
   backoff and jitter. A `Retry-After` header wins, clamped to
   [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis). When retries run
   out, the batch is written to the spill buffer if spill is on and replayed later. If spill is
   off, it is dropped with an ERROR.
2. The batch is dropped at once because retrying cannot help. An ERROR is logged as one line
   (wrapped below for reading).

```text
batch-http[SplunkHEC] dropped N record(s) — endpoint rejected the batch (HTTP 4xx);
check credentials/endpoint, retrying will not help
```

Delivery is at-least-once. A timeout after HEC already stored a batch, or a spill replay, can
produce duplicate events in Splunk.

## Verify delivery

1. **In the app.** Set [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) with
   [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO`, and watch
   `sentRecords` climb in the `batch-http[SplunkHEC] stats ...` heartbeat. It goes to Log4j2's
   StatusLogger, so make sure `<Configuration status>` is not `OFF`.
2. **In Splunk.** Open **Search & Reporting** and search your index and sourcetype. `spath`
   extracts the JSON event fields in case your sourcetype does not extract JSON automatically:

   ```text
   index=main sourcetype="mulesoft:app"
   | spath
   | table _time host level logger thread message
   ```

   Errors with stack traces:

   ```text
   index=main sourcetype="mulesoft:app"
   | spath
   | search level=ERROR exception=*
   | table _time logger message exception
   ```

3. **Ingest lag.** `_time` is the event time, and `_indextime` is when Splunk indexed the
   event:

   ```text
   index=main sourcetype="mulesoft:app"
   | eval lag_s=_indextime-_time
   | stats avg(lag_s) max(lag_s)
   ```

4. **If nothing arrives,** first look for the appender's ERROR lines in the application log (see
   [Troubleshooting](#troubleshooting)). Splunk logs the requests HEC rejected in its internal
   index:

   ```text
   index=_internal sourcetype=splunkd component=HttpInputDataHandler
   ```

## Benchmark on CloudHub

**Splunk has not been benchmarked on CloudHub.** The CloudHub runs covered Datadog, New Relic,
Elasticsearch and Dynatrace. Their results and method are in
[Benchmark results](../benchmark-results.md), and the per-vendor detail is on each vendor's page.

The local mock-intake benchmark compares batched shipping with one request per event. It does
not depend on the vendor, so it applies to Splunk as well. [Building](../building.md) shows how
to run it.

> [!NOTE]
> Results against a real HEC would depend on where your HEC runs relative to the app and how
> much load your indexers can take.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Startup ERROR `Required log4j2.xml attributes for vendor 'splunk'` (note 1) | `url` or `token` is missing or empty, or `url` is not a valid URI (note 2) | Set both attributes. Make sure the lookup behind each one resolves. |
| Same startup ERROR, but the token is configured | The token is in `apiKey`. Splunk has no `apiKey` alias. | Move it to `token`. |
| No startup ERROR and no logs, with no diagnostics at all | `<Configuration status="OFF">` hides the appender's own messages | Use `status="WARN"` or finer. |
| `dropped ... (HTTP 404)` | `url` already contains `/services/collector` (or `/event`), so the path is doubled | Give only `scheme://host:port`. |
| `dropped ... (HTTP 401)` or `(HTTP 403)` | The token is missing, invalid or disabled (note 3) | Check the variable or property is set for this process, and the token is enabled in Splunk. |
| `dropped ... (HTTP 400)` | Index not allowed for this token, malformed request, or indexer acknowledgement on ("Data channel is missing") | Add the index to the token's allowed list, or turn off indexer acknowledgement for this token. |
| `dropped ... (HTTP 413)` | The real body exceeded HEC's `max_content_length` (note 4) | Lower [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) well below your limit. |
| Retries, then `spilledRecords` growing (HTTP 503) | HEC is busy or its queues are full | Nothing to fix in the appender; check indexer health (note 5). |
| TLS handshake failure | Splunk Enterprise HEC uses a self-signed certificate | Import the certificate into the JVM trust store (note 6). |
| Duplicate keys or odd fields inside `event` | An MDC key is named `message`, `level`, `logger`, `thread` or `exception` | Rename the MDC key. |
| `source` or `service` has no effect | Neither is sent to Splunk; the HEC source comes from the token's default | Set the default source on the token, or put a source-like value in MDC. |
| Nothing reaches Splunk, and the diagnostics prefix is not `batch-http[SplunkHEC]` | `vendor` is misspelled (for example `splunkcloud`) and silently fell back to the generic sink | Use `vendor="splunk"`. |
| Mule app: the token arrives as `${...}` literally, or `<BatchHttp>` is unknown | A Mule placeholder or `secure::` property was used, or `packages` is missing | Use Log4j2 `${sys:...}` / `${env:...}` lookups and set `packages` (note 7). |

1. The ERROR names the appender. The full message reads `Required log4j2.xml attributes for
   vendor 'splunk': url, token. This appender is disabled and will ship no logs.`
2. The reason text uses the builder's names (`SplunkHecSink requires a baseUrl` /
   `... requires a token`) and names only the first missing value; the required list always
   names both. An invalid URI, for example an unresolved `${env:SPLUNK_HEC_URL}` left as literal
   text, gives the reason `Illegal character ...`.
3. An unresolved `${env:X}` is sent as the literal text, and a fallback sentinel such as
   `MISSING_TOKEN` is rejected too.
4. Either HEC's `max_content_length` is smaller than the sink's 900 KiB ceiling, or JSON escaping
   and the per-event envelope pushed the real body past the size estimate (JSON-string
   messages).
5. Retries and spill carry the backlog. To give HEC more time, raise
   [`maxRetries`](../configuration.md#opt-maxretries) /
   [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis).
6. For internal collectors only:
   [`trustAllCertificates`](../configuration.md#opt-trustallcertificates), and
   [`verifyHostName`](../configuration.md#opt-verifyhostname)`="false"` if the name does not
   match. Turning off hostname verification affects the whole JVM.
7. `packages="com.mulesoft.support.batchhttp.log4j2"`. See [Using it from a Mule app](../mule.md).

General diagnostics (where the appender's messages go, the stats counters, spill files) are in
[Troubleshooting & diagnostics](../troubleshooting.md).

---

[Docs index](../README.md) · [Configuration reference](../configuration.md) · Other vendors:
[Datadog](datadog.md) · [New Relic](newrelic.md) · [Elasticsearch](elasticsearch.md) ·
[Dynatrace](dynatrace.md) · [Generic HTTP](generic.md)
