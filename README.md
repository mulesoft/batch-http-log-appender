# batch-http-appender

A **batching HTTP log shipper** for Java — a fast, drop-in replacement for Log4j2's
stock `HttpAppender`, which sends one synchronous HTTP request per log event.

Instead, events are enqueued (non-blocking) and shipped in **compressed batches**
by background threads to **Datadog**, **Splunk HEC**, **New Relic**, **Dynatrace**,
**Elasticsearch** (ELK / Elastic Cloud), or any HTTP log intake. Current release: **0.2.0**
([CHANGELOG](CHANGELOG.md)).

## Why

The default Log4j2 `HttpAppender` does one blocking request per event. At volume,
per-request overhead (TLS, headers, round-trip latency) dominates and the logging
call blocks the application thread. Vendor intake APIs are all designed for bulk
ingest — this library uses them.

Against a local mock intake (5 ms simulated latency per request, 5000 events), the stock path
took **32634 ms and 5000 requests**; the batching appender took **82 ms and 10 requests** —
about **400× faster wall-clock, 500× fewer requests**. The method and the command to reproduce it
are in [Run the benchmark / example](docs/building.md#run-the-benchmark--example). On real
vendor intakes from CloudHub, see [Benchmarks](#benchmarks).

## Supported vendors

Each vendor has one self-contained page: setup, a copy-paste configuration, what gets sent,
how to verify delivery, troubleshooting and, where measured, its CloudHub benchmark. Every
page links the same option list, [docs/configuration.md](docs/configuration.md).

| Vendor | Page | Sample Mule project (batch / stock control) | CloudHub Test 3: batch÷stock req/s, CH1 / CH2 | Batch logs delivered, CH1 / CH2 |
| --- | --- | --- | --- | --- |
| Datadog | [docs/vendors/datadog.md](docs/vendors/datadog.md) | [datadog-batch-http](demo/mule/datadog-batch-http/) / [datadog-stock-http](demo/mule/datadog-stock-http/) | 53.5× / 28.5× | 100.0% / 100.0% |
| New Relic | [docs/vendors/newrelic.md](docs/vendors/newrelic.md) | [newrelic-batch-http](demo/mule/newrelic-batch-http/) / [newrelic-stock-http](demo/mule/newrelic-stock-http/) | 110.8× / 59.5× | 44.5% / 77.3% ¹ |
| Elasticsearch | [docs/vendors/elasticsearch.md](docs/vendors/elasticsearch.md) | [elastic-batch-http](demo/mule/elastic-batch-http/) / [elastic-stock-http](demo/mule/elastic-stock-http/) | 139.5× / 48.6× | 100.0% / 100.0% |
| Dynatrace | [docs/vendors/dynatrace.md](docs/vendors/dynatrace.md) | [dynatrace-batch-http](demo/mule/dynatrace-batch-http/) / [dynatrace-stock-http](demo/mule/dynatrace-stock-http/) | 6.5× / 4.4× ² | 100.0% / 100.0% |
| Splunk HEC | [docs/vendors/splunk.md](docs/vendors/splunk.md) | — (see the [Java example config](demo/java/benchmark/src/main/resources/log4j2-example.xml)) | not measured | — |
| Generic HTTP | [docs/vendors/generic.md](docs/vendors/generic.md) | — (see the [Java example config](demo/java/benchmark/src/main/resources/log4j2-example.xml)) | not measured | — |

Test 3 is the 5-minute sustained run on 0.1 vCore workers in Sydney; CloudHub 1.0 (CH1) and
CloudHub 2.0 (CH2) run at different concurrency, so compare within a column, never CH1 against
CH2. ¹ New Relic has only US and EU intake; these Sydney workers ship to the US, and the spill
backlog was still draining when the next test's restart replaced the worker. New Relic batch
delivered 100% in Test 4. ² A single-record POST to the in-region Dynatrace intake is quick, so
the stock appender is penalised far less there; batch req/s matches the other vendors.

## Quick start

1. **Build and install** the artifacts into your local Maven repository (JDK 17+, Maven 3.8+;
   details in [docs/building.md](docs/building.md)):

   ```bash
   mvn clean install -DskipTests
   ```

2. **Add the dependency.** `batch-http-log4j2` brings in `batch-http-core`, which has no runtime
   dependencies beyond the JDK.

   ```xml
   <dependency>
       <groupId>com.mulesoft.support.batchhttp</groupId>
       <artifactId>batch-http-log4j2</artifactId>
       <version>0.2.0</version>
   </dependency>
   ```

3. **Configure `log4j2.xml`.** A minimal Datadog example:

   ```xml
   <Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
       <Appenders>
           <BatchHttp name="datadog" vendor="datadog"
                      apiKey="${env:DD_API_KEY}" service="orders-api" source="java">
               <PatternLayout pattern="%m"/>
           </BatchHttp>
       </Appenders>
       <Loggers>
           <!-- additivity="false" keeps these events from also bubbling to the root,
                so they ship to the appender exactly once. -->
           <Logger name="com.example.orders" level="INFO" additivity="false">
               <AppenderRef ref="datadog"/>
           </Logger>
           <Root level="INFO">
               <AppenderRef ref="datadog"/>
           </Root>
       </Loggers>
   </Configuration>
   ```

   - **`packages` is required** wherever the library is on the application classloader rather
     than the Log4j2 boot classpath (every Mule 4 / CloudHub app). Without it Log4j2 ignores
     `<BatchHttp>` with a startup warning, and nothing ships.
   - **Secrets only through Log4j2 lookups** (`${env:...}`, `${sys:...}`). Mule `${...}`
     placeholders and `secure::` properties do not resolve in `log4j2.xml`; see
     [Injecting secrets](docs/mule.md#injecting-secrets-api-keys--tokens).
   - **Sync or async loggers both work.** `<BatchHttp>` already enqueues without blocking, so
     plain `<Root>`/`<Logger>` never block your threads on network I/O. `<AsyncRoot>` /
     `<AsyncLogger>` need the LMAX Disruptor (`com.lmax:disruptor`) on the classpath: the Mule
     runtime provides it, a standalone Java app must add it.

4. **Pick your vendor page** from [Supported vendors](#supported-vendors) for the vendor's
   required options, recommended production settings and a ready-to-run Mule sample. Every
   option, its default and the vendors it applies to is in
   [docs/configuration.md](docs/configuration.md). Using it from Mule:
   [docs/mule.md](docs/mule.md).

## Benchmarks

Four batch/stock pairs of the same Mule app — one per vendor, sixteen apps across CloudHub 1.0
and 2.0 — were load-tested with [`oha`](https://github.com/hatoo/oha) on 0.1 vCore workers in
Sydney, with engine 0.2.0, disk spill on, and the logs delivered counted at each vendor. Over the
5-minute run the batch appender sustained **6.5–139.5×** the stock appender's request rate on
CloudHub 1.0 and **4.4–59.5×** on CloudHub 2.0. The batch apps delivered 100% of their logs to
Datadog, Elasticsearch and Dynatrace in every test; the stock apps lost logs at all four vendors
on CloudHub 1.0. Datadog, New Relic and Elasticsearch were measured on 2026-10-12, Dynatrace on
2026-10-13. These are single runs: read them as directional.

![Sustained request throughput, batch vs stock appender, by plane and vendor](docs/img/cloudhub-throughput-batch-vs-stock.png)

Method, caveats and all four tests: [docs/benchmark-results.md](docs/benchmark-results.md). The
styled standalone report is [docs/index.html](docs/index.html) (open it locally after cloning).
To reproduce: [docs/benchmark-runbook.md](docs/benchmark-runbook.md).

## Documentation

[Configuration](docs/configuration.md) · [Mule](docs/mule.md) ·
[Architecture](docs/architecture.md) · [Building](docs/building.md) ·
[Resilience](docs/resilience.md) · [Troubleshooting](docs/troubleshooting.md) ·
[Memory safety](docs/memory-safety.md) · [Benchmarks](docs/benchmark-results.md)

What each page covers: [docs/README.md](docs/README.md).

## Status

Proof-of-concept (0.2.0). Validated by unit + end-to-end tests (`mvn test`) and the
benchmarks above. See "Roadmap" below for what a production v1 would add.

## Roadmap to production v1

- **Logback adapter** module (the core is already framework-agnostic).
- **Circuit breaker** to stop hammering a downed endpoint.
- Per-sink **metrics export** (Micrometer) and JMX beans.
- JMH benchmarks and back-pressure soak tests.
- **mTLS (client-certificate auth)** — a `keyStore`/`keyStorePassword` (and optional
  custom `trustStore`) on the appender, feeding a `KeyManager` into the same
  transport `SSLContext` the `trustAllCertificates` switch already uses on the
  TrustManager side. For collectors that require the client to present a certificate.
- Consider the **OpenTelemetry** appender + Collector path as an alternative for
  multi-vendor routing without app redeploys (build-vs-adopt trade-off).

## Author

Created and maintained by **Vitalii Mykytenko**
Senior Principal Technical Support Engineer
MuleSoft Signature Success Support, Australia

Please raise questions and bug reports as GitHub Issues rather than contacting the author
directly — see [Contributing](#contributing) below.

## License

Licensed under the Apache License, Version 2.0. The full text is in [`LICENSE.txt`](LICENSE.txt),
and every Salesforce-authored source and configuration file carries the corresponding
`SPDX-License-Identifier: Apache-2.0` header.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). The governance model is **published but not
supported**: the code is shared because it may be useful, but contributions are not actively
solicited and there is no support commitment. Bug reports, questions and ideas belong on the
repository's GitHub Issues page.

## Security

Please report security vulnerabilities as described in [`SECURITY.md`](SECURITY.md) — via
Salesforce's responsible disclosure process, not as a public GitHub issue.

## Code of Conduct

Participation in this project is governed by the Salesforce Open Source
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
