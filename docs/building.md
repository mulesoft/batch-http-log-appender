# Building, installing and verifying

How to build the Maven reactor, install the artifacts into your local repository, check that the
build is sound, keep its dependencies current and CVE-free, and run the bundled local benchmark.
Using the installed jars from a Mule application is covered in
[Using the appender from a Mule app](mule.md).

On this page:

- [Build & install locally](#build--install-locally)
- [Verify the build](#verify-the-build)
- [Dependency hygiene & CVE scanning](#dependency-hygiene--cve-scanning)
- [Run the benchmark / example](#run-the-benchmark--example)

## Build & install locally

**Prerequisites:** JDK 17+ and Maven 3.8+ (check with `java -version` and `mvn -version`).

The project is a Maven multi-module reactor (`groupId` `com.mulesoft.support.batchhttp`, version
`0.2.0`). To build everything and install the artifacts into your local Maven repository
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
    <version>0.2.0</version>
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

## Run the benchmark / example

```bash
mvn install -DskipTests
cd demo/java/benchmark && mvn exec:java
```

This runs `BenchmarkDemo` against a local mock intake that adds a simulated 5 ms of latency per
request. It ships 5000 events twice:

- once "naive": one synchronous HTTP request per event, what the stock Log4j2 `HttpAppender` does;
- once through the batching engine.

A representative result:

| Mode | Wall clock | HTTP requests |
|------|-----------:|--------------:|
| naive (1 request/event) | 32634 ms | 5000 |
| **batched (this lib)** | **82 ms** | **10** |

**≈ 400× faster wall-clock, 500× fewer requests.**

The same module carries `src/main/resources/log4j2-example.xml`, an example configuration with a
`<BatchHttp>` appender for every vendor. It is the starting point for [Splunk](vendors/splunk.md)
and the [generic sink](vendors/generic.md), which have no sample Mule project.

For measurements against real vendor intakes on CloudHub, see the
[benchmark results](benchmark-results.md) and the reproduction guide in the
[benchmark runbook](benchmark-runbook.md).

## See also

- [Using the appender from a Mule app](mule.md) — bundling the appender into a Mule app, and the
  sample A/B pairs.
- [Configuration reference](configuration.md) — every `<BatchHttp>` option.
- [Architecture](architecture.md) — modules and data path.
- [Memory safety](memory-safety.md) — validating bounded memory and full drain.

[Docs index](README.md) · [Configuration](configuration.md) · [Mule apps](mule.md) ·
[Architecture](architecture.md) · [Memory safety](memory-safety.md)
