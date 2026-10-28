# Roadmap to production v1

What a production v1 of batch-http-appender would add to the current proof-of-concept (0.3.0),
which is validated by unit + end-to-end tests (`mvn test`) and the
[CloudHub benchmarks](benchmark-results.md).

## What v1 would add

- **Logback adapter** module (the core is already framework-agnostic).
- **Circuit breaker** to stop hammering a downed endpoint.
- Per-sink **metrics export** (Micrometer) and JMX beans.
- JMH benchmarks and back-pressure soak tests.
- **mTLS (client-certificate auth)** — a `keyStore`/`keyStorePassword` (and optional
  custom `trustStore`) on the appender, feeding a `KeyManager` into the same transport
  `SSLContext` the [`trustAllCertificates`](configuration.md#opt-trustallcertificates) switch
  already uses on the TrustManager side. For collectors that require the client to present a
  certificate.
- Consider the **OpenTelemetry** appender + Collector path as an alternative for
  multi-vendor routing without app redeploys (build-vs-adopt trade-off).

Ideas and discussion belong on the repository's GitHub Issues page; see
[Contributing](../CONTRIBUTING.md).

[Docs index](README.md) · [Configuration](configuration.md) · [Project README](../README.md) ·
[Changelog](../CHANGELOG.md) · [Contributing](../CONTRIBUTING.md)
