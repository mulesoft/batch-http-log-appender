<!--
SPDX-License-Identifier: Apache-2.0
-->

# CloudHub benchmark runbook

How to reproduce (or re-run) the **"Measured on CloudHub (real Datadog and New Relic intake)"**
figures in [`../README.md`](../README.md) and the full report in
[`benchmark-results.md`](benchmark-results.md) / [`index.html`](index.html).

This is the operator-facing **manual single-run** procedure: `oha` against `/log?count=20` — 22
log events per request — over plain `http`, after a warmup, driven paired-parallel with one
vendor per side. §7 is the authoritative statement of it; §6 is where you confirm it before
measuring. **Note on provenance:** the currently published figures (2026-08-04) were produced by
the automated **4-test** harness (Test 1 = 1 min, Test 2 = in-thread churn, Test 3 = 5 min
sustained — the headline — and Test 4 = 5 min with a 30 s per-request cap), which restarts and
warms every app before each test and drives **plane-specific concurrency (CloudHub 1.0 at
`-c 10`, CloudHub 2.0 at `-c 5`)**. The manual recipe below is the equivalent single-run path;
where it and the harness differ, the harness is authoritative — follow the plane-specific `-c`
and `count=20` here so a manual re-run stays comparable.

It also exists because a version string cannot identify a build: the appender jars have changed
repeatedly (append hot path, UTF-8 byte estimation, flusher lifecycle/termination, retry timing,
spill accounting) while the POM version stayed at `0.1.3`, so `0.1.3` alone cannot tell you which
code produced a number. Re-run from source, record the commit, and republish.

> **Provenance — which figures this runbook describes.** The figures in
> [`../README.md`](../README.md), [`benchmark-results.md`](benchmark-results.md) and
> [`index.html`](index.html) are the run of **2026-08-04**, produced by the automated **4-test**
> harness (see the note above): the headline is **Test 3**, a 5-minute sustained run at
> plane-specific concurrency (CloudHub 1.0 `-c 10`, CloudHub 2.0 `-c 5`) against
> `/log?count=20` (22 log events/request) over plain `http`. Raw `oha` output for all four tests
> is retained locally (git-ignored, it carries hostnames) under `.claude/bench-results/`, and the
> charts + Markdown report are regenerated from it, so those figures are *recorded*, not asserted.
> An **earlier, superseded** set of Datadog-only tables (73.18 / 4.05 req/s on CloudHub 1.0,
> 12.75 / 2.83 on CloudHub 2.0) was measured in July under a *different* protocol: per-plane
> asymmetric load (`-c 10` / `count=30` on CloudHub 1.0 against `-c 5` / `count=15` on CloudHub
> 2.0), plain `http` on 1.0 against `https` on 2.0, and a 3-request warmup. At least **two** July
> runs exist and **no raw `oha` output from any July run survives**, so the July parameters remain
> *asserted* rather than *recorded*; §11 keeps that audit as history. Do not quote a July figure
> as current, and do not carry its cross-plane comparison forward — see §11.

## Contents

1. [What you must supply](#1-what-you-must-supply)
2. [Build the artifacts](#2-build-the-artifacts)
3. [Properties each app needs](#3-properties-each-app-needs)
4. [Deploy — CloudHub 1.0](#4-deploy--cloudhub-10)
5. [Deploy — CloudHub 2.0](#5-deploy--cloudhub-20)
6. [Pre-flight fairness checklist](#6-pre-flight-fairness-checklist)
7. [Warmup and measured run](#7-warmup-and-measured-run)
8. [What to record](#8-what-to-record)
9. [Datadog screenshots (Figures 2–4)](#9-datadog-screenshots-figures-24)
10. [How many runs](#10-how-many-runs)
11. [The CloudHub 2.0 result — reproduce or correct?](#11-the-cloudhub-20-result--reproduce-or-correct)
12. [Republish](#12-republish)
13. [Known gaps in the original procedure](#13-known-gaps-in-the-original-procedure)

---

## 1. What you must supply

| # | Item | Notes |
| --- | --- | --- |
| 1 | **Datadog account** | Trial is enough. The Logs intake is rate-limited per org/key — see §6. |
| 2 | **Datadog API key** | Organization Settings → API Keys. This is an *API key*, not an app key. |
| 3 | **Datadog site/region** | Your key only works against the site your org lives in. Both demos default to Datadog's primary log-intake host (`http-intake.logs.datadoghq.com`), so they are deployable as shipped but will reject a key from any other site. Point them at yours with `-Ddd.site=<your-intake-host>` (or `DD_SITE`) — one property, resolved identically by both apps, so no file edit is needed. Take the host for your site from [Datadog's documentation](https://docs.datadoghq.com/getting_started/site/); the set of sites changes, so this repo does not restate it. **Pass the same value to both apps, or to neither** — a pair whose halves ship to different sites is not a comparison. |
| 4 | **Anypoint org + environment** | With entitlement for **both** CloudHub 1.0 workers and CloudHub 2.0 (a private space or shared space). |
| 5 | **One region, chosen before anything is deployed** | Pick a single region and deploy **both** platforms into it. CloudHub 2.0 space availability is narrower than CloudHub 1.0 region availability, so confirm a CloudHub 2.0 space exists in the region *before* committing to it. Record the choice; it is a parameter of the benchmark, not an incidental. |
| 6 | **CH2 target space** | CloudHub 2.0 deploys into a space. That space must sit in the region chosen in item 5, and its region must be recorded — see §6 and §8. |
| 7 | **A load-generator host** | Where `oha` runs. Its network position relative to each deployment is part of the measured path, so keep it constant across every run of the session and record its location alongside the two deployment regions. |
| 8 | **`oha`** | `brew install oha`. Verified present locally: `oha 1.14.0`. |
| 9 | **Connected App** | Needed for `anypoint-cli-v4` under `client_credentials`. Every Runtime Manager scope is **environment-scoped**, so an app that holds the right scopes but was never attached to the target environment authenticates cleanly and then 403s on every Runtime Manager call. Grant, at organization level, **View Organization** (`read:organization`) and **CloudHub Organization Admin** (`admin:cloudhub`); and per environment, **View Environment**, **Create Applications**, **Read Applications**, **Manage Settings**, and **Delete Applications** (the last for cleanup). Verify as §4 describes. |

Also required, not optional: a **New Relic ingest key**. The published protocol drives a Datadog
app and a New Relic app **at the same instant in every phase** (§7), one vendor per side, so a
full reproduction needs both vendors' credentials and all four apps. As with `dd.site`, the New
Relic Log API host (`nr.logHost` / `NEW_RELIC_LOG_HOST`) must be **the same value on both apps of
the New Relic pair, or unset on both** — a pair whose halves ship to different regions is not a
comparison.

With only one vendor's key you can still measure that vendor's pair, but the phases then hold a
single app each and must run **one at a time**, because both halves of a pair share one intake and
the standing constraint is that no vendor's intake is ever loaded by two apps at once (§7). Say so
in the report: a single-vendor session is a valid batch-vs-stock contrast, but it is not the
published protocol and its phase timing differs.

---

## 2. Build the artifacts

Run from the repo root. Record `git rev-parse HEAD` — that commit, not the `0.1.3` version
string, is what identifies the build under test.

```bash
git rev-parse HEAD                       # record this in the report
mvn -B clean install                     # installs batch-http-core + batch-http-log4j2 into ~/.m2
```

The build enforces license headers at `validate` and the tests bind localhost sockets, so run
it with normal (unsandboxed) network and filesystem access.

Expect **131 tests to pass** — 117 in `batch-http-core` and 14 in `batch-http-log4j2`. Check the
counts rather than only the `BUILD SUCCESS` line, so that tests silently ceasing to run is
visible rather than mistaken for a clean build.

Then build the four Mule deployables. They are **not** modules of the root reactor, so each is
a separate invocation:

```bash
for d in datadog-batch-http datadog-stock-http newrelic-batch-http newrelic-stock-http; do
  (cd "demo/mule/$d" && mvn -B clean package)
done
```

Artifacts (`mule-application` jars):

| App | Artifact |
| --- | --- |
| `datadog-batch-http` | `demo/mule/datadog-batch-http/target/mule-datadog-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `datadog-stock-http` | `demo/mule/datadog-stock-http/target/mule-datadog-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `newrelic-batch-http` | `demo/mule/newrelic-batch-http/target/mule-newrelic-demo-1.0.0-SNAPSHOT-mule-application.jar` |
| `newrelic-stock-http` | `demo/mule/newrelic-stock-http/target/mule-newrelic-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar` |

Refresh `demo/mule/deployable/` from this build. Those clean-named copies are what §4 and §5
deploy, so they are also what the checks below verify:

```bash
mkdir -p demo/mule/deployable
for app in datadog-batch-http datadog-stock-http newrelic-batch-http newrelic-stock-http; do
  cp "$(ls demo/mule/$app/target/*-mule-application.jar)" "demo/mule/deployable/$app.jar"
done
```

**Verify each batch app bundles the appender from *this* build, not a stale one resolved from
`~/.m2`.** The version number reads `0.1.3` either way, so the check has to be on bytes.

> **Jar md5s are not reproducible across builds.** The zip archive embeds per-build
> timestamps, so the same unchanged source produces a different archive digest every time it
> is built — `batch-http-core-0.1.3.jar` has hashed several different ways from this one
> unchanged source, across the originally-deployed jar and every rebuild since.
> **Never compare a jar against a digest recorded from a previous build.** Such a comparison
> mismatches whether the code is stale or freshly rebuilt, so it cannot tell the two apart,
> and it will cry wolf on a perfectly good build.

The sound comparison is **within a single build pass**: hash the appender jars the build just
produced, then hash the copies embedded in the deployables built in that same pass. Those must
be byte-identical, which is what proves the deployable carries this build's appender rather than
an artifact resolved from somewhere else — the failure mode that spoiled the original benchmark.

```bash
VER=0.1.3
WORK="$(mktemp -d)"          # outside the repo, so nothing is extracted into the working tree

for m in batch-http-core batch-http-log4j2; do
  built="$(md5 -q "$m/target/$m-$VER.jar")"
  for app in datadog-batch-http newrelic-batch-http; do
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

The two stock apps are the control: they must run on the Mule container's stock Log4j2 and
bundle **no** appender of their own.

```bash
for app in datadog-stock-http newrelic-stock-http; do
  jar="demo/mule/deployable/$app.jar"
  printf '%-22s batch-http jars: %s   log4j jars: %s\n' "$app" \
    "$(unzip -l "$jar" | grep -c 'batch-http.*\.jar$')" \
    "$(unzip -l "$jar" | grep -c 'log4j.*\.jar$')"
done
```

Both counts must be zero for both apps. One true-but-confusing detail to expect: the stock
deployables **do** contain `META-INF/maven/com.mulesoft.support.batchhttp/…` entries. That is
only the demo app's own Maven groupId coordinates, not a bundled appender, and it is not
contamination.

Record both digests for your own build, next to its commit SHA, as the provenance record for
the figures you publish (§8). Do not carry a digest forward from an earlier build: it is a
record of *that* build and nothing more, since a later build of identical source will not
reproduce it — which is exactly why the within-build comparison above is the check that counts.

> `demo/mule/deployable/` holds the four deployables §4 and §5 deploy. The folder is
> **gitignored** (a blanket `*.jar`), so it is absent from a fresh clone and never appears in
> `git status` — the artifacts cannot be obtained by cloning, only by building. Its clean
> filenames are the trap: nothing about a jar sitting there tells you which source produced it,
> and exactly that kind of staleness spoiled the original benchmark. So refresh it from
> `target/` on every build, run the verification above against the copies in `deployable/`
> rather than only those in `target/`, and record the commit SHA of the build that populated it.

---

## 3. Properties each app needs

`log4j2.xml` is parsed by **Log4j2, not Mule**. Mule application properties and
`secure::` properties are invisible to it. Everything below must arrive as a **JVM system
property** (`-D…`) or an **environment variable** — not as a Mule application property.

### Datadog pair

| Property | Where it goes | Value | Required? |
| --- | --- | --- | --- |
| `datadog.apiKey` (or env `DD_API_KEY`) | JVM arg | your Datadog API key | **Yes.** Without it the lookup resolves to the literal `MISSING_KEY` and every send is rejected. |
| `app.service` | JVM arg | leave unset | Defaults to the app's own name (`datadog-batch-http` / `datadog-stock-http`). This is the facet that separates the two **apps** in Datadog — `host` defaults to `${sys:domain}` in both. Do not override unless you also update the Datadog queries in §9. |
| `app.platform` (or env `APP_PLATFORM`) | JVM arg | `cloudhub-1.0` / `cloudhub-2.0` | **Set it, on both apps of every pair.** This is the facet that separates the two **planes**; unset it reads `unspecified`, and then the CH1 and CH2 halves of the benchmark arrive indistinguishable in Datadog. See the note below — the scripted CH1 deploy cannot set it, so on CloudHub 1.0 it is a manual step in Runtime Manager. |
| `dd.site` (or env `DD_SITE`) | JVM arg | your site's intake host | Defaults to Datadog's primary intake host. Required if your org lives elsewhere (§1 item 3). **The two apps of the Datadog pair must agree on it** — same value on both, or unset on both. Halves that ship to different sites are not a comparison. |
| `dd.host` | JVM arg | leave unset | Defaults to `${sys:domain}` (CH1 app name), falling back to the machine hostname. |
| `dd.logUrl` | JVM arg, **stock app only** | leave unset for real Datadog | Only used to point the stock app at a mock intake, or at a whole URL the `dd.site` chain cannot express. |

The resolution chain in both apps is
`${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}`, so any of the three
works. Prefer the JVM arg on CloudHub.

> **`app.platform` must be set by hand in Runtime Manager on CloudHub 1.0.**
> `scripts/deploy-ch1.sh` passes only the vendor key as a `--property` (plus `elastic.url` for
> the Elasticsearch pair) — so an app
> deployed through it reports `platform: unspecified` until you add `app.platform` yourself in
> Runtime Manager's **Properties** tab (CloudHub 1.0 surfaces those as JVM system properties) and
> restart. Do that for **every** CloudHub 1.0 app you deploy through the script — both halves of
> each vendor pair — before a measured run, or the CH1 side of the comparison arrives unlabelled.
> A value on one half only is worse than none: it makes the A/B look like a comparison of two
> platforms. And on any property with a `:-` default, an override you no longer want must be
> **deleted, not cleared** — Log4j2 honours the default only when the property is *absent*, so a
> present-but-empty value wins and blanks the facet.

### New Relic pair

Same shape, with `newrelic.apiKey` / `NEW_RELIC_API_KEY`, `nr.hostname`, and `nr.logHost` (or
`NEW_RELIC_LOG_HOST`) selecting the Log API host for your account's region — the counterpart of
`dd.site`, and subject to the same rule: **both apps of the New Relic pair must agree on
`nr.logHost`**, same value on both or unset on both. `app.service` and `app.platform` behave
identically, `app.platform` included in the manual-step caveat above.

### Where to put the JVM args

- **CloudHub 1.0** — Runtime Manager → your app → **Settings → Properties → JVM arguments**
  (or `--property` on the CLI, see §4). Mark the key as a **secure property** so it is masked.
- **CloudHub 2.0** — Runtime Manager → your app → **Settings**, add to the JVM/`MULE_OPTS`
  arguments, or set `DD_API_KEY` as a (secure) **environment variable** in the deployment.
  CH2 exposes env vars to the JVM, so the `${env:DD_API_KEY}` leg of the chain resolves.

---

## 4. Deploy — CloudHub 1.0

**Sizing used originally: 1 worker × 0.1 vCore.**

CH1 is fully scriptable with `anypoint-cli-v4` (verified installed: `1.6.25`). Authenticate
once with `anypoint-cli-v4 conf` or the `ANYPOINT_*` environment variables.

`scripts/deploy-ch1.sh` automates this whole section — the two deploys, the region-parity
assertion, the hostname capture below, and polling until each app reports `STARTED`:

```bash
scripts/deploy-ch1.sh [--dry-run] [--datadog | --newrelic | --elastic | --all] [APP ...]
```

It defaults to the Datadog pair, and reads `ANYPOINT_CLIENT_ID`, `ANYPOINT_CLIENT_SECRET`,
`ANYPOINT_ORG`, `ANYPOINT_ENV`, `REGION` and `DD_KEY` from the environment (`NR_KEY` only when a
New Relic app is selected; `ELASTIC_URL` and `ELASTIC_KEY` only for `--elastic`). The published
protocol needs **both** vendor pairs on this plane, so run it with **`--all`** (the Datadog and New
Relic pairs) and both keys exported. `--dry-run` prints the exact commands with secrets
masked. The explicit commands below stay in the runbook regardless: they document what the script
does, and you need them for the CloudHub 2.0 side and for debugging.

Note what the script does **not** do: it passes only the vendor credential as a `--property` (plus `elastic.url` for the
Elasticsearch pair), so
every app it deploys still needs `app.platform` set by hand afterwards (§3).

> **Target the apps that are already deployed, or you will benchmark a parallel copy.** The
> CloudHub 1.0 applications from the original run may still exist, and on CloudHub 1.0 the
> application name *is* the public hostname label — deploying the same jar under a different
> name creates a second, independent app rather than replacing the first. The deployed names are
> the module names (`datadog-batch-http`, `datadog-stock-http`, `newrelic-batch-http`,
> `newrelic-stock-http`) carrying a **deployment-specific suffix**, which is recorded in the
> operator's local harness and is deliberately not written down here. Export it before
> deploying, so the script and the commands below address the existing apps:
>
> ```bash
> export CH1_APP_SUFFIX="<the suffix the existing CloudHub 1.0 apps carry>"
> ```
>
> Leave it unset only when deploying into a clean org. Confirm against
> `runtime-mgr cloudhub-application list` before the first deploy: an unexpected pair of
> near-identically-named apps is the signature of having got this wrong. CloudHub 2.0 app names
> do not carry the suffix.

**Verify the credentials before the first deploy.** A 403 here is the most likely blocker and
the most commonly misdiagnosed one. Do not test with `anypoint-cli-v4 account user describe` —
a client-credentials Connected App is not a user and has no user record, so that call 403s even
when everything is configured correctly. Use a Runtime Manager read instead, which exercises
authentication, org-name resolution, environment-name resolution and Runtime Manager access in
one call. Run it with the org and environment already configured, either through
`anypoint-cli-v4 conf` or the exports in the next block:

```bash
anypoint-cli-v4 runtime-mgr cloudhub-application list -o json    # an empty [] is a pass
```

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

> `--property name:value` sets a CloudHub **application property**. Log4j2 cannot see those.
> On CH1 they *are* additionally surfaced as system properties by the wrapper, which is why
> the chain above works — but if a run shows `MISSING_KEY` in the app log despite a successful
> deploy, the key has to go under **JVM arguments** in Runtime Manager instead
> (`-Ddatadog.apiKey=…`). There is no JVM-arguments flag in `anypoint-cli-v4` 1.6.25, so that
> fallback is a manual step in the UI and the deploy cannot stay fully scripted.

Useful during the run:

```bash
APP="datadog-batch-http${CH1_APP_SUFFIX}"
anypoint-cli-v4 runtime-mgr cloudhub-application describe "$APP"
anypoint-cli-v4 runtime-mgr cloudhub-application tail-logs "$APP"
anypoint-cli-v4 runtime-mgr cloudhub-application restart "$APP"    # between runs
anypoint-cli-v4 runtime-mgr cloudhub-application download-logs "$APP" ./logs/
```

**Do not construct the public hostname; read it back from the platform.** `--region` takes an
AWS-style name — the `<area>-<direction>-<number>` form — while the CloudHub 1.0 public hostname
uses a short region label that is a **different string**, and for `us-east-1` the hostname carries
no region component at all. Reusing `REGION` in a hostname therefore yields an unreachable URL in
every region. `describe -o json` reports the authoritative value in `fullDomain`, with `domain` as a
fallback:

```bash
SCHEME=http       # plain http on both planes — see the scheme note below
BATCH_URL="$SCHEME://$(anypoint-cli-v4 runtime-mgr cloudhub-application describe \
  "datadog-batch-http${CH1_APP_SUFFIX}" -o json | jq -r '.fullDomain // .domain')"
STOCK_URL="$SCHEME://$(anypoint-cli-v4 runtime-mgr cloudhub-application describe \
  "datadog-stock-http${CH1_APP_SUFFIX}" -o json | jq -r '.fullDomain // .domain')"
echo "$BATCH_URL" "$STOCK_URL"
```

Those are the URLs §7 loads, on port 80 — the published protocol is plain `http`. Only if the
platform reports neither field should you fall back to constructing a hostname by hand. While you
have the `describe` output open, confirm the platform reports the region you asked for, and write
it down for §8.

> **Scheme parity — plain `http` on both planes.** The published protocol drives **every** app
> over plain **`http`**, CloudHub 1.0 and CloudHub 2.0 alike, so the two planes meet the same
> transport. That is a deliberate correction of the superseded July run, which drove the
> **CloudHub 1.0** apps over plain **`http`** and the **CloudHub 2.0** apps over **`https`** (a
> private-space certificate, accepted client-side with `--insecure`). CloudHub 1.0 serves both 80
> and 443, so the July asymmetry was a choice rather than a constraint, and it meant TLS record
> processing sat on one side of the ledger only — a real cost on a 0.1 vCore container, with the
> handshake landing in the `Fastest` figure. Keep `SCHEME=http` for both planes, record the scheme
> alongside the region in §8, and do not compare any figure measured over `https` against one
> measured over `http`.

---

## 5. Deploy — CloudHub 2.0

**Sizing used originally: 1 replica × 0.1 vCore.**

**The space must be in the same region as the CloudHub 1.0 workers.** Whichever of the two
routes below you take, select a space in the region chosen in §1 and deployed to in §4, and
record the space's region. If no CloudHub 2.0 space is available in that region, do not accept
the mismatch: go back and choose a different region for **both** platforms, redeploying the
CloudHub 1.0 pair if necessary. A cross-region pair does not produce a comparable measurement.

**This step is manual.** `anypoint-cli-v4` 1.6.25 has no CloudHub 2.0 topic — `runtime-mgr`
exposes only `cloudhub-application` (CH1), `standalone-application`, `rtf`, `server`,
`serverGroup` and `gateways`. (`--releaseChannel` mentions CH2 values in its help text, but
there is no command that targets a CH2 space.)

Two options:

1. **Runtime Manager UI** (what the original run used, as far as the docs record):
   Applications → Deploy application → choose the CH2 target space, which must be one in the
   chosen region → upload
   `demo/mule/deployable/datadog-batch-http.jar` → **Replicas 1, vCores 0.1** →
   Runtime `4.9.15`, Java 17 → add the API key **and `app.platform=cloudhub-2.0`** under
   environment variables or JVM arguments. Unlike the scripted CloudHub 1.0 path, CH2 lets you set
   both at deploy time, so there is no follow-up manual step here — but it is just as easy to
   forget, and an unset `app.platform` reads `unspecified` (§3). Repeat for the stock app.

2. **`mule-maven-plugin` `cloudhub2Deployment`** — the plugin is already on each app
   (`4.10.0`) but is configured with `<classifier>` only, **no deployment block**. You would
   need to add one:

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

   then `mvn deploy -DmuleDeploy`. This route uploads the module's own `target/` artifact
   rather than the `demo/mule/deployable/` copy; the two are the same bytes as long as
   `deployable/` was refreshed from this build per §2. It requires a **Connected App** with
   Runtime Manager deploy scopes. Adding it is a repo change; it is not committed today.

Also check, before the CH2 run:

- **Inbound port.** CH2 routes ingress to the port the platform assigns. The demos set
  `http.port=8083`/`8084` in `config.properties`, and the listener binds `${http.port}`.
  System properties outrank the properties file in Mule 4, so the platform value normally
  wins — but confirm the app is actually reachable before treating a bad number as a
  measurement. A CH2 app that fails its ingress health check will not serve `/log` at all.
- **The `FILE` appender path.** Both apps write a `RollingFile` to
  `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log`, which the config comments describe as
  the CloudHub **1.0** custom-log-appender convention. `${sys:domain}` is a CH1 property. On
  CH2 that path and that property may not exist. Check the CH2 startup log for a Log4j2
  `RollingFile` error and for a filename containing a literal `${sys:domain}` — see §11.

---

## 6. Pre-flight fairness checklist

Run through this **before** the first measured run. Items marked ⚠ were **not** held constant in
the superseded July procedure — each is a known, documented asymmetry between the two planes, not
a speculative one, and each is something the published protocol deliberately fixed.

- [ ] **Same appender build in both batch apps** — each deployable's bundled appender matched
      against the jars from the same build pass, per §2.
- [ ] **Same sizing** — CH1: 1 worker × 0.1 vCore. CH2: 1 replica × 0.1 vCore.
- [ ] ⚠ **Plane-specific concurrency, identical payload** — **`count=20`** (22 log events/request)
      on every run, both planes. Concurrency is **`-c 10` on CloudHub 1.0 and `-c 5` on CloudHub
      2.0**: the CloudHub 2.0 replica is a capped-CPU, no-swap worker held at lower concurrency to
      stay off its OOM ceiling. Because `-c` differs, **read each plane's batch-vs-stock contrast
      on its own — never CloudHub 1.0 against CloudHub 2.0.** Record the values you used (§8). (The
      next benchmark round plans to even concurrency across planes; until then, keep the values
      above so a re-run matches the published figures.)
- [ ] ⚠ **Same URL scheme on both planes** — plain **`http`** everywhere. The July run drove
      CloudHub 1.0 over plain `http` and CloudHub 2.0 over `https`; see the scheme note in §4.
- [ ] **Region parity.** The CloudHub 1.0 worker region as the platform reports it and the
      CloudHub 2.0 space's region are the same region, both matching the choice made in §1.
      Confirm this from Runtime Manager rather than from what you intended to deploy. This is
      hygiene rather than a live suspicion: the July deployments were in fact **both in the
      same region**, so a cross-continent round trip is not available as an
      explanation for the July CloudHub 2.0 gap (§11). Record the region for each
      deployment anyway.
- [ ] **Load generator in a fixed location** for every run of the session, at the same distance
      from both deployments, and recorded. Note whether it is a laptop on residential/VPN
      networking or a cloud host; a VPN alone can add 50–150 ms.
- [ ] **Same runtime version and Java version** — 4.9.15 / Java 17 on every app.
- [ ] **Both apps of a vendor pair agree on their intake** — the same `dd.site` and the same
      Datadog API key across the Datadog pair, the same `nr.logHost` and the same New Relic
      ingest key across the New Relic pair (§3), so intake behaviour and rate limits are shared
      within each comparison. Same value on both halves, or unset on both halves.
- [ ] **Distinct `app.service` per app** — `datadog-batch-http` vs `datadog-stock-http`, and the
      same shape across the New Relic pair. Leave the defaults alone. `host` is `${sys:domain}` in
      both halves and is *not* a discriminator; every Datadog query in §9 filters on `service`.
- [ ] **`app.platform` set on every app** — `cloudhub-1.0` on each CH1 app, `cloudhub-2.0` on
      each CH2 app. Unset it reads `unspecified` and the two planes become indistinguishable in
      the backend. The scripted CH1 deploy passes only the vendor credential (and `elastic.url`), so on CloudHub 1.0 it
      **must be set by hand** in Runtime Manager's Properties tab and the app restarted (§3);
      on CloudHub 2.0 it goes in at deploy time (§5).
- [ ] **Run the A/B pair in adjacent phases of the same window**, batch phase then stock phase,
      so both halves meet the same intake conditions. Do not compare a batch run from Monday
      against a stock run from Tuesday.
- [ ] **Never two apps against the same vendor intake at once.** The published protocol *is*
      parallel — one Datadog app and one New Relic app under load together — but the two halves
      of any one comparison share an intake, so they never overlap. Phases are sequential; within
      a phase the two apps are pairwise by vendor. See §7.
- [ ] **Rate limits.** Both vendors throttle their Logs intake per org/key. A batch app at full
      speed ships tens of thousands of events per minute. If you see HTTP 429 in the app log
      (the batch appender logs it and honours `Retry-After`), the run is throttled — note it,
      and treat that pair's absolute req/s as unusable in isolation. Loading one intake with a
      single app per phase is what keeps this from being self-inflicted.
- [ ] **Restart each app between runs** so the in-memory queue and the connection pool start
      clean. The batch demos ship with `spillEnabled="false"`, so there is **no spill directory
      to clear** — that step applies only if you re-enable spill, and then also confirm
      `${java.io.tmpdir}/batch-http-spill/<sink>/` is empty, because a leftover backlog is
      replayed on startup (`spillRecoveryMode=ADOPT`) and competes for the same 0.1 vCore.
- [ ] **Nothing else deployed in the same environment** generating load or log volume.

---

## 7. Warmup and measured run

Per phase, in this order. `$URL` is each app's public base URL as the platform reported it in §4,
never a hostname you assembled yourself. This is the manual single-run equivalent of the harness's
**Test 3** (the 5-minute sustained headline); set `CONC` to the value for the plane you are testing.

```text
5× curl warmup  →  pause 10 s  →  oha -z 5m load test  →  settle 10 s  →  next phase
```

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

**Run it paired-parallel, one vendor per side.** Each phase puts a **Datadog app and a New Relic
app under load at the same instant** — the two batch apps together, then the two stock apps
together — and the **phases are sequential**. Both apps of that block run the same warmup, the
same measured minute and the same trailing settle, in step.

**The constraint is the intake, not the load generator: never two apps against the same vendor
intake simultaneously.** That is what the pairing buys — no vendor's intake is ever loaded by two
apps at once, so neither half of a comparison waits on the other's intake, and a throttled intake
cannot be self-inflicted. Pairing *across* vendors is safe and is the protocol; pairing *within* a
vendor (batch and stock together, or two apps of the same vendor on the two planes together) is
what invalidates the session. If you have only one vendor's key, the phases hold one app each and
run one at a time (§1).

If `oha` reports connection failures immediately, suspect the URL before you suspect the app: a
hostname assembled from the `--region` value instead of read back from `describe` is
unreachable, and this is exactly how it fails.

Notes on the flags:

- **`--no-tui -z 5m -w -c <plane-conc> <url>`** is the whole command line (Test 3). `-z 5m` and
  `-z 300s` are equivalent; the harness also runs a 1-minute Test 1 (`-z 1m`) and a 30 s-cap Test 4
  (`-z 5m -t 30s`).
- **No `--insecure`, and no `curl -k`.** Both planes are driven over plain `http` (§4), so there
  is no certificate to accept. The superseded July run needed `--insecure` because it drove
  CloudHub 2.0 over `https` against a private-space certificate; pass it again only if you
  deliberately deviate from the protocol back to `https`, and then say so in the report, because
  the resulting figures are not comparable with the published ones.
- The run uses **oha's defaults** for everything else, and they are worth pinning deliberately
  rather than inheriting: HTTP/1.1, keep-alive **on**, **no** per-request timeout (`-t` unset), no
  rate limit (`-q` unset).
- **`-w` (wait-ongoing-requests-after-deadline) IS passed** on the published runs, so the `-c`
  in-flight requests drain to completion instead of being aborted at the deadline. This makes the
  reported `Total` read slightly longer than the nominal `-z` — and the stock appender's
  one-blocking-POST-per-event tail drains visibly longer than batch, which is itself a result, not
  an artifact. Omitting `-w` instead aborts the in-flight tail and reports it as
  `aborted due to deadline`.
- **`--output-format json` was not used** — the plain text summary goes through `tee`. Adding it
  is strictly better for a re-run: machine-readable percentiles you can feed into the chart
  script, no TUI CPU cost on the client, and — see below — no exposure to the text renderer's
  display glitch. Capture both if you also want a screenshot.
- The warmup `curl` carries `--connect-timeout 15 --max-time 60` and hits the **same**
  `/log?count=N` endpoint as the measured run; its output is discarded.

**Events per request is `count + 2`** — a `batch.start` (INFO), `count` × `order.processed`
(INFO), and a `batch.completed` (WARN). So the protocol's `/log?count=20` emits **22 log events**
per request; `count=30` would emit 32.

### Concurrency by plane

The published protocol holds **`count=20` everywhere** (same per-request work on both planes) but
uses **plane-specific concurrency: `-c 10` on CloudHub 1.0, `-c 5` on CloudHub 2.0**. The CloudHub
2.0 replica is a capped-CPU, no-swap worker; at `-c 10` it risks the OOM ceiling, so it is held at
`-c 5`. Keep the payload identical and the concurrency per-plane as above.

Because `-c` differs across planes, **a cross-plane comparison is not valid** — read each plane's
batch-vs-stock contrast on its own. This is looser than the ideal (equal concurrency everywhere),
and the next-round methodology plan is to even it up by driving both planes at `-c 10` and
recording whether CloudHub 2.0 OOMs. Until then the per-plane values above are what the published
figures used, so match them for a comparable re-run.

Even with equal concurrency a cross-plane reading would still be only necessary-not-sufficient: the
two generations have different CPU models — burstable versus hard-capped — which the published
surfaces cite as the remaining reason each table stands on its own. Record the values in the
report either way, per §8.

### Mandatory: validate every run before you record it

`oha` **1.14.0 has a display glitch** that silently corrupts latency. On some runs the text
renderer injects a stray `" 10"` into time values — `Total: 6.0006 10 sec`, `p50 0.0106 10` —
and the printed latencies come out at **one tenth** of the truth. **Throughput
(requests/sec) is always reliable; latency percentiles on a glitched run are the printed value
× 10.** A re-run that hits the glitch and does not catch it publishes latencies that are wrong
by an order of magnitude while every other field looks perfectly normal, so this check is not
optional. It is not hypothetical either: the defect is still live in `oha` 1.14.0, and it hit
three of the eight runs behind the published figures, each of which carries the ×10 correction.

Two independent confirmations, both cheap:

1. **Little's Law.** Under a closed-loop load with no rate limit,
   `requests/sec × mean latency ≈ -c` — which is **10** on CloudHub 1.0 and **5** on CloudHub 2.0
   under this protocol. Land on the concurrency you asked for and the latency is real. Land on
   **one tenth** of it and apply the ×10 correction to every latency and percentile in that run.
2. **Total duration.** A `-z 5m` run must total ≈ 300 s (with `-w`, slightly longer as the tail
   drains). A printed `30.00x`/`6.000x`-style value (i.e. ≈ `-z` ÷ 10) is the same glitch showing
   in the duration field → apply the ×10 correction.

Two cautions on using the law:

- **It only ever validates the concurrency, never the payload.** `count=` does not appear in it.
  A run at `count=20` and a run at `count=30` satisfy it identically, so the law cannot tell you
  which workload produced a number. Only your own record of the parameters can.
- **It loosens on any run that sheds requests.** A request aborted at the deadline or dropped
  under back-pressure is counted in neither the throughput figure nor the latency sample, so the
  product falls a little below `-c`. Around 10% low on a run with a sub-100% success rate or a
  long mean latency is normal; a factor of 2 or 10 is not.
- **Even a clean run does not land exactly on `-c`.** Rounding in the reported mean is enough to
  move the product by several percent. The published runs completed every request they started and
  still spread across a band of up to 16% (as `README.md` states), so read agreement within
  roughly that band as a pass. What the check is looking for is the order-of-magnitude miss, not a
  few percent.

`--output-format json` sidesteps the glitch entirely, which is the strongest argument for using
it. Run the check anyway — it also catches a run that used different parameters than you think.

Then run the stock phase — both stock siblings together, one vendor per side — and then the same
two phases against the other platform.

---

## 8. What to record

Per run, from oha:

| Metric | oha field |
| --- | --- |
| Requests / sec | `summary.requestsPerSec` — a restatement of the mean latency under a closed-loop load, not an independent result; the restatement is exact while every request completes and loosens on runs that shed requests. See *Throughput is not an independent result* in §12 before writing it up |
| Total requests in 60 s | `summary.total` (successRate × total for the OK count) |
| Success rate | `summary.successRate` |
| Average latency | `summary.average` |
| Fastest / Slowest | `summary.fastest` / `summary.slowest` |
| p50 / p75 / p90 / p95 / p99 | `latencyPercentiles` |
| Aborted due to deadline | error-distribution entry |

Also record, per run, that the run **passed the §7 validation** — the Little's Law product against
the concurrency of 5, the total duration, and whether the ×10 glitch correction was applied. A
latency figure with no recorded validation is not a figure anyone can trust later; the absence of
exactly this record is why the superseded July numbers have to be audited arithmetically rather
than simply read.

Per run, from the platform and the app:

- **The exact `oha` command line**, verbatim, including `-c`, `-z` and the full `count=`
  query string, plus the **URL scheme** and which app it was paired with in the phase. These are
  parameters of the measurement, not incidental details: the July run's were never recorded, which
  is why its two platform tables cannot be compared with each other (§7, §11). The published run
  records them on every surface, and that is the standard to hold.
- **Commit SHA** of the build under test — the only identifier that survives a rebuild. Record
  both appender jar digests next to it as a record of that one build; they are not reproducible
  by a later build and cannot be used to identify code (see §2).
- **CloudHub 1.0 worker region**, as Runtime Manager reports it.
- **CloudHub 2.0 space region**, together with the space's name.
- **Load-generator location** — city or cloud region, and whether the traffic leaves through a
  VPN. All three of these belong in the published report; the measured path runs through all
  of them.
- **Worker/replica sizing** as the platform reports it, not as you requested it.
- **The appender configuration under test**, which is `maxBatchRecords=200`,
  `maxBatchBytes=900000`, `lingerMillis=2000`, `queueCapacity=25000` and `flusherThreads=1`,
  identical in both batch apps, plus the **three deliberate divergences from the library
  defaults** — `gzip="false"`, `spillEnabled="false"` and `includeTimestamp="false"`. The first
  two mean the figures measure batching in isolation rather than the appender as it would be
  deployed, so they belong in the report rather than being left implicit. Note in particular that
  **compression is off in the demo apps**: every app in the comparison POSTs plaintext, so no
  figure here reflects gzip, and none of the CPU cost on a 0.1 vCore container is compression.
- **The batch appender's own stats heartbeat** — it logs one line per 60 s with
  `accepted / sent / dropped / failed / queueDepth` plus spill totals
  (`statsIntervalMillis=60000`, `diagnosticsLevel=INFO`). Capture the lines that bracket the
  measured window; they tell you whether anything was dropped, which no oha metric can show.
  With the demos' `spillEnabled="false"`, `dropped` is the overload signal — the spill totals
  stay at zero.
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

**Figure 2 — `datadog-batch-ingest-lag.png`** (batch app, near-zero lag)

- Query `service:datadog-batch-http`.
- Time range: the measured minute plus ~5 minutes of tail.
- Open a single `order.processed` event in the side panel and show the event's
  `generatedAt` attribute next to the row's Datadog-assigned timestamp. The two should be the
  same instant. Note the browser's display offset in the caption — the current caption reads
  local time (UTC+10) and compares it against a `…Z` value, so the reader needs the offset to
  check the arithmetic. State the offset, not the timezone's name.

**Figure 3 — `datadog-stock-ingest-lag.png`** (stock app, minutes behind)

- Identical view, `service:datadog-stock-http`, same time range and same display offset.
- Pick an event whose `generatedAt` is early in the load window; its Datadog timestamp should
  trail by minutes. Capture it *after* the run ends, while the backlog is still draining —
  that is when the gap is visible.

**Figure 4 — `datadog-log-volume-batch-vs-stock.png`** (volume gap)

- Switch the Log Explorer to a **count / timeseries or top-list** visualization.
- Query `service:(datadog-batch-http OR datadog-stock-http)`, **group by `service`**.
- Same time range for both series — that is the whole point of the figure.
- The published caption is 84.7K batch vs 5.66K stock; your absolute numbers will differ, so
  update the caption text in `README.md` to match what you capture.

Keep the filenames identical so the `README.md` image links keep working.

**Figures 1 and the chart images** (`cloudhub-throughput-*`, `cloudhub-latency-*`) are
generated, not screenshotted:

```bash
python3 -m venv .venv-charts
.venv-charts/bin/pip install -r docs/charts/requirements.txt
.venv-charts/bin/python docs/charts/generate_benchmark_charts.py
```

The measurements are **hardcoded** in `docs/charts/generate_benchmark_charts.py` in the
`PLATFORMS` list. Edit those before regenerating.

---

## 10. How many runs

**Recommendation: 5 measured runs per configuration — with eight configurations (two vendors ×
two appenders × two planes), 40 runs total — and report the median with the min–max range.** The
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

Cost per run is modest: 60 s of load plus a restart and a settle. Because each phase drives two
apps at once, five passes is 20 phases rather than 40 serial runs. Budget ~5 minutes per phase
including the restarts, so ~100 minutes of wall clock, plus deploy time. The real cost is log
volume — 5 passes × 8 apps × tens of thousands of events, split across the two vendors. Check both
plans' ingestion allowances before starting, and consider a short Datadog **exclusion filter** or
index retention drop on `service:datadog-*-http` (and the New Relic equivalent) after you have the
screenshots.

Also worth doing regardless of n: **alternate the phase order** (batch, stock, stock, batch, …)
rather than always running the batch phase first, so any drift in intake responsiveness across the
session does not systematically favour one appender.

The published protocol does **not** alternate. Its order is fixed: the batch phase first, then the
stock phase, on each plane in turn — so within every pair, batch always runs before stock. That
bias cannot be quantified after the fact; alternating removes it. Keep the pairwise-by-vendor
phases, though: they are what guarantees each app meets its sibling's intake conditions while no
intake carries two apps at once (§7).

And do **one A/A run**: deploy the batch app twice under different names, same platform, same
sizing, and measure both. Whatever spread you see between two identical apps is your noise floor,
and any A/B difference smaller than that is not a result. Run the two **one after the other**, not
together — two copies of the same app ship to the same vendor intake, which is exactly the overlap
§7 forbids.

---

## 11. The CloudHub 2.0 result — reproduce or correct?

**This section is retrospective.** It audits the **superseded July** Datadog figures, and it is
kept because it is the record of *why* the protocol in §7 holds every parameter identical across
the planes. The re-run has since happened under that protocol and is what
[`../README.md`](../README.md) and [`index.html`](index.html) now publish; read the numbers below
as history, not as current results, and re-run the same reasoning against your own figures rather
than carrying these conclusions forward.

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
cannot both describe the same run. At `-c 5` the CloudHub 2.0 batch figure would need a mean
latency of 392 ms rather than the tabulated 792 ms — a factor of 2, which no documented mechanism
produces. The ×10 display glitch in §7 cannot bridge it either: the glitch is a fixed factor of
ten, so it can turn 79.2 ms into 792 ms but never 392 ms into 792 ms. And the throughput side is
independently corroborated by the July request counts (4,381 ÷ 73.18 and 755 ÷ 12.75 both land
within a second of the 60 s window). The `-c 5` reading is therefore refuted for these particular
figures, and the harness is describing a **different July run**.

What this arithmetic **cannot** settle is `count=`. Little's Law has no payload term, so it is
equally satisfied by 32 events per request and by 17. The July documents asserted `/log?count=30`
on both planes; the harness records `count=15` for CloudHub 2.0; no raw output survives to
adjudicate. That single unresolved parameter was enough on its own to make the July cross-plane
comparison unpublishable — see §7, *Concurrency by plane*, for the protocol that removes it.

The identity holds tightly on the two batch runs and only loosely on the two stock runs. The
likely reason for the looser fit is request shedding: the CloudHub 1.0 stock run completed only
95.7% of its requests, the rest aborted at the 60-second deadline or shed under back-pressure. A
request that never completes is counted in neither the throughput figure nor the latency sample,
so the arithmetic only stays exact while every request completes inside the measurement window,
and it loosens as soon as a run stops completing everything it starts. That looseness does not
rescue throughput as an independent metric. The gap runs the wrong way for that: the shedding
runs achieved slightly *less* throughput than their latency alone predicts, so the derived
figure flatters those runs rather than telling you anything the latency did not. There is still
no separate throughput collapse to explain — only the latency. This is a property of the load
generator's configuration, not of these particular numbers, so it constrains how every future
run is written up as well; see *Throughput is not an independent result* in §12.

**The CH2 penalty is roughly a constant, and it hits both apps about equally.** Compare the
medians, which are the least outlier-sensitive figures that run reported:

| | CH1 p50 | CH2 p50 | Delta | Ratio |
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
the appender.** That is also consistent with the README's own guidance to read each req/s
against its sibling in the same window rather than across platforms — a caveat the CH1-vs-CH2
comparison then quietly violates.

> **Both cross-plane tables above inherit the parameter problem.** The p50 deltas and the
> raised floor are computed across two runs whose payload per request is not established to have
> been the same, and which are known to have used different URL schemes. If CloudHub 2.0 in fact
> ran at `count=15`, its apps were doing roughly *half* the per-request work and the true
> per-request penalty is larger than the +605/+711 ms tabulated, not smaller — the direction of
> the error is at least knowable even where its size is not. Read this whole subsection as
> "there is a floor on CloudHub 2.0 worth investigating", which it supports, and not as "the
> floor is 650 ms", which it does not. The 2026-08-04 run under §7 — plain `http`, `count=20` on
> both planes, `-c 10` on CloudHub 1.0 and `-c 5` on CloudHub 2.0, recorded per run — is what
> supersedes it, and its figures are the ones to reason from.

### The region difference — falsified, not resolved

**An earlier draft of this runbook named a cross-region deployment as the leading cause of the
CloudHub 2.0 gap. That hypothesis is now dead.** The deployed hostnames recorded in the
operator's local harness put the CloudHub 1.0 workers and the CloudHub 2.0 space in **the same
region** — the two platforms use region labels that differ by a single letter, which is exactly
the kind of near-collision that makes a mismatch easy to assume and easy to misread, but they
denote the same location. The load generator was the operator's own workstation, run by hand —
the harness invokes `oha` locally, and the Datadog screenshots are captioned in the deployment's
own local time. There was never an intercontinental round trip in the measured path.

That removes the one explanation that would have accounted for the entire ~250–300 ms floor in a
single stroke, and it removes it without replacing it. The floor is real and now has no
sufficient cause identified. Everything below is a partial explanation at best.

Region parity is still worth enforcing — §1 fixes a single region up front, §4 sets `--region`
explicitly, §5 requires a CloudHub 2.0 space in that region, §6 checks the two against each
other, §8 records all three locations — but it is now **hygiene, not the fix**. Do not expect
controlling it to make the CloudHub 2.0 gap go away, and do not describe the July gap as
"explained by a region mismatch" anywhere in the republished report.

### Candidate causes of any residual overhead

With region eliminated, the following are what remain. Ordered by how well they fit and how
cheap they are to check. All are hypotheses; the measurements above are the evidence.

1. **The two July planes were not measured under the same conditions.** This was the first thing
   to rule out, because it is documented rather than inferred. The two planes differed in URL
   scheme (`http` on CloudHub 1.0, `https` on CloudHub 2.0 — §4), and the operator's harness
   records them differing in load parameters as well, though the arithmetic above puts that run's
   concurrency at `-c 10` on both planes (leaving `count=` unresolved). A plain-`http` baseline
   against an `https` comparand puts TLS record processing on one side of the ledger only, on a
   container with a tenth of a core to spend. That is worth tens of milliseconds per request,
   not hundreds — a contributor, not the explanation — but it is a *known* asymmetry where the
   rest of this list is speculative. **Check:** it needs no check; it is settled. **Already
   fixed:** §7 puts both planes on plain `http` at `count=20` — `-c 10` on CloudHub 1.0 and `-c 5`
   on CloudHub 2.0 (§6 is where you confirm it before measuring), so this asymmetry is gone from
   the published run. What it does *not* fix is
   the CPU-model difference in item 2, which is why the published surfaces still decline to read
   one plane's table against the other's.

2. **CPU quota semantics differ between a CH1 worker and a CH2 replica.** A CloudHub 1.0 0.1
   vCore worker has historically been able to burst above its nominal share on a shared host;
   a CloudHub 2.0 0.1 vCore replica is a container with a CPU limit enforced by the kernel
   scheduler, which hard-stops the container once its quota for the period is spent. This
   workload is CPU-heavy for its size — at the protocol's `count=20`, the DataWeave-built JSON
   strings per request, plus layout rendering — so a hard cap would show up as exactly this kind
   of floor. (Compression is not part of that cost on either side of the A/B: both batch demos
   run `gzip="false"`, so every app in the comparison POSTs plaintext.)
   **Check:** Runtime Manager CPU graphs during the measured minute; if CH2 sits
   pinned at 100% while CH1 does not, this is contributing. **If true:** it is a genuine
   platform difference and should be *reported*, not corrected — but say so explicitly, and
   consider also running a 0.5 vCore pair on both platforms to show it is a sizing effect.

3. **The CloudHub 2.0 ingress adds a hop.** CH2 routes public traffic through shared ingress,
   where CH1 fronts the worker more directly. Worth tens of milliseconds, not hundreds — a
   contributor, not the explanation, and partly entangled with item 1, since the July run only
   paid for TLS on the CloudHub 2.0 side. **Check:** compare `%{time_connect}` from `curl -w`
   against each platform over the protocol's plain `http` (add `%{time_appconnect}` only if you
   deliberately probe over `https`, and then probe both planes that way).

4. **The CH1-shaped `FILE` appender misbehaves on CH2.** Both apps write a `RollingFile` to
   `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log` — a path the config comments
   explicitly describe as the CloudHub **1.0** custom-log-appender convention, using
   `${sys:domain}`, a CH1 system property. If that path is not writable on CH2, or the property
   does not resolve, Log4j2 will either error per event or write to a literal `${sys:domain}`
   filename. Both cost CPU on an already-capped container, and both would be CH2-only — while
   the report claims the file appender is a constant across the comparison. **Check:** the CH2
   startup log, for a Log4j2 `RollingFile` error or an odd filename. **If true:** correct it
   symmetrically (fix the path on both platforms, or drop the appender from all four apps).

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

1. `docs/charts/generate_benchmark_charts.py` — update `PLATFORMS` with the new figures (medians
   once you have repeated passes; the published set is one run per configuration), regenerate the
   PNG/SVG pairs.
2. `docs/index.html` — headline cards, both metric tables, both percentile tables, the
   inline bar widths (they are percentages of the max latency, recompute them), the meta chips,
   and the footer.
3. `README.md` — the two CloudHub tables, the narrative paragraph beneath them, the caveat
   blockquote, and the Figure 2–4 captions.
4. Replace "single-run" with "median of N runs (min–max: …)" everywhere it appears —
   `README.md`, `docs/index.html` footer and caveats box, and the header comment in the chart
   script.
5. Record the **commit SHA** of the build under test in `docs/index.html` alongside the
   version, so this problem does not recur.
6. Record the **CloudHub 1.0 worker region, the CloudHub 2.0 space region and the
   load-generator location** in `docs/index.html` and in the `README.md` caveat, so a reader
   can see that both platforms were measured from the same distance.
7. Record the **exact `oha` command line, the `count=` value, the URL scheme and the phase pairing
   for each plane**, and state that they were identical across every run. The published run does
   say that — plain `http` at `count=20` on both planes, `-c 10` on CloudHub 1.0 and `-c 5` on
   CloudHub 2.0 — so the payload objection that sank the July cross-plane reading no longer
   applies (the concurrency still differs by plane, by design — §7). **A cross-plane comparability claim still
   may not appear anywhere** — not in `README.md`, not in `docs/index.html`, not in a chart
   subtitle or footnote — because the remaining obstacle is not the parameters but the **CPU
   model**: a burstable CloudHub 1.0 worker against a hard-capped CloudHub 2.0 replica. Each
   surface carries that statement, and each table stands as a batch-vs-stock contrast within its
   own vendor and plane; it is only reading one table against another that the record does not
   support. Do not weaken that on the strength of the parameters alone.
8. Publish each vendor pair as its **own** batch-vs-stock contrast, and do not present the Datadog
   and New Relic figures as a comparison of the two vendors: they ship to different intakes over
   different network paths, so the pairing makes the appender comparison fair within a vendor and
   says nothing about which vendor is faster.

### Throughput is not an independent result

**Do not report requests per second as a second finding alongside latency.** The load in §7 is
closed-loop: `oha -c <plane-conc>` with no rate limit keeps exactly `-c` requests in flight at all
times (10 on CloudHub 1.0, 5 on CloudHub 2.0), so the client cannot issue a request until an
earlier one returns. Under that arrangement throughput is arithmetically pinned to latency —
`requests per second = concurrency ÷ mean latency` — and the tabulated check in §11 shows that
identity reproducing the two batch results within rounding and the two stock results loosely,
the looser fit being what shedding does to the arithmetic when a run does not complete every
request it starts. The two numbers are still two views of one measurement; where they diverge,
the derived throughput merely runs ahead of what the run actually achieved, which is an artifact
of shedding and not an independent finding.

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
harness, or by the 2026-08-04 re-run recording what it did; they are kept, marked, because the
resolution is the instruction.

| Gap | Impact | Suggested resolution |
| --- | --- | --- |
| **The `oha` command line was nowhere in the repo.** Only "oha, 10 connections, 60 s" in the July prose. Flags for keep-alive, HTTP version, per-request timeout, rate limit and `-w` were all unrecorded. | A re-run with different defaults is not comparable. | **Closed.** The published protocol is `oha --no-tui -z 5m -w -c <plane-conc> <url>/log?count=20` over plain `http` (`-c 10` CloudHub 1.0, `-c 5` CloudHub 2.0), oha defaults for everything else, text output; the harness also runs Test 1 (`-z 1m`) and Test 4 (`-z 5m -t 30s`). §7 carries it verbatim, and `README.md` and `docs/index.html` both publish it. |
| **The warmup was described but not scripted.** "3 requests, 10 s pause" — sequential or concurrent? Same endpoint? Counted? | Minor, but it is the difference between a primed and an unprimed flusher. | **Closed.** 5 `curl --connect-timeout 15 --max-time 60` calls one after another to the same `/log?count=20` endpoint, discarded, then a 10 s pause; a 10 s settle after the measured run. The July run used 3; 5 is what stopped first-run throughput swinging. §7. |
| **Neither region is recorded.** Not for CH1, not for CH2, not for the load generator. | Was believed to be the single biggest reproducibility hole and the cause of the CH1-vs-CH2 gap. | **Closed, and the hypothesis it supported is falsified** — both planes were in the same region (§11). Still choose one region up front (§1), set it explicitly (§4), pick a CH2 space in it (§5), check the two match (§6) and record all three locations (§8), as hygiene. |
| **The July load parameters were not the same on the two planes.** The harness drove CloudHub 1.0 at `-c 10` / `count=30` and CloudHub 2.0 at `-c 5` / `count=15`, and the two planes over different schemes. | Any cross-plane reading measures the parameter difference as much as the platform. | **Permanently open for the July figures, closed for the current ones.** Little's Law puts the July runs at `-c 10` (§11) but cannot resolve `count=`, and no raw output survives to settle it. The current protocol holds `count=20` over plain `http` on both planes, at `-c 10` on CloudHub 1.0 and `-c 5` on CloudHub 2.0 (§7), recorded per run (§8). |
| **Which run produced the July figures.** At least two Datadog runs exist and their numbers differ by up to 4× on CloudHub 2.0. | The July table cannot be traced to a procedure, only to prose. | **Unresolvable after the fact, and superseded.** The 2026-08-04 run is published with its parameters recorded; do not cite the July figures as a platform comparison, or as current. |
| **The vendor region is not recorded** in the report. The app config pins neither: the Datadog demos default to Datadog's primary intake host and read `dd.site` per deployment, the New Relic demos likewise read `nr.logHost`, so the intake used is a property of the run, not of the repo. | Intake latency and rate limits are region-specific. | State both in the report, and record the `dd.site` and `nr.logHost` values — each identical across its own pair (§3, §8). |
| **No run log.** No raw oha output, no app logs, no timestamps are kept in the repo — nor beside the harness, whose results directory does not exist. | Nothing can be re-derived or audited, which is the reason several rows above can only be closed by re-running. | Save the JSON output and the appender heartbeat lines under `docs/benchmark-runs/` (gitignored if large), and keep them for as long as the figures are published. |
| **The New Relic pair was never benchmarked** before the 2026-08-04 run. | The New Relic figures are a first measurement; there is no prior New Relic figure to reproduce or contradict. | **Closed.** The protocol now drives the New Relic pair in every phase (§7). Label its figures as a first measurement rather than a re-run, and do not read them against the Datadog pair (§12 item 8). |
| **The exact appender build is unidentified.** Version `0.1.3` covers at least two materially different builds. | Exactly the problem forcing this re-run. | Record the commit SHA — jar digests are per-build and identify nothing across builds (§2), so they are a build record, not an identifier. |
| **CH2 deployment steps are undocumented.** The report says "0.1 vCore replica" and nothing else — no space, no ingress config, no port handling. | The CH2 half of the benchmark cannot be reproduced from the docs. | Write down the space, its region, the replica size, and the exact JVM args. |
| **The `FILE` appender is CloudHub 1.0-shaped.** `/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log`, self-described in the config as the CH1 custom-log-appender convention. | On CH2 that path convention and the `domain` system property may not apply. If the path is unwritable, Log4j2 errors; if `${sys:domain}` does not resolve, Log4j2 writes it as a literal filename. Either way it is a CH2-only cost on the same 0.1 vCore, and it is a difference the report claims does not exist. | Check the CH2 startup log. If it misbehaves, either fix the path for CH2 or drop the `FILE` appender from **both** apps on **both** platforms — symmetrically. |
