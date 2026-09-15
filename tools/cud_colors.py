"""色覚多様性（CUD）距離計算の共有ロジック。

P型（1型/protanopia）・D型（2型/deuteranopia）二色覚をシミュレートしたうえで CIE Lab 空間の
ΔE（CIE76、ユークリッド距離）を求める。design_lint.py の P12 検査と、パレット再設計スクリプト
（tools/palette_cud_redesign.py）の両方がこのモジュールを共有する＝計算式の二重管理を避ける。

シミュレーション行列は Machado, Oliveira & Fernandes (2009) の線形近似（severity=1.0=完全な
二色覚）。線形化 sRGB 上で乗算する簡便な手法で、Brettel/Viénot のような基準平面への射影より
軽量だが実務上の判別性チェックには十分。
"""
from __future__ import annotations

import math

# Machado et al. 2009, Table 1（severity=1.0 の行列。線形 RGB に対して乗算する）。
_PROTAN = (
    (0.152286, 1.052583, -0.204868),
    (0.114503, 0.786281, 0.099216),
    (-0.003882, -0.048116, 1.051998),
)
_DEUTAN = (
    (0.367322, 0.860646, -0.227968),
    (0.280085, 0.672501, 0.047413),
    (-0.011820, 0.042940, 0.968881),
)

# sRGB -> XYZ（D65 基準）の標準行列。
_RGB_TO_XYZ = (
    (0.4124564, 0.3575761, 0.1804375),
    (0.2126729, 0.7151522, 0.0721750),
    (0.0193339, 0.1191920, 0.9503041),
)


def hex_to_rgb(hex_str: str) -> tuple[float, float, float]:
    """"#rrggbb"/"rrggbb" -> (r,g,b) 各 0-255 の float。"""
    h = hex_str.strip().lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    v = int(h, 16)
    return ((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)


def _srgb_to_linear(c: float) -> float:
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c: float) -> float:
    c = 0.0 if c < 0.0 else (1.0 if c > 1.0 else c)
    v = c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
    return max(0.0, min(1.0, v)) * 255.0


def _apply_matrix(m, rgb_linear):
    r, g, b = rgb_linear
    return tuple(m[i][0] * r + m[i][1] * g + m[i][2] * b for i in range(3))


def simulate_cvd(hex_str: str, kind: str) -> tuple[float, float, float]:
    """hex 色を指定の二色覚（"protan"/"deutan"、"normal" はそのまま）でシミュレートし sRGB 0-255 で返す。"""
    r, g, b = hex_to_rgb(hex_str)
    if kind == "normal":
        return (r, g, b)
    lin = tuple(_srgb_to_linear(c) for c in (r, g, b))
    matrix = _PROTAN if kind == "protan" else _DEUTAN
    sim_lin = _apply_matrix(matrix, lin)
    return tuple(_linear_to_srgb(c) for c in sim_lin)


def _rgb_to_lab(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    lin = tuple(_srgb_to_linear(c) for c in rgb)
    x, y, z = _apply_matrix(_RGB_TO_XYZ, lin)
    # D65 白色点で正規化。
    xn, yn, zn = 0.95047, 1.0, 1.08883

    def f(t: float) -> float:
        return t ** (1 / 3) if t > (6 / 29) ** 3 else t / (3 * (6 / 29) ** 2) + 4 / 29

    fx, fy, fz = f(x / xn), f(y / yn), f(z / zn)
    L = 116 * fy - 16
    a = 500 * (fx - fy)
    bb = 200 * (fy - fz)
    return (L, a, bb)


def delta_e(hex_a: str, hex_b: str, kind: str = "normal") -> float:
    """指定の色覚（既定は通常色覚）でシミュレートしたうえでの CIE76 ΔE（Lab ユークリッド距離）。"""
    lab_a = _rgb_to_lab(simulate_cvd(hex_a, kind))
    lab_b = _rgb_to_lab(simulate_cvd(hex_b, kind))
    return math.sqrt(sum((a - b) ** 2 for a, b in zip(lab_a, lab_b)))


VISION_KINDS = ("normal", "protan", "deutan")


def worst_case_delta_e(hex_a: str, hex_b: str) -> float:
    """通常色覚・P型・D型のうち**最小の ΔE**（最も見分けにくい色覚での距離）。"""
    return min(delta_e(hex_a, hex_b, kind) for kind in VISION_KINDS)


def min_pairwise_worst_case(hexes: list[str]) -> tuple[float, tuple[int, int]]:
    """色リスト全ペアの worst_case_delta_e の最小値と、そのペアの添字を返す。"""
    best = (float("inf"), (-1, -1))
    n = len(hexes)
    for i in range(n):
        for j in range(i + 1, n):
            d = worst_case_delta_e(hexes[i], hexes[j])
            if d < best[0]:
                best = (d, (i, j))
    return best
