"""SunPlayer 像素风 Logo 生成器（v7，定稿）。

层次：深棕圆形徽章 → 金色太阳 → 四向粗短光芒 → 玩家头部。
逻辑网格 40x40，所有缩放用 NEAREST，保证放大后是硬像素块。
"""
from PIL import Image

GRID = 40

# ---- 徽章 ----
BG_OUT = (21, 15, 10)
BG_IN = (37, 27, 19)
BG_RIM = (250, 186, 64)

# ---- 太阳与光芒 ----
RAY = (255, 199, 74)
RAY_HI = (255, 236, 160)
SUN_LO = (196, 113, 14)
SUN_MID = (238, 157, 32)
SUN_HI = (255, 200, 80)
SUN_TOP = (255, 232, 162)

# ---- 玩家 ----
SKIN = (208, 146, 104)
SKIN_DK = (156, 97, 61)
SKIN_HI = (229, 174, 132)
HAIR = (72, 42, 24)
HAIR_HI = (104, 64, 36)
EYE = (22, 16, 12)
EYE_HI = (255, 255, 255)
MOUTH = (120, 62, 42)


def new_grid():
    return [[None] * GRID for _ in range(GRID)]


def put(g, x, y, c):
    if 0 <= x < GRID and 0 <= y < GRID:
        g[y][x] = c


def mask_circle(cx, cy, r):
    return {
        (x, y)
        for y in range(GRID)
        for x in range(GRID)
        if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r
    }


def disk(g, cx, cy, r, c):
    for (x, y) in mask_circle(cx, cy, r):
        put(g, x, y, c)


def rect(g, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(g, x, y, c)


def tri_ray(g, ox, oy, direction, base_r, tip_r, base_w):
    """正方向等腰三角光芒，尖端朝外。每向外一格宽度递减。"""
    span = tip_r - base_r + 1
    for i in range(span):
        r = base_r + i
        # 先保持两格宽一段时间再收窄，做出"腰"
        frac = i / span
        w = max(1, round(base_w * (1 - frac ** 1.6)))
        if i == 0:
            w = base_w
        half = (w - 1) // 2
        col = RAY_HI if i >= span - 2 else RAY
        for s in range(-half, half + 1):
            if direction == "up":
                put(g, ox + s, oy - r, col)
            elif direction == "down":
                put(g, ox + s, oy + r, col)
            elif direction == "left":
                put(g, ox - r, oy + s, col)
            else:
                put(g, ox + r, oy + s, col)


def draw():
    g = new_grid()
    ox = oy = 20
    cx = cy = 20.0

    # ============ 1. 圆形徽章 ============
    R = 19.4
    disk(g, cx, cy, R, BG_OUT)
    disk(g, cx, cy, R - 1.0, BG_RIM)
    disk(g, cx, cy, R - 1.9, BG_IN)

    # ============ 2. 太阳本体 ============
    disk(g, cx, cy, 9.6, SUN_LO)
    disk(g, cx, cy, 8.8, SUN_MID)
    disk(g, cx, cy, 7.9, SUN_HI)
    disk(g, cx, cy - 2.4, 5.2, SUN_TOP)

    # ============ 3. 四向光芒（基部 7 宽，尖端收到 1） ============
    for d in ("up", "down", "left", "right"):
        tri_ray(g, ox, oy, d, 8, 15, 7)

    # ============ 4. 玩家头部 12x12 ============
    hx0, hy0 = 15, 14
    hx1, hy1 = hx0 + 11, hy0 + 11  # 15..26, 14..25

    rect(g, hx0, hy0, hx1, hy1, SKIN)
    # 头发：顶部 2 行 + 左右各 1 列，到眉眼高度
    rect(g, hx0, hy0, hx1, hy0 + 1, HAIR)
    rect(g, hx0 + 1, hy0 + 1, hx1 - 1, hy0 + 1, HAIR_HI)
    rect(g, hx0, hy0, hx0, hy0 + 3, HAIR)
    rect(g, hx1, hy0, hx1, hy0 + 3, HAIR)
    # 左脸高光条
    rect(g, hx0 + 1, hy0 + 2, hx0 + 1, hy1 - 2, SKIN_HI)
    # 下巴/右颊阴影
    rect(g, hx0 + 1, hy1, hx1, hy1, SKIN_DK)
    rect(g, hx1 - 1, hy1 - 2, hx1 - 1, hy1 - 1, SKIN_DK)
    # 眼睛（2 宽 2 高，各加一颗高光）
    rect(g, 16, hy0 + 4, 17, hy0 + 5, EYE)
    rect(g, 23, hy0 + 4, 24, hy0 + 5, EYE)
    put(g, 16, hy0 + 4, EYE_HI)
    put(g, 23, hy0 + 4, EYE_HI)
    # 鼻子两格
    put(g, 20, hy0 + 7, SKIN_DK)
    put(g, 21, hy0 + 7, SKIN_DK)
    # 微笑：3 格弧线，颜色提亮，避免像一条黑杠
    put(g, 18, hy0 + 9, MOUTH)
    rect(g, 19, hy0 + 10, 22, hy0 + 10, MOUTH)
    put(g, 23, hy0 + 9, MOUTH)

    return g


def render(g, scale, path):
    img = Image.new("RGBA", (GRID, GRID), (0, 0, 0, 0))
    px = img.load()
    for y in range(GRID):
        for x in range(GRID):
            c = g[y][x]
            if c is not None:
                px[x, y] = (c[0], c[1], c[2], 255)
    out = img.resize((GRID * scale, GRID * scale), Image.NEAREST)
    out.save(path)
    return out.size


if __name__ == "__main__":
    import os
    import sys

    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(outdir, exist_ok=True)
    grid = draw()
    for scale, suffix in ((1, "40"), (2, "80"), (6, "240"), (13, "520")):
        size = render(grid, scale, os.path.join(outdir, f"v7-{suffix}.png"))
        print(f"v7-{suffix}.png -> {size}")
