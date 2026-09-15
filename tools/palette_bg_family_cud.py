#!/usr/bin/env python3
"""[3.546.0/ユーザー指示「公休は背景色系にする」] シフト色パレットへ「背景色系」家族（中立なグレー系
6色、公休を含む）を新設する。既存35色（早番/日勤/時短パート/遅番/夜勤の5家族30色＋特別枠5色＝
公休を除いた有休/研修/出張/特別休暇/欠勤・突発）は**値を変えず固定**し、背景色系の6色だけを
P型/D型二色覚シミュレーション後の最小 ΔE を最大化するよう生成する。
"""
import colorsys
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cud_colors as cc

random.seed(20260916)

# 既存35色（値は不変。背景色系との衝突だけをチェックする対象）。
FIXED = [
    # 早番
    "#e8e5e5", "#ffeeca", "#f5d964", "#ffca8f", "#ffd759", "#c68b0c",
    # 日勤
    "#dceeff", "#d1e1fc", "#a1c4fa", "#7bb4f8", "#1c9bfc", "#084277",
    # 時短パート
    "#eef7e8", "#d5ead6", "#b7e2bb", "#95c09a", "#369937", "#246823",
    # 遅番
    "#ddcafd", "#e1c7e7", "#d9acd9", "#c971c6", "#d421ce", "#801cb7",
    # 夜勤
    "#d9e0ec", "#9caee2", "#8f8ccb", "#5f5db0", "#3b43b5", "#1a1498",
    # 特別枠（公休を除く5区分）
    "#fab3c5", "#e5f4f7", "#fff2b8", "#d4c3ec", "#262b2f",
]
N_BG = 6
MIN_BG_CONTRAST = 1.5


def wcag_relative_luminance(hex_color):
    r, g, b = cc.hex_to_rgb(hex_color)

    def chan(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    rl, gl, bl = chan(r), chan(g), chan(b)
    return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl


def contrast_vs_white(hex_color):
    return 1.05 / (wcag_relative_luminance(hex_color) + 0.05)


def hsl_to_hex(h, s, li):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, li, s)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


# 中立(低彩度)なグレー系。明度は最淡(公休想定)から最濃までの6段階レンジで初期化。
LIGHT_STEPS = [0.90, 0.76, 0.62, 0.48, 0.34, 0.22]


def seed_color(idx):
    h = random.uniform(0, 360)
    s = random.uniform(0.02, 0.08)
    li = clamp(LIGHT_STEPS[idx] + random.uniform(-0.04, 0.04), 0.15, 0.94)
    return hsl_to_hex(h, s, li)


def jitter(idx, cur_hex, scale):
    r, g, b = cc.hex_to_rgb(cur_hex)
    h0, l0, s0 = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    h0 *= 360.0
    for _ in range(60):
        h = h0 + random.uniform(-scale, scale) * 40
        s = clamp(s0 + random.uniform(-scale, scale) * 0.06, 0.0, 0.12)
        li = clamp(l0 + random.uniform(-scale, scale) * 0.10, max(0.12, LIGHT_STEPS[idx] - 0.12), min(0.94, LIGHT_STEPS[idx] + 0.12))
        hexv = hsl_to_hex(h, s, li)
        if contrast_vs_white(hexv) >= MIN_BG_CONTRAST or idx >= 4:
            return hexv
    return cur_hex


def optimize():
    bg = [seed_color(i) for i in range(N_BG)]
    all_colors = FIXED + bg
    n = len(all_colors)
    bg_start = len(FIXED)

    def score():
        best = float("inf")
        for i in range(n):
            for j in range(i + 1, n):
                if i < bg_start and j < bg_start:
                    continue  # 固定色どうしは既知＝背景色系がらみのペアだけ気にする
                d = cc.worst_case_delta_e(all_colors[i], all_colors[j])
                if d < best:
                    best = d
        return best

    cur = score()
    best_score = cur
    best_bg = bg[:]
    ITERS = 9000
    for it in range(ITERS):
        idx = random.randrange(N_BG)
        old = all_colors[bg_start + idx]
        progress = it / ITERS
        scale = max(0.05, 1.0 - progress)
        threshold = max(0.0, (1.0 - progress) ** 2 * 1.2)
        cand = jitter(idx, old, scale) if random.random() < 0.85 else seed_color(idx)
        all_colors[bg_start + idx] = cand
        s = score()
        if s >= cur - threshold:
            cur = s
            if s > best_score:
                best_score = s
                best_bg = all_colors[bg_start:]
        else:
            all_colors[bg_start + idx] = old
    return best_bg, best_score


def main():
    best = None
    for restart in range(4):
        random.seed(20260916 + restart)
        bg, s = optimize()
        print(f"# restart {restart}: {s:.2f}", file=sys.stderr)
        if best is None or s > best[1]:
            best = (bg, s)
    bg, score = best
    print(f"# 最終 worst-case 最小 ΔE（背景色系がらみ）= {score:.2f}\n")
    print('"bg" to listOf(' + ", ".join(f'"{c}"' for c in bg) + "),")
    all_colors = FIXED + bg
    worst, pair = cc.min_pairwise_worst_case(all_colors)
    print(f"\n# 全体 worst pair: {all_colors[pair[0]]} x {all_colors[pair[1]]}  ΔE={worst:.2f}")


if __name__ == "__main__":
    main()
