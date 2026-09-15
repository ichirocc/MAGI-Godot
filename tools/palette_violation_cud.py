#!/usr/bin/env python3
"""[3.544.0/ユーザー指示] 違反色専用パレット（必須違反・要調整・族別違反19種のColorPickerDialog）を
生成する。シフト色パレット（tools/palette_shift_families_cud.py）とは別物＝「違反色は別パレットを
紡ぐ」というユーザー指示。家族ヒュー帯の制約が無い分、全域で P型/D型二色覚シミュレーション後の
最小 ΔE を自由に最大化できる。

固定アンカー9色（違反基準色の既定2色＋MagiAccent7色、値は不変）＋自由生成21色＝30色(6×5)。
"""
import colorsys
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cud_colors as cc

random.seed(20260915)

ANCHORS = [
    "#b71c1c",  # 必須違反(既定)
    "#f59e0b",  # 要調整(既定)
    "#3b6fd4",  # MagiAccent.blue
    "#2e9e62",  # MagiAccent.green
    "#e08a1e",  # MagiAccent.orange
    "#8a5cd1",  # MagiAccent.purple
    "#d24d89",  # MagiAccent.pink
    "#d23b34",  # MagiAccent.red
    "#8a979b",  # MagiAccent.gray
]
N_TOTAL = 30
N_FREE = N_TOTAL - len(ANCHORS)
MIN_BG_CONTRAST = 1.6


def wcag_relative_luminance(hex_color):
    r, g, b = cc.hex_to_rgb(hex_color)

    def chan(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    rl, gl, bl = chan(r), chan(g), chan(b)
    return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl


def contrast_vs_white(hex_color):
    lum = wcag_relative_luminance(hex_color)
    return 1.05 / (lum + 0.05)


def hsl_to_hex(h, s, li):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, li, s)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def random_color():
    for _ in range(200):
        h = random.uniform(0, 360)
        s = random.uniform(0.35, 0.85)
        li = random.uniform(0.32, 0.72)
        hexv = hsl_to_hex(h, s, li)
        if contrast_vs_white(hexv) >= MIN_BG_CONTRAST:
            return hexv
    return hexv


def jitter(cur_hex, scale):
    r, g, b = cc.hex_to_rgb(cur_hex)
    h0, l0, s0 = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    h0 *= 360.0
    for _ in range(50):
        h = h0 + random.uniform(-scale, scale) * 25
        s = clamp(s0 + random.uniform(-scale, scale) * 0.3, 0.3, 0.9)
        li = clamp(l0 + random.uniform(-scale, scale) * 0.25, 0.28, 0.76)
        hexv = hsl_to_hex(h, s, li)
        if contrast_vs_white(hexv) >= MIN_BG_CONTRAST:
            return hexv
    return cur_hex


def optimize():
    vals = ANCHORS + [random_color() for _ in range(N_FREE)]
    n = len(vals)
    free_start = len(ANCHORS)
    dist = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1, n):
            d = cc.worst_case_delta_e(vals[i], vals[j])
            dist[i][j] = dist[j][i] = d

    def global_min():
        best = float("inf")
        for i in range(n):
            for j in range(i + 1, n):
                if dist[i][j] < best:
                    best = dist[i][j]
        return best

    cur_score = global_min()
    best_score = cur_score
    best_vals = vals[:]
    ITERS = 12000
    for it in range(ITERS):
        idx = random.randrange(free_start, n)
        old = vals[idx]
        old_row = dist[idx][:]
        progress = it / ITERS
        scale = max(0.05, 1.0 - progress)
        threshold = max(0.0, (1.0 - progress) ** 2 * 1.5)
        cand = jitter(old, scale) if random.random() < 0.85 else random_color()
        vals[idx] = cand
        for j in range(n):
            if j == idx:
                continue
            d = cc.worst_case_delta_e(cand, vals[j])
            dist[idx][j] = dist[j][idx] = d
        score = global_min()
        if score >= cur_score - threshold:
            cur_score = score
            if score > best_score:
                best_score = score
                best_vals = vals[:]
        else:
            vals[idx] = old
            for j in range(n):
                dist[idx][j] = dist[j][idx] = old_row[j]
    return best_vals, best_score


def main():
    best = None
    for restart in range(4):
        random.seed(20260915 + restart)
        vals, score = optimize()
        print(f"# restart {restart}: {score:.2f}", file=sys.stderr)
        if best is None or score > best[1]:
            best = (vals, score)
    vals, score = best
    print(f"# 最終 worst-case 最小 ΔE = {score:.2f}\n")
    print("VIOLATION_COLOR_PALETTE = listOf(")
    for i in range(0, len(vals), 6):
        print("    " + ", ".join(f'"{c}"' for c in vals[i:i + 6]) + ",")
    print(")")
    worst, pair = cc.min_pairwise_worst_case(vals)
    print(f"\n# worst pair: {vals[pair[0]]} x {vals[pair[1]]}  ΔE={worst:.2f}")


if __name__ == "__main__":
    main()
