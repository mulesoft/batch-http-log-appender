#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the CloudHub benchmark charts for docs/benchmark-results.md.

Reads the RAW oha output written by the benchmark run (one <app>.txt per app per
phase) and renders PNG (300 dpi) + SVG into docs/img/. Nothing here is hardcoded
from a prior run — the figures are parsed from the result files, so re-running the
benchmark and re-running this script keeps the charts in lock-step with the report.

    python3 -m venv .venv-charts
    .venv-charts/bin/pip install -r docs/charts/requirements.txt
    .venv-charts/bin/python docs/charts/generate_benchmark_charts.py

Data source (default): ../.claude/bench-results/loadtest-<label>/  (git-ignored — it
carries hostnames). Override with BENCH_RESULTS=/path. The charts themselves contain
NO hostnames or hardware specs — only worker-TYPE names ("0.1 vCore") and app labels.

Charts produced:
  1. throughput-batch-vs-stock   — sustained req/s (Test 3, 5 min), grouped by plane.
  2. latency-batch-vs-stock      — avg / p90 / p99 latency (Test 3), log axis.
  3. latency-batch-vs-stock-range — the same Test-3 latency data as a range (dumbbell)
                                   plot: stock and batch markers joined by the "N× lower"
                                   gap, so the log axis carries no "bar length is
                                   meaningless" caveat.
  4. warmup-cold-start           — the cold-start story: Test 1 (1m) vs churn vs
                                   Test 3 (5m) req/s for the batch apps, showing why
                                   the 1-min Test-1 number under-reports warm throughput.
"""

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
RESULTS = pathlib.Path(
    os.environ.get("BENCH_RESULTS", REPO / ".claude" / "bench-results")
)

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


def load(label, app):
    """Return the parsed metrics dict for one app in one phase, or None."""
    f = RESULTS / f"loadtest-{label}" / f"{app}.txt"
    if not f.exists():
        return None
    t = f.read_text()
    return {
        "reqsec": _reqsec(t),
        "total": _time_field(t, "Total"),
        "slowest": _time_field(t, "Slowest"),
        "avg": _time_field(t, "Average"),
        "p90": _pct(t, 90),
        "p99": _pct(t, 99),
        "success": _success(t),
    }


# ---------------------------------------------------------------------------
# App / plane registry. Concurrency differs by plane on the 2026-08-04 runs
# (CH1 -c 10, CH2 -c 5), so charts compare batch vs stock WITHIN a plane only.
# ---------------------------------------------------------------------------
# (axis_label, batch_app, stock_app, plane_key)
PAIRS = [
    ("CloudHub 1.0\nDatadog", "dd-ch1-batch", "dd-ch1-stock", "ch1"),
    ("CloudHub 1.0\nNew Relic", "nr-ch1-batch", "nr-ch1-stock", "ch1"),
    ("CloudHub 2.0\nDatadog", "dd-ch2-batch", "dd-ch2-stock", "ch2"),
    ("CloudHub 2.0\nNew Relic", "nr-ch2-batch", "nr-ch2-stock", "ch2"),
]
PLATFORM_SPLIT = 2  # divider between index 1 and 2 (CH1 above, CH2 below)
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


def save(fig, stem):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    png = OUT_DIR / f"{stem}.png"
    svg = OUT_DIR / f"{stem}.svg"
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


# ===========================================================================
# Figure 1 — sustained throughput (Test 3, 5 min), grouped horizontal bars.
# ===========================================================================
def figure_throughput(label="z5m"):
    labels = [p[0] for p in PAIRS]
    batch = [load(label, p[1])["reqsec"] for p in PAIRS]
    stock = [load(label, p[2])["reqsec"] for p in PAIRS]

    y = list(range(len(PAIRS)))[::-1]
    bar_h = 0.36
    fig, ax = plt.subplots(figsize=(10.2, 6.6))

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
        ax.text(max(s, b) + 14, pos, f"{ratio:.0f}×", va="center", ha="left",
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
                 fontsize=18, fontweight="bold", x=0.045, ha="left", y=1.005)
    fig.text(0.045, 0.945,
             "5-min run · count=20 log events/request · CH1 -c 10, CH2 -c 5 "
             "· compare within a plane, not across",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    ax.legend(handles=legend_handles(), loc="lower right", frameon=False,
              ncol=1, handlelength=1.9)
    fig.text(0.045, -0.02,
             "× = batch÷stock throughput ratio within that plane+vendor. Concurrency differs by plane "
             "(CH1 -c 10, CH2 -c 5), so\nread each plane's contrast on its own — a CH1 bar is not "
             "comparable to a CH2 bar.", fontsize=10.5, color=MUTED, ha="left", va="top")
    save(fig, "cloudhub-throughput-batch-vs-stock")


# ===========================================================================
# Figure 2 — latency (Test 3), avg / p90 / p99, log axis, stacked per pair.
# ===========================================================================
def figure_latency(label="z5m"):
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.4, 12.8), sharex=True)
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
    fig.text(0.052, 0.973,
             "count=20 log events/request · CH1 -c 10, CH2 -c 5 · compare within a plane",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=legend_handles(), loc="upper left", bbox_to_anchor=(0.048, 0.955),
               frameon=False, ncol=2, columnspacing=2.4, handlelength=1.9)
    fig.text(0.052, 0.008,
             "Logarithmic axis: every gridline is 10× the one before it — read the printed numbers, "
             "not the bar lengths.\nThe true gap is larger than the bars look.",
             fontsize=10.5, color=MUTED, ha="left", va="top")
    fig.tight_layout(rect=(0, 0.03, 1, 0.95))
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
def figure_latency_range(label="z5m"):
    metrics = ["Average", "p90", "p99"]
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.2, 12.6), sharex=True)

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
            stock, batched = vals[metric]
            pos = y[idx]

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

        ax.set_yticks(y)
        ax.set_yticklabels(metrics)
        ax.set_xscale("log")
        ax.set_xlim(20, 200_000)
        ax.set_ylim(-0.55, len(metrics) - 0.30)
        ax.xaxis.set_major_formatter(FuncFormatter(thousands))
        ax.grid(axis="x", color=GRID, linewidth=0.9, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right", "left"):
            ax.spines[side].set_visible(False)
        ax.tick_params(axis="y", length=0)
        ax.set_title(pair[0].replace("\n", " — "), loc="left", fontweight="bold", pad=10)

    axes[-1].set_xlabel("Latency in milliseconds — logarithmic scale (lower is better)", labelpad=10)

    handles = [
        Line2D([], [], marker="o", markersize=13, linestyle="none", color=STOCK_FILL,
               markeredgecolor=STOCK_EDGE, markeredgewidth=1.6, label=STOCK_LABEL),
        Line2D([], [], marker="s", markersize=12, linestyle="none", color=BATCH_FILL,
               markeredgecolor=BATCH_EDGE, markeredgewidth=1.6, label=BATCH_LABEL),
    ]

    fig.suptitle("Request latency: stock appender vs batch appender (5-min run)",
                 fontsize=19, fontweight="bold", x=0.052, ha="left", y=0.990)
    fig.text(0.052, 0.963,
             "count=20 log events/request · CH1 -c 10, CH2 -c 5 · compare within a plane",
             fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(handles=handles, loc="upper left", bbox_to_anchor=(0.048, 0.943),
               frameon=False, ncol=2, columnspacing=2.4, handlelength=1.6)
    fig.text(0.052, 0.016,
             "Each marker is a measured value and the bar between them is the improvement. "
             "Logarithmic axis: every gridline is\n10× the one before it, which compresses the "
             "difference — the true gap is larger than it looks.\nPanels either side of the dashed "
             "divider ran on different CPU models — a burstable CloudHub 1.0 worker versus a\n"
             "hard-capped CloudHub 2.0 replica, at different concurrency (CH1 -c 10, CH2 -c 5) — so "
             "they are not a controlled\ncomparison of each other. gzip and disk spill were off, so "
             "this measures batching in isolation, not the appender\nas deployed. Sustained 5-minute "
             "run (Test 3), one run per configuration, 2026-08-04.",
             fontsize=10.5, color=MUTED, ha="left", va="bottom", linespacing=1.5)

    fig.tight_layout(rect=(0.02, 0.108, 0.98, 0.920))
    _draw_platform_divider(fig, axes)
    save(fig, "cloudhub-latency-batch-vs-stock-range")


# ===========================================================================
# Figure 3 — the cold-start / warmup story. For each BATCH app, req/s in
# Test 1 (1m) vs churn (1m, warm) vs Test 3 (5m). Shows Test1 < churn < Test3,
# i.e. the 1-min Test-1 number is dragged down by cold-start ramp.
# ===========================================================================
def figure_warmup():
    apps = [(p[0], p[1], p[3]) for p in PAIRS]  # batch apps only
    phases = [("Test 1 (1 min)", "z1m"), ("Churn (1 min, warm)", "z1m_churn"),
              ("Test 3 (5 min)", "z5m")]
    fills = ["#9ECAE1", "#4292C6", "#08519C"]  # light->dark blue ramp

    n = len(apps)
    x = list(range(n))
    group_w = 0.8
    bw = group_w / len(phases)
    fig, ax = plt.subplots(figsize=(10.6, 6.4))

    for pi, (pname, plabel) in enumerate(phases):
        vals = [load(plabel, a[1])["reqsec"] for a in apps]
        offs = [xi - group_w / 2 + bw / 2 + pi * bw for xi in x]
        ax.bar(offs, vals, width=bw * 0.94, color=fills[pi], edgecolor=INK,
               linewidth=0.8, label=pname, zorder=3)
        for ox, v in zip(offs, vals):
            ax.text(ox, v + max(vals) * 0.015, f"{v:,.0f}", ha="center", va="bottom",
                    fontsize=10.5, fontweight="bold", color=INK, zorder=4)

    ax.set_xticks(x)
    ax.set_xticklabels([a[0] for a in apps])  # keep the plane\nvendor two-line form
    ax.set_ylabel("Requests / second (batch appender)", labelpad=10)
    ax.grid(axis="y", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.legend(frameon=False, ncol=3, loc="upper center", bbox_to_anchor=(0.5, -0.11))

    fig.suptitle("Warmup effect: the 1-minute Test 1 under-reports warm throughput",
                 fontsize=17, fontweight="bold", x=0.045, ha="left", y=1.005)
    fig.text(0.045, 0.945,
             "Batch apps only · req/s rises Test 1 → churn → Test 3 as cold-start ramp "
             "(JIT/TLS/pools) is amortised over a longer window",
             fontsize=11, color=MUTED, ha="left", va="top")
    save(fig, "cloudhub-warmup-cold-start")


def main():
    print("Reading results from:", RESULTS)
    missing = [
        f"loadtest-{lbl}/{app}.txt"
        for lbl in ("z1m", "z1m_churn", "z5m", "z5m_t30s")
        for app in [p[1] for p in PAIRS] + [p[2] for p in PAIRS]
        if not (RESULTS / f"loadtest-{lbl}" / f"{app}.txt").exists()
    ]
    if missing:
        raise SystemExit("Missing result files:\n  " + "\n  ".join(missing))
    print("Writing charts to:", OUT_DIR)
    figure_throughput()
    figure_latency()
    figure_latency_range()
    figure_warmup()
    print("done.")


if __name__ == "__main__":
    main()
