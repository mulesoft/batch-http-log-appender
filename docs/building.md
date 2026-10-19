# Building, installing and verifying

How to build the Maven reactor, install the artifacts into your local repository, check that the
build is sound, keep its dependencies current and CVE-free, and run the bundled local benchmark.
Using the installed jars from a Mule application is covered in
[Using the appender from a Mule app](mule.md).

On this page:

- [Build & install locally](#build--install-locally)
- [Verify the build](#verify-the-build)
- [Dependency hygiene & CVE scanning](#dependency-hygiene--cve-scanning)
- [Local benchmark](#local-benchmark)

## Build & install locally

**Prerequisites:** JDK 17+ and Maven 3.8+ (check with `java -version` and `mvn -version`).

The project is a Maven multi-module reactor (`groupId` `com.mulesoft.support.batchhttp`, version
`0.2.2`). To build everything and install the artifacts into your local Maven repository
(`~/.m2/repository`) so other projects can depend on them:

```bash
git clone https://github.com/mulesoft/batch-http-log-appender.git
cd batch-http-log-appender
mvn clean install
```

This compiles all modules, runs the unit + end-to-end tests, and installs the jars locally. To
skip tests for a faster install:

```bash
mvn clean install -DskipTests
```

To build and install **only the appender and its engine** (no examples):

```bash
mvn -pl batch-http-core,batch-http-log4j2 -am clean install
```

After install, the artifacts are available to other builds as:

```xml
<dependency>
    <groupId>com.mulesoft.support.batchhttp</groupId>
    <artifactId>batch-http-log4j2</artifactId>
    <version>0.2.2</version>
</dependency>
```

`batch-http-log4j2` transitively brings in `batch-http-core`. The core jar has **no runtime
dependencies** beyond the JDK. The module layout is described in
[Architecture: Modules](architecture.md#modules).

## Verify the build

```bash
mvn test                                  # run all unit + end-to-end tests
mvn -pl batch-http-core dependency:tree   # confirm core has zero runtime deps
```

The end-to-end tests also assert the record-conservation and full-drain invariants described in
[Memory safety](memory-safety.md).

## Dependency hygiene & CVE scanning

Keeping third-party dependencies current and free of known CVEs is a project requirement, not an
afterthought. The surface is deliberately tiny:

- `batch-http-core` has **zero** runtime dependencies.
- `batch-http-log4j2` compiles against log4j 2.x, which the Mule runtime provides, so it is
  excluded at runtime.
- All versions are pinned in the parent POM.

```bash
# What's stale? Reports any dependency or plugin with a newer release.
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates

# CVE gate: OWASP dependency-check against the NVD over the whole reactor.
# Fails the build on any dependency with CVSS >= 7 (high/critical).
mvn -Psecurity verify
```

Notes on the CVE gate:

- **The `security` profile is opt-in**, because a full NVD scan is slow and benefits from an
  [NVD API key](https://nvd.nist.gov/developers/request-an-api-key) (`-Dnvd.api.key=…`).
- Wire `mvn -Psecurity verify` into CI (nightly or pre-release) rather than every local build.
- Accepted/false-positive findings go in a `dependency-check-suppressions.xml`.

> [!IMPORTANT]
> Stay on the **log4j 2.x** line. A 3.x bump would diverge from the log4j the Mule runtime
> actually supplies.

<a id="run-the-benchmark--example"></a>

## Local benchmark

`BenchmarkDemo` sends the same 5,000 log events twice to a mock intake on the same machine. The
intake takes 5 ms to answer each request.

1. **Stock path:** one blocking HTTP request per event on the calling thread, which mimics what
   Log4j2's built-in `HttpAppender` does.
2. **Batch path:** the same events through this appender's batching engine. The demo sets
   `maxBatchRecords` to 500; with the default of 200 it would send about 25 requests.

A representative result:

| | Stock `HttpAppender` style | Batch appender |
|---|---|---|
| How it sends | One request per event, waits for each answer | 500 events per request, from a background thread |
| HTTP requests | 5,000 | 10 (**500× fewer**) |
| Time to deliver all 5,000 events | 32.6 s | 0.08 s (**about 400× faster**) |
| Your thread while logging | Blocked on every request | Continues immediately |

The batch time includes `close()`, which waits until every queued event has been sent, so both
columns measure full delivery. This is the best case by design: the mock answers in exactly 5 ms
whatever the batch size. On real networks the gain depends on how far away the intake is.

### Run it

```bash
mvn install -DskipTests
cd demo/java/benchmark && mvn exec:java
```

Expect it to take about half a minute: the stock path alone needs roughly 33 seconds.

### Example configuration for every vendor

The same module carries `src/main/resources/log4j2-example.xml`, an example configuration with a
`<BatchHttp>` appender for every vendor. It is the starting point for [Splunk](vendors/splunk.md)
and the [generic sink](vendors/generic.md), which have no sample Mule project.

> [!NOTE]
> This is a local, synthetic measurement. For results against real vendor intakes on CloudHub,
> see the [benchmark results](benchmark-results.md); the [benchmark runbook](benchmark-runbook.md)
> explains how to reproduce them.

## See also

- [Using the appender from a Mule app](mule.md) — bundling the appender into a Mule app, and the
  sample A/B pairs.
- [Configuration reference](configuration.md) — every `<BatchHttp>` option.
- [Architecture](architecture.md) — modules and data path.
- [Memory safety](memory-safety.md) — validating bounded memory and full drain.

[Docs index](README.md) · [Configuration](configuration.md) · [Mule apps](mule.md) ·
[Architecture](architecture.md) · [Memory safety](memory-safety.md)
