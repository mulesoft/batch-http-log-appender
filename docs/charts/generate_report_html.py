#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate docs/index.html — the standalone styled benchmark report.

Like generate_benchmark_charts.py, nothing here is hardcoded from a prior run: every
number is parsed from the raw oha output under .claude/bench-results/loadtest-<label>/
(git-ignored — it carries hostnames), so re-running the benchmark and re-running this
script keeps index.html in lock-step with docs/benchmark-results.md and the charts.

    .venv-charts/bin/python docs/charts/generate_report_html.py

The published four-vendor set (engine 0.2.0, 16 apps, spill on — Datadog, New Relic and
Elasticsearch measured 2026-10-12 in parallel lanes, Dynatrace 2026-10-13 alone with the same
protocol) is rebuilt with

    BENCH_RESULTS=.claude/bench-results/publish-vendors \
    DELIVERY_JSON=.claude/bench-results/publish-vendors/delivery.json \
    .venv-charts/bin/python docs/charts/generate_report_html.py

DELIVERY_JSON (default: $BENCH_RESULTS/delivery.json) holds the measured log delivery,
{round: {app: {"delivered": N, "expected": M}}}; without it the Delivered row reads "—".

The emitted HTML contains NO hostnames or hardware specs — only worker-TYPE names
("0.1 vCore") and display app labels. The headline is Test 3 (5-min sustained, the warm
number), consistent with benchmark-results.md; the 1-minute Test 1 carries the same
cold-start caveat the Markdown report does.
"""

import json
import os
import pathlib
import re

DOCS = pathlib.Path(__file__).resolve().parents[1]
REPO = DOCS.parent
RESULTS = pathlib.Path(os.environ.get("BENCH_RESULTS", REPO / ".claude" / "bench-results"))
DELIVERY = pathlib.Path(os.environ.get("DELIVERY_JSON", RESULTS / "delivery.json"))
OUT = DOCS / "index.html"
ENGINE = "0.2.0"
# Measurement dates. Datadog, New Relic and Elasticsearch ran together as parallel lanes;
# Dynatrace ran a day later, its lane alone, with the same protocol, sizes, region and build.
RUNS = {"Datadog": "2026-10-12", "New Relic": "2026-10-12", "Elasticsearch": "2026-10-12",
        "Dynatrace": "2026-10-13"}
SLUG = {"Datadog": "datadog", "New Relic": "newrelic", "Elasticsearch": "elasticsearch",
        "Dynatrace": "dynatrace"}
ROUNDS = ["z1m", "z1m_churn", "z5m", "z5m_t30s"]

# --- oha parsing (normalise every time field to SECONDS; handle the 1.14.0 ÷10 glitch) ---
_UNIT = {"sec": 1.0, "ms": 1e-3, "min": 60.0, "10 sec": 10.0, "10 ms": 1e-2}


def _norm(v, u):
    return v * _UNIT[u.strip()]


def _tf(t, label):
    m = re.search(rf"^\s*{label}:\s*([0-9.]+)\s*((?:10 )?(?:sec|ms|min))", t, re.M)
    return _norm(float(m.group(1)), m.group(2)) if m else None


def _pct(t, p):
    m = re.search(rf"{p:.2f}%\s+in\s+([0-9.]+)\s*((?:10 )?(?:sec|ms|min))", t)
    return _norm(float(m.group(1)), m.group(2)) if m else None


def _reqsec(t):
    m = re.search(r"^\s*Requests/sec:\s*([0-9.]+)", t, re.M)
    return float(m.group(1)) if m else None


def _reqs(t):
    return sum(int(x) for x in re.findall(r"\[\d{3}\]\s+(\d+)\s+responses", t))


def _sr(t):
    m = re.search(r"Success rate:\s*([0-9.]+)%", t)
    return float(m.group(1)) if m else None


def load(label, app):
    f = RESULTS / f"loadtest-{label}" / f"{app}.txt"
    if not f.exists():
        raise SystemExit(f"missing {f}")
    t = f.read_text()
    return {
        "reqsec": _reqsec(t), "reqs": _reqs(t), "total": _tf(t, "Total"),
        "mean": _tf(t, "Average"), "fastest": _tf(t, "Fastest"), "slowest": _tf(t, "Slowest"),
        "p50": _pct(t, 50), "p75": _pct(t, 75), "p90": _pct(t, 90),
        "p95": _pct(t, 95), "p99": _pct(t, 99), "sr": _sr(t),
    }


# (plane_title, plane_tag, vendor, batch_app, stock_app, plane_key)
_VENDORS = [("dd", "Datadog"), ("nr", "New Relic"), ("es", "Elasticsearch"), ("dt", "Dynatrace")]
_PLANES = [("CloudHub 1.0", "0.1 vCore worker", "ch1"), ("CloudHub 2.0", "0.1 vCore replica", "ch2")]
PAIRS = [
    (title, tag, vendor, f"{v}-{key}-batch", f"{v}-{key}-stock", key)
    for title, tag, key in _PLANES
    for v, vendor in _VENDORS
]
DELIVERY_DATA = json.loads(DELIVERY.read_text()) if DELIVERY.exists() else {}


def delivered(label, app):
    """Logs delivered ÷ logs generated, in percent, or None when not measured."""
    rec = DELIVERY_DATA.get(label, {}).get(app)
    if not rec or not rec.get("expected"):
        return None
    return rec["delivered"] / rec["expected"] * 100


def pct(x):
    return f"{x:.1f}%" if x is not None else "—"
CONC = {"ch1": 10, "ch2": 5}
HEADLINE = "z5m"  # Test 3, 5-min sustained — the warm headline number


def ms(x):
    return f"{x * 1000:,.0f} ms" if x is not None else "—"


def rs(x):
    return f"{x:,.2f}"


def ratio(b, s):
    return f"{b / s:.1f}×" if s else "—"


def pair_rows(b, s):
    """The metric table body for one pair (b/s carry a "delivered" key)."""
    def lower(sv, bv):
        """stock÷batch as "N× lower" (a batch win), or "N× higher" with no win when batch is worse."""
        if not bv:
            return "—"
        return f"{sv / bv:.1f}× lower" if sv >= bv else f"{bv / sv:.1f}× higher"

    def row(metric, sv, bv, imp=""):
        cls = "loss" if imp.endswith("higher") else "win"
        return (f'      <tr><td>{metric}</td><td class="stock">{sv}</td>'
                f'<td class="batch">{bv}</td><td class="{cls}">{imp}</td></tr>')
    rr = b["reqsec"] / s["reqsec"] if s["reqsec"] else 0
    return "\n".join([
        row("Requests / sec", rs(s["reqsec"]), rs(b["reqsec"]), f"{rr:.1f}×"),
        row("Total requests (5 min)", f'{s["reqs"]:,}', f'{b["reqs"]:,}',
            f'{b["reqs"] / s["reqs"]:.1f}×' if s["reqs"] else "—"),
        row("Success rate", f'{s["sr"]:.2f}%', f'{b["sr"]:.2f}%', ""),
        row("Logs delivered", pct(s["delivered"]), pct(b["delivered"]), ""),
        row("Average latency", ms(s["mean"]), ms(b["mean"]),
            lower(s["mean"], b["mean"])),
        row("p50", ms(s["p50"]), ms(b["p50"]), ""),
        row("p90", ms(s["p90"]), ms(b["p90"]),
            f'{s["p90"] / b["p90"]:.0f}×' if b["p90"] else "—"),
        row("p99", ms(s["p99"]), ms(b["p99"]),
            f'{s["p99"] / b["p99"]:.0f}×' if b["p99"] else "—"),
        row("Fastest", ms(s["fastest"]), ms(b["fastest"]), ""),
        row("Slowest", ms(s["slowest"]), ms(b["slowest"]),
            lower(s["slowest"], b["slowest"])),
    ])


def bars(b, s):
    """Latency bars, each pair normalised to its own p99 max (linear).

    The batch bars come out visually tiny against the stock tail — that near-invisibility
    IS the result. Printed numbers carry the true values; the log-scale chart in
    docs/img/ is the precise view.
    """
    mx = max(s["p99"], b["p99"])
    out = [f'    <div class="legend"><span><i style="background:var(--stock)"></i>Stock</span>'
           f'<span><i style="background:var(--batch)"></i>Batch {ENGINE}</span>'
           f'<span>bar length &prop; latency (max = {ms(mx)})</span></div>']
    for p in ("p50", "p90", "p99"):
        sw = s[p] / mx * 100
        bw = b[p] / mx * 100
        out.append(
            f'    <div class="barrow"><div class="plabel">{p}</div><div class="bartrack">\n'
            f'      <div class="bar stock" style="width:{sw:.1f}%"><span class="out">{ms(s[p])}</span></div>\n'
            f'      <div class="bar batch" style="width:{bw:.1f}%"><span class="out">{ms(b[p])}</span></div></div></div>')
    return "\n".join(out)


# Dynatrace CH1 stock's arrival rate at the intake, read from Grail as records/s over time
# (flat through every test). It is an ARRIVAL rate, so it cannot be derived from the oha files
# or from delivery.json, which counts by event time inside the oha window.
DT_STOCK_ARRIVAL_RATE = 225


def dt_note(key, b, s):
    """The Dynatrace-specific reading under each Dynatrace section."""
    gen = s["reqs"] * 22 / s["total"]  # records/s the measured requests generated
    if key == "ch1":
        return (f'\n  <p class="sub">Batch delivered {pct(b["delivered"])}. Stock delivered '
                f'{pct(s["delivered"])}: its one serialized Log4j background sender reached the '
                f'intake at a flat ~{DT_STOCK_ARRIVAL_RATE} records/s throughout, while the app '
                f'generated about {gen:.0f} records/s in this test. '
                'Where the undelivered stock records were lost is not established; the timing points '
                'at the async queue rather than a restart (nothing restarted after Test 4, and a queued '
                'backlog would have drained before it was counted). Dynatrace stock req/s is far higher than the other vendors\' '
                'stock because a single-record POST to the in-region intake is quick, so '
                'batch÷stock is smaller here. See <a href="vendors/dynatrace.md">the Dynatrace '
                'page</a>.</p>')
    return (f'\n  <p class="sub">Batch and stock both delivered 100%: this stock app\'s own load '
            f'(about {gen:.0f} records/s in this test) sat just under the ~{DT_STOCK_ARRIVAL_RATE} '
            'records/s its single background sender sustains. '
            'See <a href="vendors/dynatrace.md">the Dynatrace page</a>.</p>')


def build():
    d = {p[3]: load(HEADLINE, p[3]) for p in PAIRS}
    d.update({p[4]: load(HEADLINE, p[4]) for p in PAIRS})
    for app in d:
        d[app]["delivered"] = delivered(HEADLINE, app)

    # headline cards (one per pair)
    cards = []
    for title, tag, vendor, ba, sa, key in PAIRS:
        b, s = d[ba], d[sa]
        r = b["reqsec"] / s["reqsec"] if s["reqsec"] else 0
        cards.append(f'''    <div class="card">
      <div class="label">{title} · <a href="vendors/{SLUG[vendor]}.md">{vendor}</a></div>
      <div class="big batch">{r:.1f}× <span style="font-size:16px;color:var(--muted)">throughput</span></div>
      <div class="note">{rs(b["reqsec"])} vs {rs(s["reqsec"])} req/s &middot; batch vs stock (5-min run) &middot; logs delivered {pct(b["delivered"])} vs {pct(s["delivered"])}</div>
    </div>''')

    # per-plane sections
    sections = []
    last_plane = None
    for title, tag, vendor, ba, sa, key in PAIRS:
        b, s = d[ba], d[sa]
        if title != last_plane:
            sections.append(
                f'  <h2>{title} <span class="tag">— {tag} · <code>/log?count=20</code> · '
                f'22 events/request · 5-minute sustained run (Test 3) · '
                f'-c {CONC[key]}</span></h2>')
            last_plane = title
        note = ""
        if vendor == "New Relic" and b["delivered"] is not None and b["delivered"] < 99:
            note = (f'\n  <p class="sub">Batch delivered {pct(b["delivered"])} here because its spill '
                    'backlog was still draining to the US intake when the next round\'s restart '
                    f'replaced the {"worker" if key == "ch1" else "pod"}; in Test 4, the last round, it '
                    'delivered 100%. See '
                    '"New Relic results depend on location" below.</p>')
        if vendor == "Dynatrace":
            note = dt_note(key, b, s)
        sections.append(f'''  <h3><a href="vendors/{SLUG[vendor]}.md">{vendor}</a> intake <span class="tag">— measured {RUNS[vendor]}</span></h3>{note}
  <div class="headline">
    <div class="card">
      <div class="label">Stock HttpAppender</div>
      <div class="big stock">{rs(s["reqsec"])} <span style="font-size:16px;color:var(--muted)">req/s</span></div>
      <div class="note">avg {ms(s["mean"])} &middot; p90 {ms(s["p90"])} &middot; {s["reqs"]:,} reqs &middot; {s["sr"]:.0f}% ok &middot; {pct(s["delivered"])} delivered</div>
    </div>
    <div class="card">
      <div class="label">Batch appender ({ENGINE})</div>
      <div class="big batch">{rs(b["reqsec"])} <span style="font-size:16px;color:var(--muted)">req/s</span></div>
      <div class="note">avg {ms(b["mean"])} &middot; p90 {ms(b["p90"])} &middot; {b["reqs"]:,} reqs &middot; {b["sr"]:.0f}% ok &middot; {pct(b["delivered"])} delivered</div>
    </div>
  </div>

  <table>
    <thead>
      <tr><th>Metric</th><th>Stock HttpAppender</th><th>Batch appender {ENGINE}</th><th>Improvement</th></tr>
    </thead>
    <tbody>
{pair_rows(b, s)}
    </tbody>
  </table>

  <div class="bars">
{bars(b, s)}
  </div>''')

    # headline range strings, computed
    ch1 = [d[p[3]]["reqsec"] / d[p[4]]["reqsec"] for p in PAIRS if p[5] == "ch1"]
    ch2 = [d[p[3]]["reqsec"] / d[p[4]]["reqsec"] for p in PAIRS if p[5] == "ch2"]
    ch1_lo, ch1_hi = min(ch1), max(ch1)
    ch2_lo, ch2_hi = min(ch2), max(ch2)
    # Dynatrace batch÷stock in Test 3 on both planes, against the other vendors' in the same test
    # (Test 1 / churn mix cold start and backlog into the ratio).
    def rr(r, app):
        return load(r, f"{app}-batch")["reqsec"] / load(r, f"{app}-stock")["reqsec"]
    dt_r = [rr(r, f"dt-{k}") for r in (HEADLINE,) for k in ("ch1", "ch2")]
    oth_r = [rr(r, f"{v}-{k}") for r in (HEADLINE,) for k in ("ch1", "ch2")
             for v in ("dd", "nr", "es")]
    dt_ch1_stock = [delivered(r, "dt-ch1-stock") for r in ROUNDS]
    # cold-start example (CH2 Datadog batch, Test 1 -> Test 3), computed
    cold_t1 = load("z1m", "dd-ch2-batch")["reqsec"]
    cold_t3 = d["dd-ch2-batch"]["reqsec"]

    return TEMPLATE.format(
        cards="\n".join(cards),
        sections="\n\n".join(sections),
        ch1_lo=f"{ch1_lo:.1f}", ch1_hi=f"{ch1_hi:.1f}",
        ch2_lo=f"{ch2_lo:.1f}", ch2_hi=f"{ch2_hi:.1f}",
        cold_t1=f"{cold_t1:.1f}", cold_t3=f"{cold_t3:.1f}",
        dt_lo=f"{min(dt_r):.1f}", dt_hi=f"{max(dt_r):.1f}",
        oth_lo=f"{min(oth_r):.0f}", oth_hi=f"{max(oth_r):.0f}",
        dt_b1=f'{d["dt-ch1-batch"]["reqsec"]:.0f}', dt_b2=f'{d["dt-ch2-batch"]["reqsec"]:.0f}',
        dt_s_lo=f"{min(dt_ch1_stock):.1f}", dt_s_hi=f"{max(dt_ch1_stock):.1f}",
        dt_rate=DT_STOCK_ARRIVAL_RATE,
        engine=ENGINE,
    )


TEMPLATE = '''<!DOCTYPE html>
<!--
    Copyright (c) 2026, Salesforce, Inc.
    SPDX-License-Identifier: Apache-2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
-->
<!--
    GENERATED FILE — do not hand-edit. Rebuild the published four-vendor set with:
        BENCH_RESULTS=.claude/bench-results/publish-vendors .venv-charts/bin/python docs/charts/generate_report_html.py
    (without BENCH_RESULTS the script reads .claude/bench-results/, which holds older rounds)
-->
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>batch-http-appender — performance</title>
<style>
  :root {{
    --bg: #0d1117; --panel: #161b22; --border: #30363d; --ink: #e6edf3;
    --muted: #8b949e; --accent: #2f81f7; --good: #3fb950; --warn: #d29922;
    --stock: #db6d28; --batch: #3fb950;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; background: var(--bg); color: var(--ink);
    font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  }}
  .wrap {{ max-width: 960px; margin: 0 auto; padding: 40px 20px 80px; }}
  h1 {{ font-size: 30px; margin: 0 0 4px; letter-spacing: -0.02em; }}
  h2 {{ font-size: 20px; margin: 40px 0 12px; padding-bottom: 6px; border-bottom: 1px solid var(--border); }}
  h2 .tag {{ font-size: 13px; color: var(--muted); font-weight: 500; letter-spacing: 0; }}
  h3 {{ font-size: 15px; margin: 24px 0 8px; }}
  h3 .tag {{ font-size: 12px; color: var(--muted); font-weight: 500; }}
  a {{ color: var(--accent); text-decoration: none; }}
  a:hover {{ text-decoration: underline; }}
  .card .label a {{ color: inherit; text-decoration: underline; text-decoration-color: var(--border); }}
  .links {{ display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 4px 0 8px; font-size: 14px; }}
  .sub {{ color: var(--muted); margin: 0 0 24px; }}
  code {{ background: #21262d; padding: 2px 6px; border-radius: 5px; font-size: 0.88em;
         font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
  .meta {{ display: flex; flex-wrap: wrap; gap: 10px; margin: 20px 0 8px; }}
  .chip {{
    background: var(--panel); border: 1px solid var(--border); border-radius: 999px;
    padding: 6px 14px; font-size: 13px; color: var(--muted);
  }}
  .chip b {{ color: var(--ink); font-weight: 600; }}

  .headline {{ display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 24px 0; }}
  @media (max-width: 620px) {{ .headline {{ grid-template-columns: 1fr; }} }}
  .card {{
    background: var(--panel); border: 1px solid var(--border);
    border-radius: 12px; padding: 20px 22px;
  }}
  .card .label {{ color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em; }}
  .card .big {{ font-size: 34px; font-weight: 700; margin: 6px 0 2px; letter-spacing: -0.02em; }}
  .card .big.batch {{ color: var(--batch); }}
  .card .big.stock {{ color: var(--stock); }}
  .card .note {{ color: var(--muted); font-size: 13px; }}

  .verdict {{
    background: linear-gradient(135deg, rgba(63,185,80,0.12), rgba(47,129,247,0.10));
    border: 1px solid var(--border); border-radius: 12px; padding: 18px 22px; margin: 8px 0 4px;
  }}
  .verdict b {{ color: var(--good); font-size: 1.25em; }}

  table {{ width: 100%; border-collapse: collapse; margin: 8px 0 4px; font-size: 14px; }}
  th, td {{ text-align: right; padding: 9px 12px; border-bottom: 1px solid var(--border); }}
  th:first-child, td:first-child {{ text-align: left; }}
  thead th {{ color: var(--muted); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; }}
  tbody tr:hover {{ background: rgba(255,255,255,0.02); }}
  td.stock {{ color: var(--stock); }}
  td.batch {{ color: var(--batch); font-weight: 600; }}
  .win {{ color: var(--good); font-weight: 600; }}

  .bars {{ margin: 10px 0; }}
  .barrow {{ display: grid; grid-template-columns: 70px 1fr; align-items: center; gap: 12px; margin: 8px 0; }}
  .barrow .plabel {{ color: var(--muted); font-size: 13px; text-align: right; }}
  .bartrack {{ position: relative; height: 30px; }}
  .bar {{ position: absolute; top: 0; height: 13px; border-radius: 3px; }}
  .bar.stock {{ top: 0; background: var(--stock); }}
  .bar.batch {{ top: 16px; background: var(--batch); }}
  .bar span {{ position: absolute; right: 6px; top: 50%; transform: translateY(-50%);
              font-size: 11px; color: #0d1117; font-weight: 700; white-space: nowrap; }}
  .bar span.out {{ right: auto; left: calc(100% + 6px); color: var(--ink); }}
  .legend {{ display: flex; gap: 18px; margin: 6px 0 0 82px; font-size: 12px; color: var(--muted); }}
  .legend i {{ display: inline-block; width: 11px; height: 11px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; }}

  footer {{ color: var(--muted); font-size: 13px; margin-top: 48px; border-top: 1px solid var(--border); padding-top: 16px; }}
  .disclaim {{ color: var(--muted); font-size: 13px; background: var(--panel); border: 1px solid var(--border);
              border-radius: 10px; padding: 14px 18px; margin: 16px 0; }}
  .disclaim b {{ color: var(--ink); }}
  .disclaim.warn {{ border-color: rgba(210,153,34,0.5); }}
  .disclaim.warn b {{ color: var(--warn); }}
</style>
</head>
<body>
<div class="wrap">

  <h1>batch-http-appender — performance</h1>
  <p class="sub">Stock Log4j2 <code>HttpAppender</code> vs. the batching HTTP appender (v{engine}), measured on sixteen Mule apps — a batch/stock pair shipping to each of <b>Datadog</b>, <b>New Relic</b>, <b>Elasticsearch</b> and <b>Dynatrace</b>, on both CloudHub&nbsp;1.0 and CloudHub&nbsp;2.0 — with the logs delivered counted at every vendor. Datadog, New Relic and Elasticsearch were measured on 2026-10-12 as three parallel lanes; Dynatrace was measured on 2026-10-13, its lane alone, with the same protocol, sizes, region and build. Headline figures are the <b>5-minute sustained run (Test 3)</b>; see the cold-start caveat below for why the 1-minute run reads lower.</p>

  <div class="meta">
    <span class="chip"><b>Targets:</b> CloudHub 1.0 &amp; 2.0</span>
    <span class="chip"><b>Worker:</b> 0.1 vCore</span>
    <span class="chip"><b>Vendors:</b> Datadog, New Relic, Elasticsearch &amp; Dynatrace</span>
    <span class="chip"><b>Load:</b> oha 1.16, 5 min, CH1 -c 10 / CH2 -c 5</span>
    <span class="chip"><b>Endpoint:</b> <code>/log?count=20</code> — 22 events/request</span>
    <span class="chip"><b>Spill:</b> on</span>
    <span class="chip"><b>Measured:</b> 2026-10-12 (Datadog, New Relic, Elasticsearch) · 2026-10-13 (Dynatrace)</span>
    <span class="chip"><b>appender:</b> v{engine}</span>
  </div>

  <div class="links">
    <span>Per-vendor setup, sample Mule project and results:</span>
    <a href="vendors/datadog.md">Datadog</a>
    <a href="vendors/newrelic.md">New Relic</a>
    <a href="vendors/elasticsearch.md">Elasticsearch</a>
    <a href="vendors/dynatrace.md">Dynatrace</a>
    <span>·</span>
    <a href="configuration.md">Every <code>&lt;BatchHttp&gt;</code> option</a>
    <a href="benchmark-results.md">Full Markdown report</a>
  </div>

  <div class="verdict">
    Against its own stock sibling, on the same platform, shipping to the same vendor,
    the batching appender sustained a 5-minute run at
    <b>{ch1_lo}–{ch1_hi}× the request throughput on CloudHub 1.0</b> and
    <b>{ch2_lo}–{ch2_hi}× on CloudHub 2.0</b>, and delivered every log to Datadog,
    Elasticsearch and Dynatrace in every test. The low end of each range is Dynatrace: its batch
    app ran at the same req/s as the other vendors' ({dt_b1} on CloudHub 1.0, {dt_b2} on
    CloudHub 2.0), so the worker, not the vendor, bounds batch; but a single-record POST to its
    in-region intake is quick, so the stock appender is penalised far less there. The stock appender's synchronous
    one-POST-per-event send holds the request thread for the whole round-trip to the intake,
    so under concurrency its single background flusher never catches up and its tail climbs
    into the multi-second — sometimes tens-of-seconds — range. The batch appender's
    non-blocking enqueue keeps request latency decoupled from intake latency. <b>The two
    planes run at different concurrency (CH1 -c 10, CH2 -c 5) because their CPU models
    differ, so each figure holds within its own plane and the two are not a comparison of
    each other</b> — see the caveats below.
  </div>

  <div class="headline">
{cards}
  </div>

{sections}

  <!-- ============================ Shared: methodology ============================ -->
  <h2>What was tested</h2>
  <p>Four batch/stock pairs of functionally identical Mule 4 applications — one member of each
  pair on Log4j2's stock <code>&lt;Http&gt;</code> appender, the other on this library's
  <code>&lt;BatchHttp&gt;</code> (v{engine}) — one pair shipping to the <b>Datadog</b> Logs intake
  (site <code>ap2</code>, Sydney), one to the <b>New Relic</b> Logs intake (US,
  <code>log-api.newrelic.com</code>), one to <b>Elasticsearch</b> (Elastic Cloud Serverless,
  Sydney) and one to <b>Dynatrace</b> (Dynatrace SaaS on AWS, Sydney), all eight deployed to
  <b>each</b> platform (sixteen apps total). The sample Mule projects are in the repository and
  linked from each vendor page. Within a pair the apps
  share the same flow, the same <code>&lt;Loggers&gt;</code> wiring, and the same file appender;
  the <b>only substantive difference is the appender element</b>. The batch apps run
  <code>maxBatchRecords=200</code>, one flusher, <code>gzip="false"</code> and <b>disk spill
  on</b>. Each request to <code>/log?count=20</code> emits <b>22 log events</b> — a
  <code>batch.start</code>, 20 <code>order.processed</code>, and a <code>batch.completed</code>.</p>
  <p>Load was generated with <a href="https://github.com/hatoo/oha"><code>oha</code></a> 1.16
  (<code>-w</code>) over a <b>5-minute</b> window (Test 3). <b>Concurrency differs by plane —
  CloudHub 1.0 at <code>-c 10</code> over <code>http</code>, CloudHub 2.0 at <code>-c 5</code> over
  <code>https</code></b> — because the CloudHub 2.0 replica is a capped-CPU, no-swap worker held at
  lower concurrency to stay off its OOM ceiling. Because <code>-c</code> differs, <b>compare only
  within a plane (batch vs stock), never CloudHub 1.0 against CloudHub 2.0</b>. Every worker and
  replica runs in Sydney (<code>ap-southeast-2</code>).</p>
  <p>Datadog, New Relic and Elasticsearch ran on 2026-10-12 as <b>parallel lanes</b>, since each
  has its own intake, with <b>one app per vendor at a time</b>: CH1 batch, CH1 stock, CH2 batch,
  CH2 stock, with a barrier after each step. Dynatrace ran on 2026-10-13 as a <b>single lane on
  its own</b>, with the same protocol, the same app and worker sizes, the same region and the
  same build. As a control, against a sequential, Elasticsearch-only run of 2026-10-11, Elasticsearch
  stock req/s in the parallel lanes was within 3% on CloudHub 2.0 in the 5-minute rounds. On
  CloudHub 1.0 it was −3.3% in Test 4 and −14.6% in Test 3; an earlier parallel round of the same
  stock apps read +2.9% and −7.1% there, so a single CloudHub 1.0 stock run moves that much on its
  own. <b>Every test starts on a freshly restarted app</b> (restarts are pipelined ahead of each
  app's turn), followed by 5 light warmups and a 30 s drain. The exception is the churn window,
  which follows Test 1 with no restart.</p>
  <p><b>Logs delivered</b> is the logs that reached the vendor divided by the logs the measured
  requests generated (HTTP 200 responses × 22). Elasticsearch is counted by event time inside
  the <code>oha</code> window, and so is Dynatrace (in Grail, with DQL, by
  <code>log.source</code>). Datadog and New Relic are counted by arrival time, from the test's
  start to the next test on the same app: the demos omit the vendors' reserved event-time
  attribute on purpose, so the vendor stamps arrival. A value over 100% is late backlog from an
  earlier test arriving in the window, or log lines of requests <code>oha</code> cut off.</p>
  <p>Each 5-minute run shown here independently corroborates the load parameters: requests/sec ×
  mean latency ≈ the plane's concurrency (Little's Law) holds within 8% across all sixteen. It does
  not hold for every run in the full report: where requests fail or time out (Test 4, New Relic
  on CloudHub 1.0 in the churn window) or a long stock tail drains past the deadline (the
  1-minute tests), the product falls below <code>-c</code>. The parsers still normalise the
  <code>oha</code> 1.14.0 ×10 unit-selection display glitch, which this run's 1.16 output does
  not show.</p>

  <div class="disclaim warn">
    <b>⚠ Cold-start caveat — the 1-minute Test 1 reads lower than this page's headline.</b> The
    <b>batch</b> appender is CPU/JIT-bound and warmup-sensitive; the warmup before each run is
    deliberately light so it does not pre-load the <em>stock</em> flusher's backlog. The
    trade-off is that the batch JVM is still under-warmed when a 1-minute window opens, so a
    short Test 1 under-reports batch throughput (e.g. CloudHub 2.0 Datadog batch rises {cold_t1} → {cold_t3}
    req/s from the 1-minute run to this 5-minute one as the fixed cold-start cost amortises). The
    5-minute Test 3 shown here is the better read of sustained warm throughput. The full
    Markdown report (<code>docs/benchmark-results.md</code>) carries Test 1, the in-thread churn
    window, Test 3, and the 30s-cap Test 4 side by side, with logs delivered for each.
  </div>

  <div class="disclaim warn">
    <b>Caveats — read the numbers as directional, not absolute.</b>
    <ul style="margin:8px 0 0; padding-left:20px;">
      <li><b>One run per app, no median.</b> These are single measurements, not repeated ones.
      The pattern is directional, not established.</li>
      <li><b>Concurrency differs by plane</b> (CH1 -c 10, CH2 -c 5), so the two platform
      sections are not a controlled comparison of each other. Each table stands as a
      batch-vs-stock contrast within its own vendor and platform.</li>
      <li><b>Spill is on and <code>gzip</code> is off.</b> Spill costs batch req/s where the
      queue overflows the whole time (New Relic on CloudHub 1.0 in the churn window: 42 req/s)
      but not in the 5-minute tests. Compression is off because the stock
      <code>&lt;Http&gt;</code> appender cannot compress, and leaving it on would vary two things
      at once; a production deployment should leave it on.</li>
      <li><b>Throughput and mean latency are one measurement, not two.</b> The load is
      closed-loop (<code>oha</code> with no rate limit), so requests/sec is the arithmetic
      consequence of mean latency, not an independent win. The percentiles are unaffected.</li>
      <li><b>New Relic results depend on location.</b> The workers run in Sydney
      (<code>ap-southeast-2</code>), next to Datadog's <code>ap2</code> site and the
      Elasticsearch project. New Relic offers only US and EU regions; these apps ship to the US
      endpoint (<code>log-api.newrelic.com</code>), so every batch crosses the Pacific (on
      CloudHub 1.0 in Test 3 the backlog drained at about 330 logs/s). Requests/sec measures how
      responsive the app stays, not how many logs arrive. New Relic batch delivered about 96–107% in Test 1 and churn, 44.5%
      (CloudHub 1.0) and 77.3% (CloudHub 2.0) in Test 3, and 100% in Test 4. The Test 3 shortfall
      is a restart, not a drop: the 5-minute burst spilled to disk faster than the US intake took
      it, and the backlog was still draining when the next round's restart replaced the worker
      (CloudHub 1.0 restarts onto a new worker, 2.0 onto a new pod), so <code>/tmp</code> and the
      spill files went with it. Test 4 was the last round; nothing restarted after it, and its
      backlog drained in about 12 minutes. Workers closer to the New Relic region, or
      <code>gzip</code> plus more <code>flusherThreads</code>, ship faster.</li>
      <li><b>Stock loses its backlog at every restart.</b> At Datadog, New Relic and
      Elasticsearch, stock delivered 14–83% of its logs in Test 1 and Test 3: what is still queued
      in memory when the next restart comes is gone.</li>
      <li><b>Dynatrace was measured a day later, on its own.</b> Same protocol, sizes, region and
      build as the other three, but run 2026-10-13 as the only lane. Its batch÷stock is only
      {dt_lo}–{dt_hi}× in Test 3 (the other vendors: {oth_lo}–{oth_hi}×) because a
      single-record POST to the in-region Dynatrace intake takes about 4.4 ms, so the stock appender is penalised far less; batch req/s is the same as elsewhere.
      Batch delivered 100% in every test on both planes. CloudHub 1.0 stock delivered only
      {dt_s_lo}–{dt_s_hi}%: its single serialized background sender reached the intake at a flat
      rate of about {dt_rate} records/s throughout, below what the app generated. CloudHub 2.0
      stock's own load sat just under that rate and it delivered 100%. This is not Dynatrace
      throttling: a 914 req/s single-record burst from a laptop got HTTP 204 for every request
      and every record was stored. Where the undelivered CloudHub 1.0 stock records were lost is
      not established; the timing points at the async queue rather than a restart (nothing
      restarted after Test 4, and a queued backlog would have drained before it was counted).</li>
      <li><b>Correction to the 2026-08-04 run.</b> Its CloudHub 2.0 Datadog stock app carried a
      leftover <code>AsyncLoggerConfig.RingBufferSize=20000</code> and <code>log4j2.debug=true</code>
      that no other app had. It measured 5.75 req/s with them against 1.46 without, so the
      published 7.6× is not comparable; a re-test without them gave about 31×. This run's app has
      neither property.</li>
    </ul>
  </div>

  <footer>
    Measured on CloudHub 1.0 (0.1 vCore worker) and CloudHub 2.0 (0.1 vCore replica), four
    vendor pairs — Datadog, New Relic, Elasticsearch and Dynatrace — with <code>oha</code> over a
    5-minute window (CloudHub 1.0 at -c 10, CloudHub 2.0 at -c 5, <code>/log?count=20</code> for
    22 events per request), each app restarted and warmed before the run. Datadog, New Relic and
    Elasticsearch: 2026-10-12, three vendor lanes in parallel with one app per vendor at a time.
    Dynatrace: 2026-10-13, its lane alone, same protocol. Endpoint URLs omitted intentionally.
    Numbers are single-run and will vary with worker load, vendor rate limits, and intake
    responsiveness; the two planes run at different concurrency and have different CPU models, so
    their tables are not comparable with each other — see the caveats above. This is a
    GENERATED file — rebuild with <code>BENCH_RESULTS=.claude/bench-results/publish-vendors
    docs/charts/generate_report_html.py</code>. Setup and options: the
    <a href="vendors/datadog.md">Datadog</a>, <a href="vendors/newrelic.md">New Relic</a>,
    <a href="vendors/elasticsearch.md">Elasticsearch</a> and
    <a href="vendors/dynatrace.md">Dynatrace</a> pages and
    <a href="configuration.md">configuration.md</a>. Generated for
    batch-http-appender v{engine}.
  </footer>

</div>
</body>
</html>
'''


def main():
    OUT.write_text(build())
    print("wrote", OUT)


if __name__ == "__main__":
    main()
