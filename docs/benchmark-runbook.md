<!--
SPDX-License-Identifier: Apache-2.0
-->

# CloudHub benchmark runbook

How to reproduce (or re-run) the CloudHub benchmark of the batch appender against the stock
`<Http>` appender at **Datadog, New Relic, Elasticsearch and Dynatrace**. The results are
summarised on the [landing page](../README.md#benchmarks) and on each
[vendor page](README.md#pick-your-vendor), and reported in full in
[`benchmark-results.md`](benchmark-results.md) / [`index.html`](index.html).

This is the operator-facing **manual single-run** procedure: `oha` against `/log?count=20` (22
log events per request), after a warmup, driven paired-parallel with one vendor per side. §7 is
the authoritative statement of it; §6 is where you confirm it before measuring.

| Protocol parameter | CloudHub 1.0 | CloudHub 2.0 |
| --- | --- | --- |
| URL scheme | plain `http` | `https` |
| Concurrency | `-c 10` | `-c 5` |
| Payload | `/log?count=20` (22 events/request) | `/log?count=20` (22 events/request) |
| Sizing | 1 worker × 0.1 vCore | 1 replica × 0.1 vCore |
| Runtime / Java | 4.9.15 / Java 17 | 4.9.15 / Java 17 |

The runbook also exists because **a version string cannot identify a build**. The appender jars
have changed repeatedly while the POM version stayed at `0.1.3` — in the append hot path, UTF-8
byte estimation, flusher lifecycle/termination, retry timing and spill accounting — so `0.1.3`
alone cannot tell you which code produced a number. Re-run from source, record the commit, and
republish.

<a id="contents"></a>

On this page:

- [Provenance of the published figures](#provenance-of-the-published-figures)
- [1. What you must supply](#1-what-you-must-supply)
- [2. Build the artifacts](#2-build-the-artifacts)
- [3. Properties each app needs](#3-properties-each-app-needs)
- [4. Deploy — CloudHub 1.0](#4-deploy--cloudhub-10)
- [5. Deploy — CloudHub 2.0](#5-deploy--cloudhub-20)
- [6. Pre-flight fairness checklist](#6-pre-flight-fairness-checklist)
- [7. Warmup and measured run](#7-warmup-and-measured-run)
- [8. What to record](#8-what-to-record)
- [9. Datadog screenshots (Figures 2–4)](#9-datadog-screenshots-figures-24)
- [10. How many runs](#10-how-many-runs)
- [11. The CloudHub 2.0 result — reproduce or correct?](#11-the-cloudhub-20-result--reproduce-or-correct)
- [12. Republish](#12-republish)
- [13. Known gaps in the original procedure](#13-known-gaps-in-the-original-procedure)

---

## Provenance of the published figures

The figures in [`benchmark-results.md`](benchmark-results.md), [`index.html`](index.html), the
vendor pages and the [landing page](../README.md#supported-vendors) come from two runs of engine
0.2.0, spill on, with logs delivered counted at each vendor — sixteen apps in all:

| Run | Apps | Vendors (lanes) |
| --- | --- | --- |
| **2026-10-12** | twelve | Datadog, New Relic, Elasticsearch — three parallel lanes |
| **2026-10-13** | four | Dynatrace — its lane alone, same protocol, sizes, region and build |

They were produced by the automated **4-test** harness, which restarts and warms every app before
each test, drives **plane-specific concurrency (CloudHub 1.0 at `-c 10` over `http`, CloudHub 2.0
at `-c 5` over `https`)** against `/log?count=20`, and runs the vendors as parallel lanes with one
app per vendor at a time:

| Test | What it is |
| --- | --- |
| Test 1 | 1 min |
| Test 2 | in-thread churn |
| **Test 3** | 5 min sustained — **the headline** |
| Test 4 | 5 min with a 30 s per-request cap |

**The manual recipe below is the equivalent single-run path.** It still describes a two-vendor
pairing per phase; add the Elasticsearch and Dynatrace apps as further concurrent lanes, one app
per vendor. Where the recipe and the harness differ, the harness is authoritative — follow the
plane-specific `-c` and `count=20` here so a manual re-run stays comparable.

Raw `oha` output for all four tests is retained locally (git-ignored, because it carries
hostnames) under `.claude/bench-results/`. The charts and the Markdown report are regenerated
from it, so those figures are *recorded*, not asserted.

<details>
<summary>Superseded runs: 2026-08-04 and July</summary>

- **2026-08-04.** Superseded by the run of 2026-10-12. Its CloudHub 2.0 Datadog stock app carried
  a leftover `AsyncLoggerConfig.RingBufferSize=20000` and `log4j2.debug=true`, so its 7.6× is not
  comparable.
- **July (earlier, superseded).** A set of Datadog-only tables — 73.18 / 4.05 req/s on CloudHub
  1.0, 12.75 / 2.83 on CloudHub 2.0 — measured under a *different* protocol:
  - per-plane asymmetric load: `-c 10` / `count=30` on CloudHub 1.0 against `-c 5` / `count=15`
    on CloudHub 2.0;
  - plain `http` on 1.0 against `https` on 2.0;
  - a 3-request warmup.

  At least **two** July runs exist and **no raw `oha` output from any July run survives**, so the
  July parameters remain *asserted* rather than *recorded*; §11 keeps that audit as history. Do
  not quote a July figure as current, and do not carry its cross-plane comparison forward — see
  §11.

</details>

---

## 1. What you must supply

| # | Item | Notes |
| --- | --- | --- |
| 1 | **Datadog account** | Trial is enough. The Logs intake is rate-limited per org/key — see §6. |
| 2 | **Datadog API key** | Organization Settings → API Keys. An *API key*, not an app key. |
| 3 | **Datadog site/region** | Your key only works against your org's site. See note 1. |
| 4 | **Anypoint org + environment** | Entitled for **both** CloudHub 1.0 workers and CloudHub 2.0 (a private or shared space). |
| 5 | **One region, chosen before anything is deployed** | Deploy **both** platforms into it. See note 2. |
| 6 | **CH2 target space** | Must sit in the region chosen in item 5; record its region (§6, §8). |
| 7 | **A load-generator host** | Where `oha` runs. See note 3. |
| 8 | **`oha`** | `brew install oha`. See note 4. |
| 9 | **Connected App** | For `anypoint-cli-v4` under `client_credentials`. See note 5. |

1. **Datadog site.** Both demos default to Datadog's primary log-intake host
   (`http-intake.logs.datadoghq.com`), so they are deployable as shipped but will reject a key
   from any other site. Point them at yours with `-Ddd.site=<your-intake-host>` (or `DD_SITE`) —
   one property, resolved identically by both apps, so no file edit is needed. Take the host for
   your site from [Datadog's site documentation](https://docs.datadoghq.com/getting_started/site/);
   the set of sites changes, so this repo does not restate it. **Pass the same value to both
   apps, or to neither** — a pair whose halves ship to different sites is not a comparison.
2. **Region.** CloudHub 2.0 space availability is narrower than CloudHub 1.0 region
   availability, so confirm a CloudHub 2.0 space exists in the region *before* committing to it.
   Record the choice; it is a parameter of the benchmark, not an incidental.
3. **Load generator.** Its network position relative to each deployment is part of the measured
   path, so keep it constant across every run of the session and record its location alongside
   the two deployment regions.
4. **`oha` version.** The published 2026-10-12 run used `oha 1.16`; 1.14.0 has the latency
   display glitch described in §7, so check your version before you measure.
5. **Connected App scopes.** Every Runtime Manager scope is **environment-scoped**, so an app
   that holds the right scopes but was never attached to the target environment authenticates
   cleanly and then 403s on every Runtime Manager call. Verify as §4 describes. Grant:
   - at organization level: **View Organization** (`read:organization`) and **CloudHub
     Organization Admin** (`admin:cloudhub`);
   - per environment: **View Environment**, **Create Applications**, **Read Applications**,
     **Manage Settings**, and **Delete Applications** (the last for cleanup).

### Other vendors' credentials

Also required, not optional:

- a **New Relic ingest key**;
- an **Elasticsearch** endpoint with an API key (the published run used Elastic Cloud Serverless
  in Sydney);
- a **Dynatrace** environment URL with an access token that holds only the `logs.ingest` scope
  (the published run used Dynatrace SaaS on AWS Sydney);
- a second, separate Dynatrace **platform token** with `storage:logs:read` and
  `storage:buckets:read`, for counting what reached Dynatrace (§8). The apps never use it, so
  keep it out of their properties.

The published run drives the vendors as parallel lanes, one app per vendor at a time. A full
reproduction therefore needs all four vendors' credentials and all eight apps on each plane —
sixteen in total (the published Dynatrace lane ran on its own, a day after the other three). The
manual recipe below (§7) pairs two vendors per phase; add the Elasticsearch and Dynatrace apps as
further concurrent lanes.

**Both apps of a vendor pair must ship to the same intake.** As with `dd.site`, the New Relic
Log API host (`nr.logHost` / `NEW_RELIC_LOG_HOST`) must be the same value on both apps of the
New Relic pair, or unset on both. The two Elasticsearch apps must ship to the same project, and
the two Dynatrace apps to the same environment — a pair whose halves ship to different regions
is not a comparison.

With only one vendor's key you can still measure that vendor's pair, but the phases then hold a
single app each and must run **one at a time**. Both halves of a pair share one intake, and the
standing constraint is that no vendor's intake is ever loaded by two apps at once (§7). Say so in
the report: a single-vendor session is a valid batch-vs-stock contrast, but it is not the
published protocol and its phase timing differs.

---

## 2. Build the artifacts

### Build and test the library

Run from the repo root. Record `git rev-parse HEAD` — that commit, not the version string
(`0.2.0` for the published run), is what identifies the build under test.

```bash
git rev-parse HEAD                       # record this in the report
mvn -B clean install                     # installs batch-http-core + batch-http-log4j2 into ~/.m2
```

The build enforces license headers at `validate` and the tests bind localhost sockets, so run
it with normal (unsandboxed) network and filesystem access.

Expect **204 tests to pass** at the current commit — 185 in `batch-http-core` and 19 in
`batch-http-log4j2` (the counts move as tests are added; compare against the commit you record).
Check the counts rather than only the `BUILD SUCCESS` line, so that tests silently ceasing to run
is visible rather than mistaken for a clean build.

### Build the eight Mule deployables

They are **not** modules of the root reactor, so each is a separate invocation:

```bash
for d in datadog-batch-http datadog-stock-http newrelic-batch-http newrelic-stock-http \
         elastic-batch-http elastic-stock-http dynatrace-batch-http dynatrace-stock-http; do
  (cd "demo/mule/$d" && mvn -B clean package)
done
```

<details>
<summary>Artifact path for each app (<code>mule-application</code> jars)</summary>

| App | Artifact |
| --- | --- |
| `datadog-batch-http` | `demo/mule/datadog-batch-http/target/mule-datadog-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `datadog-stock-http` | `demo/mule/datadog-stock-http/target/mule-datadog-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `newrelic-batch-http` | `demo/mule/newrelic-batch-http/target/mule-newrelic-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `newrelic-stock-http` | `demo/mule/newrelic-stock-http/target/mule-newrelic-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `elastic-batch-http` | `demo/mule/elastic-batch-http/target/mule-elastic-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `elastic-stock-http` | `demo/mule/elastic-stock-http/target/mule-elastic-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `dynatrace-batch-http` | `demo/mule/dynatrace-batch-http/target/mule-dynatrace-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `dynatrace-stock-http` | `demo/mule/dynatrace-stock-http/target/mule-dynatrace-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |

</details>

### Refresh `demo/mule/deployable/`

Refresh `demo/mule/deployable/` from this build. Those clean-named copies are what §4 and §5
deploy, so they are also what the checks below verify:

```bash
mkdir -p demo/mule/deployable
for app in datadog-batch-http datadog-stock-http newrelic-batch-http newrelic-stock-http \
           elastic-batch-http elastic-stock-http dynatrace-batch-http dynatrace-stock-http; do
  cp "$(ls demo/mule/$app/target/*-mule-application.jar)" "demo/mule/deployable/$app.jar"
done
```

> [!NOTE]
> `demo/mule/deployable/` holds the eight deployables §4 and §5 deploy. The folder is
> **gitignored** (a blanket `*.jar`), so it is absent from a fresh clone and never appears in
> `git status` — the artifacts cannot be obtained by cloning, only by building. Its clean
> filenames are the trap: nothing about a jar sitting there tells you which source produced it,
> and exactly that kind of staleness spoiled the original benchmark. Refresh it from `target/` on
> every build, run the verification below against the copies in `deployable/` rather than only
> those in `target/`, and record the commit SHA of the build that populated it.

### Verify the batch apps bundle this build's appender

Verify that each batch app bundles the appender from *this* build, not a stale one resolved from
`~/.m2`. The version number reads the same either way, so the check has to be on bytes.

> [!IMPORTANT]
> **Jar md5s are not reproducible across builds.** The zip archive embeds per-build timestamps,
> so the same unchanged source produces a different archive digest every time it is built —
> under 0.1.3, `batch-http-core-0.1.3.jar` hashed several different ways from one unchanged
> source, across the originally-deployed jar and every rebuild since. **Never compare a jar
> against a digest recorded from a previous build.** Such a comparison mismatches whether the
> code is stale or freshly rebuilt, so it cannot tell the two apart, and it will cry wolf on a
> perfectly good build.

The sound comparison is **within a single build pass**: hash the appender jars the build just
produced, then hash the copies embedded in the deployables built in that same pass. Those must
be byte-identical. That proves the deployable carries this build's appender rather than an
artifact resolved from somewhere else — the failure mode that spoiled the original benchmark.

```bash
VER=0.2.0                    # the root POM's <version>; the batch demos pin the same batchhttp.version
WORK="$(mktemp -d)"          # outside the repo, so nothing is extracted into the working tree

for m in batch-http-core batch-http-log4j2; do
  built="$(md5 -q "$m/target/$m-$VER.jar")"
  for app in datadog-batch-http newrelic-batch-http elastic-batch-http dynatrace-batch-http; do
    entry="repository/com/mulesoft/support/batchhttp/$m/$VER/$m-$VER.jar"
    unzip -o -q "demo/mule/deployable/$app.jar" "$entry" -d "$WORK/$app"
    bundled="$(md5 -q "$WORK/$app/$entry")"
    [ "$built" = "$bundled" ] && verdict=OK || verdict="MISMATCH — stale bundled appender"
    printf '%-22s %-18s %s  %s\n' "$app" "$m" "$bundled" "$verdict"
  done
done

rm -rf "$WORK"
```

Every line must read `OK`. The jar in each module's `target/` is the same one `mvn install` put
in `~/.m2/repository`, so either copy works as the left-hand side.

### Verify the stock apps bundle no appender

The four stock apps are the control: they must run on the Mule container's stock Log4j2 and
bundle **no** appender of their own.

```bash
for app in datadog-stock-http newrelic-stock-http elastic-stock-http dynatrace-stock-http; do
  jar="demo/mule/deployable/$app.jar"
  printf '%-22s batch-http jars: %s   log4j jars: %s\n' "$app" \
    "$(unzip -l "$jar" | grep -c 'batch-http.*\.jar$')" \
    "$(unzip -l "$jar" | grep -c 'log4j.*\.jar$')"
done
```

Both counts must be zero for every stock app. One true-but-confusing detail to expect: the stock
deployables **do** contain `META-INF/maven/com.mulesoft.support.batchhttp/…` entries. That is
only the demo app's own Maven groupId coordinates, not a bundled appender, and it is not
contamination.

### Record the digests

Record both digests for your own build, next to its commit SHA, as the provenance record for
the figures you publish (§8). Do not carry a digest forward from an earlier build: it is a
record of *that* build and nothing more, since a later build of identical source will not
reproduce it. That is exactly why the within-build comparison above is the check that counts.

---

## 3. Properties each app needs

`log4j2.xml` is parsed by **Log4j2, not Mule**. A value that exists only inside Mule — a Mule
properties file or a `secure::` placeholder — is invisible to it. Everything below must reach the
JVM as a **system property** or an **environment variable**.

A Runtime Manager application property does reach it on CloudHub 1.0, which surfaces it as a JVM
system property. That is what `scripts/deploy-ch1.sh` relies on, and why the §4 note about
falling back to JVM arguments exists. The Elasticsearch and Dynatrace demo READMEs rely on
CloudHub passing application properties to the JVM the same way on CloudHub 2.0. See
[Injecting secrets](mule.md#injecting-secrets-api-keys--tokens).

### Datadog pair

| Property | Where it goes | Value | Required? |
| --- | --- | --- | --- |
| `datadog.apiKey` (or env `DD_API_KEY`) | JVM arg | your Datadog API key | **Yes** — note 1 |
| `app.service` | JVM arg | leave unset | No — note 2 |
| `app.platform` (or env `APP_PLATFORM`) | JVM arg | `cloudhub-1.0` / `cloudhub-2.0` | **Set it, on both apps of every pair** — note 3 |
| `dd.site` (or env `DD_SITE`) | JVM arg | your site's intake host | If your org is not on the primary site — note 4 |
| `dd.host` | JVM arg | leave unset | No — note 5 |
| `dd.logUrl` | JVM arg, **stock app only** | leave unset for real Datadog | No — note 6 |

1. Without it the lookup resolves to the literal `MISSING_KEY` and every send is rejected.
2. Defaults to the app's own name (`datadog-batch-http` / `datadog-stock-http`). This is the facet
   that separates the two **apps** in Datadog — `host` defaults to `${sys:domain}` in both. Do not
   override unless you also update the Datadog queries in §9.
3. This is the facet that separates the two **planes**; unset, it reads `unspecified`, and then
   the CH1 and CH2 halves of the benchmark arrive indistinguishable in Datadog. The scripted CH1
   deploy cannot set it, so on CloudHub 1.0 it is a manual step in Runtime Manager — see the
   callout below.
4. Defaults to Datadog's primary intake host. Required if your org lives elsewhere (§1 item 3).
   **The two apps of the Datadog pair must agree on it** — same value on both, or unset on both.
   Halves that ship to different sites are not a comparison.
5. Defaults to `${sys:domain}` (CH1 app name), falling back to the machine hostname.
6. Only used to point the stock app at a mock intake, or at a whole URL the `dd.site` chain
   cannot express.

The resolution chain in both apps is
`${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}`, so any of the three
works. Prefer the JVM arg on CloudHub.

> [!IMPORTANT]
> **`app.platform` must be set by hand in Runtime Manager on CloudHub 1.0.**
> `scripts/deploy-ch1.sh` passes only the vendor key as a `--property` (plus `elastic.url` for
> the Elasticsearch pair and `dynatrace.url` for the Dynatrace pair). An app deployed through it
> reports `platform: unspecified` until you add `app.platform` yourself in Runtime Manager's
> **Properties** tab (CloudHub 1.0 surfaces those as JVM system properties) and restart. Do that
> for **every** CloudHub 1.0 app you deploy through the script — both halves of each vendor pair
> — before a measured run, or the CH1 side of the comparison arrives unlabelled.

A value on one half only is worse than none: it makes the A/B look like a comparison of two
platforms. And on any property with a `:-` default, an override you no longer want must be
**deleted, not cleared** — Log4j2 honours the default only when the property is *absent*, so a
present-but-empty value wins and blanks the facet.

### New Relic pair

Same shape as the Datadog pair, with:

- `newrelic.apiKey` / `NEW_RELIC_API_KEY` for the key;
- `nr.hostname`;
- `nr.logHost` (or `NEW_RELIC_LOG_HOST`) selecting the Log API host for your account's region —
  the counterpart of `dd.site`, and subject to the same rule: **both apps of the New Relic pair
  must agree on `nr.logHost`**, same value on both or unset on both.

`app.service` and `app.platform` behave identically, `app.platform` included in the manual-step
caveat above.

### Elasticsearch pair

| Property | Where it goes | Value | Required? |
| --- | --- | --- | --- |
| `elastic.url` (or env `ELASTIC_URL`) | application property | the Elasticsearch endpoint (scheme, host, optional port) | **Yes** — note 1 |
| `elastic.apiKey` (or env `ELASTIC_API_KEY`) | **secure** application property | the base64 "encoded" API key | **Yes** — note 2 |
| `elastic.dataStream` | application property | leave unset | No — note 3 |
| `app.service`, `app.platform` | JVM arg | as for the Datadog pair | note 4 |

1. Unset, it falls back to a reserved `.invalid` host, so the app fails DNS loudly instead of
   shipping anywhere.
2. Unset, it ships the literal `MISSING_KEY`.
3. Defaults to `logs-mule-default`. Same value on both apps, or the delivery count has to look in
   two places.
4. `app.service` becomes ECS `service.name`, the facet that separates the two **apps**;
   `app.platform` separates the planes, with the same manual-step caveat on CloudHub 1.0.

Both apps must ship to the **same project**. Delivery is counted by event time inside the `oha`
window (§8). The demo READMEs list the API key privileges.

### Dynatrace pair

| Property | Where it goes | Value | Required? |
| --- | --- | --- | --- |
| `dynatrace.url` (or env `DT_ENVIRONMENT_URL`) | application property | the environment's API URL — note 1 | **Yes** — note 2 |
| `dynatrace.token` (or env `DT_API_TOKEN`) | **secure** application property | an access token with **only** the `logs.ingest` scope | **Yes** — note 3 |
| `domain` | application property, **CloudHub 2.0 only** | the app's name, e.g. `dynatrace-batch-http` | note 4 |
| `app.platform` | JVM arg | `cloudhub-1.0` / `cloudhub-2.0` | As for the Datadog pair, manual-step caveat included. |

1. The `*.live.dynatrace.com` form, or the ActiveGate `/e/<environment-id>` form — not the
   `*.apps.dynatrace.com` UI address.
2. Unset, it falls back to a reserved `.invalid` host. Give the **stock** app the bare environment
   URL, no trailing slash and no path: the stock appender appends `/api/v2/logs/ingest` without
   normalising it.
3. Unset, it ships the literal `MISSING_TOKEN`, which Dynatrace refuses with `401`.
4. Sets `log.source`, the one identity the Dynatrace sink sends — so it is what separates the
   batch app from its stock sibling and one plane from the other, and what the §8 DQL count
   filters on. CloudHub 1.0 sets `domain` to the application name on every app; CloudHub 2.0 does
   not. To drop an override, delete the property rather than clearing it.

The Dynatrace sink has no `service` or `host` field, so `app.service` plays no part here. Both
apps must ship to the **same environment**. The platform token used for counting (§1) is not an
app property.

### Where to put the JVM args

- **CloudHub 1.0** — Runtime Manager → your app → **Settings → Properties → JVM arguments**
  (or `--property` on the CLI, see §4). Mark the key as a **secure property** so it is masked.
- **CloudHub 2.0** — Runtime Manager → your app → **Settings**, add to the JVM/`MULE_OPTS`
  arguments, or set `DD_API_KEY` as a (secure) **environment variable** in the deployment.
  CH2 exposes env vars to the JVM, so the `${env:DD_API_KEY}` leg of the chain resolves.

---

## 4. Deploy — CloudHub 1.0

Sizing used originally: **1 worker × 0.1 vCore**.

CH1 is fully scriptable with `anypoint-cli-v4` (verified installed: `1.6.25`). Authenticate
once with `anypoint-cli-v4 conf` or the `ANYPOINT_*` environment variables.

### Scripted deploy

`scripts/deploy-ch1.sh` automates this whole section — the two deploys, the region-parity
assertion, the hostname capture below, and polling until each app reports `STARTED`:

```bash
scripts/deploy-ch1.sh [--dry-run] [--datadog | --newrelic | --elastic | --dynatrace | --all] [APP ...]
```

It defaults to the Datadog pair, and reads these from the environment:

| Variable | Read when |
| --- | --- |
| `ANYPOINT_CLIENT_ID`, `ANYPOINT_CLIENT_SECRET`, `ANYPOINT_ORG`, `ANYPOINT_ENV`, `REGION`, `DD_KEY` | always |
| `NR_KEY` | only when a New Relic app is selected |
| `ELASTIC_URL`, `ELASTIC_KEY` | only for `--elastic` |
| `DT_ENVIRONMENT_URL`, `DT_API_TOKEN` | only for `--dynatrace` |

The published protocol needs **all four** vendor pairs on this plane:

- **`--all`** deploys the Datadog and New Relic pairs (with both keys exported);
- `--elastic` and `--dynatrace` deploy the other two;
- `--dry-run` prints the exact commands with secrets masked.

The explicit commands below stay in the runbook regardless: they document what the script does,
and you need them for the CloudHub 2.0 side and for debugging.

Note what the script does **not** do: it passes only the vendor credential as a `--property`
(plus `elastic.url` for the Elasticsearch pair and `dynatrace.url` for the Dynatrace pair), so
every app it deploys still needs `app.platform` set by hand afterwards (§3).

### Target the apps that are already deployed

**Target the existing apps, or you will benchmark a parallel copy.** The CloudHub 1.0
applications from the original run may still exist, and on CloudHub 1.0 the application name
*is* the public hostname label. Deploying the same jar under a different name creates a second,
independent app rather than replacing the first.

The deployed names are the module names (`datadog-batch-http`, `datadog-stock-http`,
`newrelic-batch-http`, `newrelic-stock-http`, `elastic-batch-http`, `elastic-stock-http`,
`dynatrace-batch-http`, `dynatrace-stock-http`) carrying a **deployment-specific suffix**. The
suffix is recorded in the operator's local harness and is deliberately not written down here.
Export it before deploying, so the script and the commands below address the existing apps:

```bash
export CH1_APP_SUFFIX="<the suffix the existing CloudHub 1.0 apps carry>"
```

Leave it unset only when deploying into a clean org. Confirm against
`runtime-mgr cloudhub-application list` before the first deploy: an unexpected pair of
near-identically-named apps is the signature of having got this wrong. CloudHub 2.0 app names
do not carry the suffix.

### Verify the credentials before the first deploy

A 403 here is the most likely blocker and the most commonly misdiagnosed one. Do not test with
`anypoint-cli-v4 account user describe` — a client-credentials Connected App is not a user and
has no user record, so that call 403s even when everything is configured correctly.

Use a Runtime Manager read instead. It exercises authentication, org-name resolution,
environment-name resolution and Runtime Manager access in one call. Run it with the org and
environment already configured, either through `anypoint-cli-v4 conf` or the exports in the
next block:

```bash
anypoint-cli-v4 runtime-mgr cloudhub-application list -o json    # an empty [] is a pass
```

### Deploy the pair by hand

`--region` is **not** a flag to leave at its default. Set it explicitly to the single region
chosen in §1, the same region the CloudHub 2.0 space in §5 lives in, and record the value you
used. An unset or inherited region is how the two platforms silently end up on different
continents.

```bash
export ANYPOINT_ORG="<your org>"
export ANYPOINT_ENV="<your env>"
export DD_KEY="<your datadog api key>"
export REGION="<the region chosen in §1, as an AWS-style region name>"
export CH1_APP_SUFFIX=""      # the suffix the existing apps carry — see the note above

anypoint-cli-v4 runtime-mgr cloudhub-application deploy \
  "datadog-batch-http${CH1_APP_SUFFIX}" \
  demo/mule/deployable/datadog-batch-http.jar \
  --workers 1 --workerSize 0.1 \
  --runtime 4.9.15 --javaVersion 17 \
  --region "${REGION}" \
  --property "datadog.apiKey:${DD_KEY}"

anypoint-cli-v4 runtime-mgr cloudhub-application deploy \
  "datadog-stock-http${CH1_APP_SUFFIX}" \
  demo/mule/deployable/datadog-stock-http.jar \
  --workers 1 --workerSize 0.1 \
  --runtime 4.9.15 --javaVersion 17 \
  --region "${REGION}" \
  --property "datadog.apiKey:${DD_KEY}"
```

`deploy` creates and errors on an app that already exists; use `modify` with the same flags to
redeploy over one that does. `scripts/deploy-ch1.sh` picks between the two automatically.

`--property name:value` sets a CloudHub **application property**, which Log4j2 cannot see. On
CH1 such properties *are* additionally surfaced as system properties by the wrapper, which is why
the chain above works. But if a run shows `MISSING_KEY` in the app log despite a successful
deploy, the key has to go under **JVM arguments** in Runtime Manager instead
(`-Ddatadog.apiKey=…`). There is no JVM-arguments flag in `anypoint-cli-v4` 1.6.25, so that
fallback is a manual step in the UI and the deploy cannot stay fully scripted.

Useful during the run:

```bash
APP="datadog-batch-http${CH1_APP_SUFFIX}"
anypoint-cli-v4 runtime-mgr cloudhub-application describe "$APP"
anypoint-cli-v4 runtime-mgr cloudhub-application tail-logs "$APP"
anypoint-cli-v4 runtime-mgr cloudhub-application restart "$APP"    # between runs
anypoint-cli-v4 runtime-mgr cloudhub-application download-logs "$APP" ./logs/
```

### Read the public hostname back from the platform

**Do not construct the public hostname.** `--region` takes an AWS-style name — the
`<area>-<direction>-<number>` form — while the CloudHub 1.0 public hostname uses a short region
label that is a **different string**, and for `us-east-1` the hostname carries no region
component at all. Reusing `REGION` in a hostname therefore yields an unreachable URL in every
region. `describe -o json` reports the authoritative value in `fullDomain`, with `domain` as a
fallback:

```bash
SCHEME=http       # plain http on both planes — see the scheme note below
BATCH_URL="$SCHEME://$(anypoint-cli-v4 runtime-mgr cloudhub-application describe \
  "datadog-batch-http${CH1_APP_SUFFIX}" -o json | jq -r '.fullDomain // .domain')"
STOCK_URL="$SCHEME://$(anypoint-cli-v4 runtime-mgr cloudhub-application describe \
  "datadog-stock-http${CH1_APP_SUFFIX}" -o json | jq -r '.fullDomain // .domain')"
echo "$BATCH_URL" "$STOCK_URL"
```

Those are the URLs §7 loads, on port 80 — CloudHub 1.0 is driven over plain `http`. Only if the
platform reports neither field should you fall back to constructing a hostname by hand. While you
have the `describe` output open, confirm the platform reports the region you asked for, and write
it down for §8.

### Scheme — what the published run used

The published 2026-10-12 run drove **CloudHub 1.0 over plain `http`** and **CloudHub 2.0 over
`https`** (the harness passes `--insecure`). TLS record processing therefore sits on the
CloudHub 2.0 side only — a real cost on a 0.1 vCore container, with the handshake landing in the
`Fastest` figure. That is one more reason the published surfaces never compare the two planes.

Within a plane, batch and stock meet the same transport, which is the comparison the report
makes. If you drive either plane over a different scheme, it is not the published protocol, so
say so. Either way, record the scheme alongside the region in §8, and do not compare any figure
measured over `https` against one measured over `http`.

---

## 5. Deploy — CloudHub 2.0

Sizing used originally: **1 replica × 0.1 vCore**.

**The space must be in the same region as the CloudHub 1.0 workers.** Whichever of the two
routes below you take, select a space in the region chosen in §1 and deployed to in §4, and
record the space's region. If no CloudHub 2.0 space is available in that region, do not accept
the mismatch: go back and choose a different region for **both** platforms, redeploying the
CloudHub 1.0 pair if necessary. A cross-region pair does not produce a comparable measurement.

**This step is manual.** `anypoint-cli-v4` 1.6.25 has no CloudHub 2.0 topic — `runtime-mgr`
exposes only `cloudhub-application` (CH1), `standalone-application`, `rtf`, `server`,
`serverGroup` and `gateways`. (`--releaseChannel` mentions CH2 values in its help text, but
there is no command that targets a CH2 space.) There are two options.

### Option 1: Runtime Manager UI

This is what the original run used, as far as the docs record:

1. Applications → Deploy application.
2. Choose the CH2 target space, which must be one in the chosen region.
3. Upload `demo/mule/deployable/datadog-batch-http.jar`.
4. Set **Replicas 1, vCores 0.1**, Runtime `4.9.15`, Java 17.
5. Add the API key **and `app.platform=cloudhub-2.0`** under environment variables or JVM
   arguments.

Unlike the scripted CloudHub 1.0 path, CH2 lets you set both at deploy time, so there is no
follow-up manual step here — but it is just as easy to forget, and an unset `app.platform` reads
`unspecified` (§3). Repeat for the stock app, and for every other pair with its own properties
(§3). The Dynatrace pair also needs `domain` set to the app's name here, or both apps report the
same fallback `log.source`.

### Option 2: `mule-maven-plugin` `cloudhub2Deployment`

The plugin is already on each app (`4.10.0`) but is configured with `<classifier>` only, **no
deployment block**. You would need to add one:

```xml
<configuration>
  <classifier>mule-application</classifier>
  <cloudhub2Deployment>
    <uri>https://anypoint.mulesoft.com</uri>
    <provider>MC</provider>
    <environment>${anypoint.env}</environment>
    <target>${ch2.space}</target>
    <muleVersion>4.9.15</muleVersion>
    <replicas>1</replicas>
    <vCores>0.1</vCores>
    <connectedAppClientId>${anypoint.clientId}</connectedAppClientId>
    <connectedAppClientSecret>${anypoint.clientSecret}</connectedAppClientSecret>
    <connectedAppGrantType>client_credentials</connectedAppGrantType>
    <properties>
      <datadog.apiKey>${env.DD_API_KEY}</datadog.apiKey>
    </properties>
  </cloudhub2Deployment>
</configuration>
```

Then run `mvn deploy -DmuleDeploy`. This route uploads the module's own `target/` artifact
rather than the `demo/mule/deployable/` copy; the two are the same bytes as long as
`deployable/` was refreshed from this build per §2. It requires a **Connected App** with Runtime
Manager deploy scopes. Adding it is a repo change; it is not committed today.

### Check before the CH2 run

- **Inbound port.** CH2 routes ingress to the port the platform assigns. The demos set
  `http.port` in `config.properties` (8081–8088, a distinct port per app), and the listener binds
  `${http.port}`. System properties outrank the properties file in Mule 4, so the platform value
  normally wins — but confirm the app is actually reachable before treating a bad number as a
  measurement. A CH2 app that fails its ingress health check will not serve `/log` at all.
- **The `FILE` appender path.** Both apps write a `RollingFile` to
  `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log`, which the config comments describe as
  the CloudHub **1.0** custom-log-appender convention. `${sys:domain}` is a CH1 property. On
  CH2 that path and that property may not exist. Check the CH2 startup log for a Log4j2
  `RollingFile` error and for a filename containing a literal `${sys:domain}` — see §11.

---

## 6. Pre-flight fairness checklist

Run through this **before** the first measured run. Items marked ⚠ were **not** held constant in
the superseded July procedure. Each is a known, documented asymmetry between the two planes, not
a speculative one, and each is something the published protocol deliberately fixed.

### Build, sizing and load

- [ ] **Same appender build in both batch apps** — each deployable's bundled appender matched
      against the jars from the same build pass, per §2.
- [ ] **Same sizing** — CH1: 1 worker × 0.1 vCore. CH2: 1 replica × 0.1 vCore.
- [ ] **Same runtime version and Java version** — 4.9.15 / Java 17 on every app.
- [ ] ⚠ **Plane-specific concurrency, identical payload** — **`count=20`** (22 log events/request)
      on every run, both planes. Concurrency is **`-c 10` on CloudHub 1.0 and `-c 5` on CloudHub
      2.0**: the CloudHub 2.0 replica is a capped-CPU, no-swap worker held at lower concurrency to
      stay off its OOM ceiling. Because `-c` differs, read each plane's batch-vs-stock contrast
      on its own — **never CloudHub 1.0 against CloudHub 2.0.** Record the values you used (§8).
      (The next benchmark round plans to even concurrency across planes; until then, keep the
      values above so a re-run matches the published figures.)
- [ ] ⚠ **Same URL scheme as the published run** — plain **`http`** on CloudHub 1.0, **`https`**
      on CloudHub 2.0 — and the same scheme for both apps of a plane; see the scheme note in §4.

### Location

- [ ] **Region parity.** The CloudHub 1.0 worker region as the platform reports it and the
      CloudHub 2.0 space's region are the same region, both matching the choice made in §1.
      Confirm this from Runtime Manager rather than from what you intended to deploy. This is
      hygiene rather than a live suspicion: the July deployments were in fact **both in the
      same region**, so a cross-continent round trip is not available as an explanation for the
      July CloudHub 2.0 gap (§11). Record the region for each deployment anyway.
- [ ] **Load generator in a fixed location** for every run of the session, at the same distance
      from both deployments, and recorded. Note whether it is a laptop on residential/VPN
      networking or a cloud host; a VPN alone can add 50–150 ms.

### Vendor intake and labels

- [ ] **Both apps of a vendor pair agree on their intake** — so intake behaviour and rate limits
      are shared within each comparison (§3). Same value on both halves, or unset on both halves:
      - Datadog pair: the same `dd.site` and the same Datadog API key;
      - New Relic pair: the same `nr.logHost` and the same New Relic ingest key;
      - Elasticsearch pair: the same Elasticsearch project;
      - Dynatrace pair: the same Dynatrace environment.
- [ ] **Distinct `app.service` per app** — `datadog-batch-http` vs `datadog-stock-http`, and the
      same shape across the New Relic pair. Leave the defaults alone. `host` is `${sys:domain}` in
      both halves and is *not* a discriminator; every Datadog query in §9 filters on `service`.
      The Dynatrace pair is the exception: it has no `service` field, and its apps are told
      apart by `log.source` — automatic on CloudHub 1.0, the `domain` property on CloudHub 2.0.
- [ ] **`app.platform` set on every app** — `cloudhub-1.0` on each CH1 app, `cloudhub-2.0` on
      each CH2 app. Unset, it reads `unspecified` and the two planes become indistinguishable in
      the backend. The scripted CH1 deploy passes only the vendor credential (and `elastic.url` /
      `dynatrace.url`), so on CloudHub 1.0 it **must be set by hand** in Runtime Manager's
      Properties tab and the app restarted (§3); on CloudHub 2.0 it goes in at deploy time (§5).
- [ ] **Rate limits.** Vendors throttle their Logs intake per org, key or environment. A batch app
      at full speed ships tens of thousands of events per minute. If you see HTTP 429 in the app
      log (the batch appender logs it and honours `Retry-After`), the run is throttled — note it,
      and treat that pair's absolute req/s as unusable in isolation. Loading one intake with a
      single app per phase is what keeps this from being self-inflicted.

### Run hygiene

- [ ] **Run the A/B pair in adjacent phases of the same window**, batch phase then stock phase,
      so both halves meet the same intake conditions. Do not compare a batch run from Monday
      against a stock run from Tuesday.
- [ ] **Never two apps against the same vendor intake at once.** The published protocol *is*
      parallel — one app per vendor under load together — but the two halves of any one
      comparison share an intake, so they never overlap. Phases are sequential; within a phase
      the two apps are pairwise by vendor. See §7.
- [ ] **Restart each app between runs** so the in-memory queue and the connection pool start
      clean. The batch demos ship with spill on (`spillEnabled="${sys:batch.spillEnabled:-true}"`),
      so also confirm `${java.io.tmpdir}/batch-http-spill/<sink>/` is empty, because a leftover
      backlog is replayed on startup (`spillRecoveryMode=ADOPT`) and competes for the same
      0.1 vCore. On CloudHub a restart lands on a new worker (1.0) or pod (2.0) with an empty
      `/tmp`, so the directory starts clean, and any backlog still on disk is lost. Set
      `batch.spillEnabled=false` to measure batching alone; then there is no spill directory
      to clear.
- [ ] **Nothing else deployed in the same environment** generating load or log volume.

---

## 7. Warmup and measured run

Per phase, in this order. `$URL` is each app's public base URL as the platform reported it in §4,
never a hostname you assembled yourself. This is the manual single-run equivalent of the
harness's **Test 3** (the 5-minute sustained headline); set `CONC` to the value for the plane you
are testing.

```text
5× curl warmup  →  pause 10 s  →  oha -z 5m load test  →  settle 10 s  →  next phase
```

The harness behind the published figures warms lighter: 5 requests at `/log?count=1`, then a
30 s drain, so the warmup does not pre-load the stock flusher's backlog. The `count=20` warmup
below is the manual variant; record which one you used.

```bash
# One app's side of a phase. The paired app of the other vendor runs this same block
# concurrently, in step — see "Run it paired-parallel" below.
URL="${BATCH_URL}"          # captured from describe -o json in §4
CONC=10                     # CloudHub 1.0: -c 10 ; CloudHub 2.0: -c 5 — see "Concurrency by plane" below
COUNT=20                    # both planes, so 22 log events per request

# 1. Warmup — 5 requests, one after another. Primes JIT, the connection pool,
#    and (batch app) the flusher thread. A lighter warmup left first-run
#    throughput swinging, which is why it is 5 and not 1 or 3.
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "  warmup#$i -> HTTP %{http_code} in %{time_total}s\n" \
    --connect-timeout 15 --max-time 60 "$URL/log?count=$COUNT"
done

# 2. Pause 10 s. Lets the batch app's linger window drain the warmup events so the
#    measured run starts from an empty queue.
sleep 10

# 3. Measured run (Test 3 = 5 minutes). Add -w to drain in-flight requests past the
#    deadline as the harness does; add -t 30s for the Test 4 per-request-cap variant.
oha --no-tui -z 5m -w -c "$CONC" "$URL/log?count=$COUNT" | tee "run-<app>-<n>.txt"

# 4. Settle 10 s before the next phase.
sleep 10
```

If `oha` reports connection failures immediately, suspect the URL before you suspect the app: a
hostname assembled from the `--region` value instead of read back from `describe` is
unreachable, and this is exactly how it fails.

**Events per request is `count + 2`** — a `batch.start` (INFO), `count` × `order.processed`
(INFO), and a `batch.completed` (WARN). So the protocol's `/log?count=20` emits **22 log events**
per request; `count=30` would emit 32.

### Run it paired-parallel

**Run it paired-parallel, one vendor per side.** Each phase puts a **Datadog app and a New Relic
app under load at the same instant** — the two batch apps together, then the two stock apps
together — and the **phases are sequential**. Both apps of that block run the same warmup, the
same measured minute and the same trailing settle, in step. After the batch phase, run the stock
phase — both stock siblings together, one vendor per side — and then the same two phases against
the other platform.

**The constraint is the intake, not the load generator: never two apps against the same vendor
intake simultaneously.** That is what the pairing buys — no vendor's intake is ever loaded by two
apps at once, so neither half of a comparison waits on the other's intake, and a throttled intake
cannot be self-inflicted.

- Pairing *across* vendors is safe and is the protocol.
- Pairing *within* a vendor (batch and stock together, or two apps of the same vendor on the two
  planes together) is what invalidates the session.

If you have only one vendor's key, the phases hold one app each and run one at a time (§1). The
published Dynatrace lane is that case: it ran on its own, a day after the other three, with the
same phases and the same protocol.

### Notes on the flags

- **`--no-tui -z 5m -w -c <plane-conc> <url>`** is the whole command line (Test 3). `-z 5m` and
  `-z 300s` are equivalent; the harness also runs a 1-minute Test 1 (`-z 1m`) and a 30 s-cap
  Test 4 (`-z 5m -t 30s`).
- **`--insecure` / `curl -k` only matter on CloudHub 2.0.** The published run drove CloudHub 2.0
  over `https` and the harness passes `--insecure` (a no-op over CloudHub 1.0's plain `http`). If
  you drive CloudHub 2.0 over plain `http` instead, there is no certificate to accept; say so in
  the report, because the resulting figures are not comparable with the published ones.
- The run uses **oha's defaults** for everything else, and they are worth pinning deliberately
  rather than inheriting: HTTP/1.1, keep-alive **on**, **no** per-request timeout (`-t` unset), no
  rate limit (`-q` unset).
- **`-w` (wait-ongoing-requests-after-deadline) is passed** on the published runs, so the `-c`
  in-flight requests drain to completion instead of being aborted at the deadline. This makes the
  reported `Total` read slightly longer than the nominal `-z`. The stock appender's
  one-blocking-POST-per-event tail drains visibly longer than batch, which is itself a result,
  not an artifact. Omitting `-w` instead aborts the in-flight tail and reports it as
  `aborted due to deadline`.
- **`--output-format json` was not used** — the plain text summary goes through `tee`. Adding it
  is strictly better for a re-run: machine-readable percentiles you can feed into the chart
  script, no TUI CPU cost on the client, and — see below — no exposure to the text renderer's
  display glitch. Capture both if you also want a screenshot.
- The warmup `curl` carries `--connect-timeout 15 --max-time 60` and hits the **same**
  `/log?count=N` endpoint as the measured run; its output is discarded.

### Concurrency by plane

The published protocol holds **`count=20` everywhere** (same per-request work on both planes) but
uses **plane-specific concurrency: `-c 10` on CloudHub 1.0, `-c 5` on CloudHub 2.0**. The CloudHub
2.0 replica is a capped-CPU, no-swap worker; at `-c 10` it risks the OOM ceiling, so it is held at
`-c 5`. Keep the payload identical and the concurrency per-plane as above.

Because `-c` differs across planes, **a cross-plane comparison is not valid** — read each plane's
batch-vs-stock contrast on its own. This is looser than the ideal (equal concurrency everywhere).
The next-round methodology plan is to even it up by driving both planes at `-c 10` and recording
whether CloudHub 2.0 OOMs. Until then the per-plane values above are what the published figures
used, so match them for a comparable re-run.

Even with equal concurrency a cross-plane reading would still be only necessary-not-sufficient:
the two generations have different CPU models — burstable versus hard-capped — which the
published surfaces cite as the remaining reason each table stands on its own. Record the values
in the report either way, per §8.

### Mandatory: validate every run before you record it

> [!WARNING]
> **`oha` 1.14.0 has a display glitch that silently corrupts latency.** On some runs the text
> renderer injects a stray `" 10"` into time values, and the printed latencies come out at
> **one tenth** of the truth. Throughput (requests/sec) is always reliable; latency percentiles on
> a glitched run are the printed value × 10.

The glitch looks like `Total: 6.0006 10 sec` or `p50 0.0106 10`. A re-run that hits it and does
not catch it publishes latencies that are wrong by an order of magnitude while every other field
looks perfectly normal, so this check is not optional. It is not hypothetical either: the defect
is still live in `oha` 1.14.0, and it hit three of the eight runs behind the superseded
2026-08-04 figures, each of which carried the ×10 correction. The published 2026-10-12 run used
`oha` 1.16, whose output does not show it; run the check anyway, whatever version you have.

Two independent confirmations, both cheap:

1. **Little's Law.** Under a closed-loop load with no rate limit,
   `requests/sec × mean latency ≈ -c` — which is **10** on CloudHub 1.0 and **5** on CloudHub 2.0
   under this protocol. Land on the concurrency you asked for and the latency is real. Land on
   **one tenth** of it and apply the ×10 correction to every latency and percentile in that run.
2. **Total duration.** A `-z 5m` run must total ≈ 300 s (with `-w`, slightly longer as the tail
   drains). A printed `30.00x`/`6.000x`-style value (i.e. ≈ `-z` ÷ 10) is the same glitch showing
   in the duration field → apply the ×10 correction.

Three cautions on using the law:

- **It only ever validates the concurrency, never the payload.** `count=` does not appear in it.
  A run at `count=20` and a run at `count=30` satisfy it identically, so the law cannot tell you
  which workload produced a number. Only your own record of the parameters can.
- **It loosens on any run that sheds requests.** A request aborted at the deadline or dropped
  under back-pressure is counted in neither the throughput figure nor the latency sample, so the
  product falls a little below `-c`. Around 10% low on a run with a sub-100% success rate or a
  long mean latency is normal; a factor of 2 or 10 is not.
- **Even a clean run does not land exactly on `-c`.** Rounding in the reported mean is enough to
  move the product by several percent. The published runs completed every request they started
  and still spread across a band of up to 16%, so read agreement within roughly that band as a
  pass. What the check is looking for is the order-of-magnitude miss, not a few percent.

`--output-format json` sidesteps the glitch entirely, which is the strongest argument for using
it. Run the check anyway — it also catches a run that used different parameters than you think.

---

## 8. What to record

### From oha, per run

| Metric | oha field |
| --- | --- |
| Requests / sec | `summary.requestsPerSec` — not an independent result; see the note below |
| Total requests in 60 s | `summary.total` (successRate × total for the OK count) |
| Success rate | `summary.successRate` |
| Average latency | `summary.average` |
| Fastest / Slowest | `summary.fastest` / `summary.slowest` |
| p50 / p75 / p90 / p95 / p99 | `latencyPercentiles` |
| Aborted due to deadline | error-distribution entry |

Requests/sec is a restatement of the mean latency under a closed-loop load, not an independent
result. The restatement is exact while every request completes and loosens on runs that shed
requests. See [Throughput is not an independent result](#throughput-is-not-an-independent-result)
in §12 before writing it up.

### Logs delivered, per run

Record **logs delivered** at the vendor — the logs that reached it ÷ the logs the measured
requests generated (HTTP 200 responses × 22). The harness writes `LOAD_START_UTC` and
`LOAD_END_UTC` into each `oha` output file; the counting windows are:

| Vendor | Counted by | Window |
| --- | --- | --- |
| Elasticsearch | event time (`@timestamp`) | inside the `oha` window, per `service.name` |
| Dynatrace | event time (`timestamp`), in Grail with DQL | inside the `oha` window, per `log.source` |
| Datadog | arrival time | from the test's start to the next test on the same app, per `service` |
| New Relic | arrival time | the same as Datadog |

Datadog and New Relic count by arrival because the demos omit the vendors' reserved event-time
attribute on purpose (`includeTimestamp="false"`), so the vendor stamps arrival.

For Dynatrace, run the count over the API with the **platform** token from §1
(`storage:logs:read` + `storage:buckets:read`), not the ingest token, against the environment's
platform address:

```bash
curl -s -X POST \
  "https://<environment-id>.apps.dynatrace.com/platform/storage/query/v1/query:execute" \
  -H "Authorization: Bearer $DT_PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"fetch logs, from:\"<LOAD_START_UTC>\", to:\"<LOAD_END_UTC>\" | filter log.source == \"<the app log.source>\" | summarize count()"}'
```

A query that does not finish within the request returns `"state":"RUNNING"` and a `requestToken`;
fetch the result with `GET …/query:poll?request-token=<token>`. Count after the backlog has had
time to drain: event time puts late records back inside the window, but only once they arrive.

### Validation, per run

Record that the run **passed the §7 validation**:

- the Little's Law product against the plane's concurrency (10 on CloudHub 1.0, 5 on CloudHub
  2.0);
- the total duration;
- whether the ×10 glitch correction was applied.

A latency figure with no recorded validation is not a figure anyone can trust later. The absence
of exactly this record is why the superseded July numbers have to be audited arithmetically
rather than simply read.

### From the platform and the app, per run

- **The exact `oha` command line**, verbatim, including `-c`, `-z` and the full `count=` query
  string, plus the **URL scheme** and which app it was paired with in the phase. These are
  parameters of the measurement, not incidental details: the July run's were never recorded,
  which is why its two platform tables cannot be compared with each other (§7, §11). The
  published run records them on every surface, and that is the standard to hold.
- **Commit SHA** of the build under test — the only identifier that survives a rebuild. Record
  both appender jar digests next to it as a record of that one build; they are not reproducible
  by a later build and cannot be used to identify code (see §2).
- **CloudHub 1.0 worker region**, as Runtime Manager reports it.
- **CloudHub 2.0 space region**, together with the space's name.
- **Load-generator location** — city or cloud region, and whether the traffic leaves through a
  VPN. All three of these locations belong in the published report; the measured path runs
  through all of them.
- **Worker/replica sizing** as the platform reports it, not as you requested it.
- **The appender configuration under test.** These belong in the report rather than being left
  implicit:
  - `maxBatchRecords=200`, `maxBatchBytes=900000`, `lingerMillis=2000`, `queueCapacity=25000`
    and `flusherThreads=1`, identical in every batch app;
  - the **two deliberate divergences from the library defaults** — `gzip="false"` and
    `includeTimestamp="false"`;
  - the spill setting (on by default; record it if you ran with `batch.spillEnabled=false`).

  Note in particular that **compression is off in the demo apps**: every app in the comparison
  POSTs plaintext, so no figure here reflects gzip, and none of the CPU cost on a 0.1 vCore
  container is compression.
- **The batch appender's own stats heartbeat.** It logs one line per 60 s with
  `accepted / sent / dropped / failed / queueDepth` plus spill totals
  (`statsIntervalMillis=60000`, `diagnosticsLevel=INFO`). Capture the lines that bracket the
  measured window; they tell you whether anything was dropped, which no oha metric can show.
  - With the demos' spill on, `spilledRecords` is the overload signal. Records lost because the
    spill buffer is full show up in `spillStats().droppedRecords` (and in `evictedRecords` when
    an oldest segment is evicted), not in `dropped`.
  - `dropped` counts terminal rejections (a 4xx, documents a bulk intake refused) and
    serialization failures.
  - With `batch.spillEnabled=false`, `dropped` is the overload signal as well and the spill
    totals stay at zero.
- **Any HTTP 429** in the app log.
- **CPU and memory** from Runtime Manager for the measured minute. On a 0.1 vCore container a
  pegged CPU is the whole story, and it is the cheapest way to test the throttling hypothesis
  in §11.

---

## 9. Datadog screenshots (Figures 2–4)

These three images in `docs/img/` are hand-captured from the Datadog UI. Both apps must have been
under load in the **same session** — under the §7 protocol that means adjacent phases, batch then
stock, so a query window that spans both is one that covers both measured minutes and the backlog
draining after them.

All three use **Logs → Search** (Log Explorer). Base query:

```text
service:(datadog-batch-http OR datadog-stock-http)
```

Keep the filenames identical so the image links in `vendors/datadog.md` keep working.

### Figure 2 — `datadog-batch-ingest-lag.png`

Batch app, near-zero lag:

- Query `service:datadog-batch-http`.
- Time range: the measured minute plus ~5 minutes of tail.
- Open a single `order.processed` event in the side panel and show the event's `generatedAt`
  attribute next to the row's Datadog-assigned timestamp. The two should be the same instant.
- Note the browser's display offset in the caption. The current caption reads local time
  (UTC+10) and compares it against a `…Z` value, so the reader needs the offset to check the
  arithmetic. State the offset, not the timezone's name.

### Figure 3 — `datadog-stock-ingest-lag.png`

Stock app, minutes behind:

- Identical view, `service:datadog-stock-http`, same time range and same display offset.
- Pick an event whose `generatedAt` is early in the load window; its Datadog timestamp should
  trail by minutes.
- Capture it *after* the run ends, while the backlog is still draining — that is when the gap is
  visible.

### Figure 4 — `datadog-log-volume-batch-vs-stock.png`

Volume gap:

- Switch the Log Explorer to a **count / timeseries or top-list** visualization.
- Query `service:(datadog-batch-http OR datadog-stock-http)`, **group by `service`**.
- Same time range for both series — that is the whole point of the figure.
- The published caption is 84.7K batch vs 5.66K stock; your absolute numbers will differ, so
  update the caption text in
  [the Datadog page's ingest-lag section](vendors/datadog.md#ingest-lag-generation-to-arrival) to
  match what you capture.

### Figure 1 and the generated charts

Figure 1 and the chart images are generated, not screenshotted: the cross-vendor `cloudhub-*`
charts in `docs/img/`, and the per-vendor `<vendor>-throughput`, `-latency` and `-delivered`
charts in `docs/img/vendors/`.

```bash
python3 -m venv .venv-charts
.venv-charts/bin/pip install -r docs/charts/requirements.txt
BENCH_RESULTS=.claude/bench-results/publish-vendors \
DELIVERY_JSON=.claude/bench-results/publish-vendors/delivery.json \
SUMMARY_JSON=.claude/bench-results/publish-vendors/summary.json \
  .venv-charts/bin/python docs/charts/generate_benchmark_charts.py
```

Nothing in the script is hardcoded from a prior run. It parses the raw `oha` output under
`BENCH_RESULTS` and the delivery counts in `DELIVERY_JSON`, and `SUMMARY_JSON` writes every parsed
number to one file, the single source for the prose in §12. Point the variables at your own run.

---

## 10. How many runs

**Recommendation: 5 measured runs per configuration — with sixteen configurations (four vendors ×
two appenders × two planes), 80 runs total — and report the median with the min–max range.** The
published figures are one run per configuration, so this remains outstanding.

Reasoning:

- With 5 runs the median is the 3rd value and is robust to one outlier in either direction.
  That is the smallest count that lets you honestly drop the "single-run" hedge and write
  "median of 5" instead.
- 3 runs is the absolute floor. It gives a median that survives *one* bad run, but with no
  slack: a second bad run and you have nothing. Use 3 only if platform time is genuinely
  scarce, and keep a caveat about variance.
- More than 5 buys little here. The dominant variance sources — vendor intake behaviour,
  worker placement, network path — are not the kind of noise that averages away with n; they
  shift the whole distribution. Ten runs of a throttled window is ten throttled runs.

### Cost

Cost per run is the measured window (1 minute for Test 1, 5 minutes for Test 3) plus a restart, a
warmup and a drain. Because each phase drives one app per vendor at once, a pass is four phases
(CH1 batch, CH1 stock, CH2 batch, CH2 stock). Five passes are therefore 20 phases per test rather
than 80 serial runs; add the restarts and deploy time.

The real cost is log volume — 5 passes × 16 apps × tens of thousands of events, split across the
four vendors. Check all four plans' ingestion allowances before starting. Consider a short
Datadog **exclusion filter** or index retention drop on `service:datadog-*-http` (and the New
Relic, Elasticsearch and Dynatrace equivalents) after you have the screenshots.

### Alternate the phase order

Also worth doing regardless of n: **alternate the phase order** (batch, stock, stock, batch, …)
rather than always running the batch phase first, so any drift in intake responsiveness across the
session does not systematically favour one appender.

The published protocol does **not** alternate. Its order is fixed: the batch phase first, then the
stock phase, on each plane in turn — so within every pair, batch always runs before stock. That
bias cannot be quantified after the fact; alternating removes it. Keep the pairwise-by-vendor
phases, though: they are what guarantees each app meets its sibling's intake conditions while no
intake carries two apps at once (§7).

### Run one A/A test

Do **one A/A run**: deploy the batch app twice under different names, same platform, same
sizing, and measure both. Whatever spread you see between two identical apps is your noise floor,
and any A/B difference smaller than that is not a result. Run the two **one after the other**, not
together — two copies of the same app ship to the same vendor intake, which is exactly the overlap
§7 forbids.

---

## 11. The CloudHub 2.0 result — reproduce or correct?

> [!NOTE]
> **This section is retrospective.** It audits the **superseded July** Datadog figures, and it is
> kept because it is the record of *why* the protocol in §7 holds every parameter identical across
> the planes. The re-run has since happened under that protocol and is what
> [`benchmark-results.md`](benchmark-results.md) and [`index.html`](index.html) now publish. Read
> the numbers below as history, not as current results, and re-run the same reasoning against
> your own figures rather than carrying these conclusions forward.

The July CloudHub 2.0 batch throughput (12.75 req/s) against CloudHub 1.0's (73.18) was the number
most likely to be challenged, and the question was whether a re-run should reproduce or correct it.

### What the superseded July numbers already tell us

**The load was closed-loop, so throughput is not an independent measurement.** With `oha -c 10`
and no rate limit, exactly 10 requests are in flight at all times, so
`throughput ≈ concurrency ÷ mean latency`. Check it:

| Run | Reported req/s × mean latency | ≈ concurrency | Verdict |
| --- | ---: | ---: | --- |
| CH1 batch | 73.18 × 0.137 s | **10.03** | tight fit at `-c 10` |
| CH2 batch | 12.75 × 0.792 s | **10.10** | tight fit at `-c 10` |
| CH1 stock | 4.05 × 2.229 s | **9.03** | loose (95.7% success — shedding) |
| CH2 stock | 2.83 × 3.225 s | **9.13** | loose (long mean, deadline aborts) |

**All four July runs were driven at `-c 10`, including both CloudHub 2.0 runs.** That is worth
stating explicitly because the operator's harness records `-c 5` for CloudHub 2.0, and the two
cannot both describe the same run:

- At `-c 5` the CloudHub 2.0 batch figure would need a mean latency of 392 ms rather than the
  tabulated 792 ms — a factor of 2, which no documented mechanism produces.
- The ×10 display glitch in §7 cannot bridge it either: the glitch is a fixed factor of ten, so
  it can turn 79.2 ms into 792 ms but never 392 ms into 792 ms.
- The throughput side is independently corroborated by the July request counts (4,381 ÷ 73.18
  and 755 ÷ 12.75 both land within a second of the 60 s window).

The `-c 5` reading is therefore refuted for these particular figures, and the harness is
describing a **different July run**.

What this arithmetic **cannot** settle is `count=`. Little's Law has no payload term, so it is
equally satisfied by 32 events per request and by 17. The July documents asserted `/log?count=30`
on both planes; the harness records `count=15` for CloudHub 2.0; no raw output survives to
adjudicate. That single unresolved parameter was enough on its own to make the July cross-plane
comparison unpublishable — see §7, [Concurrency by plane](#concurrency-by-plane), for the
protocol that removes it.

The identity holds tightly on the two batch runs and only loosely on the two stock runs. The
likely reason for the looser fit is request shedding: the CloudHub 1.0 stock run completed only
95.7% of its requests, the rest aborted at the 60-second deadline or shed under back-pressure. A
request that never completes is counted in neither the throughput figure nor the latency sample,
so the arithmetic only stays exact while every request completes inside the measurement window,
and it loosens as soon as a run stops completing everything it starts.

That looseness does not rescue throughput as an independent metric. The gap runs the wrong way
for that: the shedding runs achieved slightly *less* throughput than their latency alone
predicts, so the derived figure flatters those runs rather than telling you anything the latency
did not. There is still no separate throughput collapse to explain — only the latency. This is a
property of the load generator's configuration, not of these particular numbers, so it
constrains how every future run is written up as well; see
[Throughput is not an independent result](#throughput-is-not-an-independent-result) in §12.

**The CH2 penalty is roughly a constant, and it hits both apps about equally.** Compare the
medians, which are the least outlier-sensitive figures that run reported:

| App | CH1 p50 | CH2 p50 | Delta | Ratio |
| --- | ---: | ---: | ---: | ---: |
| Stock | 365 ms | 1,076 ms | **+711 ms** | 2.9× |
| Batch | 88 ms | 693 ms | **+605 ms** | 7.9× |

The *absolute* penalty is nearly the same for both apps (+711 vs +605 ms). The *ratio* differs
enormously only because the batch app's CloudHub 1.0 baseline was tiny — a fixed +650 ms is
catastrophic against 88 ms and unremarkable against 365 ms.

The minimums say the same thing: the fastest request of the whole run was 15 ms on CH1 batch
but 288 ms on CH2 batch (32 ms → 607 ms for stock). Saturation and queuing raise the mean and
the tail; they do not raise the floor. A raised floor means something is adding a fixed cost to
every single request.

**So the most defensible reading is that the "anomaly" is a ratio artifact of a constant
additive per-request overhead on the CloudHub 2.0 deployment, not a behavioural difference in
the appender.** That is also consistent with the published report's own guidance to read each
req/s against its sibling in the same window rather than across platforms — a caveat the
CH1-vs-CH2 comparison then quietly violates.

**Both cross-plane tables above inherit the parameter problem.** The p50 deltas and the raised
floor are computed across two runs whose payload per request is not established to have been the
same, and which are known to have used different URL schemes. If CloudHub 2.0 in fact ran at
`count=15`, its apps were doing roughly *half* the per-request work and the true per-request
penalty is larger than the +605/+711 ms tabulated, not smaller — the direction of the error is at
least knowable even where its size is not.

Read this whole subsection as "there is a floor on CloudHub 2.0 worth investigating", which it
supports, and not as "the floor is 650 ms", which it does not. The runs recorded per run since
then supersede it: first 2026-08-04 (itself superseded), now the published 2026-10-12 run —
`count=20` on both planes, CloudHub 1.0 at `-c 10` over `http` and CloudHub 2.0 at `-c 5` over
`https` — whose figures are the ones to reason from.

### The region difference — falsified, not resolved

**An earlier draft of this runbook named a cross-region deployment as the leading cause of the
CloudHub 2.0 gap. That hypothesis is now dead.** The deployed hostnames recorded in the
operator's local harness put the CloudHub 1.0 workers and the CloudHub 2.0 space in **the same
region**. The two platforms use region labels that differ by a single letter — exactly the kind
of near-collision that makes a mismatch easy to assume and easy to misread — but they denote the
same location.

The load generator was the operator's own workstation, run by hand — the harness invokes `oha`
locally, and the Datadog screenshots are captioned in the deployment's own local time. There was
never an intercontinental round trip in the measured path.

That removes the one explanation that would have accounted for the entire ~250–300 ms floor in a
single stroke, and it removes it without replacing it. The floor is real and now has no
sufficient cause identified. Everything below is a partial explanation at best.

Region parity is still worth enforcing, but it is now **hygiene, not the fix**:

- §1 fixes a single region up front;
- §4 sets `--region` explicitly;
- §5 requires a CloudHub 2.0 space in that region;
- §6 checks the two against each other;
- §8 records all three locations.

Do not expect controlling it to make the CloudHub 2.0 gap go away, and do not describe the July
gap as "explained by a region mismatch" anywhere in the republished report.

### Candidate causes of any residual overhead

With region eliminated, the following are what remain. Ordered by how well they fit and how
cheap they are to check. All are hypotheses; the measurements above are the evidence.

1. **The two July planes were not measured under the same conditions.** This was the first thing
   to rule out, because it is documented rather than inferred. The two planes differed in URL
   scheme (`http` on CloudHub 1.0, `https` on CloudHub 2.0 — §4), and the operator's harness
   records them differing in load parameters as well, though the arithmetic above puts that run's
   concurrency at `-c 10` on both planes (leaving `count=` unresolved).

   A plain-`http` baseline against an `https` comparand puts TLS record processing on one side of
   the ledger only, on a container with a tenth of a core to spend. That is worth tens of
   milliseconds per request, not hundreds — a contributor, not the explanation — but it is a
   *known* asymmetry where the rest of this list is speculative.

   **Check:** it needs no check; it is settled. **Not fixed, and recorded instead:** the
   published 2026-10-12 run still drives CloudHub 1.0 over `http` and CloudHub 2.0 over `https`,
   at `count=20` on both, `-c 10` and `-c 5` (§6 is where you confirm it before measuring). The
   scheme asymmetry stays, alongside the CPU-model difference in item 2, which is why the
   published surfaces decline to read one plane's table against the other's.

2. **CPU quota semantics differ between a CH1 worker and a CH2 replica.** A CloudHub 1.0 0.1
   vCore worker has historically been able to burst above its nominal share on a shared host;
   a CloudHub 2.0 0.1 vCore replica is a container with a CPU limit enforced by the kernel
   scheduler, which hard-stops the container once its quota for the period is spent. This
   workload is CPU-heavy for its size — at the protocol's `count=20`, the DataWeave-built JSON
   strings per request, plus layout rendering — so a hard cap would show up as exactly this kind
   of floor. (Compression is not part of that cost on either side of the A/B: both batch demos
   run `gzip="false"`, so every app in the comparison POSTs plaintext.)

   **Check:** Runtime Manager CPU graphs during the measured minute; if CH2 sits pinned at 100%
   while CH1 does not, this is contributing. **If true:** it is a genuine platform difference and
   should be *reported*, not corrected — but say so explicitly, and consider also running a
   0.5 vCore pair on both platforms to show it is a sizing effect.

3. **The CloudHub 2.0 ingress adds a hop.** CH2 routes public traffic through shared ingress,
   where CH1 fronts the worker more directly. Worth tens of milliseconds, not hundreds — a
   contributor, not the explanation, and partly entangled with item 1, since the July run only
   paid for TLS on the CloudHub 2.0 side.

   **Check:** compare `%{time_connect}` from `curl -w` against each platform over plain `http`
   (add `%{time_appconnect}` if you probe over `https`, as the published CloudHub 2.0 runs used,
   and then probe both planes that way).

4. **The CH1-shaped `FILE` appender misbehaves on CH2.** Both apps write a `RollingFile` to
   `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log` — a path the config comments
   explicitly describe as the CloudHub **1.0** custom-log-appender convention, using
   `${sys:domain}`, a CH1 system property. If that path is not writable on CH2, or the property
   does not resolve, Log4j2 will either error per event or write to a literal `${sys:domain}`
   filename. Both cost CPU on an already-capped container, and both would be CH2-only — while
   the report claims the file appender is a constant across the comparison.

   **Check:** the CH2 startup log, for a Log4j2 `RollingFile` error or an odd filename. **If
   true:** correct it symmetrically (fix the path on both platforms, or drop the appender from
   all four apps).

5. **Different runtime patch, Java version, or heap between the two deployments.** Not
   recorded. **Check:** Runtime Manager reports both. **If true:** pin them and re-run.

### What is *not* supported by the evidence

The appender code changed since the benchmark — the enqueue path, the UTF-8 byte estimator,
and flusher termination all differ between the deployed jar and current `main`. That difference
rests on comparing the class files themselves; the two archives also differ by digest, but
archive digests differ on every build regardless (see §2) and are no evidence either way. It is
tempting to blame the CH2 number on the old build, but:

- The old jar's flusher loop already used a blocking `take()`, so the earlier hot-spin defect
  that the current source documents as fixed was **not** present in the benchmarked build.
- The one hot-path difference — the old build's `offer()` could call a blocking `put()` on one
  branch, where current code uses a timed offer — sits on the `BLOCK` overflow path, and the
  demos are configured `overflowPolicy="DROP_NEWEST"`. It is unlikely to have been on the
  measured path.
- Most decisively, an appender-side defect would have hurt the **batch** app specifically. The
  measured CH2 penalty falls on the stock app just as hard in absolute terms, and the stock app
  contains none of this code.

Re-run on current `main` regardless — that is the point of the exercise — but do not expect it
to explain CH2 on its own.

---

## 12. Republish

1. **Charts.** Re-run `docs/charts/generate_benchmark_charts.py` against the new results (§9;
   medians once you have repeated passes — the published set is one run per configuration). It
   regenerates the cross-vendor PNG/SVG pairs in `docs/img/`, the per-vendor ones in
   `docs/img/vendors/`, and `summary.json`, the numbers every item below quotes.
2. **`docs/index.html`** — generated: re-run `docs/charts/generate_report_html.py` against the
   same results, then check the headline cards, the metric and percentile tables, the meta chips
   and the footer.
3. **The prose surfaces.** Update:
   - `docs/benchmark-results.md` — the per-test tables, the Test 3 headline tables, "How to read
     this" and the chart captions;
   - each vendor page's **Benchmark on CloudHub** section — its tables, charts and "what the
     numbers show"; on the Datadog page, the Figure 2–4 captions;
   - `README.md` — the **Supported vendors** table (Test 3 batch÷stock and batch delivered) and
     the **Benchmarks** paragraph.
4. **Run count.** Replace "single-run" with "median of N runs (min–max: …)" everywhere it
   appears — `README.md`, `docs/benchmark-results.md`, the vendor pages, the `docs/index.html`
   footer and caveats box, and the header comment in the chart script.
5. **Commit SHA.** Record the commit SHA of the build under test in `docs/index.html` alongside
   the version, so this problem does not recur.
6. **Locations.** Record the CloudHub 1.0 worker region, the CloudHub 2.0 space region and the
   load-generator location in `docs/index.html` and in `docs/benchmark-results.md`, so a reader
   can see that both platforms were measured from the same distance.
7. **Parameters.** Record the exact `oha` command line, the `count=` value, the URL scheme and
   the phase pairing for each plane, and state that they were identical across every run. The
   published 2026-10-12 run does say that — `count=20` on both planes, CloudHub 1.0 at `-c 10`
   over `http`, CloudHub 2.0 at `-c 5` over `https` — so the payload objection that sank the July
   cross-plane reading no longer applies (the concurrency still differs by plane, by design —
   §7). See the cross-plane rule below.
8. **Vendors.** Publish each vendor pair as its **own** batch-vs-stock contrast, and do not
   present the Datadog, New Relic, Elasticsearch and Dynatrace figures as a comparison of the
   vendors. They ship to different intakes over different network paths (New Relic's crosses the
   Pacific), and a lane measured on another day (Dynatrace) meets different platform conditions.
   The pairing makes the appender comparison fair within a vendor and says nothing about which
   vendor is faster.

**A cross-plane comparability claim still may not appear anywhere** — not in `README.md`,
`docs/benchmark-results.md` or a vendor page, not in `docs/index.html`, not in a chart subtitle
or footnote. The remaining obstacle is not the parameters but the **CPU model**: a burstable
CloudHub 1.0 worker against a hard-capped CloudHub 2.0 replica. Each surface carries that
statement, and each table stands as a batch-vs-stock contrast within its own vendor and plane;
it is only reading one table against another that the record does not support. Do not weaken
that on the strength of the parameters alone.

### Throughput is not an independent result

**Do not report requests per second as a second finding alongside latency.** The load in §7 is
closed-loop: `oha -c <plane-conc>` with no rate limit keeps exactly `-c` requests in flight at all
times (10 on CloudHub 1.0, 5 on CloudHub 2.0), so the client cannot issue a request until an
earlier one returns. Under that arrangement throughput is arithmetically pinned to latency:
`requests per second = concurrency ÷ mean latency`.

The tabulated check in §11 shows that identity reproducing the two batch results within rounding
and the two stock results loosely, the looser fit being what shedding does to the arithmetic when
a run does not complete every request it starts. The two numbers are still two views of one
measurement. Where they diverge, the derived throughput merely runs ahead of what the run
actually achieved, which is an artifact of shedding and not an independent finding.

The consequences for how a run is written up:

- Present throughput as the **consequence** of the latency improvement, not as an additional,
  independently observed win. The sentence to write is "mean latency fell from *x* to *y*,
  which at the plane's concurrency is *z* requests per second", not "latency improved *and*
  throughput improved".
- A ratio quoted for throughput and a ratio quoted for mean latency are **the same number**.
  Do not sum them, average them, or list them as separate results. Quoting an "*n*× throughput
  gain" and an "*n*× mean-latency gain" side by side counts one measurement twice.
- The same applies to the charts. A throughput chart is a rescaled reciprocal of a mean-latency
  chart, so if both are published, say so in the caption rather than letting the pair read as
  two corroborating measurements.
- Percentiles are unaffected by this. p50, p90, p95 and p99 carry information the mean does
  not, and the shape of the distribution — particularly the floor discussed in §11 — remains a
  genuinely separate thing to report.

If a future run wants a throughput number that is genuinely independent of latency, it needs an
**open-loop, fixed-rate** load generator configuration instead: drive a set request rate and
observe whether the service keeps up and what its latency does at that rate. For `oha` that
means the request-rate limit flag (`-q`), typically stepped across several rates. The procedure
in §7 deliberately does not use it, so nothing this runbook measures as written supports an
independent throughput claim.

---

## 13. Known gaps in the original procedure

Things the July docs did not pin down. Decide each one now and write it into the report. Several
entries that appeared here as unknowns have since been **closed** — by recovering the operator's
harness, or by the 2026-08-04 re-run recording what it did. They are kept, marked, because the
resolution is the instruction.

| # | Gap | Status |
| ---: | --- | --- |
| 1 | The `oha` command line was nowhere in the repo | **Closed** |
| 2 | The warmup was described but not scripted | **Closed** |
| 3 | Neither region is recorded | **Closed**; its hypothesis falsified |
| 4 | The July load parameters differed by plane | **Open** for July, closed for current figures |
| 5 | Which run produced the July figures | **Unresolvable**; superseded |
| 6 | The vendor region is not recorded | State it in the report |
| 7 | No run log | Save raw output |
| 8 | The New Relic pair was never benchmarked before 2026-08-04 | **Closed** |
| 9 | The exact appender build is unidentified | Record the commit SHA |
| 10 | CH2 deployment steps are undocumented | Write them down |
| 11 | The `FILE` appender is CloudHub 1.0-shaped | Check the CH2 startup log |

<details>
<summary>Gap, impact and resolution for each entry</summary>

### 1. The `oha` command line was nowhere in the repo

- **Gap:** only "oha, 10 connections, 60 s" in the July prose. Flags for keep-alive, HTTP
  version, per-request timeout, rate limit and `-w` were all unrecorded.
- **Impact:** a re-run with different defaults is not comparable.
- **Resolution — closed.** The published protocol is
  `oha --no-tui -z 5m -w -c <plane-conc> <url>/log?count=20` (CloudHub 1.0 at `-c 10` over
  `http`, CloudHub 2.0 at `-c 5` over `https`), oha defaults for everything else, text output;
  the harness also runs Test 1 (`-z 1m`) and Test 4 (`-z 5m -t 30s`). §7 carries it verbatim,
  and `docs/benchmark-results.md` and `docs/index.html` both publish it.

### 2. The warmup was described but not scripted

- **Gap:** "3 requests, 10 s pause" — sequential or concurrent? Same endpoint? Counted?
- **Impact:** minor, but it is the difference between a primed and an unprimed flusher.
- **Resolution — closed.** The harness that produced the published 2026-10-12 figures sends 5
  light warmups (`/log?count=1`) one after another, discarded, then a 30 s drain before the
  measured window; light, so the warmup does not pre-load the stock flusher's backlog. The
  manual variant in §7 is 5 `curl --connect-timeout 15 --max-time 60` calls to the same
  `/log?count=20` endpoint, then a 10 s pause and a 10 s settle after the measured run; if you
  use it, say so in the report. The July run used 3.

### 3. Neither region is recorded

- **Gap:** not for CH1, not for CH2, not for the load generator.
- **Impact:** was believed to be the single biggest reproducibility hole and the cause of the
  CH1-vs-CH2 gap.
- **Resolution — closed, and the hypothesis it supported is falsified:** both planes were in the
  same region (§11). Still choose one region up front (§1), set it explicitly (§4), pick a CH2
  space in it (§5), check the two match (§6) and record all three locations (§8), as hygiene.

### 4. The July load parameters were not the same on the two planes

- **Gap:** the harness drove CloudHub 1.0 at `-c 10` / `count=30` and CloudHub 2.0 at `-c 5` /
  `count=15`, and the two planes over different schemes.
- **Impact:** any cross-plane reading measures the parameter difference as much as the platform.
- **Resolution — permanently open for the July figures, closed for the current ones.** Little's
  Law puts the July runs at `-c 10` (§11) but cannot resolve `count=`, and no raw output survives
  to settle it. The current protocol holds `count=20` on both planes, CloudHub 1.0 at `-c 10`
  over `http` and CloudHub 2.0 at `-c 5` over `https` (§7), recorded per run (§8).

### 5. Which run produced the July figures

- **Gap:** at least two Datadog runs exist and their numbers differ by up to 4× on CloudHub 2.0.
- **Impact:** the July table cannot be traced to a procedure, only to prose.
- **Resolution — unresolvable after the fact, and superseded.** The published run is 2026-10-12,
  with its parameters recorded (the 2026-08-04 run it replaced is history too); do not cite the
  July figures as a platform comparison, or as current.

### 6. The vendor region is not recorded in the report

- **Gap:** the app config pins neither. The Datadog demos default to Datadog's primary intake
  host and read `dd.site` per deployment, and the New Relic demos likewise read `nr.logHost`, so
  the intake used is a property of the run, not of the repo.
- **Impact:** intake latency and rate limits are region-specific.
- **Resolution:** state every vendor's region in the report, and record the `dd.site` and
  `nr.logHost` values, the Elasticsearch project region and the Dynatrace environment region —
  each identical across its own pair (§3, §8). The published run: Datadog `ap2` (Sydney), New
  Relic US, Elasticsearch Serverless in Sydney, Dynatrace SaaS on AWS Sydney.

### 7. No run log

- **Gap:** no raw oha output, no app logs, no timestamps are kept in the repo — nor beside the
  harness, whose results directory does not exist.
- **Impact:** nothing can be re-derived or audited, which is the reason several entries above can
  only be closed by re-running.
- **Resolution:** save the JSON output and the appender heartbeat lines under
  `docs/benchmark-runs/` (gitignored if large), and keep them for as long as the figures are
  published.

### 8. The New Relic pair was never benchmarked before the 2026-08-04 run

- **Impact:** the New Relic figures are a first measurement; there is no prior New Relic figure
  to reproduce or contradict.
- **Resolution — closed.** The protocol now drives the New Relic pair in every phase (§7). Label
  its figures as a first measurement rather than a re-run, and do not read them against the
  Datadog pair (§12 item 8).

### 9. The exact appender build is unidentified

- **Gap:** version `0.1.3` covers at least two materially different builds.
- **Impact:** exactly the problem forcing this re-run.
- **Resolution:** record the commit SHA — jar digests are per-build and identify nothing across
  builds (§2), so they are a build record, not an identifier.

### 10. CH2 deployment steps are undocumented

- **Gap:** the report says "0.1 vCore replica" and nothing else — no space, no ingress config, no
  port handling.
- **Impact:** the CH2 half of the benchmark cannot be reproduced from the docs.
- **Resolution:** write down the space, its region, the replica size, and the exact JVM args.

### 11. The `FILE` appender is CloudHub 1.0-shaped

- **Gap:** `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log`, self-described in the config as
  the CH1 custom-log-appender convention.
- **Impact:** on CH2 that path convention and the `domain` system property may not apply. If the
  path is unwritable, Log4j2 errors; if `${sys:domain}` does not resolve, Log4j2 writes it as a
  literal filename. Either way it is a CH2-only cost on the same 0.1 vCore, and it is a
  difference the report claims does not exist.
- **Resolution:** check the CH2 startup log. If it misbehaves, either fix the path for CH2 or drop
  the `FILE` appender from **both** apps on **both** platforms — symmetrically.

</details>

---

[Docs index](README.md) · [Benchmark results](benchmark-results.md) ·
[Configuration](configuration.md) · [Mule guide](mule.md) · [Building](building.md)
