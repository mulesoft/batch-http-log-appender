#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the CloudHub benchmark charts for docs/benchmark-results.md and the vendor pages.

Reads the RAW oha output written by the benchmark run (one <app>.txt per app per
phase) and renders PNG (300 dpi) + SVG into docs/img/ (cross-vendor) and
docs/img/vendors/ (one vendor each). Nothing here is hardcoded from a prior run — the
figures are parsed from the result files, so re-running the benchmark and re-running
this script keeps the charts in lock-step with the report.

    python3 -m venv .venv-charts
    .venv-charts/bin/pip install -r docs/charts/requirements.txt
    .venv-charts/bin/python docs/charts/generate_benchmark_charts.py

Data source (default): ../.claude/bench-results/loadtest-<label>/  (git-ignored — it
carries hostnames). Override with BENCH_RESULTS=/path. Measured log delivery is read from
DELIVERY_JSON (default: $BENCH_RESULTS/delivery.json), shaped
{round: {app: {"delivered": N, "expected": M}}}. The published four-vendor set (Datadog,
New Relic and Elasticsearch measured 2026-10-12 in parallel lanes; Dynatrace measured
2026-10-13 alone, same protocol) is rebuilt with

    BENCH_RESULTS=.claude/bench-results/publish-vendors \
    DELIVERY_JSON=.claude/bench-results/publish-vendors/delivery.json \
    SUMMARY_JSON=.claude/bench-results/publish-vendors/summary.json \
    .venv-charts/bin/python docs/charts/generate_benchmark_charts.py

SUMMARY_JSON (optional) also writes every parsed number — per app and round, plus the
batch÷stock req/s ratios — as one JSON file, so prose writers quote a single source.
Latencies in it are in SECONDS.

The charts themselves contain NO hostnames or hardware specs — only worker-TYPE names
("0.1 vCore") and app labels.

Cross-vendor charts (docs/img/):
  1. cloudhub-throughput-batch-vs-stock   — sustained req/s (Test 3, 5 min), grouped by plane.
  2. cloudhub-latency-batch-vs-stock      — avg / p90 / p99 latency (Test 3), log axis.
  3. cloudhub-latency-batch-vs-stock-range — the same Test-3 latency data as a range
                                   (dumbbell) plot: stock and batch markers joined by the
                                   "N× lower" gap.
  4. cloudhub-warmup-cold-start   — Test 1 (1m) vs churn vs Test 3 (5m) req/s, batch AND
                                   stock side by side per plane.
  5. cloudhub-delivered-logs      — logs delivered as a share of logs generated, batch vs
                                   stock, per test (only when DELIVERY_JSON exists).

Per-vendor charts (docs/img/vendors/<slug>-*), one vendor's batch vs stock on CH1 and CH2:
  6. <slug>-throughput — req/s in each of the four tests.
  7. <slug>-latency    — p50 / p90 / p99 in Test 3 as a range (dumbbell) plot.
  8. <slug>-delivered  — logs delivered % in each of the four tests.
"""

import json

import os
import pathlib
import re
import tempfile

os.environ.setdefault("MPLCONFIGDIR", tempfile.mkdtemp(prefix="mplconfig-"))

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
DOCS = pathlib.Path(__file__).resolve().parents[1]
REPO = DOCS.parent
OUT_DIR = DOCS / "img"
VENDOR_OUT_DIR = OUT_DIR / "vendors"
RESULTS = pathlib.Path(
    os.environ.get("BENCH_RESULTS", REPO / ".claude" / "bench-results")
)
DELIVERY = pathlib.Path(os.environ.get("DELIVERY_JSON", RESULTS / "delivery.json"))
SUMMARY = os.environ.get("SUMMARY_JSON")
ENGINE = "0.2.0"

# ---------------------------------------------------------------------------
# Vendor registry. "run" is the measurement date; "lanes" says how it ran.
# Datadog, New Relic and Elasticsearch ran together as three parallel lanes;
# Dynatrace ran a day later in its own lane, alone, with the same protocol.
# ---------------------------------------------------------------------------
VENDORS = [
    # (prefix, display name, slug, run date, lanes note)
    ("dd", "Datadog", "datadog", "2026-10-12", "three vendor lanes in parallel"),
    ("nr", "New Relic", "newrelic", "2026-10-12", "three vendor lanes in parallel"),
    ("es", "Elasticsearch", "elasticsearch", "2026-10-12", "three vendor lanes in parallel"),
    ("dt", "Dynatrace", "dynatrace", "2026-10-13", "Dynatrace lane alone"),
]
VENDOR = {v[0]: v for v in VENDORS}
RUNS = {v[0]: v[3] for v in VENDORS}
MEASURED_NOTE = ("Datadog, New Relic and Elasticsearch measured 2026-10-12 in parallel lanes; "
                 "Dynatrace 2026-10-13, alone, same protocol")

ROUNDS = ["z1m", "z1m_churn", "z5m", "z5m_t30s"]
TEST_LABELS = {"z1m": "Test 1", "z1m_churn": "Test 2", "z5m": "Test 3", "z5m_t30s": "Test 4"}
PLANE_TITLE = {"ch1": "CloudHub 1.0  (-c 10)", "ch2": "CloudHub 2.0  (-c 5)"}

# ---------------------------------------------------------------------------
# oha output parsing. oha 1.14.0 prints time fields in one of several units and
# has a known unit-selection defect that emits "N.NNN 10 sec" (the value must be
# multiplied by 10). Throughput (Requests/sec) is always reliable and unitless.
# We normalise every time field to SECONDS.
# ---------------------------------------------------------------------------
_UNIT = {
    "sec": 1.0,
    "ms": 1e-3,
    "min": 60.0,
    "10 sec": 10.0,  # oha 1.14.0 glitch
    "10 ms": 1e-2,
}


def _norm(value, unit):
    return value * _UNIT[unit.strip()]


def _time_field(txt, label):
    m = re.search(rf"^\s*{label}:\s*([0-9.]+)\s*((?:10 )?(?:sec|ms|min))", txt, re.M)
    return _norm(float(m.group(1)), m.group(2)) if m else None


def _reqsec(txt):
    m = re.search(r"^\s*Requests/sec:\s*([0-9.]+)", txt, re.M)
    return float(m.group(1)) if m else None


def _success(txt):
    m = re.search(r"Success rate:\s*([0-9.]+)", txt)
    return float(m.group(1)) if m else None


def _pct(txt, p):
    m = re.search(rf"{p:.2f}%\s+in\s+([0-9.]+)\s*((?:10 )?(?:sec|ms|min))", txt)
    return _norm(float(m.group(1)), m.group(2)) if m else None


def _status_counts(txt):
    """{status_code: responses} from oha's 'Status code distribution' block."""
    return {int(c): int(n) for c, n in re.findall(r"\[(\d{3})\]\s+(\d+)\s+responses", txt)}


def _errors(txt):
    """Requests with no HTTP response (timeouts, closed connections), summed."""
    m = re.search(r"Error distribution:\n((?:\s*\[\d+\].*\n?)+)", txt)
    if not m:
        return 0
    return sum(int(n) for n in re.findall(r"\[(\d+)\]", m.group(1)))


_CACHE = {}


def load(label, app):
    """Return the parsed metrics dict for one app in one phase, or None."""
    key = (label, app)
    if key in _CACHE:
        return _CACHE[key]
    f = RESULTS / f"loadtest-{label}" / f"{app}.txt"
    if not f.exists():
        return None
    t = f.read_text()
    codes = _status_counts(t)
    _CACHE[key] = {
        "reqsec": _reqsec(t),
        "total": _time_field(t, "Total"),
        "slowest": _time_field(t, "Slowest"),
        "avg": _time_field(t, "Average"),
        "p50": _pct(t, 50),
        "p90": _pct(t, 90),
        "p99": _pct(t, 99),
        "success": _success(t),
        "ok": codes.get(200, 0),
        "non200": sum(n for c, n in codes.items() if c != 200),
        "errors": _errors(t),
    }
    return _CACHE[key]


# ---------------------------------------------------------------------------
# App / plane registry. Concurrency differs by plane (CH1 -c 10, CH2 -c 5), so
# charts compare batch vs stock WITHIN a plane only.
# ---------------------------------------------------------------------------
# (axis_label, batch_app, stock_app, plane_key, vendor_prefix)
PAIRS = [
    (f"{'CloudHub 1.0' if plane == 'ch1' else 'CloudHub 2.0'}\n{name}",
     f"{v}-{plane}-batch", f"{v}-{plane}-stock", plane, v)
    for plane in ("ch1", "ch2")
    for (v, name, _slug, _run, _lanes) in VENDORS
]
PLATFORM_SPLIT = len(VENDORS)  # divider between the CH1 rows (above) and CH2 rows (below)
CONC = {"ch1": 10, "ch2": 5}

# ---------------------------------------------------------------------------
# Colourblind-safe palette (Okabe–Ito blue/orange), hatch on batch so colour is
# never the sole distinguishing channel.
# ---------------------------------------------------------------------------
STOCK_FILL = "#0072B2"
STOCK_EDGE = "#023E5F"
BATCH_FILL = "#E69F00"
BATCH_EDGE = "#7A5400"
INK = "#1A1A1A"
MUTED = "#4D4D4D"
GRID = "#C9C9C9"
STOCK_LABEL = "Stock HttpAppender"
BATCH_LABEL = "Batch appender"

DPI = 300

plt.rcParams.update(
    {
        "font.family": "DejaVu Sans",
        "font.size": 13,
        "text.color": INK,
        "axes.labelcolor": INK,
        "axes.edgecolor": "#8C8C8C",
        "axes.linewidth": 1.2,
        "xtick.color": MUTED,
        "ytick.color": INK,
        "xtick.labelsize": 12.5,
        "ytick.labelsize": 13.5,
        "axes.titlesize": 15,
        "legend.fontsize": 13,
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "savefig.facecolor": "white",
        "svg.fonttype": "path",
    }
)


def thousands(value, _pos=None):
    return f"{value:,.0f}"


def ratio_txt(r):
    """batch÷stock label: one decimal below 10× (Dynatrace's 2.7–6.5× must not round away)."""
    return f"{r:.1f}×" if r < 10 else f"{r:.0f}×"


def save(fig, stem, out_dir=OUT_DIR):
    out_dir.mkdir(parents=True, exist_ok=True)
    png = out_dir / f"{stem}.png"
    svg = out_dir / f"{stem}.svg"
    fig.savefig(png, dpi=DPI, bbox_inches="tight")
    fig.savefig(svg, bbox_inches="tight")
    plt.close(fig)
    print(f"  {png}")
    print(f"  {svg}")


def legend_handles(hatched=True):
    return [
        Patch(facecolor=STOCK_FILL, edgecolor=STOCK_EDGE, linewidth=1.2, label=STOCK_LABEL),
        Patch(
            facecolor=BATCH_FILL,
            edgecolor=BATCH_EDGE,
            linewidth=1.2,
            hatch="///" if hatched else None,
            label=BATCH_LABEL,
        ),
    ]


def marker_handles():
    return [
        Line2D([], [], marker="o", markersize=13, linestyle="none", color=STOCK_FILL,
               markeredgecolor=STOCK_EDGE, markeredgewidth=1.6, label=STOCK_LABEL),
        Line2D([], [], marker="s", markersize=12, linestyle="none", color=BATCH_FILL,
               markeredgecolor=BATCH_EDGE, markeredgewidth=1.6, label=BATCH_LABEL),
    ]


# ===========================================================================
# Figure 1 — sustained throughput (Test 3, 5 min), grouped horizontal bars.
# ===========================================================================
def figure_throughput(label="z5m"):
    labels = [p[0] for p in PAIRS]
    batch = [load(label, p[1])["reqsec"] for p in PAIRS]
    stock = [load(label, p[2])["reqsec"] for p in PAIRS]

    y = list(range(len(PAIRS)))[::-1]
    bar_h = 0.36
    fig, ax = plt.subplots(figsize=(10.2, 11.6))

    for i, pos in enumerate(y):
        s, b = stock[i], batch[i]
        ax.barh(pos + bar_h / 2 + 0.02, s, height=bar_h, color=STOCK_FILL,
                edgecolor=STOCK_EDGE, linewidth=1.2, zorder=3)
        ax.barh(pos - bar_h / 2 - 0.02, b, height=bar_h, color=BATCH_FILL,
                edgecolor=BATCH_EDGE, linewidth=1.2, hatch="///", zorder=3)
        ax.text(s + 2, pos + bar_h / 2 + 0.02, f"{s:,.1f}", va="center", ha="left",
                fontsize=12, fontweight="bold", color=INK, zorder=4)
        ax.text(b + 2, pos - bar_h / 2 - 0.02, f"{b:,.1f}", va="center", ha="left",
                fontsize=12, fontweight="bold", color=INK, zorder=4)
        ratio = b / s if s else float("nan")
        ax.text(max(s, b) + 14, pos, ratio_txt(ratio), va="center", ha="left",
                fontsize=13.5, fontweight="bold", color=BATCH_EDGE, zorder=4)

    ax.set_yticks(y)
    ax.set_yticklabels(labels)
    ax.set_xlim(0, max(batch) * 1.22)
    ax.set_ylim(-0.62, len(PAIRS) - 0.38)
    ax.set_xlabel("Requests / second over a sustained 5-minute run  (higher is better)", labelpad=10)
    ax.grid(axis="x", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)

    # platform divider
    yd = (y[PLATFORM_SPLIT - 1] + y[PLATFORM_SPLIT]) / 2
    ax.axhline(yd, color=GRID, linewidth=1.2, linestyle=(0, (4, 4)), zorder=1)

    fig.suptitle("Sustained request throughput: batch vs stock appender",
                 fontsize=18, fontweight="bold", x=0.045, ha="left", y=0.995)
    fig.text(0.045, 0.962,
             "5-min run (Test 3) · 22 log events/request · CH1 -c 10, CH2 -c 5 "
             f"· engine {ENGINE}, spill on · compare within a plane, not across",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    ax.legend(handles=legend_handles(), loc="lower right", frameon=False,
              ncol=1, handlelength=1.9)
    fig.subplots_adjust(top=0.925, bottom=0.08)
    fig.text(0.045, 0.012,
             "× = batch÷stock throughput ratio within that plane+vendor. Concurrency differs by plane "
             "(CH1 -c 10, CH2 -c 5), so\nread each plane's contrast on its own — a CH1 bar is not "
             "comparable to a CH2 bar. Dynatrace's smaller ratio is a faster\nstock baseline (an "
             "in-region single-record POST is quick), not a slower batch. One run per app.\n"
             f"{MEASURED_NOTE}.",
             fontsize=10.5, color=MUTED, ha="left", va="top")
    save(fig, "cloudhub-throughput-batch-vs-stock")


# ===========================================================================
# Figure 2 — latency (Test 3), avg / p90 / p99, log axis, stacked per pair.
# ===========================================================================
def figure_latency(label="z5m"):
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.4, 24.0), sharex=True)
    bar_h = 0.34
    metrics = ["Average", "p90", "p99"]

    for ax, pair in zip(axes, PAIRS):
        b = load(label, pair[1])
        s = load(label, pair[2])
        vals = {
            "Average": (s["avg"] * 1000, b["avg"] * 1000),
            "p90": (s["p90"] * 1000, b["p90"] * 1000),
            "p99": (s["p99"] * 1000, b["p99"] * 1000),
        }
        y = list(range(len(metrics)))[::-1]
        for idx, metric in enumerate(metrics):
            sv, bv = vals[metric]
            pos = y[idx]
            ax.barh(pos + bar_h / 2 + 0.02, sv, height=bar_h, color=STOCK_FILL,
                    edgecolor=STOCK_EDGE, linewidth=1.2, zorder=3)
            ax.barh(pos - bar_h / 2 - 0.02, bv, height=bar_h, color=BATCH_FILL,
                    edgecolor=BATCH_EDGE, linewidth=1.2, hatch="///", zorder=3)
            ax.text(sv * 1.13, pos + bar_h / 2 + 0.02, f"{sv:,.0f} ms", va="center",
                    ha="left", fontsize=12, fontweight="bold", color=INK, zorder=4)
            ax.text(bv * 1.13, pos - bar_h / 2 - 0.02, f"{bv:,.0f} ms", va="center",
                    ha="left", fontsize=12, fontweight="bold", color=INK, zorder=4)
        ax.set_yticks(y)
        ax.set_yticklabels(metrics)
        ax.set_xscale("log")
        ax.set_xlim(1, 200_000)
        ax.set_ylim(-0.62, len(metrics) - 0.38)
        ax.xaxis.set_major_formatter(FuncFormatter(thousands))
        ax.grid(axis="x", color=GRID, linewidth=0.9, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        ax.set_title(pair[0].replace("\n", " — "), loc="left", fontweight="bold", pad=10)

    axes[-1].set_xlabel("Latency in milliseconds — logarithmic scale (lower is better)", labelpad=10)
    fig.suptitle("Request latency: stock vs batch appender (5-min run)",
                 fontsize=18, fontweight="bold", x=0.052, ha="left", y=0.997)
    fig.text(0.052, 0.985,
             f"22 log events/request · CH1 -c 10, CH2 -c 5 · engine {ENGINE}, spill on "
             "· compare within a plane",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=legend_handles(), loc="upper left", bbox_to_anchor=(0.048, 0.976),
               frameon=False, ncol=2, columnspacing=2.4, handlelength=1.9)
    fig.text(0.052, 0.008,
             "Logarithmic axis: every gridline is 10× the one before it — read the printed numbers, "
             "not the bar lengths.\nThe true gap is larger than the bars look. One run per app. "
             "Datadog, New Relic and Elasticsearch measured\n2026-10-12 in parallel lanes; "
             "Dynatrace 2026-10-13, alone, same protocol.",
             fontsize=10.5, color=MUTED, ha="left", va="top")
    fig.tight_layout(rect=(0, 0.015, 1, 0.970))
    _draw_platform_divider(fig, axes)
    save(fig, "cloudhub-latency-batch-vs-stock")


def _draw_platform_divider(fig, axes):
    """Dashed rule between the CH1 panels (above) and the CH2 panels (below).

    The panels either side are separate experiments on different CPU models
    (and different concurrency: CH1 -c 10, CH2 -c 5), so the divider carries the
    same "not a controlled comparison" meaning as the plane split in the other
    charts. Call after tight_layout — it reads the final axes positions.
    """
    above = axes[PLATFORM_SPLIT - 1].get_position().y0
    below = axes[PLATFORM_SPLIT].get_position().y1
    y = (above + below) / 2
    fig.add_artist(
        Line2D([0.045, 0.975], [y, y], color=GRID, linewidth=1.2,
               linestyle=(0, (4, 4)), zorder=0)
    )


# ===========================================================================
# Figure 2b — the same Test-3 latency data as a range (dumbbell) plot. Stock
# and batch markers joined by the "N× lower" gap; no bar baseline, so the log
# axis carries no "bar length is meaningless" caveat.
# ===========================================================================
def _dumbbell_row(ax, pos, stock, batched):
    ax.plot([batched, stock], [pos, pos], color="#9A9A9A", linewidth=3.0,
            solid_capstyle="round", zorder=2)
    ax.plot(stock, pos, marker="o", markersize=15, color=STOCK_FILL,
            markeredgecolor=STOCK_EDGE, markeredgewidth=1.6, zorder=3)
    ax.plot(batched, pos, marker="s", markersize=14, color=BATCH_FILL,
            markeredgecolor=BATCH_EDGE, markeredgewidth=1.6, zorder=3)
    ax.text(batched * 0.82, pos, f"{batched:,.0f} ms", va="center", ha="right",
            fontsize=12.5, fontweight="bold", color=INK)
    ax.text(stock * 1.22, pos, f"{stock:,.0f} ms", va="center", ha="left",
            fontsize=12.5, fontweight="bold", color=INK)
    ax.text((batched * stock) ** 0.5, pos + 0.30,
            f"{stock / batched:.1f}× lower", va="bottom", ha="center",
            fontsize=11.5, color=MUTED, style="italic")


def _dumbbell_axes(ax, metrics, xlim):
    y = list(range(len(metrics)))[::-1]
    ax.set_yticks(y)
    ax.set_yticklabels(metrics)
    ax.set_xscale("log")
    ax.set_xlim(*xlim)
    ax.set_ylim(-0.55, len(metrics) - 0.30)
    ax.xaxis.set_major_formatter(FuncFormatter(thousands))
    ax.grid(axis="x", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(axis="y", length=0)


def figure_latency_range(label="z5m"):
    metrics = ["Average", "p90", "p99"]
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.2, 23.6), sharex=True)

    for ax, pair in zip(axes, PAIRS):
        b = load(label, pair[1])
        s = load(label, pair[2])
        vals = {
            "Average": (s["avg"] * 1000, b["avg"] * 1000),
            "p90": (s["p90"] * 1000, b["p90"] * 1000),
            "p99": (s["p99"] * 1000, b["p99"] * 1000),
        }
        y = list(range(len(metrics)))[::-1]
        for idx, metric in enumerate(metrics):
            _dumbbell_row(ax, y[idx], *vals[metric])
        _dumbbell_axes(ax, metrics, (20, 200_000))
        ax.set_title(pair[0].replace("\n", " — "), loc="left", fontweight="bold", pad=10)

    axes[-1].set_xlabel("Latency in milliseconds — logarithmic scale (lower is better)", labelpad=10)

    fig.suptitle("Request latency: stock appender vs batch appender (5-min run)",
                 fontsize=19, fontweight="bold", x=0.052, ha="left", y=0.995)
    fig.text(0.052, 0.981,
             f"22 log events/request · CH1 -c 10, CH2 -c 5 · engine {ENGINE}, spill on "
             "· compare within a plane",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=marker_handles(), loc="upper left", bbox_to_anchor=(0.048, 0.971),
               frameon=False, ncol=2, columnspacing=2.4, handlelength=1.6)
    fig.text(0.052, 0.012,
             "Each marker is a measured value and the bar between them is the improvement. "
             "Logarithmic axis: every gridline is\n10× the one before it, which compresses the "
             "difference — the true gap is larger than it looks.\nPanels either side of the dashed "
             "divider ran on different CPU models — a burstable CloudHub 1.0 worker versus a\n"
             "hard-capped CloudHub 2.0 replica, at different concurrency (CH1 -c 10, CH2 -c 5) — so "
             "they are not a controlled\ncomparison of each other. Batch apps ran with disk spill on "
             "and gzip off (the stock appender cannot\ncompress). Sustained 5-minute "
             "run (Test 3), one run per app. Datadog, New Relic and Elasticsearch measured\n"
             "2026-10-12 in parallel lanes; Dynatrace 2026-10-13, alone, same protocol.",
             fontsize=10.5, color=MUTED, ha="left", va="bottom", linespacing=1.5)

    fig.tight_layout(rect=(0.02, 0.075, 0.98, 0.962))
    _draw_platform_divider(fig, axes)
    save(fig, "cloudhub-latency-batch-vs-stock-range")


# ===========================================================================
# Figure 3 — the cold-start / warmup story, batch AND stock side by side. For
# each app and phase (Test 1 1m → churn 1m warm → Test 3 5m) the batch bar and
# the stock bar sit together, so two things read at once: batch req/s ramps as
# the cold-start cost (JIT/TLS/pools) is amortised over a longer window, while
# stock stays flat and far below — even cold batch (Test 1) beats warm stock.
# The two planes ran at different concurrency / CPU, so they get separate panels
# each on its own y-scale (compare within a panel, not across).
# ===========================================================================
def _fmt(v):
    return f"{v:,.1f}" if v < 10 else f"{v:,.0f}"


def _warmup_panel(ax, pairs, phases, title):
    step_phase = 1.0   # spacing between phase clusters inside one app
    gap_app = 1.45     # extra spacing between the apps in the panel
    bw = 0.40          # bar width; batch/stock offset ±0.215 around the cluster
    cluster_x, cluster_lbl, app_marks = [], [], []
    ymax = 0.0

    for ai, pair in enumerate(pairs):
        base = ai * (2 * step_phase + gap_app)
        for pi, (_pname, plabel) in enumerate(phases):
            c = base + pi * step_phase
            b = load(plabel, pair[1])["reqsec"]
            s = load(plabel, pair[2])["reqsec"]
            ymax = max(ymax, b, s)
            ax.bar(c - 0.215, b, width=bw, color=BATCH_FILL, edgecolor=BATCH_EDGE,
                   linewidth=1.1, hatch="///", zorder=3)
            ax.bar(c + 0.215, s, width=bw, color=STOCK_FILL, edgecolor=STOCK_EDGE,
                   linewidth=1.1, zorder=3)
            cluster_x.append(c)
            cluster_lbl.append(phases[pi][0])
        app_marks.append((base + step_phase, pair[0]))

    head = ymax * 0.045
    for ai, pair in enumerate(pairs):
        base = ai * (2 * step_phase + gap_app)
        for pi, (_pname, plabel) in enumerate(phases):
            c = base + pi * step_phase
            b = load(plabel, pair[1])["reqsec"]
            s = load(plabel, pair[2])["reqsec"]
            ax.text(c - 0.215, b + head, _fmt(b), ha="center", va="bottom",
                    fontsize=10, fontweight="bold", color=INK, zorder=4)
            ax.text(c + 0.215, s + head, _fmt(s), ha="center", va="bottom",
                    fontsize=10, fontweight="bold", color=STOCK_EDGE, zorder=4)
            if s:
                ax.text(c, max(b, s) + head * 3.2, ratio_txt(b / s), ha="center",
                        va="bottom", fontsize=10.5, fontweight="bold",
                        color=BATCH_EDGE, zorder=4)

    ax.set_ylim(0, ymax * 1.22)
    ax.set_xlim(-0.7, app_marks[-1][0] + step_phase + 0.6)
    ax.set_xticks(cluster_x)
    ax.set_xticklabels(cluster_lbl, fontsize=10.5)
    ax.tick_params(axis="x", length=0)
    ax.grid(axis="y", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.set_title(title, loc="left", fontsize=14, fontweight="bold", pad=8)

    # Vendor labels below the phase row, spanning each app's 3 clusters (the
    # panel title already names the plane).
    for cx, name in app_marks:
        ax.text(cx, -0.145, name.split("\n")[-1], transform=ax.get_xaxis_transform(),
                ha="center", va="top", fontsize=12, fontweight="bold", color=INK)


def figure_warmup():
    ch1 = [p for p in PAIRS if p[3] == "ch1"]
    ch2 = [p for p in PAIRS if p[3] == "ch2"]
    phases = [("Test 1\n1 min", "z1m"), ("Churn\n1 min", "z1m_churn"),
              ("Test 3\n5 min", "z5m")]

    # Four vendors per plane: the planes stack (CH1 above, CH2 below) so each
    # panel keeps the full width for its 4 apps × 3 phases.
    fig, (axT, axB) = plt.subplots(2, 1, figsize=(17.6, 12.6))
    _warmup_panel(axT, ch1, phases, PLANE_TITLE["ch1"])
    _warmup_panel(axB, ch2, phases, PLANE_TITLE["ch2"])
    for ax in (axT, axB):
        ax.set_ylabel("Requests / second  (higher is better)", labelpad=10)

    fig.suptitle("Warmup: batch throughput ramps as cold-start is amortised — stock stays flat and far lower",
                 fontsize=16.5, fontweight="bold", x=0.045, ha="left", y=1.000)
    fig.text(0.045, 0.975,
             "req/s per phase, batch vs stock side by side · Test 1 (1 min) → Churn (1 min, warm) "
             f"→ Test 3 (5 min) · engine {ENGINE}, spill on",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=legend_handles(), loc="upper left", bbox_to_anchor=(0.045, 0.958),
               frameon=False, ncol=2, columnspacing=2.2, handlelength=1.9)
    fig.text(0.045, -0.005,
             "× = batch÷stock req/s for that phase. Batch rises Test 1 → Test 3 as the cold-start "
             "ramp (JIT/TLS/connection pools) is amortised over a longer window, so the\n1-min Test 1 "
             "under-reports warm batch throughput; stock stays flat or falls as its backlog grows. "
             "Even cold batch (Test 1) already beats warm stock. New Relic\non CloudHub 1.0 dips in the "
             "churn window: its queue overflows to disk the whole minute (the US intake drains slower "
             "than the app logs), and the spill I/O\nshares the 0.1 vCore. Dynatrace stock runs faster "
             "than the other stock apps (an in-region single-record POST is quick), so its ratio is "
             "smaller; its batch\nreq/s matches the other vendors'. The two planes ran at different "
             "concurrency and CPU (CH1 -c 10 burstable, CH2 -c 5 hard-capped), so each panel is on its\n"
             "own y-scale — read within a panel, not across. One run per app. "
             f"{MEASURED_NOTE}.",
             fontsize=10.5, color=MUTED, ha="left", va="top", linespacing=1.5)

    fig.tight_layout(rect=(0, 0.02, 1, 0.935), h_pad=4.0)
    save(fig, "cloudhub-warmup-cold-start")


# ===========================================================================
# Figure 4 — logs delivered. For every app and test: logs that reached the
# vendor ÷ logs the measured requests generated (responses × 22), batch and
# stock side by side, CH1 above, CH2 below. Elasticsearch and Dynatrace are
# counted by event time inside the oha window; Datadog and New Relic by arrival
# time, from the test start to the next test on the same app. Over 100% = late
# backlog from an earlier test, or log lines of requests oha cut off.
# ===========================================================================
_DELIVERY_PHASES = [("Test 1\n1 min", "z1m"), ("Churn\n1 min", "z1m_churn"),
                    ("Test 3\n5 min", "z5m"), ("Test 4\n5m, -t 30s", "z5m_t30s")]


def _delivered(data, label, app):
    rec = data.get(label, {}).get(app)
    if not rec or not rec.get("expected"):
        return None
    return rec["delivered"] / rec["expected"] * 100


def _delivery_panel(ax, data, pairs, title):
    step_phase = 1.0
    gap_app = 1.25
    bw = 0.40
    n = len(_DELIVERY_PHASES)
    cluster_x, cluster_lbl, app_marks = [], [], []
    ymax = 100.0
    for ai, pair in enumerate(pairs):
        base = ai * ((n - 1) * step_phase + gap_app)
        for pi, (plabel_txt, plabel) in enumerate(_DELIVERY_PHASES):
            c = base + pi * step_phase
            for off, app, fill, edge, hatch, ink in (
                    (-0.215, pair[1], BATCH_FILL, BATCH_EDGE, "///", INK),
                    (+0.215, pair[2], STOCK_FILL, STOCK_EDGE, None, STOCK_EDGE)):
                v = _delivered(data, plabel, app)
                if v is None:
                    continue
                ymax = max(ymax, v)
                ax.bar(c + off, v, width=bw, color=fill, edgecolor=edge, linewidth=1.1,
                       hatch=hatch, zorder=3)
                ax.text(c + off, v + 2.5, f"{v:.0f}", ha="center", va="bottom",
                        fontsize=9, fontweight="bold", color=ink, zorder=4, rotation=90)
            cluster_x.append(c)
            cluster_lbl.append(plabel_txt)
        app_marks.append((base + (n - 1) * step_phase / 2, pair[0]))

    ax.axhline(100, color=MUTED, linewidth=1.2, linestyle=(0, (4, 3)), zorder=2)
    ax.set_ylim(0, ymax * 1.18)
    ax.set_xlim(-0.7, cluster_x[-1] + 0.7)
    ax.set_xticks(cluster_x)
    ax.set_xticklabels(cluster_lbl, fontsize=9.5)
    ax.tick_params(axis="x", length=0)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _p: f"{v:.0f}%"))
    ax.grid(axis="y", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.set_title(title, loc="left", fontsize=14, fontweight="bold", pad=8)
    for cx, name in app_marks:
        ax.text(cx, -0.17, name.split("\n")[-1], transform=ax.get_xaxis_transform(),
                ha="center", va="top", fontsize=12, fontweight="bold", color=INK)


def figure_delivered(data):
    ch1 = [p for p in PAIRS if p[3] == "ch1"]
    ch2 = [p for p in PAIRS if p[3] == "ch2"]

    fig, (axT, axB) = plt.subplots(2, 1, figsize=(18.4, 11.6))
    _delivery_panel(axT, data, ch1, PLANE_TITLE["ch1"])
    _delivery_panel(axB, data, ch2, PLANE_TITLE["ch2"])
    for ax in (axT, axB):
        ax.set_ylabel("Logs delivered, % of logs generated", labelpad=10)

    fig.suptitle("Logs delivered: batch vs stock appender, per test",
                 fontsize=16.5, fontweight="bold", x=0.045, ha="left", y=1.000)
    fig.text(0.045, 0.975,
             "logs that reached the vendor ÷ (responses × 22) · dashed line = 100% "
             f"· engine {ENGINE}, spill on",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=legend_handles(), loc="upper left", bbox_to_anchor=(0.045, 0.958),
               frameon=False, ncol=2, columnspacing=2.2, handlelength=1.9)
    fig.text(0.045, -0.005,
             "Elasticsearch and Dynatrace are counted by event time inside the load window; Datadog and "
             "New Relic by arrival time, from the test start to the next test on the same app.\nOver "
             "100% = late backlog from an earlier test arriving in the window, or log lines of requests "
             "oha cut off. New Relic batch reads low in Test 3 because its spill backlog was still\n"
             "draining to the US intake when the next round's restart replaced the worker or pod; Test 4 "
             "was the last round, nothing restarted after it, and the backlog drained. Datadog, New\n"
             "Relic and Elasticsearch stock lose their in-memory backlog at every restart (Elasticsearch "
             "stock reads 0% in churn: those events were still queued behind the Test 1 backlog when\n"
             "the next restart came). Dynatrace stock on CloudHub 1.0 reached the intake at a flat rate "
             "below what the app generated; where the rest was lost is not established. One run per app.\n"
             f"{MEASURED_NOTE}.",
             fontsize=10.5, color=MUTED, ha="left", va="top", linespacing=1.5)

    fig.tight_layout(rect=(0, 0.02, 1, 0.935), h_pad=4.5)
    save(fig, "cloudhub-delivered-logs")


# ===========================================================================
# Per-vendor figures — docs/img/vendors/<slug>-{throughput,latency,delivered}.
# One vendor only: its batch and stock apps on CloudHub 1.0 (left / top) and
# CloudHub 2.0 (right / bottom), each plane on its own scale.
# ===========================================================================
_VENDOR_PHASES = [("Test 1\n1 min", "z1m"), ("Test 2\nchurn, 1 min", "z1m_churn"),
                  ("Test 3\n5 min", "z5m"), ("Test 4\n5 min, -t 30s", "z5m_t30s")]

# How each vendor's delivery was counted, and what to read into it.
_DELIVERY_NOTE = {
    "dd": ("Counted by arrival time, from the test start to the next test on the same app (Test 4: "
           "load + 15 min); over 100% is late backlog\nfrom an earlier test (churn follows Test 1 with "
           "no restart). In Tests 1 and 3 stock's in-memory backlog died at the next restart;\nnothing "
           "restarted after Test 4, so its CloudHub 1.0 shortfall is backlog undrained or lost when "
           "counting stopped (which one is not established)."),
    "nr": ("Counted by arrival time, from the test start to the next test on the same app; over "
           "100% is late backlog from an earlier test.\nThese Sydney workers ship to New Relic's US "
           "intake. Batch Test 3 reads low because its spill backlog was still draining when the\n"
           "next round's restart replaced the worker or pod; Test 4, the last round, drained fully. "
           "In Tests 1 and 3\nstock's in-memory backlog died at the next restart."),
    "es": ("Counted by event time inside the load window. Stock reads 0% in churn: those events "
           "were still queued behind the Test 1\nbacklog when the next restart came. In Tests 1 and 3 "
           "stock's in-memory backlog died at the next restart."),
    "dt": ("Counted by event time in Grail (DQL by log.source). CloudHub 1.0 stock reached the "
           "intake at a flat rate below what the app\ngenerated (one serialized background sender); "
           "where the rest was lost is not established. CloudHub 2.0 stock's lower load\nstayed "
           "just under that rate, so it delivered everything."),
}


def _vendor_footer(v):
    _p, _name, _slug, run, lanes = VENDOR[v]
    return f"One run per app, measured {run} ({lanes}), engine {ENGINE}, spill on, gzip off."


def _vendor_bar_panel(ax, v, plane, values, fmt, title, ratio=False, pct=False):
    """values(app, round) -> number or None. Batch hatched left, stock right."""
    bw = 0.38
    xs = list(range(len(_VENDOR_PHASES)))
    ymax = 100.0 if pct else 0.0
    vals = []
    for x, (_lbl, rnd) in zip(xs, _VENDOR_PHASES):
        b = values(f"{v}-{plane}-batch", rnd)
        s = values(f"{v}-{plane}-stock", rnd)
        vals.append((x, b, s))
        ymax = max(ymax, b or 0, s or 0)
    head = ymax * 0.02
    for x, b, s in vals:
        if b is not None:
            ax.bar(x - 0.2, b, width=bw, color=BATCH_FILL, edgecolor=BATCH_EDGE,
                   linewidth=1.1, hatch="///", zorder=3)
            ax.text(x - 0.2, b + head, fmt(b), ha="center", va="bottom", fontsize=10.5,
                    fontweight="bold", color=INK, zorder=4)
        if s is not None:
            ax.bar(x + 0.2, s, width=bw, color=STOCK_FILL, edgecolor=STOCK_EDGE,
                   linewidth=1.1, zorder=3)
            ax.text(x + 0.2, s + head, fmt(s), ha="center", va="bottom", fontsize=10.5,
                    fontweight="bold", color=STOCK_EDGE, zorder=4)
        if ratio and b and s:
            ax.text(x, max(b, s) + head * 5.5, ratio_txt(b / s), ha="center", va="bottom",
                    fontsize=12, fontweight="bold", color=BATCH_EDGE, zorder=4)
    if pct:
        ax.axhline(100, color=MUTED, linewidth=1.2, linestyle=(0, (4, 3)), zorder=2)
        ax.yaxis.set_major_formatter(FuncFormatter(lambda y, _p: f"{y:.0f}%"))
    ax.set_ylim(0, ymax * (1.22 if ratio else 1.14))
    ax.set_xlim(-0.6, len(xs) - 0.4)
    ax.set_xticks(xs)
    ax.set_xticklabels([p[0] for p in _VENDOR_PHASES], fontsize=11)
    ax.tick_params(axis="x", length=0)
    ax.grid(axis="y", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.set_title(title, loc="left", fontsize=14, fontweight="bold", pad=8)


def _vendor_frame(fig, title, subtitle, footer, handles, legend_y=0.905):
    fig.suptitle(title, fontsize=17, fontweight="bold", x=0.04, ha="left", y=1.01)
    fig.text(0.04, 0.965, subtitle, fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=handles, loc="upper left", bbox_to_anchor=(0.035, legend_y + 0.035),
               frameon=False, ncol=2, columnspacing=2.2, handlelength=1.9)
    fig.text(0.04, -0.01, footer, fontsize=10.5, color=MUTED, ha="left", va="top",
             linespacing=1.5)


def vendor_throughput(v):
    name, slug = VENDOR[v][1], VENDOR[v][2]
    fig, axes = plt.subplots(1, 2, figsize=(14.0, 6.6))
    for ax, plane in zip(axes, ("ch1", "ch2")):
        _vendor_bar_panel(ax, v, plane, lambda app, rnd: load(rnd, app)["reqsec"],
                          _fmt, PLANE_TITLE[plane], ratio=True)
    axes[0].set_ylabel("Requests / second  (higher is better)", labelpad=10)
    _vendor_frame(
        fig, f"{name}: request throughput, batch vs stock appender",
        f"req/s per test · 22 log events/request · CH1 -c 10, CH2 -c 5 · engine {ENGINE}, spill on",
        "× = batch÷stock req/s in that test. Each plane is on its own y-scale and ran at its own "
        "concurrency on different CPU — compare\nbatch with stock within a panel, not CloudHub 1.0 "
        "with 2.0. Test 3 (5 min) is the warm headline; Test 1 includes the cold start.\n"
        + _vendor_footer(v),
        legend_handles())
    fig.tight_layout(rect=(0, 0, 1, 0.905), w_pad=3.0)
    save(fig, f"{slug}-throughput", VENDOR_OUT_DIR)


def vendor_latency(v, label="z5m"):
    name, slug = VENDOR[v][1], VENDOR[v][2]
    metrics = ["p50", "p90", "p99"]
    lo, hi = float("inf"), 0.0
    rows = {}
    for plane in ("ch1", "ch2"):
        b = load(label, f"{v}-{plane}-batch")
        s = load(label, f"{v}-{plane}-stock")
        rows[plane] = [(s[m] * 1000, b[m] * 1000) for m in metrics]
        lo = min([lo] + [r[1] for r in rows[plane]])
        hi = max([hi] + [r[0] for r in rows[plane]])
    # room for the printed values either side of the markers
    xlo, xhi = lo * 0.22, hi * 5.0

    fig, axes = plt.subplots(2, 1, figsize=(9.6, 8.8), sharex=True)
    for ax, plane in zip(axes, ("ch1", "ch2")):
        y = list(range(len(metrics)))[::-1]
        for idx, (sv, bv) in enumerate(rows[plane]):
            _dumbbell_row(ax, y[idx], sv, bv)
        _dumbbell_axes(ax, metrics, (xlo, xhi))
        ax.set_title(PLANE_TITLE[plane], loc="left", fontsize=14, fontweight="bold", pad=10)
    axes[-1].set_xlabel("Latency in milliseconds — logarithmic scale (lower is better)", labelpad=10)
    _vendor_frame(
        fig, f"{name}: request latency, batch vs stock (5-min run)",
        f"Test 3 · p50 / p90 / p99 · 22 log events/request · CH1 -c 10, CH2 -c 5 · engine {ENGINE}",
        "Each marker is a measured value; the line between them is the improvement. Logarithmic "
        "axis: every gridline is 10×\nthe one before it. The two planes ran at different "
        "concurrency on different CPU, so they are not a comparison of each other.\n"
        + _vendor_footer(v),
        marker_handles(), legend_y=0.915)
    fig.tight_layout(rect=(0, 0, 1, 0.925), h_pad=2.5)
    save(fig, f"{slug}-latency", VENDOR_OUT_DIR)


def vendor_delivered(v, data):
    name, slug = VENDOR[v][1], VENDOR[v][2]
    fig, axes = plt.subplots(1, 2, figsize=(14.0, 6.6), sharey=True)
    for ax, plane in zip(axes, ("ch1", "ch2")):
        _vendor_bar_panel(ax, v, plane, lambda app, rnd: _delivered(data, rnd, app),
                          lambda x: f"{x:.0f}%", PLANE_TITLE[plane], pct=True)
    axes[0].set_ylabel("Logs delivered, % of logs generated", labelpad=10)
    _vendor_frame(
        fig, f"{name}: logs delivered, batch vs stock appender",
        f"logs that reached {name} ÷ (HTTP 200 responses × 22) · dashed line = 100% "
        f"· engine {ENGINE}, spill on",
        _DELIVERY_NOTE[v] + "\n" + _vendor_footer(v),
        legend_handles())
    fig.tight_layout(rect=(0, 0, 1, 0.905), w_pad=3.0)
    save(fig, f"{slug}-delivered", VENDOR_OUT_DIR)


# ===========================================================================
# summary.json — the single numeric source for prose writers.
# ===========================================================================
def write_summary(path, data):
    apps = {}
    for v, *_rest in VENDORS:
        for plane in ("ch1", "ch2"):
            for kind in ("batch", "stock"):
                app = f"{v}-{plane}-{kind}"
                apps[app] = {}
                for rnd in ROUNDS:
                    m = load(rnd, app)
                    rec = data.get(rnd, {}).get(app) or {}
                    exp = rec.get("expected")
                    dlv = rec.get("delivered")
                    apps[app][rnd] = {
                        "rps": m["reqsec"], "avg": m["avg"], "p50": m["p50"], "p90": m["p90"],
                        "p99": m["p99"], "slowest": m["slowest"], "total": m["total"],
                        "success": m["success"], "ok": m["ok"], "non200": m["non200"],
                        "errors": m["errors"], "delivered": dlv, "expected": exp,
                        "delivered_pct": round(dlv / exp * 100, 4) if exp else None,
                    }
    ratios = {}
    for v, *_rest in VENDORS:
        for plane in ("ch1", "ch2"):
            ratios[f"{v}-{plane}"] = {
                rnd: round(apps[f"{v}-{plane}-batch"][rnd]["rps"]
                           / apps[f"{v}-{plane}-stock"][rnd]["rps"], 4)
                for rnd in ROUNDS
            }
    out = {
        "rounds": ROUNDS,
        "test_labels": TEST_LABELS,
        "units": {"rps": "requests/second", "latency": "seconds (avg, p50, p90, p99, slowest, total)",
                  "success": "percent", "delivered_pct": "percent"},
        "vendors": {v: {"name": n, "slug": s} for v, n, s, _r, _l in VENDORS},
        "apps": apps,
        "ratios": ratios,
        "runs": RUNS,
    }
    p = pathlib.Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(out, indent=2) + "\n")
    print(f"  {p}")


def main():
    print("Reading results from:", RESULTS)
    missing = [
        f"loadtest-{lbl}/{app}.txt"
        for lbl in ROUNDS
        for app in [p[1] for p in PAIRS] + [p[2] for p in PAIRS]
        if not (RESULTS / f"loadtest-{lbl}" / f"{app}.txt").exists()
    ]
    if missing:
        raise SystemExit("Missing result files:\n  " + "\n  ".join(missing))
    data = json.loads(DELIVERY.read_text()) if DELIVERY.exists() else None
    if data is None:
        print("  (no", DELIVERY, "— skipping the delivered-logs figures)")
    print("Writing charts to:", OUT_DIR)
    figure_throughput()
    figure_latency()
    figure_latency_range()
    figure_warmup()
    if data is not None:
        figure_delivered(data)
    for v, *_rest in VENDORS:
        vendor_throughput(v)
        vendor_latency(v)
        if data is not None:
            vendor_delivered(v, data)
    if SUMMARY:
        print("Writing summary to:", SUMMARY)
        write_summary(SUMMARY, data or {})
    print("done.")


if __name__ == "__main__":
    main()
