# Using the appender from a Mule app

How to bundle `<BatchHttp>` into a Mule 4 application, how to get API keys and tokens into
`log4j2.xml` (which Mule does not parse), and what the sample batch/stock Mule projects in
`demo/mule/` are for. The per-vendor settings are on the vendor pages; every option is in
[configuration.md](configuration.md).

- [Bundling the appender into a Mule app](#bundling-the-appender-into-a-mule-app)
- [log4j2.xml in a Mule app](#log4j2xml-in-a-mule-app)
- [Injecting secrets (API keys / tokens)](#injecting-secrets-api-keys--tokens)
- [The sample A/B pairs](#the-sample-ab-pairs)
- [Measuring the batching impact (A/B control)](#measuring-the-batching-impact-ab-control)
- [Splitting CloudHub 1.0 from CloudHub 2.0](#splitting-cloudhub-10-from-cloudhub-20)
- [Deploying the pairs to CloudHub 1.0](#deploying-the-pairs-to-cloudhub-10)

## Bundling the appender into a Mule app

Each `demo/mule/*-batch-http` module shows the appender bundled into a Mule 4.9 application;
the walkthrough below uses `demo/mule/newrelic-batch-http`. After `mvn install` (so the appender
jars are in your local `.m2` — see [building.md](building.md#build--install-locally)), build the
Mule app — the `mule-maven-plugin` bundles the appender into the app so Mule's app classloader
can discover the `<BatchHttp>` plugin at startup:

```bash
cd demo/mule/newrelic-batch-http && mvn clean package
```

Set the New Relic key at runtime (it is **not** committed) via an env var or JVM
arg, then deploy the resulting `target/*-mule-application.jar`:

```bash
export NEW_RELIC_API_KEY=YOUR_NEW_RELIC_INGEST_KEY
```

The other vendors' batch modules build the same way; their key names are listed in
[The sample A/B pairs](#the-sample-ab-pairs) and on each vendor page.

## log4j2.xml in a Mule app

Three points from the general usage notes matter in every Mule app:

- **The `packages` attribute is required.** The `<Configuration>` element must carry
  `packages="com.mulesoft.support.batchhttp.log4j2"`, because the library is on the
  application classloader rather than the Log4j2 boot classpath — which is the case in **every
  Mule 4 / CloudHub app**. Without it Log4j2 ignores `<BatchHttp>` (a startup warning names it
  as unknown, and no logs ship). Log4j2's own `WARN` that package scanning is deprecated is
  about the mechanism; keep the attribute.
- **Async loggers need no extra dependency.** `<AsyncRoot>` / `<AsyncLogger>` require the LMAX
  Disruptor, which the Mule runtime already provides.
- **The demos ship only the flow's logs.** Their root carries the file appender alone, because
  their layout wraps the flow's JSON message and a plain-text framework line would render as an
  invalid document. To ship framework and library logs as well, add the appender-ref to the
  root and keep `additivity="false"` on the application logger so nothing ships twice.

## Injecting secrets (API keys / tokens)

`log4j2.xml` is parsed by **Log4j2's own property resolver, not by Mule** (or Spring, or
any host framework). Two consequences that trip people up:

- **Mule `${...}` placeholders do *not* work here.** A `secure::` property or an
  `${api.key}` from a Mule property file will arrive as the literal string. Use a
  **Log4j2 lookup** instead — `${sys:...}` for a JVM system property or `${env:...}` for
  an environment variable:

  ```xml
  <BatchHttp name="datadog" vendor="datadog"
             apiKey="${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}"/>
  ```

  The `:-` chains fallbacks left to right and ends in a visible sentinel, so a missing
  secret surfaces as an obvious `MISSING_KEY` rejection rather than the literal `${env:...}`
  text Log4j2 leaves in place when a lookup does not resolve.

- **The value must reach the JVM as a system property or an environment variable.** A value
  that exists only inside Mule — a Mule properties file, or a `secure::` placeholder — is
  invisible to Log4j2. Set it as a JVM system property (e.g. `-Ddatadog.apiKey=…` via the
  runtime's JVM args / wrapper) so the `${sys:...}` lookup resolves it, or as an environment
  variable for the `${env:...}` leg. Where that goes depends on the runtime:
  - **CloudHub** — the demo apps take their keys from Runtime Manager **Properties** (the key
    as a **secure** property, so it is masked). CloudHub 1.0 surfaces those as JVM system
    properties, and the Elasticsearch and Dynatrace demo READMEs rely on CloudHub passing
    application properties to the JVM the same way on CloudHub 2.0. Alternatively add a
    `-D` JVM argument, or on CloudHub 2.0 set the key as a (secure) environment variable in the
    deployment. [benchmark-runbook.md](benchmark-runbook.md#where-to-put-the-jvm-args) lists
    where each plane takes JVM arguments.
  - **Standalone Mule / Anypoint Code Builder** — under the Mule wrapper a JVM argument must be
    written `-M-Ddatadog.apiKey=…`; a bare `-D` is taken by the wrapper and never becomes a
    system property of the Mule JVM, so the lookup falls through to its sentinel.

The `apiKey`/`token` attributes ([`apiKey`](configuration.md#opt-apikey),
[`token`](configuration.md#opt-token)) are marked `sensitive`, so Log4j2 masks them in its
own status/debug output.

## The sample A/B pairs

Each vendor with a sample project has a **pair** of Mule apps: the *batch* app uses
`<BatchHttp>`, and its *stock* control sibling uses Log4j2's built-in `<Http>` appender (one
POST per event). Within a pair the flow and workload are identical, so the pair isolates the
appender. Every Mule demo uses a distinct port (8081–8088) so they can all run at once.

| Vendor | Batch app (port) | Stock app (port) | Credential | Vendor page |
|--------|------------------|------------------|------------|-------------|
| New Relic | `demo/mule/newrelic-batch-http` (8081) | `demo/mule/newrelic-stock-http` (8082) | `NEW_RELIC_API_KEY` / `-Dnewrelic.apiKey` | [newrelic.md](vendors/newrelic.md) |
| Datadog | `demo/mule/datadog-batch-http` (8083) | `demo/mule/datadog-stock-http` (8084) | `DD_API_KEY` / `-Ddatadog.apiKey` | [datadog.md](vendors/datadog.md) |
| Elasticsearch | `demo/mule/elastic-batch-http` (8085) | `demo/mule/elastic-stock-http` (8086) | `elastic.url` + secure `elastic.apiKey` | [elasticsearch.md](vendors/elasticsearch.md) |
| Dynatrace | `demo/mule/dynatrace-batch-http` (8087) | `demo/mule/dynatrace-stock-http` (8088) | `dynatrace.url` + secure `dynatrace.token` | [dynatrace.md](vendors/dynatrace.md) |

Splunk and the generic sink have no sample Mule project; start from the Java example
configuration `demo/java/benchmark/src/main/resources/log4j2-example.xml` (see
[splunk.md](vendors/splunk.md) and [generic.md](vendors/generic.md)).

### New Relic pair

`demo/mule/newrelic-batch-http` (port 8081) and its control sibling
`demo/mule/newrelic-stock-http` (port 8082) are the reference pair; the other pairs below are
described relative to it. The New Relic flows lead each event with an extra `message` summary key, because
that vendor's Logs UI parses a JSON `message` and shows nothing without one.

### Datadog flavor of the same pair

The `demo/mule/datadog-batch-http` and `demo/mule/datadog-stock-http` modules are the **Datadog
counterparts** of the New Relic pair above — the same flow shape and workload, shipping instead
to the Datadog Logs intake (`/api/v2/logs`). The two pairs are not byte-identical to each other:
the New Relic flows carry the extra `message` key described above. Within a pair the flow *is*
identical, which is what the A/B needs. `datadog-batch-http` (port 8083) uses
`<BatchHttp vendor="datadog">`; its stock-`<Http>` control sibling `datadog-stock-http`
(port 8084) sends one POST per event. See each module's README for build, key injection
(`DD_API_KEY` / `-Ddatadog.apiKey`), and the A/B measurement guide.

### Elasticsearch flavor of the same pair

`demo/mule/elastic-batch-http` (port 8085) and `demo/mule/elastic-stock-http` (port 8086) are
the **Elasticsearch counterparts** of the Datadog pair: the same flow and workload, shipping to an
Elasticsearch data stream (`logs-mule-default` by default). The batch app uses
`<BatchHttp vendor="elasticsearch">`, which posts to the Bulk API. Its stock-`<Http>` sibling
posts one document per event to `/<data-stream>/_doc`, and its layout spells out the same
ECS document, so the stored documents differ only in `service.name`. Both read the endpoint
from the `elastic.url` property and the API key from the secure `elastic.apiKey` property. See
each module's README for the API key privileges and the optional ingest pipeline that parses the
JSON inside `message`.

### Dynatrace flavor of the same pair

`demo/mule/dynatrace-batch-http` (port 8087) and `demo/mule/dynatrace-stock-http` (port 8088) are
the **Dynatrace counterparts** of the Elasticsearch pair: the same flow and workload, shipping to
the Log Monitoring API v2 (`/api/v2/logs/ingest`). The batch app uses
`<BatchHttp vendor="dynatrace">`, which posts a JSON array per batch. Its stock-`<Http>` sibling
posts one log object per event, and its layout spells out the same attributes the sink sends, so
the stored records differ only in `log.source` — the CloudHub application name
(`${sys:domain}`; on CloudHub 2.0 set a `domain` application property). Both read the environment
URL from the `dynatrace.url` property and an access token with only the `logs.ingest` scope from
the secure `dynatrace.token` property. See each module's README for finding and counting the
records with DQL.

## Measuring the batching impact (A/B control)

The stock module of each pair is a **control sibling** of the batch module: the same Mule app
and workload, but wired to Log4j2's **stock `<Http>` appender** (one synchronous POST per event)
instead of `<BatchHttp>`. Run both side-by-side (for the New Relic pair, ports 8081 vs 8082)
against a local mock intake and compare request counts and wall-clock to quantify the batching
speedup. See the stock module's README (for example `demo/mule/newrelic-stock-http/README.md`)
for the step-by-step measurement guide and a ready-to-run mock. Results measured against the
real vendor intakes on CloudHub are in [benchmark-results.md](benchmark-results.md).

Note that **the batch demo apps deliberately override two library defaults**, in each case
to keep the A/B varying only the appender:

- **[`gzip`](configuration.md#opt-gzip)`="false"`**. The stock `<Http>` appender has no
  compression option, so leaving gzip on would make the pair differ by batching *and*
  compression.
- **[`includeTimestamp`](configuration.md#opt-includetimestamp)`="false"`** (Datadog and New
  Relic pairs only; the Elasticsearch and Dynatrace sinks always send the event time). This
  one is not about the stock appender's capabilities but about what stays observable: with the vendor's
  reserved event-time field omitted, the vendor stamps each record with its own *arrival* time,
  so `arrival − generatedAt` is the ingest lag (measured for Datadog on
  [datadog.md](vendors/datadog.md)). Supply the field and the stored timestamp equals the
  generation time, hiding shipping delay by construction. The stock siblings omit it the same
  way — their layouts carry no such key — so both halves are measured identically.

The first is the right production setting to change back — a real deployment should leave
compression on. The second is a measurement decision either way: a production app usually
*wants* its own event time.

**Disk spill is on**, the library default: the batch demos ship
`spillEnabled="${sys:batch.spillEnabled:-true}"`, so queue overflow and batches that fail every
retry go to disk and replay when the intake catches up, and `spilledRecords` is the overload
signal. Set the system property `batch.spillEnabled=false` to measure batching alone; overflow
then drops per [`overflowPolicy`](configuration.md#opt-overflowpolicy) and `dropped` becomes the
signal (see [resilience.md](resilience.md#it-is-on-by-default--read-this-before-upgrading)). The
spill buffer's replay threads and disk I/O share a 0.1 vCore worker or a CPU-capped CloudHub 2.0
replica with request handling; in the 2026-10-12 benchmark that cost batch req/s only where the
queue overflowed the whole time.

## Splitting CloudHub 1.0 from CloudHub 2.0

Every demo emits a **`platform`** attribute on every event, so a pair deployed to both
CloudHub generations stays separable in the log backend. The two facets have one job each and
should not be swapped: **`service` identifies the app** (batch versus stock) and **`platform`
identifies the plane** (CloudHub 1.0 versus 2.0). Both are overridable — `service` with
`-Dapp.service=...`, defaulting to each app's own name, and `platform` as below — and for both, an
override you no longer want must be **deleted, not cleared**: Log4j2 honours a `:-` default only
when the property is *absent*, so a present-but-empty value wins and blanks the facet. Overloading
`service` with a plane name and then clearing it is precisely how the `SERVICE` column once went
empty for a whole run, and `platform` exists so there is no reason to overload it again.

Set the plane per deployment with `-Dapp.platform=cloudhub-1.0` or
`APP_PLATFORM=cloudhub-2.0`; unset it reads `unspecified` rather than guessing. Both lookup keys
are namespaced because `sys:` and `env:` are shared with whatever the container already defines —
a bare `PLATFORM` is a plausible base-image variable and would silently shadow the facet. The
emitted attribute stays `platform`. It must be set on **both** apps of a pair to be useful — a
value on one half only makes the pair look like a comparison of two platforms. This is a demo
convenience, not a library feature: a `<Properties>` entry plus one field in each app's
`<PatternLayout>`, which is the one mechanism `<BatchHttp>` and the stock `<Http>` appender share.

## Deploying the pairs to CloudHub 1.0

`scripts/deploy-ch1.sh` deploys (or redeploys) a pair to CloudHub 1.0 per
[benchmark-runbook.md](benchmark-runbook.md): `--datadog` (the default), `--newrelic`,
`--elastic` or `--dynatrace` selects the pair, `--all` the Datadog and New Relic pairs, explicit
app names override the flags, and `--dry-run` prints the commands with secrets masked. Run it with
`--help` for the required environment (Connected App credentials, organization, environment,
region and the vendor credential). On CloudHub 1.0 the application name is the public hostname
label, so set `CH1_APP_SUFFIX` to the suffix your deployed apps carry, or the script stands up a
parallel app instead of redeploying the existing one.

**The scripted CloudHub 1.0 deploy does not set the `platform` facet.** `scripts/deploy-ch1.sh`
passes only the vendor credential (for the Elasticsearch and Dynatrace pairs, also
`elastic.url` / `dynatrace.url`) as `--property`, so an app deployed through it reports
`platform: unspecified` until you add `app.platform` by hand in the Runtime Manager **Properties**
tab (CloudHub 1.0 surfaces those as JVM system properties) and restart. Do that for both halves of
the pair before a benchmark run, or the CH1 side of the comparison arrives unlabelled.

## See also

- Vendor pages: [Datadog](vendors/datadog.md), [New Relic](vendors/newrelic.md),
  [Elasticsearch](vendors/elasticsearch.md), [Dynatrace](vendors/dynatrace.md),
  [Splunk](vendors/splunk.md), [generic](vendors/generic.md).
- [configuration.md](configuration.md) — every `<BatchHttp>` option.
- [building.md](building.md) — building and installing the appender jars.
- [benchmark-runbook.md](benchmark-runbook.md) — deploying and measuring the pairs on CloudHub.
- [troubleshooting.md](troubleshooting.md) — where the appender reports its health in a Mule app.
- [Docs index](README.md)
