#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the CloudHub benchmark charts used by the article and docs/index.html.

Renders PNG (300 dpi) + SVG into docs/img/.

    python3 -m venv .venv-charts
    .venv-charts/bin/pip install -r docs/charts/requirements.txt
    .venv-charts/bin/python docs/charts/generate_benchmark_charts.py
"""

import os
import pathlib
import tempfile

os.environ.setdefault("MPLCONFIGDIR", tempfile.mkdtemp(prefix="mplconfig-"))

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter

# ---------------------------------------------------------------------------
# Measurements. Source of truth: README.md, section "Measured on CloudHub (real
# Datadog and New Relic intake)" — the same figures also appear in
# docs/index.html. Four vendor-and-platform pairs measured 2026-08-03: two
# paired-parallel passes of oha --no-tui -z 1m -c 5 against /log?count=15 (17 log
# events per request) over plain http, after a 5-request warmup and a 10 s pause,
# 0.1 vCore on both platforms. Each phase drove one Datadog app and one New Relic
# app concurrently, so no vendor's intake was ever loaded by two apps at once.
# All eight runs completed the full 60 s at 100% success.
#
# READING CAVEAT. One run per pair, no median — directional, not established.
# Roughly 2x run-to-run variance has been observed on CloudHub 1.0 across
# repeated passes, and the CloudHub 2.0 Datadog figure of 1.19x is inside that
# band. Little's Law (requests/sec × mean latency ≈ concurrency) puts all eight
# runs at the concurrency of 5 to within 0.1–16%, so the load parameters are
# corroborated by the results rather than merely asserted. Latency for three runs
# (the CloudHub 1.0 stock pair and the CloudHub 2.0 New Relic stock run) carries a
# x10 correction for an oha 1.14.0 unit-selection defect; the figures below are
# already corrected. gzip and spillEnabled were off in the demos, so this is
# batching in isolation, not the appender as deployed. The two platforms have
# different CPU models — burstable versus hard-capped — so each pair is a valid
# batch-vs-stock contrast within its own plane; reading one plane against the
# other is not a controlled comparison.
# ---------------------------------------------------------------------------

TEST_CONDITIONS = (
    "0.1 vCore \u00b7 oha -c 5 \u00b7 60 s \u00b7 /log?count=15 (17 events/request) "
    "\u00b7 compare within a platform, not across"
)

# Four vendor-and-platform pairs, grouped by platform so the platform divider can
# be drawn between index 1 and index 2.
PAIRS = [
    {
        "platform": "CloudHub 1.0",
        "vendor": "Datadog",
        "subtitle": "CloudHub 1.0 \u00b7 Datadog \u2014 0.1 vCore worker",
        "axis_label": "CloudHub 1.0\nDatadog",
        # latency in milliseconds: (stock, batched)
        "latency": {
            "Average": (774, 50),
            "p90": (2460, 79),
            "p99": (9492, 235),
        },
        "requests_per_sec": (6.23, 99.54),
        "requests_in_60s": (369, 5968),
        "success_rate": (100.0, 100.0),
        "throughput_multiplier": "15.97\u00d7",
    },
    {
        "platform": "CloudHub 1.0",
        "vendor": "New Relic",
        "subtitle": "CloudHub 1.0 \u00b7 New Relic \u2014 0.1 vCore worker",
        "axis_label": "CloudHub 1.0\nNew Relic",
        "latency": {
            "Average": (893, 59),
            "p90": (238, 101),
            "p99": (19627, 265),
        },
        "requests_per_sec": (4.68, 84.96),
        "requests_in_60s": (276, 5093),
        "success_rate": (100.0, 100.0),
        "throughput_multiplier": "18.14\u00d7",
    },
    {
        "platform": "CloudHub 2.0",
        "vendor": "Datadog",
        "subtitle": "CloudHub 2.0 \u00b7 Datadog \u2014 0.1 vCore replica",
        "axis_label": "CloudHub 2.0\nDatadog",
        "latency": {
            "Average": (281, 236),
            "p90": (471, 390),
            "p99": (1105, 678),
        },
        "requests_per_sec": (17.86, 21.27),
        "requests_in_60s": (1067, 1272),
        "success_rate": (100.0, 100.0),
        "throughput_multiplier": "1.19\u00d7",
    },
    {
        "platform": "CloudHub 2.0",
        "vendor": "New Relic",
        "subtitle": "CloudHub 2.0 \u00b7 New Relic \u2014 0.1 vCore replica",
        "axis_label": "CloudHub 2.0\nNew Relic",
        "latency": {
            "Average": (1028, 304),
            "p90": (1009, 485),
            "p99": (22036, 1474),
        },
        "requests_per_sec": (4.30, 16.44),
        "requests_in_60s": (253, 982),
        "success_rate": (100.0, 100.0),
        "throughput_multiplier": "3.82\u00d7",
    },
]

# Index of the first pair on the second platform — where the divider goes.
PLATFORM_SPLIT = next(
    i for i, pair in enumerate(PAIRS) if pair["platform"] != PAIRS[0]["platform"]
)

# ---------------------------------------------------------------------------
# Styling. Okabe-Ito blue/orange: colour-blind safe, no red/green, and the two
# series also differ in lightness and carry a hatch so colour is never the only
# channel distinguishing them.
# ---------------------------------------------------------------------------

STOCK_FILL = "#0072B2"
STOCK_EDGE = "#023E5F"
BATCH_FILL = "#E69F00"
BATCH_EDGE = "#7A5400"

INK = "#1A1A1A"
MUTED = "#4D4D4D"
GRID = "#C9C9C9"

STOCK_LABEL = "Stock HttpAppender"
BATCH_LABEL = "Batch appender 0.1.3"

DPI = 300
OUT_DIR = pathlib.Path(__file__).resolve().parents[1] / "img"

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
    """Write PNG + SVG and report the PNG's pixel dimensions."""
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    png = OUT_DIR / f"{stem}.png"
    svg = OUT_DIR / f"{stem}.svg"
    fig.savefig(png, dpi=DPI)
    fig.savefig(svg)
    plt.close(fig)
    width_px = round(fig.get_figwidth() * DPI)
    height_px = round(fig.get_figheight() * DPI)
    print(f"  {png}  ({width_px} x {height_px} px @ {DPI} dpi)")
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


def draw_platform_divider(fig, axes):
    """Dashed rule between the two platform groups of stacked panels.

    The panels either side of it are separate experiments on different CPU models,
    so the divider carries the same "not a controlled comparison" meaning as the
    vertical one in the throughput chart. Call after tight_layout, since it reads
    the final axes positions.
    """
    above = axes[PLATFORM_SPLIT - 1].get_position().y0
    below = axes[PLATFORM_SPLIT].get_position().y1
    y = (above + below) / 2
    fig.add_artist(
        Line2D(
            [0.045, 0.975],
            [y, y],
            color=GRID,
            linewidth=1.2,
            linestyle=(0, (4, 4)),
            zorder=0,
        )
    )


# ---------------------------------------------------------------------------
# Figure 1 — latency, grouped bars on a shared logarithmic axis.
# ---------------------------------------------------------------------------


def figure_latency_bars():
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.2, 13.4), sharex=True)
    bar_h = 0.34

    for ax, pair in zip(axes, PAIRS):
        metrics = list(pair["latency"].keys())
        y = list(range(len(metrics)))[::-1]  # Average at the top

        for idx, metric in enumerate(metrics):
            stock, batched = pair["latency"][metric]
            pos = y[idx]

            ax.barh(
                pos + bar_h / 2 + 0.02,
                stock,
                height=bar_h,
                color=STOCK_FILL,
                edgecolor=STOCK_EDGE,
                linewidth=1.2,
                zorder=3,
            )
            ax.barh(
                pos - bar_h / 2 - 0.02,
                batched,
                height=bar_h,
                color=BATCH_FILL,
                edgecolor=BATCH_EDGE,
                linewidth=1.2,
                hatch="///",
                zorder=3,
            )

            ax.text(
                stock * 1.13,
                pos + bar_h / 2 + 0.02,
                f"{stock:,} ms",
                va="center",
                ha="left",
                fontsize=12.5,
                fontweight="bold",
                color=INK,
                zorder=4,
            )
            ax.text(
                batched * 1.13,
                pos - bar_h / 2 - 0.02,
                f"{batched:,} ms",
                va="center",
                ha="left",
                fontsize=12.5,
                fontweight="bold",
                color=INK,
                zorder=4,
            )

        ax.set_yticks(y)
        ax.set_yticklabels(metrics)
        ax.set_xscale("log")
        ax.set_xlim(10, 200_000)
        ax.set_ylim(-0.62, len(metrics) - 0.38)
        ax.xaxis.set_major_formatter(FuncFormatter(thousands))
        ax.grid(axis="x", color=GRID, linewidth=0.9, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        ax.set_title(pair["subtitle"], loc="left", fontweight="bold", pad=10)

    axes[-1].set_xlabel("Latency in milliseconds \u2014 logarithmic scale (lower is better)", labelpad=10)

    fig.suptitle(
        "Request latency: stock appender vs batch appender",
        fontsize=19,
        fontweight="bold",
        x=0.052,
        ha="left",
        y=0.990,
    )
    fig.text(
        0.052,
        0.965,
        TEST_CONDITIONS,
        fontsize=11.5,
        color=MUTED,
        ha="left",
        va="top",
    )
    fig.legend(
        handles=legend_handles(),
        loc="upper left",
        bbox_to_anchor=(0.048, 0.946),
        frameon=False,
        ncol=2,
        columnspacing=2.4,
        handlelength=1.9,
    )
    fig.text(
        0.052,
        0.016,
        "Logarithmic axis: every gridline is 10\u00d7 the one before it, so bar lengths are not proportional to the values \u2014 read\n"
        "the printed numbers. A log axis compresses the difference; the true gap is larger than the bars look.\n"
        "One run per pair, no median. Panels either side of the dashed divider ran on different CPU models \u2014 a burstable\n"
        "CloudHub 1.0 worker versus a hard-capped CloudHub 2.0 replica \u2014 so they are not a controlled comparison of\n"
        "each other. gzip and disk spill were off, so this measures batching in isolation, not the appender as deployed.\n"
        "Source: batch-http-log-appender repository, README.md \u2014 single measured run per configuration, 2026-08-03.",
        fontsize=10.5,
        color=MUTED,
        ha="left",
        va="bottom",
        linespacing=1.5,
    )

    fig.tight_layout(rect=(0.02, 0.105, 0.98, 0.925))
    draw_platform_divider(fig, axes)
    save(fig, "cloudhub-latency-batch-vs-stock")


# ---------------------------------------------------------------------------
# Figure 1b — the same latency data as a range (dumbbell) plot. No bar baseline,
# so the logarithmic axis carries no "length is meaningless" caveat.
# ---------------------------------------------------------------------------


def figure_latency_range():
    fig, axes = plt.subplots(len(PAIRS), 1, figsize=(9.2, 12.6), sharex=True)

    for ax, pair in zip(axes, PAIRS):
        metrics = list(pair["latency"].keys())
        y = list(range(len(metrics)))[::-1]

        for idx, metric in enumerate(metrics):
            stock, batched = pair["latency"][metric]
            pos = y[idx]

            ax.plot(
                [batched, stock],
                [pos, pos],
                color="#9A9A9A",
                linewidth=3.0,
                solid_capstyle="round",
                zorder=2,
            )
            ax.plot(
                stock,
                pos,
                marker="o",
                markersize=15,
                color=STOCK_FILL,
                markeredgecolor=STOCK_EDGE,
                markeredgewidth=1.6,
                zorder=3,
            )
            ax.plot(
                batched,
                pos,
                marker="s",
                markersize=14,
                color=BATCH_FILL,
                markeredgecolor=BATCH_EDGE,
                markeredgewidth=1.6,
                zorder=3,
            )

            ax.text(
                batched * 0.82,
                pos,
                f"{batched:,} ms",
                va="center",
                ha="right",
                fontsize=12.5,
                fontweight="bold",
                color=INK,
            )
            ax.text(
                stock * 1.22,
                pos,
                f"{stock:,} ms",
                va="center",
                ha="left",
                fontsize=12.5,
                fontweight="bold",
                color=INK,
            )
            ax.text(
                (batched * stock) ** 0.5,
                pos + 0.30,
                f"{stock / batched:.1f}\u00d7 lower",
                va="bottom",
                ha="center",
                fontsize=11.5,
                color=MUTED,
                style="italic",
            )

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
        ax.set_title(pair["subtitle"], loc="left", fontweight="bold", pad=10)

    axes[-1].set_xlabel("Latency in milliseconds \u2014 logarithmic scale (lower is better)", labelpad=10)

    handles = [
        Line2D(
            [], [], marker="o", markersize=13, linestyle="none",
            color=STOCK_FILL, markeredgecolor=STOCK_EDGE, markeredgewidth=1.6, label=STOCK_LABEL,
        ),
        Line2D(
            [], [], marker="s", markersize=12, linestyle="none",
            color=BATCH_FILL, markeredgecolor=BATCH_EDGE, markeredgewidth=1.6, label=BATCH_LABEL,
        ),
    ]

    fig.suptitle(
        "Request latency: stock appender vs batch appender",
        fontsize=19,
        fontweight="bold",
        x=0.052,
        ha="left",
        y=0.990,
    )
    fig.text(0.052, 0.963, TEST_CONDITIONS, fontsize=11.5, color=MUTED, ha="left", va="top")
    fig.legend(
        handles=handles,
        loc="upper left",
        bbox_to_anchor=(0.048, 0.943),
        frameon=False,
        ncol=2,
        columnspacing=2.4,
        handlelength=1.6,
    )
    fig.text(
        0.052,
        0.016,
        "Each marker is a measured value and the bar between them is the improvement. Logarithmic axis: every gridline is\n"
        "10\u00d7 the one before it, which compresses the difference \u2014 the true gap is larger than it looks.\n"
        "One run per pair, no median. Panels either side of the dashed divider ran on different CPU models \u2014 a burstable\n"
        "CloudHub 1.0 worker versus a hard-capped CloudHub 2.0 replica \u2014 so they are not a controlled comparison of\n"
        "each other. gzip and disk spill were off, so this measures batching in isolation, not the appender as deployed.\n"
        "Source: batch-http-log-appender repository, README.md \u2014 single measured run per configuration, 2026-08-03.",
        fontsize=10.5,
        color=MUTED,
        ha="left",
        va="bottom",
        linespacing=1.5,
    )

    fig.tight_layout(rect=(0.02, 0.108, 0.98, 0.920))
    draw_platform_divider(fig, axes)
    save(fig, "cloudhub-latency-batch-vs-stock-range")


# ---------------------------------------------------------------------------
# Figure 2 — throughput. Linear axis from zero; the success rate rides along as
# an annotation rather than a weak chart of its own.
# ---------------------------------------------------------------------------


def figure_throughput():
    fig, ax = plt.subplots(figsize=(11.4, 6.6))

    group_x = [0.0, 1.15, 2.60, 3.75]
    width = 0.30
    offset = 0.175

    for gx, pair in zip(group_x, PAIRS):
        stock, batched = pair["requests_per_sec"]

        ax.bar(
            gx - offset, stock, width=width,
            color=STOCK_FILL, edgecolor=STOCK_EDGE, linewidth=1.2, zorder=3,
        )
        ax.bar(
            gx + offset, batched, width=width,
            color=BATCH_FILL, edgecolor=BATCH_EDGE, linewidth=1.2, hatch="///", zorder=3,
        )

        for x, value in ((gx - offset, stock), (gx + offset, batched)):
            ax.text(
                x, value + 1.6, f"{value:.2f}",
                ha="center", va="bottom", fontsize=14, fontweight="bold", color=INK, zorder=4,
            )

        # Multiplier bracket spanning the pair.
        top = max(stock, batched)
        bracket_y = top + 14.0
        ax.plot(
            [gx - offset, gx - offset, gx + offset, gx + offset],
            [top + 9.5, bracket_y, bracket_y, top + 9.5],
            color=MUTED, linewidth=1.4, zorder=3,
        )
        # Four groups leave no room for a sentence per bracket, so the bracket carries the
        # multiplier alone and the footnote says what it means.
        ax.text(
            gx, bracket_y + 1.8,
            pair["throughput_multiplier"],
            ha="center", va="bottom", fontsize=14, fontweight="bold", color=INK, zorder=4,
        )

    # The two platform groups are separate experiments on different CPU models sharing an axis
    # for convenience, not a controlled comparison. The divider says so visually; the footnote
    # says so in words.
    divider_x = (group_x[PLATFORM_SPLIT - 1] + group_x[PLATFORM_SPLIT]) / 2
    ax.axvline(divider_x, color=GRID, linewidth=1.2, linestyle=(0, (4, 4)), zorder=1)

    ax.set_xticks(group_x)
    ax.set_xticklabels([p["axis_label"] for p in PAIRS], fontsize=13.0, fontweight="bold")
    ax.set_xlim(-0.55, 4.30)
    ax.set_ylim(0, 125)
    ax.set_ylabel("Requests per second (higher is better)", labelpad=10)
    ax.grid(axis="y", color=GRID, linewidth=0.9, zorder=0)
    ax.set_axisbelow(True)
    ax.tick_params(axis="x", length=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)

    fig.suptitle(
        "Throughput: stock appender vs batch appender",
        fontsize=19,
        fontweight="bold",
        x=0.052,
        ha="left",
        y=0.982,
    )
    fig.text(0.042, 0.922, TEST_CONDITIONS, fontsize=12.0, color=MUTED, ha="left", va="top")
    fig.legend(
        handles=legend_handles(),
        loc="upper left",
        bbox_to_anchor=(0.039, 0.878),
        frameon=False,
        ncol=2,
        columnspacing=2.4,
        handlelength=1.9,
    )
    fig.text(
        0.042,
        0.030,
        "The bracket over each pair is the batch-vs-stock throughput multiplier. Linear axis starting at zero. Throughput is a\n"
        "restatement of mean latency, not a second finding: the load is closed-loop, so requests per second \u2248 5 \u00f7 mean\n"
        "latency. All eight runs completed the full 60 s at 100% success.\n"
        "One run per pair, no median; roughly 2\u00d7 run-to-run variance has been observed on CloudHub 1.0, and the 1.19\u00d7\n"
        "here is inside that band. The two platforms either side of the divider ran on different CPU models \u2014 a burstable\n"
        "CloudHub 1.0 worker versus a hard-capped CloudHub 2.0 replica \u2014 so they are not a controlled comparison.\n"
        "Source: batch-http-log-appender repository, README.md \u2014 single measured run per configuration, 2026-08-03.",
        fontsize=10.5,
        color=MUTED,
        ha="left",
        va="bottom",
        linespacing=1.5,
    )

    fig.tight_layout(rect=(0.02, 0.235, 0.98, 0.812))
    save(fig, "cloudhub-throughput-batch-vs-stock")


if __name__ == "__main__":
    print("Writing benchmark charts to", OUT_DIR)
    figure_latency_bars()
    figure_latency_range()
    figure_throughput()
