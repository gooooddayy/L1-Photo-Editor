# -*- coding: utf-8 -*-
# L1 图片编辑器 APK 图标 = PC 网页 logo（照片图形：圆角矩形边框 + 圆点 + 山形）白线绘制在主题绿圆角方块上
import zlib, struct

S = 192
px = [[None] * S for _ in range(S)]
ACC = (31, 167, 95, 255)    # #1FA75F
ACC_D = (26, 146, 82, 255)  # 底部微暗，制造轻微层次
WHITE = (255, 255, 255, 255)

def set_px(x, y, c):
    if 0 <= x < S and 0 <= y < S:
        px[y][x] = c

def in_rounded(x, y, x0, y0, x1, y1, r):
    if not (x0 <= x < x1 and y0 <= y < y1):
        return False
    for cx, cy in ((x0 + r, y0 + r), (x1 - r - 1, y0 + r), (x0 + r, y1 - r - 1), (x1 - r - 1, y1 - r - 1)):
        in_corner_x = x < x0 + r or x >= x1 - r
        in_corner_y = y < y0 + r or y >= y1 - r
        if in_corner_x and in_corner_y:
            dx = x - (x1 - r - 1 if x >= x1 - r else x0 + r)
            dy = y - (y1 - r - 1 if y >= y1 - r else y0 + r)
            if dx * dx + dy * dy > r * r:
                return False
    return True

# 1) 背景：绿色圆角方块（带纵向微渐变）
for y in range(S):
    for x in range(S):
        if in_rounded(x, y, 0, 0, S, S, 40):
            t = y / S
            col = tuple(round(ACC[i] + (ACC_D[i] - ACC[i]) * t) for i in range(3)) + (255,)
            px[y][x] = col

# 2) 白色照片图形（网页 logo：<rect rx2/><circle/><path 山形>）
#    照片外框：圆角矩形描边（外圈白，内圈透回绿 = 描边效果）
OX0, OY0, OX1, OY1, OR = 42, 46, 150, 146, 14
for y in range(OY0 - 7, OY1 + 7):
    for x in range(OX0 - 7, OX1 + 7):
        if in_rounded(x, y, OX0 - 7, OY0 - 7, OX1 + 7, OY1 + 7, OR + 7) and \
           not in_rounded(x, y, OX0, OY0, OX1, OY1, OR):
            set_px(x, y, WHITE)

# 太阳圆点（左上）
for y in range(S):
    for x in range(S):
        dx, dy = x - 68, y - 76
        if dx * dx + dy * dy <= 9 * 9:
            set_px(x, y, WHITE)

# 山形：两条折线勾边（网页 path m21 15-4.5-4.5L7 20）→ 实心山
def fill_tri(x0, y0, x1, y1, x2, y2):
    minx, miny = min(x0, x1, x2), min(y0, y1, y2)
    maxx, maxy = max(x0, x1, x2), max(y0, y1, y2)
    def sign(ax, ay, bx, by, cx, cy):
        return (cx - bx) * (ay - by) - (ax - bx) * (cy - by)
    for y in range(int(miny), int(maxy) + 1):
        for x in range(int(minx), int(maxx) + 1):
            d1 = sign(x0, y0, x1, y1, x, y)
            d2 = sign(x1, y1, x2, y2, x, y)
            d3 = sign(x2, y2, x0, y0, x, y)
            neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
            pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
            if not (neg and pos):
                # 只画外框内部、且在照片外框以内
                if in_rounded(x, y, OX0 + 4, OY0 + 4, OX1 - 4, OY1 - 4, OR):
                    set_px(x, y, WHITE)

# 大山（左）与 小山（右），底部对齐照片下缘
fill_tri(56, 132, 90, 96, 118, 132)
fill_tri(104, 132, 124, 110, 140, 132)

# 3) PNG 编码
raw = b''
for y in range(S):
    raw += b'\x00' + b''.join(struct.pack('4B', *(px[y][x] or (0, 0, 0, 0))) for x in range(S))

def chunk(t, d):
    c = t + d
    return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0)) \
    + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
open('app/src/main/res/drawable/ic_launcher.png', 'wb').write(png)
print('icon ok', len(png))
