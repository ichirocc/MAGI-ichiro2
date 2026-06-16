#!/usr/bin/env python3
# Faithful mock render of the redesigned MAGI UI, using the EXACT theme tokens
# (colors / corner radii / type scale) from MainActivity.kt + MagiApp.kt.
# Not a live Android screenshot (no emulator here) — a token-accurate mock to
# eyeball the "soft friendly teal" redesign.
from PIL import Image, ImageDraw, ImageFont

JP = "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf"
S = 2.75  # dp -> px

# ---- light color scheme (exact values from MainActivity.kt) ----
C = dict(
    primary="#3B82F6", onPrimary="#FFFFFF",
    primaryContainer="#DCE9FE", onPrimaryContainer="#0B2E66",
    secondary="#6366F1", secondaryContainer="#E6E9FF", onSecondaryContainer="#1E1B4B",
    tertiary="#22C55E", tertiaryContainer="#DCFCE7", onTertiaryContainer="#065F36",
    background="#F5F5F7", onBackground="#111318",
    surface="#FFFFFF", onSurface="#111318",
    surfaceVariant="#F0F1F4", onSurfaceVariant="#6B7280",
    error="#EF4444", onError="#FFFFFF",
    errorContainer="#FEE2E2", onErrorContainer="#7F1D1D",
    outline="#D9DCE3",
    amberBg="#FEF3C7", amberFg="#7C5800",
)

def dp(x): return int(x * S)
def font(sp, bold=False): return ImageFont.truetype(JP, dp(sp))

W = dp(360)

def new_canvas(h_dp):
    img = Image.new("RGB", (W, dp(h_dp)), C["background"])
    return img, ImageDraw.Draw(img)

def rrect(d, x, y, w, h, r, fill, outline=None, ow=0):
    d.rounded_rectangle([x, y, x + w, y + h], radius=dp(r), fill=fill, outline=outline, width=ow)

def text(d, x, y, s, sp, color, bold=False, anchor="la", maxw=None):
    f = font(sp, bold)
    if maxw and d.textlength(s, font=f) > maxw:
        while s and d.textlength(s + "…", font=f) > maxw:
            s = s[:-1]
        s += "…"
    d.text((x, y), s, font=f, fill=color, anchor=anchor)
    return f

def center(d, cx, y, s, sp, color, bold=False):
    f = font(sp, bold)
    d.text((cx, y), s, font=f, fill=color, anchor="ma")

# ---- shared chrome ----------------------------------------------------------
def topbar(d, status_label, status_bg, status_fg):
    d.rectangle([0, 0, W, dp(56)], fill=C["surface"])
    # MAGI pill badge
    bw = dp(64)
    rrect(d, dp(16), dp(13), bw, dp(30), 16, C["primary"])
    center(d, dp(16) + bw // 2, dp(18), "MAGI", 15, C["onPrimary"], True)
    text(d, dp(16) + bw + dp(10), dp(18), "勤務表", 15, C["onSurfaceVariant"])
    # status chip (right)
    f = font(13)
    tw = d.textlength(status_label, font=f)
    cw = int(tw) + dp(24)
    rrect(d, W - dp(16) - cw, dp(15), cw, dp(26), 16, status_bg)
    text(d, W - dp(16) - cw + dp(12), dp(20), status_label, 13, status_fg)
    d.line([0, dp(56), W, dp(56)], fill=C["outline"], width=1)

def bottom_bars(d, H, sel, primary_label):
    navh = dp(64); cmdh = dp(80)
    cmd_y = H - navh - cmdh
    # command bar
    d.rectangle([0, cmd_y, W, cmd_y + cmdh], fill=C["surface"])
    d.line([0, cmd_y, W, cmd_y], fill=C["outline"], width=1)
    ux = dp(16)
    rrect(d, ux, cmd_y + dp(10), dp(96), dp(60), 18, C["surface"], outline=C["outline"], ow=2)
    center(d, ux + dp(48), cmd_y + dp(28), "元に戻す", 14, C["onSurfaceVariant"])
    bx = ux + dp(96) + dp(10)
    rrect(d, bx, cmd_y + dp(10), W - dp(16) - bx, dp(60), 18, C["primary"])
    center(d, (bx + W - dp(16)) // 2 - dp(10), cmd_y + dp(26), "▶  " + primary_label, 16, C["onPrimary"], True)
    # nav
    ny = H - navh
    d.rectangle([0, ny, W, H], fill=C["surface"])
    d.line([0, ny, W, ny], fill=C["outline"], width=1)
    items = ["ホーム", "勤務表", "編集", "分析", "設定"]
    cwid = W / 5
    for i, lab in enumerate(items):
        cx = int(cwid * (i + 0.5))
        if i == sel:
            rrect(d, cx - dp(26), ny + dp(8), dp(52), dp(26), 16, C["secondaryContainer"])
        col = C["primary"] if i == sel else C["onSurfaceVariant"]
        nav_icon(d, cx, ny + dp(21), i, col)
        center(d, cx, ny + dp(38), lab, 11, col)

def nav_icon(d, cx, cy, kind, col):
    s = dp(9)
    if kind == 0:  # home
        d.polygon([(cx - s, cy), (cx, cy - s), (cx + s, cy)], fill=col)
        d.rectangle([cx - s + dp(2), cy, cx + s - dp(2), cy + s - dp(1)], fill=col)
    elif kind == 1:  # schedule grid
        for a in range(2):
            for b in range(2):
                d.rounded_rectangle([cx - s + b * (s + dp(1)), cy - s + a * (s + dp(1)),
                                     cx - s + b * (s + dp(1)) + s - dp(2), cy - s + a * (s + dp(1)) + s - dp(2)], radius=dp(2), fill=col)
    elif kind == 2:  # edit pencil
        d.line([(cx - s, cy + s), (cx + s, cy - s)], fill=col, width=dp(3))
        d.polygon([(cx + s, cy - s), (cx + s - dp(4), cy - s), (cx + s, cy - s + dp(4))], fill=col)
    elif kind == 3:  # bars
        for k, hh in enumerate([s, s * 2 - dp(4), s + dp(3)]):
            xx = cx - s + k * dp(7)
            d.rounded_rectangle([xx, cy + s - hh, xx + dp(4), cy + s], radius=dp(1), fill=col)
    else:  # settings gear (ring)
        d.ellipse([cx - s, cy - s, cx + s, cy + s], outline=col, width=dp(3))
        d.ellipse([cx - dp(3), cy - dp(3), cx + dp(3), cy + dp(3)], fill=col)

def card(d, x, y, w, h, fill=None, r=22):
    rrect(d, x, y, w, h, r, fill or C["surface"])

def bigstat(d, x, y, w, value, label):
    h = dp(72)
    rrect(d, x, y, w, h, 22, C["surfaceVariant"])
    center(d, x + w // 2, y + dp(14), value, 22, C["onSurface"], True)
    center(d, x + w // 2, y + dp(46), label, 12, C["onSurfaceVariant"])
    return h

# ---- HOME -------------------------------------------------------------------
def home():
    H = dp(740)
    img, d = new_canvas(740)
    topbar(d, "配布可", C["tertiaryContainer"], C["onTertiaryContainer"])
    x, y, w = dp(16), dp(70), W - dp(32)
    # StatusHero
    hh = dp(118)
    rrect(d, x, y, w, hh, 28, C["tertiaryContainer"])
    d.ellipse([x + dp(20), y + dp(22), x + dp(20) + dp(40), y + dp(22) + dp(40)], outline=C["onPrimaryContainer"], width=dp(2))
    center(d, x + dp(40), y + dp(28), "✓", 20, C["onPrimaryContainer"], True)
    text(d, x + dp(76), y + dp(22), "配布できます", 22, C["onPrimaryContainer"], True)
    text(d, x + dp(76), y + dp(54), "必須条件はすべて満たしています（残り調整 55）", 13, C["onPrimaryContainer"], maxw=w - dp(90))
    text(d, x + dp(76), y + dp(82), "充足 92%   不足 0   必要 240", 14, C["onPrimaryContainer"], True)
    y += hh + dp(16)
    # SummaryCard
    ch = dp(170)
    card(d, x, y, w, ch)
    text(d, x + dp(16), y + dp(16), "勤務表の状態", 17, C["onSurface"], True)
    sw = (w - dp(32) - dp(20)) // 3
    sy = y + dp(48)
    bigstat(d, x + dp(16), sy, sw, "0", "未解決(必須)")
    bigstat(d, x + dp(16) + sw + dp(10), sy, sw, "55", "調整(任意)")
    bigstat(d, x + dp(16) + 2 * (sw + dp(10)), sy, sw, "78", "違反 合計")
    text(d, x + dp(16), sy + dp(84), "スタッフ 10 名 ・ 31 日 ・ シフト 10 種 ・ グループ 4", 13, C["onSurface"])
    text(d, x + dp(16), sy + dp(108), "2世代カバレッジ(MIN=OR): ON ・ 初期 HARD 8/SOFT 132", 12, C["onSurfaceVariant"])
    y += ch + dp(16)
    # ActionCard
    ah = dp(150)
    card(d, x, y, w, ah)
    text(d, x + dp(16), y + dp(16), "アルゴリズム", 17, C["onSurface"], True)
    chips = [("高速", False), ("標準 ★", True), ("推奨 ★★", False), ("学習", False)]
    cx = x + dp(16)
    for lab, seld in chips:
        f = font(13); cw2 = int(d.textlength(lab, font=f)) + dp(22)
        rrect(d, cx, y + dp(48), cw2, dp(34), 16, C["primary"] if seld else C["surfaceVariant"])
        text(d, cx + dp(11), y + dp(54), lab, 13, C["onPrimary"] if seld else C["onSurfaceVariant"])
        cx += cw2 + dp(8)
    text(d, x + dp(16), y + dp(96), "時間予算 180 秒 ・ 並列 4", 13, C["onSurfaceVariant"])
    rrect(d, x + dp(16), y + dp(116), w - dp(32), dp(2), 1, C["outline"])
    bottom_bars(d, H, 0, "最適化する")
    return img

# ---- SCHEDULE ---------------------------------------------------------------
def schedule():
    H = dp(740)
    img, d = new_canvas(740)
    topbar(d, "配布可", C["tertiaryContainer"], C["onTertiaryContainer"])
    x, y, w = dp(16), dp(70), W - dp(32)
    ch = dp(300)
    card(d, x, y, w, ch)
    text(d, x + dp(16), y + dp(16), "勤務表グリッド", 17, C["onSurface"], True)
    text(d, x + dp(16), y + dp(44), "セルをタップでシフトを巡回。違反は色で表示。", 12, C["onSurfaceVariant"])
    syms = ["休", "日", "夜", "明", "有", "P", "A", "休", "日", "夜"]
    names = ["古泉 健一", "山本 昌幸", "福澤 俊陽", "佐藤 美和", "鈴木 隆"]
    gx, gy = x + dp(16), y + dp(70)
    cw, chh, gap = dp(26), dp(26), dp(4)
    # header days
    for j in range(10):
        center(d, gx + dp(70) + j * (cw + gap) + cw // 2, gy, str(j + 1), 10, C["onSurfaceVariant"])
    gy += dp(20)
    import random; random.seed(3)
    for r_i, nm in enumerate(names):
        text(d, gx, gy + r_i * (chh + gap) + dp(5), nm, 12, C["onSurface"], maxw=dp(66))
        for j in range(10):
            vio = random.random() < 0.12
            bg = C["errorContainer"] if vio else C["surfaceVariant"]
            fg = C["onErrorContainer"] if vio else C["onSurface"]
            cxp = gx + dp(70) + j * (cw + gap)
            rrect(d, cxp, gy + r_i * (chh + gap), cw, chh, 12, bg)
            center(d, cxp + cw // 2, gy + r_i * (chh + gap) + dp(5), syms[(r_i + j) % len(syms)], 12, fg, True)
    y += ch + dp(16)
    # Calendar card
    cah = dp(210)
    card(d, x, y, w, cah)
    text(d, x + dp(16), y + dp(16), "スタッフ別カレンダー", 16, C["onSurface"], True)
    rrect(d, x + w - dp(120), y + dp(12), dp(48), dp(30), 16, C["surface"], outline=C["outline"], ow=2)
    center(d, x + w - dp(120) + dp(24), y + dp(18), "前", 13, C["onSurfaceVariant"])
    rrect(d, x + w - dp(64), y + dp(12), dp(48), dp(30), 16, C["surface"], outline=C["outline"], ow=2)
    center(d, x + w - dp(64) + dp(24), y + dp(18), "次", 13, C["onSurfaceVariant"])
    text(d, x + dp(16), y + dp(48), "古泉 健一 / N — タップで担当可能シフトを巡回", 12, C["onSurfaceVariant"])
    cy = y + dp(72); ccw = (w - dp(32) - 6 * dp(4)) // 7
    for wk in range(2):
        for j in range(7):
            vio = (wk * 7 + j) in (3, 9)
            bg = C["errorContainer"] if vio else C["surfaceVariant"]
            fg = C["onErrorContainer"] if vio else C["onSurface"]
            cxp = x + dp(16) + j * (ccw + dp(4))
            yy = cy + wk * (dp(58) + dp(6))
            rrect(d, cxp, yy, ccw, dp(58), 14, bg)
            center(d, cxp + ccw // 2, yy + dp(8), f"{wk*7+j+1}日", 9, fg if vio else C["onSurfaceVariant"])
            center(d, cxp + ccw // 2, yy + dp(28), syms[(wk + j) % len(syms)], 15, fg, True)
    bottom_bars(d, H, 1, "最適化する")
    return img

# ---- ANALYSIS ---------------------------------------------------------------
def analysis():
    H = dp(740)
    img, d = new_canvas(740)
    topbar(d, "配布可", C["tertiaryContainer"], C["onTertiaryContainer"])
    x, y, w = dp(16), dp(70), W - dp(32)
    ch = dp(230)
    card(d, x, y, w, ch)
    text(d, x + dp(16), y + dp(16), "V6 1ヶ月俯瞰", 17, C["onSurface"], True)
    text(d, x + dp(16), y + dp(42), "人員の穴・負荷の偏り・入力ミスを集計します。", 12, C["onSurfaceVariant"])
    sw = (w - dp(32) - dp(20)) // 3
    sy = y + dp(66)
    bigstat(d, x + dp(16), sy, sw, "0", "HARD Core")
    bigstat(d, x + dp(16) + sw + dp(10), sy, sw, "0", "Guard")
    bigstat(d, x + dp(16) + 2 * (sw + dp(10)), sy, sw, "92%", "充足")
    text(d, x + dp(16), sy + dp(86), "最優先: 人員不足なし", 14, C["primary"], True)
    # risk chips
    ry = sy + dp(112)
    risks = [("月", 0), ("火", 0), ("水", 1), ("木", 0), ("金", 2), ("土", 0)]
    cxp = x + dp(16)
    for lab, sh in risks:
        if sh <= 0: bg, fg = C["primaryContainer"], C["onPrimaryContainer"]
        elif sh == 1: bg, fg = C["amberBg"], C["amberFg"]
        else: bg, fg = C["errorContainer"], C["onErrorContainer"]
        cww = dp(50)
        rrect(d, cxp, ry, cww, dp(42), 16, bg)
        center(d, cxp + cww // 2, ry + dp(5), lab, 11, fg)
        center(d, cxp + cww // 2, ry + dp(22), ("不足%d" % sh) if sh > 0 else "OK", 11, fg, True)
        cxp += cww + dp(6)
    y += ch + dp(16)
    # Breakdown card
    bh = dp(220)
    card(d, x, y, w, bh)
    text(d, x + dp(16), y + dp(16), "違反の内訳", 17, C["onSurface"], True)
    rows = [
        ("希望不一致", 55, C["tertiaryContainer"], C["onTertiaryContainer"]),
        ("必要人数との差", 23, C["errorContainer"], C["onErrorContainer"]),
        ("個人別回数範囲", 28, C["amberBg"], C["amberFg"]),
        ("グループ適切回数差", 232, C["surfaceVariant"], C["onSurfaceVariant"]),
        ("シフト間隔", 0, C["primaryContainer"], C["onPrimaryContainer"]),
        ("連続パターン", 0, C["primaryContainer"], C["onPrimaryContainer"]),
    ]
    ry = y + dp(48)
    for lab, val, bg, fg in rows:
        rrect(d, x + dp(16), ry, w - dp(32), dp(24), 12, bg)
        text(d, x + dp(28), ry + dp(4), lab, 12, fg)
        text(d, x + w - dp(28), ry + dp(4), str(val), 12, fg, True, anchor="ra")
        ry += dp(28)
    bottom_bars(d, H, 3, "最適化する")
    return img

screens = [("home", home()), ("schedule", schedule()), ("analysis", analysis())]
paths = []
for name, im in screens:
    p = f"/home/user/MAGI-ShiftOptimizer/tools/mock_{name}.png"
    im.save(p)
    paths.append(p)
# combined PDF via reportlab (no JPEG codec needed)
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas as rcanvas
from reportlab.lib.utils import ImageReader
pdf_path = "/home/user/MAGI-ShiftOptimizer/tools/magi_ui_mock.pdf"
pw, ph = A4
c = rcanvas.Canvas(pdf_path, pagesize=A4)
for p in paths:
    im = Image.open(p)
    iw, ih = im.size
    scale = min(pw / iw, ph / ih) * 0.95
    dw, dh = iw * scale, ih * scale
    c.drawImage(ImageReader(p), (pw - dw) / 2, (ph - dh) / 2, dw, dh)
    c.showPage()
c.save()
print("WROTE:", *paths, pdf_path, sep="\n")
