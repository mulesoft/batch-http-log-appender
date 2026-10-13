#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
#
# Deploy the CloudHub 1.0 half of the benchmark described in docs/benchmark-runbook.md §4.
#
set -euo pipefail

CLI=${ANYPOINT_CLI:-anypoint-cli-v4}
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOYABLE_DIR="$REPO_ROOT/demo/mule/deployable"

# Runbook §4 / §6: 1 worker x 0.1 vCore, runtime 4.9.15 on Java 17, identical for every app.
WORKERS=1
WORKER_SIZE=0.1
RUNTIME=4.9.15
JAVA_VERSION=17

# The original benchmark drove CloudHub 1.0 over plain http and CloudHub 2.0 over https, so the
# two planes were never measured over the same transport. The published protocol equalises on
# plain http for both planes (README.md, docs/index.html).
SCHEME=${SCHEME:-http}

# Load parameters the published protocol uses on BOTH planes, identical on each: 5 concurrent
# connections against /log?count=15, i.e. 17 log events per request. Printed at the end so the
# two halves of the benchmark cannot drift apart again -- driving each plane with different
# parameters is what made the original run's two platform tables incomparable.
LOAD_CONC=${LOAD_CONC:-5}
LOAD_COUNT=${LOAD_COUNT:-15}

# How long to wait for an app to reach STARTED, and how often to re-check.
START_TIMEOUT_SECONDS=${START_TIMEOUT_SECONDS:-900}
POLL_INTERVAL_SECONDS=${POLL_INTERVAL_SECONDS:-15}

MASK='********'
DRY_RUN=0

# On CloudHub 1.0 the application name IS the public hostname label, so deploying a jar under a
# new name creates a second, parallel app rather than replacing the first. The apps from the
# original benchmark carry a deployment-specific suffix; export CH1_APP_SUFFIX so this script
# addresses them instead of standing up duplicates. Leave it empty for a clean org.
SUFFIX=${CH1_APP_SUFFIX:-}

DATADOG_APPS=("datadog-batch-http$SUFFIX" "datadog-stock-http$SUFFIX")
NEWRELIC_APPS=("newrelic-batch-http$SUFFIX" "newrelic-stock-http$SUFFIX")
ELASTIC_APPS=("elastic-batch-http$SUFFIX" "elastic-stock-http$SUFFIX")
DYNATRACE_APPS=("dynatrace-batch-http$SUFFIX" "dynatrace-stock-http$SUFFIX")

usage() {
  cat <<'EOF'
Usage: scripts/deploy-ch1.sh [--dry-run] [--datadog | --newrelic | --elastic | --dynatrace | --all] [APP ...]

Deploys (or redeploys) the CloudHub 1.0 benchmark applications per docs/benchmark-runbook.md §4.

App names are the demo module names plus $CH1_APP_SUFFIX. On CloudHub 1.0 the app name is the
public hostname label, so targeting the wrong name deploys a parallel app instead of redeploying
the existing one -- set CH1_APP_SUFFIX to whatever the already-deployed apps use.

Only the vendor credential is passed as a --property (plus elastic.url for an elastic-* app and
dynatrace.url for a dynatrace-* app). Set app.platform=cloudhub-1.0 by hand in
Runtime Manager's Properties tab, or the events arrive labelled platform: unspecified and the
two planes are indistinguishable at the vendor.

Selection (default: the Datadog pair):
  --datadog          datadog-batch-http$SUFFIX  datadog-stock-http$SUFFIX   (default)
  --newrelic         newrelic-batch-http$SUFFIX newrelic-stock-http$SUFFIX
  --elastic          elastic-batch-http$SUFFIX  elastic-stock-http$SUFFIX
  --dynatrace        dynatrace-batch-http$SUFFIX dynatrace-stock-http$SUFFIX
  --all              the Datadog and New Relic pairs (the published benchmark)
  APP ...            explicit app names, overriding the flags above
                     (or set APPS="datadog-batch-http-x datadog-stock-http-x" in the environment)

Options:
  --dry-run          print the exact commands, with secrets masked, and execute nothing
  -h, --help         this message

Required environment:
  ANYPOINT_CLIENT_ID       Connected App client ID (client_credentials grant)
  ANYPOINT_CLIENT_SECRET   Connected App client secret
  ANYPOINT_ORG             business group / organization name or ID
  ANYPOINT_ENV             environment name
  REGION                   the single region chosen in runbook §1, as an AWS-style region name
  DD_KEY                   Datadog API key      (required when deploying a datadog-* app)
  NR_KEY                   New Relic ingest key (required when deploying a newrelic-* app)
  ELASTIC_URL              Elasticsearch endpoint (required when deploying an elastic-* app)
  ELASTIC_KEY              Elasticsearch encoded API key (required when deploying an elastic-* app)
  DT_ENVIRONMENT_URL       Dynatrace environment URL (required when deploying a dynatrace-* app)
  DT_API_TOKEN             Dynatrace access token, logs.ingest scope only (required when deploying a dynatrace-* app)

Optional environment:
  CH1_APP_SUFFIX           suffix the deployed CloudHub 1.0 apps carry; default empty
  SCHEME                   scheme for the printed load URLs, default http (both planes)
  LOAD_CONC                oha -c value printed in the summary, default 5
  LOAD_COUNT               /log?count= value printed in the summary, default 15 (17 log events)
  START_TIMEOUT_SECONDS    default 900
  POLL_INTERVAL_SECONDS    default 15
  ANYPOINT_CLI             CLI executable, default anypoint-cli-v4
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[%s] %s\n' "$(date -u '+%H:%M:%SZ')" "$*"
}

# --------------------------------------------------------------------------
# Argument parsing
# --------------------------------------------------------------------------

selection=""
explicit_apps=()

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)  DRY_RUN=1 ;;
    --datadog)  selection=datadog ;;
    --newrelic) selection=newrelic ;;
    --elastic)  selection=elastic ;;
    --dynatrace) selection=dynatrace ;;
    --all)      selection=all ;;
    -h|--help)  usage; exit 0 ;;
    -*)         usage >&2; die "unknown option: $1" ;;
    *)          explicit_apps+=("$1") ;;
  esac
  shift
done

APPS_TO_DEPLOY=()
if [ ${#explicit_apps[@]} -gt 0 ]; then
  APPS_TO_DEPLOY=("${explicit_apps[@]}")
elif [ -n "${APPS:-}" ]; then
  read -r -a APPS_TO_DEPLOY <<<"$APPS"
else
  case "$selection" in
    newrelic) APPS_TO_DEPLOY=("${NEWRELIC_APPS[@]}") ;;
    elastic)  APPS_TO_DEPLOY=("${ELASTIC_APPS[@]}") ;;
    dynatrace) APPS_TO_DEPLOY=("${DYNATRACE_APPS[@]}") ;;
    all)      APPS_TO_DEPLOY=("${DATADOG_APPS[@]}" "${NEWRELIC_APPS[@]}") ;;
    *)        APPS_TO_DEPLOY=("${DATADOG_APPS[@]}") ;;
  esac
fi

# --------------------------------------------------------------------------
# App table: name -> jar, credential property name, credential variable name
# --------------------------------------------------------------------------

# Matched as prefixes so an app carrying $CH1_APP_SUFFIX still resolves to its own artifact.
app_jar() {
  case "$1" in
    datadog-batch-http*)  printf '%s' "$DEPLOYABLE_DIR/datadog-batch-http.jar" ;;
    datadog-stock-http*)  printf '%s' "$DEPLOYABLE_DIR/datadog-stock-http.jar" ;;
    newrelic-batch-http*) printf '%s' "$DEPLOYABLE_DIR/newrelic-batch-http.jar" ;;
    newrelic-stock-http*) printf '%s' "$DEPLOYABLE_DIR/newrelic-stock-http.jar" ;;
    elastic-batch-http*)  printf '%s' "$DEPLOYABLE_DIR/elastic-batch-http.jar" ;;
    elastic-stock-http*)  printf '%s' "$DEPLOYABLE_DIR/elastic-stock-http.jar" ;;
    dynatrace-batch-http*) printf '%s' "$DEPLOYABLE_DIR/dynatrace-batch-http.jar" ;;
    dynatrace-stock-http*) printf '%s' "$DEPLOYABLE_DIR/dynatrace-stock-http.jar" ;;
    *) return 1 ;;
  esac
}

app_property_name() {
  case "$1" in
    datadog-*)  printf 'datadog.apiKey' ;;
    newrelic-*) printf 'newrelic.apiKey' ;;
    elastic-*)  printf 'elastic.apiKey' ;;
    dynatrace-*) printf 'dynatrace.token' ;;
    *) return 1 ;;
  esac
}

app_secret() {
  case "$1" in
    datadog-*)  printf '%s' "${DD_KEY:-}" ;;
    newrelic-*) printf '%s' "${NR_KEY:-}" ;;
    elastic-*)  printf '%s' "${ELASTIC_KEY:-}" ;;
    dynatrace-*) printf '%s' "${DT_API_TOKEN:-}" ;;
    *) return 1 ;;
  esac
}

# --------------------------------------------------------------------------
# Pre-flight: fail before touching the platform, naming the exact missing variable
# --------------------------------------------------------------------------

missing=()
require_var() {
  local name=$1
  if [ -z "${!name:-}" ]; then
    missing+=("$name")
  fi
}

require_var ANYPOINT_CLIENT_ID
require_var ANYPOINT_CLIENT_SECRET
require_var ANYPOINT_ORG
require_var ANYPOINT_ENV
require_var REGION

need_dd=0
need_nr=0
need_es=0
need_dt=0
for app in "${APPS_TO_DEPLOY[@]}"; do
  app_jar "$app" >/dev/null || die "unknown app '$app' (known: ${DATADOG_APPS[*]} ${NEWRELIC_APPS[*]} ${ELASTIC_APPS[*]} ${DYNATRACE_APPS[*]})"
  case "$app" in
    datadog-*)  need_dd=1 ;;
    newrelic-*) need_nr=1 ;;
    elastic-*)  need_es=1 ;;
    dynatrace-*) need_dt=1 ;;
  esac
done
[ "$need_dd" -eq 1 ] && require_var DD_KEY
[ "$need_nr" -eq 1 ] && require_var NR_KEY
[ "$need_es" -eq 1 ] && require_var ELASTIC_URL && require_var ELASTIC_KEY
[ "$need_dt" -eq 1 ] && require_var DT_ENVIRONMENT_URL && require_var DT_API_TOKEN

if [ ${#missing[@]} -gt 0 ]; then
  printf 'ERROR: the following required environment variables are unset or empty:\n' >&2
  for m in "${missing[@]}"; do printf '  - %s\n' "$m" >&2; done
  # A missing credential does not fail the deploy: the app starts and the Log4j2 lookup chain
  # resolves to the literal string MISSING_KEY, shipping a silently broken benchmark run.
  printf 'Refusing to deploy. An unset credential would deploy an app that logs MISSING_KEY.\n' >&2
  exit 1
fi

command -v "$CLI" >/dev/null 2>&1 || die "$CLI not found on PATH"

for app in "${APPS_TO_DEPLOY[@]}"; do
  jar="$(app_jar "$app")"
  [ -f "$jar" ] || die "artifact not found: $jar (runbook §2 — build, then refresh demo/mule/deployable/)"
done

# --------------------------------------------------------------------------
# Command execution and masked echoing
# --------------------------------------------------------------------------

# Renders a command for display with any --property value replaced by asterisks. Credentials
# reach the CLI only through the environment and this array, never through a printed string.
render_masked() {
  local out="" arg mask_next=0
  for arg in "$@"; do
    if [ "$mask_next" -eq 1 ]; then
      out+=" $(printf '%q' "${arg%%:*}"):$MASK"
      mask_next=0
      continue
    fi
    [ "$arg" = "--property" ] && mask_next=1
    out+=" $(printf '%q' "$arg")"
  done
  printf '%s' "${out# }"
}

run_cli() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  + %s\n' "$(render_masked "$@")"
    return 0
  fi
  "$@"
}

# Same as run_cli but captures stdout and never echoes it, for the JSON reads.
capture_cli() {
  "$@" 2>/dev/null
}

# Recursive first-match lookup, because the CLI's describe JSON nests worker details and the
# exact nesting differs between CloudHub API revisions.
json_find() {
  python3 -c '
import json, sys
keys = [k.lower() for k in sys.argv[1:]]
def walk(node, key):
    if isinstance(node, dict):
        for k, v in node.items():
            if k.lower() == key and not isinstance(v, (dict, list)) and v not in (None, ""):
                return v
        for v in node.values():
            r = walk(v, key)
            if r is not None:
                return r
    elif isinstance(node, list):
        for v in node:
            r = walk(v, key)
            if r is not None:
                return r
    return None
try:
    doc = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for key in keys:
    r = walk(doc, key)
    if r is not None:
        print(r)
        break
' "$@"
}

# --------------------------------------------------------------------------
# Deploy
# --------------------------------------------------------------------------

app_exists() {
  capture_cli "$CLI" runtime-mgr cloudhub-application describe "$1" -o json >/dev/null 2>&1
}

describe_json() {
  capture_cli "$CLI" runtime-mgr cloudhub-application describe "$1" -o json
}

deploy_one() {
  local app=$1
  local jar prop secret verb
  local extra=()
  jar="$(app_jar "$app")"
  prop="$(app_property_name "$app")"
  secret="$(app_secret "$app")"
  # Elasticsearch and Dynatrace have no fixed intake host: the endpoint is per project /
  # per environment. (dynatrace-* apps get log.source from ${sys:domain}, which CloudHub 1.0
  # sets to the app name, so nothing else is needed for identity.)
  case "$app" in
    elastic-*)   extra=(--property "elastic.url:$ELASTIC_URL") ;;
    dynatrace-*) extra=(--property "dynatrace.url:$DT_ENVIRONMENT_URL") ;;
  esac

  # deploy creates; it errors on an app that already exists. Re-running the benchmark five
  # times means most invocations are updates, so branch to modify when the app is already there.
  if [ "$DRY_RUN" -eq 1 ]; then
    verb=deploy
  elif app_exists "$app"; then
    verb=modify
  else
    verb=deploy
  fi

  log "$app: $verb"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  (dry run: existence not checked; a live run picks deploy or modify from describe)\n'
  fi
  run_cli "$CLI" runtime-mgr cloudhub-application "$verb" \
    "$app" "$jar" \
    --workers "$WORKERS" --workerSize "$WORKER_SIZE" \
    --runtime "$RUNTIME" --javaVersion "$JAVA_VERSION" \
    --region "$REGION" \
    --property "$prop:$secret" ${extra[@]+"${extra[@]}"}
}

wait_started() {
  local app=$1 waited=0 status=""
  while [ "$waited" -lt "$START_TIMEOUT_SECONDS" ]; do
    status="$(describe_json "$app" | json_find status || true)"
    case "$status" in
      STARTED|started) log "$app: STARTED after ${waited}s"; return 0 ;;
      UNDEPLOYED|DEPLOY_FAILED|UPDATE_FAILED|FAILED)
        die "$app: reached terminal status $status — check 'runtime-mgr cloudhub-application tail-logs $app'" ;;
    esac
    sleep "$POLL_INTERVAL_SECONDS"
    waited=$((waited + POLL_INTERVAL_SECONDS))
  done
  die "$app: not STARTED after ${START_TIMEOUT_SECONDS}s (last status: ${status:-unknown})"
}

SUMMARY=()

verify_one() {
  local app=$1 json status region_reported size workers_reported runtime_reported url
  json="$(describe_json "$app")"
  status="$(printf '%s' "$json"      | json_find status)"
  region_reported="$(printf '%s' "$json" | json_find region)"
  size="$(printf '%s' "$json"        | json_find workerSize vCores weight)"
  workers_reported="$(printf '%s' "$json" | json_find workers amount)"
  runtime_reported="$(printf '%s' "$json"  | json_find muleVersion runtimeVersion runtime)"
  url="$(printf '%s' "$json"         | json_find fullDomain domain)"

  case "$url" in
    *.*) url="$SCHEME://$url" ;;
    # Never assemble a CloudHub 1.0 hostname (runbook §4): --region takes an AWS-style name
    # while the public hostname carries a different, shorter label -- and none at all in
    # us-east-1 -- so anything constructed here would be unreachable in every region.
    *)   die "$app: platform reported neither fullDomain nor domain; read the hostname from Runtime Manager by hand (runbook §4)" ;;
  esac

  # Region parity is hygiene rather than the live suspicion it was once thought to be: the
  # original deployments turned out to share a region (runbook §11). Still assert it, because a
  # silent mismatch would reintroduce a variable the report claims is controlled.
  if [ "$region_reported" != "$REGION" ]; then
    die "$app: REGION MISMATCH — requested '$REGION', platform reports '${region_reported:-unknown}'. Benchmark comparison is invalid; fix before measuring."
  fi

  SUMMARY+=("$(printf '%-22s %-10s %-16s %-8s %-10s %s' \
    "$app" "$status" "$region_reported" "${size:-?}" "${runtime_reported:-?}" "$url")")
  printf '  %-22s status=%s region=%s workers=%s workerSize=%s runtime=%s url=%s\n' \
    "$app" "$status" "$region_reported" "${workers_reported:-?}" "${size:-?}" "${runtime_reported:-?}" "$url"
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

cat <<EOF
CloudHub 1.0 benchmark deploy (docs/benchmark-runbook.md §4)
  organization  : $ANYPOINT_ORG
  environment   : $ANYPOINT_ENV
  region        : $REGION
  app suffix    : $([ -n "$SUFFIX" ] && printf '%s' "$SUFFIX" || printf '(none -- set CH1_APP_SUFFIX to redeploy existing apps)')
  sizing        : $WORKERS worker x $WORKER_SIZE vCore, runtime $RUNTIME / Java $JAVA_VERSION
  client id     : $MASK
  client secret : $MASK
  datadog key   : $([ "$need_dd" -eq 1 ] && printf '%s' "$MASK" || printf 'n/a')
  newrelic key  : $([ "$need_nr" -eq 1 ] && printf '%s' "$MASK" || printf 'n/a')
  elastic key   : $([ "$need_es" -eq 1 ] && printf '%s' "$MASK" || printf 'n/a')
  dynatrace tok : $([ "$need_dt" -eq 1 ] && printf '%s' "$MASK" || printf 'n/a')
  apps          : ${APPS_TO_DEPLOY[*]}
  mode          : $([ "$DRY_RUN" -eq 1 ] && printf 'DRY RUN' || printf 'live')

EOF

for app in "${APPS_TO_DEPLOY[@]}"; do
  deploy_one "$app"
  if [ "$DRY_RUN" -eq 0 ]; then
    wait_started "$app"
    verify_one "$app"
  fi
done

if [ "$DRY_RUN" -eq 1 ]; then
  printf '\nDry run complete. Nothing was deployed and no credential was printed.\n'
  exit 0
fi

printf '\n%-22s %-10s %-16s %-8s %-10s %s\n' APP STATUS REGION VCORES RUNTIME URL
printf '%s\n' "${SUMMARY[@]}"
printf '\nAll apps report region %s. Record it in the report per runbook §8.\n' "$REGION"
printf 'Prime each app with 5 warmup requests and a 10 s pause, then measure:\n'
printf '  oha --no-tui -z 1m -c %s <url>/log?count=%s\n' "$LOAD_CONC" "$LOAD_COUNT"
printf 'Run paired-parallel, one vendor per side; never two apps against the same vendor intake.\n'
printf 'Use the same -c and count= on CloudHub 2.0. Different parameters per plane are what made\n'
printf 'the two platform tables of the original run incomparable (runbook §11).\n'
printf 'Validate every result with Little'"'"'s Law before recording it: req/s x avg latency ~ -c,\n'
printf 'and total run ~ 60s; oha 1.14.0 can print latencies 10x low (runbook §7).\n'
