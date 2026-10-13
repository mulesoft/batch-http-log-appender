# Using the appender from a Mule app

How to bundle `<BatchHttp>` into a Mule 4 application, how to get API keys and tokens into
`log4j2.xml` (which Mule does not parse), and what the sample batch/stock Mule projects in
`demo/mule/` are for. The per-vendor settings are on the vendor pages; every option is in the
[configuration reference](configuration.md).

On this page:

- [Bundling the appender into a Mule app](#bundling-the-appender-into-a-mule-app)
- [log4j2.xml in a Mule app](#log4j2xml-in-a-mule-app)
- [Injecting secrets (API keys / tokens)](#injecting-secrets-api-keys--tokens)
- [The sample A/B pairs](#the-sample-ab-pairs)
- [Measuring the batching impact (A/B control)](#measuring-the-batching-impact-ab-control)
- [Splitting CloudHub 1.0 from CloudHub 2.0](#splitting-cloudhub-10-from-cloudhub-20)
- [Deploying the pairs to CloudHub 1.0](#deploying-the-pairs-to-cloudhub-10)

## Bundling the appender into a Mule app

Each `demo/mule/*-batch-http` module shows the appender bundled into a Mule 4.9 application.
The walkthrough below uses `demo/mule/newrelic-batch-http`:

1. Run `mvn install` so the appender jars are in your local `.m2` (see
   [Build & install locally](building.md#build--install-locally)).
2. Build the Mule app. The `mule-maven-plugin` bundles the appender into the app, so Mule's app
   classloader can discover the `<BatchHttp>` plugin at startup:

   ```bash
   cd demo/mule/newrelic-batch-http && mvn clean package
   ```

3. Set the New Relic key at runtime via an env var or JVM arg. The key is **not** committed:

   ```bash
   export NEW_RELIC_API_KEY=YOUR_NEW_RELIC_INGEST_KEY
   ```

4. Deploy the resulting `target/*-mule-application.jar`.

The other vendors' batch modules build the same way. Their key names are listed in
[The sample A/B pairs](#the-sample-ab-pairs) and on each vendor page.

## log4j2.xml in a Mule app

Three points from the general usage notes matter in every Mule app.

> [!IMPORTANT]
> The `<Configuration>` element **must** carry `packages="com.mulesoft.support.batchhttp.log4j2"`.
> The library is on the application classloader rather than the Log4j2 boot classpath, which is
> the case in every Mule 4 / CloudHub app. Without the attribute Log4j2 ignores `<BatchHttp>`: a
> startup warning names it as unknown, and no logs ship.

```xml
<Configuration packages="com.mulesoft.support.batchhttp.log4j2">
    <!-- Properties, Appenders (including BatchHttp) and Loggers go here -->
</Configuration>
```

Log4j2's own `WARN` that package scanning is deprecated is about the mechanism; keep the
attribute. The other two points:

- **Async loggers need no extra dependency.** `<AsyncRoot>` / `<AsyncLogger>` require the LMAX
  Disruptor, which the Mule runtime already provides.
- **The demos ship only the flow's logs.** Their root carries the file appender alone, because
  their layout wraps the flow's JSON message, and a plain-text framework line would render as an
  invalid document. To ship framework and library logs as well, add the appender-ref to the root
  and keep `additivity="false"` on the application logger so nothing ships twice.

## Injecting secrets (API keys / tokens)

`log4j2.xml` is parsed by **Log4j2's own property resolver, not by Mule** (or Spring, or any host
framework). That has two consequences that trip people up.

### Use a Log4j2 lookup, not a Mule placeholder

Mule `${...}` placeholders do *not* work in `log4j2.xml`. A `secure::` property or an
`${api.key}` from a Mule property file arrives as the literal string. Use a Log4j2 lookup instead:
`${sys:...}` for a JVM system property, or `${env:...}` for an environment variable.

```xml
<BatchHttp name="datadog" vendor="datadog"
           apiKey="${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}"/>
```

The `:-` chains fallbacks left to right and ends in a visible sentinel. A missing secret then
surfaces as an obvious `MISSING_KEY` rejection, rather than the literal `${env:...}` text Log4j2
leaves in place when a lookup does not resolve.

### Get the value into the JVM

The value must reach the JVM as a system property or an environment variable. A value that exists
only inside Mule (a Mule properties file, or a `secure::` placeholder) is invisible to Log4j2.
Set it as a JVM system property (for example `-Ddatadog.apiKey=…` via the runtime's JVM args /
wrapper) so the `${sys:...}` lookup resolves it, or as an environment variable for the
`${env:...}` leg. Where that goes depends on the runtime:

| Runtime | Where to set the key |
|---------|----------------------|
| CloudHub 1.0 | A **secure** Runtime Manager property (see note 1), or a `-D` JVM argument |
| CloudHub 2.0 | A secure Runtime Manager property (see note 2), a `-D` JVM argument, or a (secure) environment variable in the deployment |
| Standalone Mule / Anypoint Code Builder | A JVM argument written `-M-Ddatadog.apiKey=…` (see note 3) |

1. The demo apps take their keys from Runtime Manager **Properties**, the key as a secure property
   so it is masked. CloudHub 1.0 surfaces those properties as JVM system properties.
2. The Elasticsearch and Dynatrace demo READMEs rely on CloudHub passing application properties
   to the JVM the same way on CloudHub 2.0.
3. Under the Mule wrapper a bare `-D` is taken by the wrapper and never becomes a system property
   of the Mule JVM, so the lookup falls through to its sentinel.

[Where to put the JVM args](benchmark-runbook.md#where-to-put-the-jvm-args) in the benchmark
runbook lists where each plane takes JVM arguments.

The [`apiKey`](configuration.md#opt-apikey) and [`token`](configuration.md#opt-token) attributes
are marked `sensitive`, so Log4j2 masks them in its own status/debug output.

## The sample A/B pairs

Each vendor with a sample project has a **pair** of Mule apps:

- the *batch* app uses `<BatchHttp>`;
- its *stock* control sibling uses Log4j2's built-in `<Http>` appender (one POST per event).

Within a pair the flow and workload are identical, so the pair isolates the appender. Every Mule
demo uses a distinct port (8081–8088), so they can all run at once.

| Vendor | Batch app (port) | Stock app (port) | Credential | Vendor page |
|--------|------------------|------------------|------------|-------------|
| New Relic | `demo/mule/newrelic-batch-http` (8081) | `demo/mule/newrelic-stock-http` (8082) | `NEW_RELIC_API_KEY` / `-Dnewrelic.apiKey` | [New Relic](vendors/newrelic.md) |
| Datadog | `demo/mule/datadog-batch-http` (8083) | `demo/mule/datadog-stock-http` (8084) | `DD_API_KEY` / `-Ddatadog.apiKey` | [Datadog](vendors/datadog.md) |
| Elasticsearch | `demo/mule/elastic-batch-http` (8085) | `demo/mule/elastic-stock-http` (8086) | `elastic.url` + secure `elastic.apiKey` | [Elasticsearch](vendors/elasticsearch.md) |
| Dynatrace | `demo/mule/dynatrace-batch-http` (8087) | `demo/mule/dynatrace-stock-http` (8088) | `dynatrace.url` + secure `dynatrace.token` | [Dynatrace](vendors/dynatrace.md) |

> [!NOTE]
> Splunk and the generic sink have no sample Mule project. Start from the Java example
> configuration `demo/java/benchmark/src/main/resources/log4j2-example.xml` (see the
> [Splunk](vendors/splunk.md) and [generic sink](vendors/generic.md) pages).

### New Relic pair

`demo/mule/newrelic-batch-http` (port 8081) and its control sibling
`demo/mule/newrelic-stock-http` (port 8082) are the reference pair; the other pairs below are
described relative to it.

- **Extra `message` key:** the New Relic flows lead each event with an extra `message` summary
  key, because that vendor's Logs UI parses a JSON `message` and shows nothing without one.

### Datadog flavor of the same pair

`demo/mule/datadog-batch-http` (port 8083) and `demo/mule/datadog-stock-http` (port 8084) are the
Datadog counterparts of the New Relic pair: the same flow shape and workload, shipping instead to
the Datadog Logs intake.

- **Intake:** `/api/v2/logs`.
- **Batch app:** `<BatchHttp vendor="datadog">`. The stock-`<Http>` control sibling sends one
  POST per event.
- **Versus the New Relic pair:** the two pairs are not byte-identical to each other, because the
  New Relic flows carry the extra `message` key. Within a pair the flow *is* identical, which is
  what the A/B needs.
- **Module READMEs:** build, key injection (`DD_API_KEY` / `-Ddatadog.apiKey`), and the A/B
  measurement guide.

### Elasticsearch flavor of the same pair

`demo/mule/elastic-batch-http` (port 8085) and `demo/mule/elastic-stock-http` (port 8086) are the
Elasticsearch counterparts of the Datadog pair: the same flow and workload, shipping to an
Elasticsearch data stream.

- **Intake:** a data stream, `logs-mule-default` by default.
- **Batch app:** `<BatchHttp vendor="elasticsearch">`, which posts to the Bulk API.
- **Stock app:** posts one document per event to `/<data-stream>/_doc`. Its layout spells out the
  same ECS document, so the stored documents differ only in `service.name`.
- **Credentials:** both read the endpoint from the `elastic.url` property and the API key from
  the secure `elastic.apiKey` property.
- **Module READMEs:** the API key privileges, and the optional ingest pipeline that parses the
  JSON inside `message`.

### Dynatrace flavor of the same pair

`demo/mule/dynatrace-batch-http` (port 8087) and `demo/mule/dynatrace-stock-http` (port 8088) are
the Dynatrace counterparts of the Elasticsearch pair: the same flow and workload, shipping to the
Log Monitoring API v2.

- **Intake:** `/api/v2/logs/ingest`.
- **Batch app:** `<BatchHttp vendor="dynatrace">`, which posts a JSON array per batch.
- **Stock app:** posts one log object per event. Its layout spells out the same attributes the
  sink sends, so the stored records differ only in `log.source`: the CloudHub application name
  (`${sys:domain}`; on CloudHub 2.0 set a `domain` application property).
- **Credentials:** both read the environment URL from the `dynatrace.url` property, and an access
  token with only the `logs.ingest` scope from the secure `dynatrace.token` property.
- **Module READMEs:** finding and counting the records with DQL.

## Measuring the batching impact (A/B control)

The stock module of each pair is a **control sibling** of the batch module: the same Mule app and
workload, but wired to Log4j2's stock `<Http>` appender (one synchronous POST per event) instead
of `<BatchHttp>`.

To quantify the batching speedup, run both side by side (for the New Relic pair, ports 8081 vs
8082) against a local mock intake, and compare request counts and wall-clock. The stock module's
README (for example `demo/mule/newrelic-stock-http/README.md`) has the step-by-step measurement
guide and a ready-to-run mock. Results measured against the real vendor intakes on CloudHub are
in [the benchmark results](benchmark-results.md).

### Library defaults the batch demos override

The batch demo apps **deliberately override two library defaults**, in each case to keep the A/B
varying only the appender:

| Option | Demo value | Why | In production |
|--------|------------|-----|---------------|
| [`gzip`](configuration.md#opt-gzip) | `"false"` | The stock `<Http>` appender has no compression option (note 1) | Change it back: leave compression on |
| [`includeTimestamp`](configuration.md#opt-includetimestamp) | `"false"` (Datadog and New Relic pairs only, note 2) | Keeps the ingest lag observable (note 3) | A measurement decision either way; a production app usually *wants* its own event time |

1. Leaving gzip on would make the pair differ by batching *and* compression.
2. The Elasticsearch and Dynatrace sinks always send the event time.
3. This one is not about the stock appender's capabilities but about what stays observable. With
   the vendor's reserved event-time field omitted, the vendor stamps each record with its own
   *arrival* time, so `arrival − generatedAt` is the ingest lag (measured for Datadog on the
   [Datadog page](vendors/datadog.md)). Supply the field and the stored timestamp equals the
   generation time, hiding shipping delay by construction. The stock siblings omit it the same
   way (their layouts carry no such key), so both halves are measured identically.

### Disk spill stays on

**Disk spill is on**, the library default: the batch demos ship
`spillEnabled="${sys:batch.spillEnabled:-true}"`. Set the system property
`batch.spillEnabled=false` to measure batching alone.

| `batch.spillEnabled` | What happens | Overload signal |
|----------------------|--------------|-----------------|
| unset / `true` (default) | Queue overflow and batches that fail every retry go to disk, and replay when the intake catches up | `spilledRecords` |
| `false` | Overflow drops per [`overflowPolicy`](configuration.md#opt-overflowpolicy) | `dropped` |

See the resilience page's
[spill-default notes](resilience.md#it-is-on-by-default--read-this-before-upgrading).

The spill buffer's replay threads and disk I/O share a 0.1 vCore worker, or a CPU-capped
CloudHub 2.0 replica, with request handling. In the 2026-10-12 benchmark that
cost batch req/s only where the queue overflowed the whole time.

## Splitting CloudHub 1.0 from CloudHub 2.0

Every demo emits a **`platform`** attribute on every event, so a pair deployed to both CloudHub
generations stays separable in the log backend. The two facets have one job each and should not
be swapped:

| Facet | Identifies | Override with | When unset |
|-------|------------|---------------|------------|
| `service` | The app (batch versus stock) | `-Dapp.service=...` | Each app's own name |
| `platform` | The plane (CloudHub 1.0 versus 2.0) | `-Dapp.platform=cloudhub-1.0` or `APP_PLATFORM=cloudhub-2.0` | `unspecified` (rather than guessing) |

> [!IMPORTANT]
> An override you no longer want must be **deleted, not cleared**. Log4j2 honours a `:-` default
> only when the property is *absent*, so a present-but-empty value wins and blanks the facet.
> Overloading `service` with a plane name and then clearing it is precisely how the `SERVICE`
> column once went empty for a whole run; `platform` exists so there is no reason to overload it
> again.

Set the plane per deployment, and on **both** apps of a pair: a value on one half only makes the
pair look like a comparison of two platforms.

Both lookup keys are namespaced because `sys:` and `env:` are shared with whatever the container
already defines. A bare `PLATFORM` is a plausible base-image variable and would silently shadow
the facet. The emitted attribute stays `platform`.

This is a demo convenience, not a library feature: a `<Properties>` entry plus one field in each
app's `<PatternLayout>`, which is the one mechanism `<BatchHttp>` and the stock `<Http>` appender
share.

## Deploying the pairs to CloudHub 1.0

`scripts/deploy-ch1.sh` deploys (or redeploys) a pair to CloudHub 1.0 per the
[benchmark runbook](benchmark-runbook.md):

| Argument | Effect |
|----------|--------|
| `--datadog` (the default) | Selects the Datadog pair |
| `--newrelic` | Selects the New Relic pair |
| `--elastic` | Selects the Elasticsearch pair |
| `--dynatrace` | Selects the Dynatrace pair |
| `--all` | Selects the Datadog and New Relic pairs |
| explicit app names | Override the flags |
| `--dry-run` | Prints the commands with secrets masked |
| `--help` | Lists the required environment (see below) |

The required environment is the Connected App credentials, organization, environment, region and
the vendor credential. On CloudHub 1.0 the application name is the public hostname label, so set
`CH1_APP_SUFFIX` to the suffix your deployed apps carry. Otherwise the script stands up a parallel
app instead of redeploying the existing one.

> [!NOTE]
> **The scripted CloudHub 1.0 deploy does not set the `platform` facet.** `scripts/deploy-ch1.sh`
> passes only the vendor credential as `--property` (for the Elasticsearch and Dynatrace pairs,
> also `elastic.url` / `dynatrace.url`). An app deployed through it reports
> `platform: unspecified` until you add `app.platform` by hand in the Runtime Manager
> **Properties** tab (CloudHub 1.0 surfaces those as JVM system properties) and restart. Do that
> for both halves of the pair before a benchmark run, or the CH1 side of the comparison arrives
> unlabelled.

## See also

- Vendor pages: [Datadog](vendors/datadog.md), [New Relic](vendors/newrelic.md),
  [Elasticsearch](vendors/elasticsearch.md), [Dynatrace](vendors/dynatrace.md),
  [Splunk](vendors/splunk.md), [generic](vendors/generic.md).
- [Configuration reference](configuration.md) — every `<BatchHttp>` option.
- [Building](building.md) — building and installing the appender jars.
- [Benchmark runbook](benchmark-runbook.md) — deploying and measuring the pairs on CloudHub.
- [Troubleshooting](troubleshooting.md) — where the appender reports its health in a Mule app.

[Docs index](README.md) · [Configuration](configuration.md) · [Building](building.md) ·
[Architecture](architecture.md) · [Benchmark runbook](benchmark-runbook.md) ·
[Troubleshooting](troubleshooting.md)
