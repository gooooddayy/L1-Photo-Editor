# Lucide SVG -> Android VectorDrawable 转换（stroke 风格保留）
import re, os

SRC = "_icons"
DST = "app/src/main/res/drawable"
ICONS = {
    "image-plus": "ic_import",
    "undo-2": "ic_undo",
    "history": "ic_history",
    "download": "ic_export",
    "crop": "ic_crop",
    "rotate-ccw": "ic_rotate",
    "flip-horizontal-2": "ic_mirror",
    "grip": "ic_mosaic",
    "type": "ic_text",
    "scissors": "ic_matting",
    "eraser": "ic_erase",
}

def circle_path(cx, r, cy):
    return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 -{2*r},0"

def convert(svg):
    svg = re.sub(r"<!--.*?-->", "", svg, flags=re.S)
    parts = []
    for m in re.finditer(r"<(path|circle|line|polyline)\b[^>]*>", svg):
        tag, el = m.group(1), m.group(0)
        if tag == "path":
            d = re.search(r'd="([^"]+)"', el).group(1)
        elif tag == "circle":
            cx = float(re.search(r'cx="([^"]+)"', el).group(1))
            cy = float(re.search(r'cy="([^"]+)"', el).group(1))
            r = float(re.search(r'r="([^"]+)"', el).group(1))
            d = circle_path(cx, r, cy)
        elif tag == "line":
            x1, y1 = re.search(r'x1="([^"]+)"', el).group(1), re.search(r'y1="([^"]+)"', el).group(1)
            x2, y2 = re.search(r'x2="([^"]+)"', el).group(1), re.search(r'y2="([^"]+)"', el).group(1)
            d = f"M{x1},{y1}L{x2},{y2}"
        else:
            nums = re.findall(r'-?\d+\.?\d*', re.search(r'points="([^"]+)"', el).group(1))
            if len(nums) % 2 != 0:
                raise ValueError(src + ": polyline 点数不是偶数")
            d = "M" + "L".join(f"{nums[i]},{nums[i+1]}" for i in range(0, len(nums), 2))
        parts.append(d)
    return " ".join(parts)

def validate(d, name):
    # 按 SVG 语法逐命令校验参数个数（覆盖全部命令，含小写）
    NUM = r'[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?'
    CMDS = "MmLlHhVvCcSsQqTtAaZz"
    NEED = {"M":2,"m":2,"L":2,"l":2,"T":2,"t":2,"H":1,"h":1,"V":1,"v":1,
            "C":6,"c":6,"S":4,"s":4,"Q":4,"q":4,"A":7,"a":7}
    for cmd, seg in re.findall(r'([' + CMDS + r'])([^' + CMDS + r']*)', d):
        n = len(re.findall(NUM, seg))
        need = NEED.get(cmd)
        if need and n % need != 0:
            raise ValueError(f"{name}: {cmd}{seg!r} 参数个数 {n} 不合法")

os.makedirs(DST, exist_ok=True)
for src, dst in ICONS.items():
    svg = open(os.path.join(SRC, src + ".svg"), encoding="utf-8").read()
    d = convert(svg)
    validate(d, src)
    xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="{d}"
        android:strokeColor="#FF3C4A40" android:strokeWidth="2"
        android:strokeLineCap="round" android:strokeLineJoin="round"/>
</vector>
'''
    open(os.path.join(DST, dst + ".xml"), "w", encoding="utf-8").write(xml)
    print(dst, "ok,", len(d), "chars")
