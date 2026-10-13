# batch-http-appender

A **batching HTTP log shipper** for Java: a fast, drop-in replacement for Log4j2's stock
`HttpAppender`, which sends one synchronous HTTP request per log event. Current release:
**0.2.1** ([CHANGELOG](CHANGELOG.md)).

On this page: [Why](#why) · [Supported vendors](#supported-vendors) · [Quick start](#quick-start) ·
[Benchmarks](#benchmarks) · [Documentation](#documentation) · [Project](#project)

## Why

- Log4j2's default `HttpAppender` does one blocking request per event, so the logging call
  blocks the application thread.
- At volume, per-request overhead (TLS, headers, round-trip latency) dominates.
- Vendor intake APIs are all designed for bulk ingest, and this library uses them: events are
  enqueued (non-blocking) and shipped in compressed batches by background threads.
- **About 400× faster wall-clock, 500× fewer requests** against a local mock intake (5 ms
  simulated latency per request, 5000 events): the stock path took 32634 ms and 5000 requests,
  the batching appender 82 ms and 10 requests. Method and command:
  [Run the benchmark / example](docs/building.md#run-the-benchmark--example). On real vendor
  intakes from CloudHub, see [Benchmarks](#benchmarks).

## Supported vendors

Each vendor page is self-contained: setup, a copy-paste configuration, what gets sent, how to
verify delivery, troubleshooting and, where measured, its CloudHub benchmark. Every page links
the same option list, [docs/configuration.md](docs/configuration.md).

In the table, Test 3 is the 5-minute sustained run on 0.1 vCore workers in Sydney, on CloudHub 1.0
(CH1) and CloudHub 2.0 (CH2).

| Vendor page | Sample Mule project | Test 3 batch ÷ stock req/s, CH1 / CH2 | Batch logs delivered, CH1 / CH2 |
| --- | --- | --- | --- |
| [Datadog](docs/vendors/datadog.md) | [batch](demo/mule/datadog-batch-http/) · [stock](demo/mule/datadog-stock-http/) | 53.5× / 28.5× | 100.0% / 100.0% |
| [New Relic](docs/vendors/newrelic.md) | [batch](demo/mule/newrelic-batch-http/) · [stock](demo/mule/newrelic-stock-http/) | 110.8× / 59.5× | 44.5% / 77.3% ¹ |
| [Elasticsearch](docs/vendors/elasticsearch.md) (ELK / Elastic Cloud) | [batch](demo/mule/elastic-batch-http/) · [stock](demo/mule/elastic-stock-http/) | 139.5× / 48.6× | 100.0% / 100.0% |
| [Dynatrace](docs/vendors/dynatrace.md) | [batch](demo/mule/dynatrace-batch-http/) · [stock](demo/mule/dynatrace-stock-http/) | 6.5× / 4.4× ² | 100.0% / 100.0% |
| [Splunk HEC](docs/vendors/splunk.md) | none yet; [Java example config](demo/java/benchmark/src/main/resources/log4j2-example.xml) | not measured | — |
| [Generic HTTP](docs/vendors/generic.md) (any HTTP log intake) | none yet; [Java example config](demo/java/benchmark/src/main/resources/log4j2-example.xml) | not measured | — |

CH1 and CH2 run at different concurrency, so compare within a column, never CH1 against CH2.

1. New Relic has only US and EU intake; these Sydney workers ship to the US, and the spill
   backlog was still draining when the next test's restart replaced the worker. New Relic batch
   delivered 100% in Test 4.
2. A single-record POST to the in-region Dynatrace intake is quick, so the stock appender is
   penalised far less there; batch req/s matches the other vendors.

## Quick start

1. **Build and install** the artifacts into your local Maven repository (JDK 17+, Maven 3.8+;
   see [docs/building.md](docs/building.md)):

   ```bash
   mvn clean install -DskipTests
   ```

2. **Add the dependency.** `batch-http-log4j2` brings in `batch-http-core`, which has no runtime
   dependencies beyond the JDK.

   ```xml
   <dependency>
       <groupId>com.mulesoft.support.batchhttp</groupId>
       <artifactId>batch-http-log4j2</artifactId>
       <version>0.2.1</version>
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

4. **Pick your vendor page** from [Supported vendors](#supported-vendors) for the required
   options, recommended production settings and a ready-to-run Mule sample. Every option, its
   default and the vendors it applies to: [docs/configuration.md](docs/configuration.md). Using
   it from Mule: [docs/mule.md](docs/mule.md).

> [!IMPORTANT]
> **`packages` is required** wherever the library is on the application classloader rather than
> the Log4j2 boot classpath (every Mule 4 / CloudHub app). Without it Log4j2 ignores
> `<BatchHttp>` with a startup warning, and nothing ships.

Two more rules for `log4j2.xml`:

- **Secrets only through Log4j2 lookups** (`${env:...}`, `${sys:...}`). Mule `${...}`
  placeholders and `secure::` properties do not resolve in `log4j2.xml`; see
  [Injecting secrets](docs/mule.md#injecting-secrets-api-keys--tokens).
- **Sync or async loggers both work.** `<BatchHttp>` already enqueues without blocking, so plain
  `<Root>`/`<Logger>` never block your threads on network I/O. `<AsyncRoot>` / `<AsyncLogger>`
  need the LMAX Disruptor (`com.lmax:disruptor`) on the classpath: the Mule runtime provides it,
  a standalone Java app must add it.

## Benchmarks

Four batch/stock pairs of the same Mule app (one per vendor, sixteen apps across CloudHub 1.0 and
2.0) were load-tested with [`oha`](https://github.com/hatoo/oha) on 0.1 vCore workers in Sydney,
with engine 0.2.0, disk spill on, and the logs delivered counted at each vendor.

**Over the 5-minute run the batch appender sustained 6.5–139.5× the stock appender's request rate
on CloudHub 1.0 and 4.4–59.5× on CloudHub 2.0.** The batch apps delivered 100% of their logs to
Datadog, Elasticsearch and Dynatrace in every test; the stock apps lost logs at all four vendors
on CloudHub 1.0. Datadog, New Relic and Elasticsearch were measured on 2026-10-12, Dynatrace on
2026-10-13. These are single runs: read them as directional.

The chart compares sustained request throughput, batch against stock, per vendor on each plane;
look at the gap within each vendor group (smallest for Dynatrace; see note 2 under the
vendor table).

![Sustained request throughput, batch vs stock appender, by plane and vendor](docs/img/cloudhub-throughput-batch-vs-stock.png)

Method, caveats and all four tests: [docs/benchmark-results.md](docs/benchmark-results.md).
Styled standalone report: [docs/index.html](docs/index.html) (open it locally after cloning). To
reproduce: [docs/benchmark-runbook.md](docs/benchmark-runbook.md).

## Documentation

Start at the [documentation index](docs/README.md): every page, grouped by task, with what it
covers.

<a id="status"></a><a id="roadmap-to-production-v1"></a><a id="author"></a><a id="license"></a>
<a id="contributing"></a><a id="security"></a><a id="code-of-conduct"></a>

## Project

**Status:** proof-of-concept (0.2.1), validated by unit + end-to-end tests (`mvn test`) and the
benchmarks above. The [roadmap](docs/roadmap.md) lists what a production v1 would add; release
history is in the [CHANGELOG](CHANGELOG.md).

- **Governance: published but not supported.** The code is shared because it may be useful, but
  contributions are not actively solicited and there is no support commitment. Bug reports,
  questions and ideas belong on the repository's GitHub Issues page, not with the author
  directly; see [CONTRIBUTING.md](CONTRIBUTING.md).
- **Security:** report vulnerabilities as described in [SECURITY.md](SECURITY.md), via
  Salesforce's responsible disclosure process, not as a public GitHub issue.
- **License:** Apache License, Version 2.0 ([LICENSE.txt](LICENSE.txt)). Every Salesforce-authored
  source and configuration file carries the `SPDX-License-Identifier: Apache-2.0` header.
- **Code of Conduct:** participation is governed by the Salesforce Open Source
  [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
- **Author:** created and maintained by Vitalii Mykytenko, Senior Principal Technical Support
  Engineer, MuleSoft Signature Success Support, Australia.

[Docs index](docs/README.md) · [Configuration](docs/configuration.md) ·
[Benchmarks](docs/benchmark-results.md) · [Roadmap](docs/roadmap.md) · [Changelog](CHANGELOG.md)
