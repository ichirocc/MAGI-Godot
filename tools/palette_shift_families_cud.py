#!/usr/bin/env python3
"""[3.544.0/ユーザー指示] ユーザー提示の36色シフト家族パレット（早番/日勤/時短パート/遅番/夜勤の
5段階グラデーション＋特別枠(非稼働・特命)6区分）を、**見た目の意図（色相・並び・淡→濃の方向）は
保ったまま**、P型/D型二色覚シミュレーション後にペアが潰れないよう微調整する。

各色は元の値を中心にした狭い範囲（ヒュー±14°・彩度±0.12・明度±0.10）でしか動かさない＝
「色相/区分は保ちつつ微調整」というユーザー指示を尊重する制約。
"""
import colorsys
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cud_colors as cc

random.seed(20260915)

# ユーザー提示の原案（6家族×6区分）。
ORIGINAL = {
    "early": ["#FFFFFF", "#FFF9C4", "#FFF176", "#FFE082", "#FFCA28", "#FFA000"],
    "day": ["#F0F8FF", "#E3F2FD", "#BBDEFB", "#90CAF9", "#42A5F5", "#0D47A1"],
    "short": ["#F1F8E9", "#E8F5E9", "#C8E6C9", "#A5D6A7", "#4CAF50", "#1B5E20"],
    "late": ["#FAF5FF", "#F3E5F5", "#E1BEE7", "#CE93D8", "#9C27B0", "#4A148C"],
    "night": ["#E8EAF6", "#C5CAE9", "#9FA8DA", "#5C6BC0", "#3949AB", "#1A237E"],
    "special": ["#EEEEEE", "#FCE4EC", "#E0F2F1", "#FFF3E0", "#EDE7F6", "#374151"],
}
FAMILY_ORDER = ["early", "day", "short", "late", "night", "special"]
N_PER = 6

# [制約] 各色は原案から大きく離さない。最も潰れやすいのは最淡(index0-1)の白に近い色なので、
# その2つだけ許容レンジをやや広げる（白へ寄りすぎない下限彩度を追加で確保）。
HUE_RADIUS = 14.0
SAT_RADIUS = 0.12
LIGHT_RADIUS = 0.10
MIN_SAT_PALE = 0.06  # index0-1(最淡)は彩度をこの下限以上に保つ＝「ほぼ白」同士の衝突を避ける


def hex_to_hsl(h):
    r, g, b = cc.hex_to_rgb(h)
    hh, li, s = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    return hh * 360.0, s, li


def hsl_to_hex(h, s, li):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, li, s)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def bounded_random(fam, idx):
    h0, s0, l0 = hex_to_hsl(ORIGINAL[fam][idx])
    min_sat = MIN_SAT_PALE if idx <= 1 else 0.0
    h = h0 + random.uniform(-HUE_RADIUS, HUE_RADIUS)
    s = clamp(s0 + random.uniform(-SAT_RADIUS, SAT_RADIUS), max(0.0, min_sat), 1.0)
    li = clamp(l0 + random.uniform(-LIGHT_RADIUS, LIGHT_RADIUS), 0.06, 0.94)
    return hsl_to_hex(h, s, li)


def bounded_jitter(fam, idx, cur_hex, scale):
    h0, s0, l0 = hex_to_hsl(ORIGINAL[fam][idx])
    hc, sc, lc = hex_to_hsl(cur_hex)
    min_sat = MIN_SAT_PALE if idx <= 1 else 0.0
    for _ in range(40):
        h = clamp(hc + random.uniform(-scale, scale) * 8, h0 - HUE_RADIUS, h0 + HUE_RADIUS)
        s = clamp(sc + random.uniform(-scale, scale) * 0.15, max(0.0, s0 - SAT_RADIUS, min_sat), min(1.0, s0 + SAT_RADIUS))
        li = clamp(lc + random.uniform(-scale, scale) * 0.12, max(0.06, l0 - LIGHT_RADIUS), min(0.94, l0 + LIGHT_RADIUS))
        return hsl_to_hex(h, s, li)
    return cur_hex


def optimize():
    keys = [(f, i) for f in FAMILY_ORDER for i in range(N_PER)]
    vals = [ORIGINAL[f][i] for f, i in keys]
    n = len(keys)
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
    ITERS = 15000
    for it in range(ITERS):
        idx = random.randrange(n)
        fam, fi = keys[idx]
        old = vals[idx]
        old_row = dist[idx][:]
        progress = it / ITERS
        scale = max(0.05, 1.0 - progress)
        threshold = max(0.0, (1.0 - progress) ** 2 * 1.2)
        cand = bounded_jitter(fam, fi, old, scale) if random.random() < 0.85 else bounded_random(fam, fi)
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
    return keys, best_vals, best_score


def main():
    best = None
    for restart in range(3):
        random.seed(20260915 + restart)
        keys, vals, score = optimize()
        print(f"# restart {restart}: {score:.2f}", file=sys.stderr)
        if best is None or score > best[2]:
            best = (keys, vals, score)
    keys, vals, score = best
    result = {f: [None] * N_PER for f in FAMILY_ORDER}
    for (f, i), v in zip(keys, vals):
        result[f][i] = v
    print(f"# 最終 worst-case 最小 ΔE = {score:.2f}\n")
    for f in FAMILY_ORDER:
        print(f"# {f}: " + " ".join(f"{o}->{n}" for o, n in zip(ORIGINAL[f], result[f])))
    print()
    for f in FAMILY_ORDER:
        print(f'"{f}" to listOf(' + ", ".join(f'"{c}"' for c in result[f]) + "),")
    flat = [result[f][i] for f in FAMILY_ORDER for i in range(N_PER)]
    worst, pair = cc.min_pairwise_worst_case(flat)
    print(f"\n# worst remaining pair: {flat[pair[0]]} x {flat[pair[1]]}  ΔE={worst:.2f}")


if __name__ == "__main__":
    main()
