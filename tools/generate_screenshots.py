#!/usr/bin/env python3
"""
tools/generate_screenshots.py

Generates pixel-accurate 24:9 widescreen (1920x720) screenshots of FITCAMX
for documentation, README, and release showcases.
"""

import os
import math
from PIL import Image, ImageDraw, ImageFont

# Canvas dimensions (Native Gen5W 24:9 display)
WIDTH = 1920
HEIGHT = 720

# Paths
FONTS_DIR = "/usr/share/fonts/truetype/liberation"
FONT_BOLD = os.path.join(FONTS_DIR, "LiberationSans-Bold.ttf")
FONT_REGULAR = os.path.join(FONTS_DIR, "LiberationSans-Regular.ttf")
FONT_MONO = os.path.join(FONTS_DIR, "LiberationMono-Regular.ttf")
FONT_MONO_BOLD = os.path.join(FONTS_DIR, "LiberationMono-Bold.ttf")

OUTPUT_DIR = "docs/screenshots"

# Color Palette (from app/res/values/colors.xml)
COLOR_BG_DARK = (18, 20, 24)          # #121418
COLOR_BG_PANEL = (27, 30, 36)         # #1b1e24
COLOR_BG_BUTTON = (40, 44, 53)        # #282c35
COLOR_BG_BUTTON_PRESSED = (59, 65, 77) # #3b414d
COLOR_ACCENT_BLUE = (0, 145, 255)     # #0091ff
COLOR_ACCENT_RED = (229, 57, 53)      # #e53935
COLOR_ACCENT_GREEN = (46, 204, 113)   # #2ecc71
COLOR_ACCENT_GOLD = (243, 156, 18)    # #f39c12
COLOR_TEXT_PRIMARY = (255, 255, 255)  # #ffffff
COLOR_TEXT_SECONDARY = (158, 158, 158)# #9e9e9e
COLOR_DIVIDER = (42, 47, 59)          # #2a2f3b
COLOR_DIAG_BG = (21, 25, 34)          # #151922
COLOR_LOG_BTN = (46, 64, 87)          # #2e4057

def get_font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()

def draw_rounded_rect(draw, box, radius, fill=None, outline=None, width=1):
    """Draws a rounded rectangle."""
    x0, y0, x1, y1 = box
    if radius <= 0:
        draw.rectangle(box, fill=fill, outline=outline, width=width)
        return
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=fill, outline=outline, width=width)

def draw_button(draw, box, text, font, bg_color, text_color, radius=4):
    """Draws a themed button with centered text."""
    draw_rounded_rect(draw, box, radius=radius, fill=bg_color)
    x0, y0, x1, y1 = box
    bbox = font.getbbox(text)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = x0 + (x1 - x0 - tw) // 2
    ty = y0 + (y1 - y0 - th) // 2 - bbox[1]
    draw.text((tx, ty), text, fill=text_color, font=font)

def render_dashcam_front_view(width, height):
    """Renders a realistic front-facing road scenery from a dashcam."""
    img = Image.new("RGBA", (width, height), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)

    # 1. Sky gradient (dusk / twilight clear sky)
    horizon_y = int(height * 0.44)
    for y in range(horizon_y):
        t = y / horizon_y
        r = int(24 * (1 - t) + 120 * t)
        g = int(58 * (1 - t) + 165 * t)
        b = int(120 * (1 - t) + 215 * t)
        draw.line([(0, y), (width, y)], fill=(r, g, b, 255))

    # Sun glow on horizon
    sun_x = int(width * 0.62)
    for rad in range(120, 0, -5):
        alpha = int(35 * (1 - rad / 120))
        glow_box = [sun_x - rad * 2, horizon_y - rad, sun_x + rad * 2, horizon_y + rad]
        draw.ellipse(glow_box, fill=(255, 230, 180, alpha))

    # 2. Distant mountain ridge
    mountain_pts = [(0, horizon_y)]
    num_peaks = 24
    for i in range(num_peaks + 1):
        px = int(width * i / num_peaks)
        py = int(horizon_y - 25 - 28 * math.sin(i * 0.8) - 15 * math.cos(i * 1.7))
        mountain_pts.append((px, py))
    mountain_pts.append((width, horizon_y))
    draw.polygon(mountain_pts, fill=(45, 65, 88, 255))

    # Closer mountain ridge
    mountain_pts2 = [(0, horizon_y)]
    for i in range(num_peaks + 1):
        px = int(width * i / num_peaks)
        py = int(horizon_y - 12 - 16 * math.sin(i * 1.1 + 0.5))
        mountain_pts2.append((px, py))
    mountain_pts2.append((width, horizon_y))
    draw.polygon(mountain_pts2, fill=(35, 52, 68, 255))

    # 3. Ground / roadside terrain
    draw.rectangle([0, horizon_y, width, height], fill=(30, 48, 32, 255))

    # Road terrain perspective
    vp_x = int(width * 0.52)
    vp_y = horizon_y

    # Road surface (asphalt)
    road_poly = [
        (vp_x - 35, vp_y),
        (vp_x + 35, vp_y),
        (int(width * 1.05), height),
        (int(width * -0.05), height)
    ]
    draw.polygon(road_poly, fill=(42, 44, 48, 255))

    # Asphalt texture / shading gradient
    for y in range(vp_y, height):
        progress = (y - vp_y) / (height - vp_y)
        shade = int(38 + 14 * progress)
        lx = int(vp_x - 35 + ((width * -0.05) - (vp_x - 35)) * progress)
        rx = int(vp_x + 35 + ((width * 1.05) - (vp_x + 35)) * progress)
        draw.line([(lx, y), (rx, y)], fill=(shade, shade, int(shade * 1.05), 255))

    # Road shoulder lines
    # Left solid yellow line
    for y in range(vp_y, height):
        prog = (y - vp_y) / (height - vp_y)
        lx = int(vp_x - 22 + ((width * 0.08) - (vp_x - 22)) * prog)
        w = max(1, int(4 * prog))
        draw.line([(lx - w, y), (lx + w, y)], fill=(235, 185, 35, 255))

    # Right solid white line
    for y in range(vp_y, height):
        prog = (y - vp_y) / (height - vp_y)
        rx = int(vp_x + 22 + ((width * 0.92) - (vp_x + 22)) * prog)
        w = max(1, int(4 * prog))
        draw.line([(rx - w, y), (rx + w, y)], fill=(240, 240, 245, 255))

    # Lane dividing dashed white lines
    for lane_t in [0.36, 0.64]:
        for step in range(25):
            t_start = (step / 25.0) ** 1.8
            t_end = ((step + 0.55) / 25.0) ** 1.8
            if t_start > 1.0: break
            t_end = min(1.0, t_end)

            y1 = int(vp_y + (height - vp_y) * t_start)
            y2 = int(vp_y + (height - vp_y) * t_end)
            if y2 <= y1: continue

            x1 = int(vp_x + ((width * lane_t) - vp_x) * t_start)
            x2 = int(vp_x + ((width * lane_t) - vp_x) * t_end)
            w = max(1, int(3.5 * t_start))
            draw.line([(x1, y1), (x2, y2)], fill=(240, 240, 245, 240), width=w)

    # Roadside foliage silhouettes
    tree_color = (20, 36, 22, 255)
    for i in range(14):
        prog = (i / 14.0) ** 1.5
        tx = int(vp_x - 45 - 280 * prog)
        ty = int(vp_y + 120 * prog)
        th = int(20 + 90 * prog)
        tw = int(12 + 45 * prog)
        draw.ellipse([tx - tw, ty - th, tx + tw, ty], fill=tree_color)

    for i in range(14):
        prog = (i / 14.0) ** 1.5
        tx = int(vp_x + 45 + 320 * prog)
        ty = int(vp_y + 120 * prog)
        th = int(22 + 95 * prog)
        tw = int(14 + 50 * prog)
        draw.ellipse([tx - tw, ty - th, tx + tw, ty], fill=tree_color)

    # Preceding vehicle on the highway ahead (SUV in center lane)
    car_prog = 0.42
    car_x = int(vp_x + ((width * 0.50) - vp_x) * car_prog)
    car_y = int(vp_y + (height - vp_y) * car_prog)
    car_w = int(72 * car_prog)
    car_h = int(52 * car_prog)

    # Shadow
    draw.ellipse([car_x - car_w, car_y - 4, car_x + car_w, car_y + 8], fill=(20, 20, 22, 180))
    # Car body
    draw.rectangle([car_x - car_w//2, car_y - car_h, car_x + car_w//2, car_y], fill=(70, 75, 82, 255))
    draw.polygon([
        (car_x - car_w//2, car_y - car_h),
        (car_x - car_w//3, car_y - int(car_h * 1.5)),
        (car_x + car_w//3, car_y - int(car_h * 1.5)),
        (car_x + car_w//2, car_y - car_h)
    ], fill=(55, 60, 68, 255))
    # Rear window
    draw.polygon([
        (car_x - car_w//2 + 3, car_y - car_h + 2),
        (car_x - car_w//3 + 2, car_y - int(car_h * 1.45)),
        (car_x + car_w//3 - 2, car_y - int(car_h * 1.45)),
        (car_x + car_w//2 - 3, car_y - car_h + 2)
    ], fill=(25, 28, 35, 255))
    # Tail lights
    tl_w = max(2, int(6 * car_prog))
    tl_h = max(2, int(4 * car_prog))
    draw.rectangle([car_x - car_w//2 + 2, car_y - int(car_h * 0.6), car_x - car_w//2 + 2 + tl_w, car_y - int(car_h * 0.6) + tl_h], fill=(230, 40, 40, 255))
    draw.rectangle([car_x + car_w//2 - 2 - tl_w, car_y - int(car_h * 0.6), car_x + car_w//2 - 2, car_y - int(car_h * 0.6) + tl_h], fill=(230, 40, 40, 255))

    # Bottom vehicle cowl / windshield tint line
    hood_color = (15, 16, 19, 255)
    hood_pts = [
        (0, height),
        (0, height - 28),
        (int(width * 0.25), height - 18),
        (int(width * 0.50), height - 14),
        (int(width * 0.75), height - 18),
        (width, height - 28),
        (width, height)
    ]
    draw.polygon(hood_pts, fill=hood_color)

    # Dashcam timestamp watermark (authentic Novatek / FitcamX OSD stamp)
    mono_font = get_font(FONT_MONO_BOLD, 15)
    stamp_text = "FITCAMX 4K UHD  2026-11-10 16:06:04  55 MPH  GPS: 37.7749°N 122.4194°W"
    draw.text((26, height - 52), stamp_text, fill=(0, 0, 0, 220), font=mono_font)
    draw.text((25, height - 53), stamp_text, fill=(245, 245, 245, 240), font=mono_font)

    return img

def render_dashcam_rear_view(width, height):
    """Renders a realistic rear-facing camera view (looking back out rear glass)."""
    img = Image.new("RGBA", (width, height), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)

    # 1. Sky
    horizon_y = int(height * 0.42)
    for y in range(horizon_y):
        t = y / horizon_y
        r = int(35 * (1 - t) + 110 * t)
        g = int(65 * (1 - t) + 155 * t)
        b = int(115 * (1 - t) + 200 * t)
        draw.line([(0, y), (width, y)], fill=(r, g, b, 255))

    # Distant hills
    mountain_pts = [(0, horizon_y)]
    num_peaks = 20
    for i in range(num_peaks + 1):
        px = int(width * i / num_peaks)
        py = int(horizon_y - 20 - 18 * math.sin(i * 0.9))
        mountain_pts.append((px, py))
    mountain_pts.append((width, horizon_y))
    draw.polygon(mountain_pts, fill=(40, 58, 75, 255))

    # Road & terrain
    draw.rectangle([0, horizon_y, width, height], fill=(32, 45, 34, 255))

    vp_x = int(width * 0.48)
    vp_y = horizon_y

    road_poly = [
        (vp_x - 30, vp_y),
        (vp_x + 30, vp_y),
        (int(width * 1.08), height),
        (int(width * -0.08), height)
    ]
    draw.polygon(road_poly, fill=(44, 46, 50, 255))

    # Road lanes
    for y in range(vp_y, height):
        progress = (y - vp_y) / (height - vp_y)
        shade = int(40 + 12 * progress)
        lx = int(vp_x - 30 + ((width * -0.08) - (vp_x - 30)) * progress)
        rx = int(vp_x + 30 + ((width * 1.08) - (vp_x + 30)) * progress)
        draw.line([(lx, y), (rx, y)], fill=(shade, shade, shade, 255))

    # Lane markings
    for lane_t in [0.34, 0.66]:
        for step in range(20):
            t_start = (step / 20.0) ** 1.8
            t_end = ((step + 0.55) / 20.0) ** 1.8
            if t_start > 1.0: break
            t_end = min(1.0, t_end)
            y1 = int(vp_y + (height - vp_y) * t_start)
            y2 = int(vp_y + (height - vp_y) * t_end)
            if y2 <= y1: continue
            x1 = int(vp_x + ((width * lane_t) - vp_x) * t_start)
            x2 = int(vp_x + ((width * lane_t) - vp_x) * t_end)
            w = max(1, int(3 * t_start))
            draw.line([(x1, y1), (x2, y2)], fill=(240, 240, 245, 220), width=w)

    # Trailing car behind us in the same lane
    follow_prog = 0.55
    cx = int(vp_x + ((width * 0.50) - vp_x) * follow_prog)
    cy = int(vp_y + (height - vp_y) * follow_prog)
    cw = int(115 * follow_prog)
    ch = int(75 * follow_prog)

    # Shadow
    draw.ellipse([cx - cw, cy - 6, cx + cw, cy + 12], fill=(18, 18, 20, 200))
    # Car front bumper / grill / headlights
    draw.rectangle([cx - cw//2, cy - ch, cx + cw//2, cy], fill=(160, 165, 172, 255))
    draw.polygon([
        (cx - cw//2, cy - ch),
        (cx - cw//3, cy - int(ch * 1.4)),
        (cx + cw//3, cy - int(ch * 1.4)),
        (cx + cw//2, cy - ch)
    ], fill=(135, 140, 148, 255))
    # Windshield
    draw.polygon([
        (cx - cw//2 + 4, cy - ch + 2),
        (cx - cw//3 + 3, cy - int(ch * 1.35)),
        (cx + cw//3 - 3, cy - int(ch * 1.35)),
        (cx + cw//2 - 4, cy - ch + 2)
    ], fill=(22, 25, 30, 255))
    # Grill
    draw.rectangle([cx - cw//4, cy - int(ch * 0.5), cx + cw//4, cy - int(ch * 0.15)], fill=(30, 32, 36, 255))
    # Headlights
    hl_w = max(3, int(10 * follow_prog))
    hl_h = max(2, int(5 * follow_prog))
    draw.rectangle([cx - cw//2 + 4, cy - int(ch * 0.55), cx - cw//2 + 4 + hl_w, cy - int(ch * 0.55) + hl_h], fill=(255, 255, 240, 255))
    draw.rectangle([cx + cw//2 - 4 - hl_w, cy - int(ch * 0.55), cx + cw//2 - 4, cy - int(ch * 0.55) + hl_h], fill=(255, 255, 240, 255))

    # Rear window defroster wire lines
    for wire_y in range(int(height * 0.15), int(height * 0.90), 45):
        draw.line([(0, wire_y), (width, wire_y)], fill=(120, 80, 60, 50), width=1)

    # Watermark
    mono_font = get_font(FONT_MONO_BOLD, 15)
    stamp_text = "FITCAMX REAR 1080P  2026-11-10 16:06:04  55 MPH"
    draw.text((26, height - 52), stamp_text, fill=(0, 0, 0, 220), font=mono_font)
    draw.text((25, height - 53), stamp_text, fill=(245, 245, 245, 240), font=mono_font)

    return img

def render_sidebar(draw, active_tab="live", wifi_status="Wi-Fi: Connected (192.168.1.33)", diag_info="Live Feed: FRONT (LIVE) on 192.168.1.33"):
    """Renders the left navigation sidebar (width: 240px)."""
    sb_w = 240
    draw.rectangle([0, 0, sb_w, HEIGHT], fill=COLOR_BG_PANEL)

    # 1. Title Row: "FITCAMX" + "v0.6.0"
    font_title = get_font(FONT_BOLD, 22)
    font_ver = get_font(FONT_REGULAR, 12)
    draw.text((14, 14), "FITCAMX", fill=COLOR_ACCENT_BLUE, font=font_title)
    draw.text((165, 21), "v0.6.0", fill=COLOR_TEXT_SECONDARY, font=font_ver)

    # 2. Wi-Fi status
    font_status = get_font(FONT_BOLD, 12)
    draw.text((14, 46), wifi_status, fill=COLOR_ACCENT_GOLD, font=font_status)

    # 3. Rescan Button
    font_btn = get_font(FONT_BOLD, 13)
    draw_button(draw, [14, 72, sb_w - 14, 108], "Rescan", font_btn, COLOR_BG_BUTTON, COLOR_TEXT_PRIMARY, radius=4)

    # 4. Navigation Section Label
    font_sec = get_font(FONT_BOLD, 11)
    draw.text((14, 122), "NAVIGATION", fill=COLOR_TEXT_SECONDARY, font=font_sec)

    # 5. Mode Switcher Tabs
    live_bg = COLOR_ACCENT_BLUE if active_tab == "live" else COLOR_BG_BUTTON
    live_txt = COLOR_TEXT_PRIMARY
    draw_button(draw, [14, 142, sb_w - 14, 184], "Live View", font_btn, live_bg, live_txt, radius=4)

    gal_bg = COLOR_ACCENT_BLUE if active_tab == "gallery" else COLOR_BG_BUTTON
    gal_txt = COLOR_TEXT_PRIMARY
    draw_button(draw, [14, 192, sb_w - 14, 234], "SD Card Footage", font_btn, gal_bg, gal_txt, radius=4)

    # 6. Action buttons at bottom of sidebar
    font_action = get_font(FONT_BOLD, 13)
    draw_button(draw, [14, HEIGHT - 180, sb_w - 14, HEIGHT - 142], "Export Log to USB", font_action, COLOR_LOG_BTN, COLOR_ACCENT_GOLD, radius=4)
    draw_button(draw, [14, HEIGHT - 134, sb_w - 14, HEIGHT - 96], "Exit", font_action, COLOR_BG_BUTTON, COLOR_ACCENT_GREEN, radius=4)

    # 7. Diagnostic Bar at bottom
    draw_rounded_rect(draw, [10, HEIGHT - 88, sb_w - 10, HEIGHT - 10], radius=4, fill=COLOR_DIAG_BG)
    font_diag = get_font(FONT_REGULAR, 10)
    font_diag_bold = get_font(FONT_BOLD, 10)
    draw.text((16, HEIGHT - 80), diag_info, fill=(153, 170, 187), font=font_diag)
    draw.text((sb_w - 90, HEIGHT - 32), "[Diagnostics]", fill=COLOR_ACCENT_BLUE, font=font_diag_bold)

def render_camera_controls(draw, view_mode="FRONT", rec_active=True):
    """Renders right camera controls column (width: 180px, x: 1740..1920)."""
    rx0 = 1740
    rx1 = WIDTH
    draw.rectangle([rx0, 0, rx1, HEIGHT], fill=COLOR_BG_PANEL)

    # Section Label
    font_sec = get_font(FONT_BOLD, 11)
    draw.text((rx0 + 12, 14), "CAMERA CONTROLS", fill=COLOR_TEXT_SECONDARY, font=font_sec)

    # Mode Selector Button
    font_btn = get_font(FONT_BOLD, 13)
    view_text = f"View: {view_mode}"
    draw_button(draw, [rx0 + 10, 38, rx1 - 10, 80], view_text, font_btn, COLOR_ACCENT_BLUE, COLOR_TEXT_PRIMARY, radius=4)

    # Snapshot
    draw_button(draw, [rx0 + 10, 88, rx1 - 10, 130], "Snapshot", font_btn, COLOR_BG_BUTTON, COLOR_TEXT_PRIMARY, radius=4)

    # Emergency Lock Clip
    draw_button(draw, [rx0 + 10, 138, rx1 - 10, 180], "Lock Clip", font_btn, COLOR_ACCENT_GOLD, (0, 0, 0), radius=4)

    # Mic Toggle
    draw_button(draw, [rx0 + 10, 188, rx1 - 10, 230], "Mic On", font_btn, COLOR_BG_BUTTON, COLOR_TEXT_PRIMARY, radius=4)

    # Manual Record Toggle
    rec_text = "Stop Recording" if rec_active else "Record"
    rec_bg = COLOR_ACCENT_RED if rec_active else COLOR_BG_BUTTON
    draw_button(draw, [rx0 + 10, 238, rx1 - 10, 280], rec_text, font_btn, rec_bg, COLOR_TEXT_PRIMARY, radius=4)

    # Bottom Settings Buttons
    font_small = get_font(FONT_BOLD, 12)
    draw_button(draw, [rx0 + 10, HEIGHT - 92, rx1 - 10, HEIGHT - 52], "Rec: 4K UHD", font_small, COLOR_BG_BUTTON, COLOR_ACCENT_GOLD, radius=4)
    draw_button(draw, [rx0 + 10, HEIGHT - 46, rx1 - 10, HEIGHT - 10], "Sync Clock", font_small, COLOR_BG_BUTTON, COLOR_TEXT_SECONDARY, radius=4)

def render_badge(draw, x, y, text, bg_color, text_color=(255, 255, 255), is_bold=True):
    """Renders a translucent status badge overlay."""
    font = get_font(FONT_BOLD if is_bold else FONT_REGULAR, 13)
    bbox = font.getbbox(text)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    pad_x, pad_y = 12, 6
    bx1 = x + tw + pad_x * 2
    by1 = y + th + pad_y * 2
    draw_rounded_rect(draw, [x, y, bx1, by1], radius=4, fill=bg_color)
    draw.text((x + pad_x, y + pad_y - bbox[1]), text, fill=text_color, font=font)
    return bx1

# ==============================================================================
# SCREENSHOT 1: Live Front Camera Monitoring (1920x720)
# ==============================================================================
def create_screenshot_live_front():
    img = Image.new("RGBA", (WIDTH, HEIGHT), COLOR_BG_DARK)
    draw = ImageDraw.Draw(img)

    # 1. Left Sidebar
    render_sidebar(draw, active_tab="live", diag_info="Status: RTSP Live (Front) | 30 FPS")

    # 2. Right Camera Controls
    render_camera_controls(draw, view_mode="FRONT", rec_active=True)

    # 3. Center Live Video Surface (1500px width container, 1280x720 16:9 stream centered)
    container_x0 = 240
    container_w = 1500
    stream_w = 1280
    stream_h = 720
    stream_x = container_x0 + (container_w - stream_w) // 2

    # Pillarbox bars background
    draw.rectangle([container_x0, 0, container_x0 + container_w, HEIGHT], fill=(0, 0, 0, 255))

    # Render front dashcam scenery
    front_scenery = render_dashcam_front_view(stream_w, stream_h)
    img.paste(front_scenery, (stream_x, 0))

    # Overlays on live feed
    # Top-left stream badge
    render_badge(draw, stream_x + 14, 14, "FRONT (LIVE)", (0, 0, 0, 160))

    # Top-right recording badge
    render_badge(draw, stream_x + stream_w - 95, 14, "● REC", (220, 35, 35, 200))

    return img.convert("RGB")

# ==============================================================================
# SCREENSHOT 2: Dual View with Rear Picture-in-Picture (1920x720)
# ==============================================================================
def create_screenshot_dual_pip():
    img = Image.new("RGBA", (WIDTH, HEIGHT), COLOR_BG_DARK)
    draw = ImageDraw.Draw(img)

    # 1. Left Sidebar
    render_sidebar(draw, active_tab="live", diag_info="Status: RTSP Dual Feed | 30 FPS")

    # 2. Right Camera Controls (Mode is DUAL PIP)
    render_camera_controls(draw, view_mode="DUAL (PIP)", rec_active=True)

    # 3. Center Live Video Surface
    container_x0 = 240
    container_w = 1500
    stream_w = 1280
    stream_h = 720
    stream_x = container_x0 + (container_w - stream_w) // 2

    draw.rectangle([container_x0, 0, container_x0 + container_w, HEIGHT], fill=(0, 0, 0, 255))

    # Base: Front camera view
    front_scenery = render_dashcam_front_view(stream_w, stream_h)
    img.paste(front_scenery, (stream_x, 0))

    # PIP Inset: Rear camera view at top right
    pip_w = 340
    pip_h = 192
    pip_x = stream_x + stream_w - pip_w - 18
    pip_y = 56

    rear_scenery = render_dashcam_rear_view(pip_w, pip_h)
    # Border & Shadow for PIP window
    draw.rectangle([pip_x - 3, pip_y - 3, pip_x + pip_w + 3, pip_y + pip_h + 3], fill=(0, 145, 255, 220))
    img.paste(rear_scenery, (pip_x, pip_y))

    # Inset badge inside PIP window
    render_badge(draw, pip_x + 8, pip_y + 8, "REAR (PIP)", (0, 0, 0, 180))

    # Main stream badges
    render_badge(draw, stream_x + 14, 14, "DUAL (LIVE)", (0, 0, 0, 160))
    render_badge(draw, stream_x + stream_w - 95, 14, "● REC", (220, 35, 35, 200))

    return img.convert("RGB")

# ==============================================================================
# SCREENSHOT 3: SD Card Gallery Playback with Overlaid Scrub Bar (1920x720)
# ==============================================================================
def create_screenshot_gallery():
    img = Image.new("RGBA", (WIDTH, HEIGHT), COLOR_BG_DARK)
    draw = ImageDraw.Draw(img)

    # 1. Left Sidebar (SD Card tab active)
    render_sidebar(draw, active_tab="gallery", diag_info="Status: SD Card Indexed | 1,763 clips")

    # 2. Main Gallery Frame (x: 240..1920, width: 1680)
    # Left list column: width 680px (x: 240..920)
    # Right preview column: width 1000px (x: 920..1920)
    list_x0 = 240
    list_w = 680
    list_x1 = list_x0 + list_w
    prev_x0 = list_x1
    prev_w = WIDTH - prev_x0

    # Draw List Column Background
    draw.rectangle([list_x0, 0, list_x1, HEIGHT], fill=COLOR_BG_PANEL)

    # Title & Refresh row
    font_h = get_font(FONT_BOLD, 14)
    font_btn_sm = get_font(FONT_BOLD, 12)
    draw.text((list_x0 + 14, 14), "Footage on SD Card (1,763 clips)", fill=COLOR_TEXT_PRIMARY, font=font_h)
    draw_button(draw, [list_x1 - 110, 8, list_x1 - 12, 38], "Refresh", font_btn_sm, COLOR_BG_BUTTON, COLOR_TEXT_PRIMARY, radius=4)

    # Filter chips
    chip_y0 = 46
    chip_h = 30
    chip_w = (list_w - 28 - 18) // 4
    chips = [("All", COLOR_ACCENT_BLUE, True), ("Front", COLOR_BG_BUTTON, False), ("Rear", COLOR_BG_BUTTON, False), ("Emergency", COLOR_BG_BUTTON, False)]
    for i, (label, color, is_sel) in enumerate(chips):
        cx0 = list_x0 + 14 + i * (chip_w + 6)
        cx1 = cx0 + chip_w
        txt_c = COLOR_TEXT_PRIMARY if is_sel else COLOR_TEXT_SECONDARY
        draw_button(draw, [cx0, chip_y0, cx1, chip_y0 + chip_h], label, font_btn_sm, color, txt_c, radius=3)

    # Clips List Items
    clips_data = [
        ("FRONT", COLOR_ACCENT_BLUE, "20260710164009_089529A.TS", "2026/07/10 16:40:09", "312 MB", False, True),
        ("REAR", COLOR_ACCENT_GOLD, "20260710164009_089529B.TS", "2026/07/10 16:40:09", "124 MB", False, False),
        ("FRONT", COLOR_ACCENT_BLUE, "20260710163909_089528A.TS", "2026/07/10 16:39:09", "312 MB", True, False),
        ("FRONT", COLOR_ACCENT_BLUE, "20260710163809_089527A.TS", "2026/07/10 16:38:09", "312 MB", False, False),
        ("REAR", COLOR_ACCENT_GOLD, "20260710163809_089527B.TS", "2026/07/10 16:38:09", "124 MB", False, False),
        ("FRONT", COLOR_ACCENT_BLUE, "20260710163709_089526A.TS", "2026/07/10 16:37:09", "312 MB", False, False),
        ("REAR", COLOR_ACCENT_GOLD, "20260710163709_089526B.TS", "2026/07/10 16:37:09", "124 MB", False, False),
        ("FRONT", COLOR_ACCENT_BLUE, "20260710163609_089525A.TS", "2026/07/10 16:36:09", "312 MB", False, False),
        ("FRONT", COLOR_ACCENT_BLUE, "20260710163509_089524A.TS", "2026/07/10 16:35:09", "312 MB", False, False),
    ]

    item_y = 86
    item_h = 68
    font_clip_name = get_font(FONT_BOLD, 14)
    font_clip_sub = get_font(FONT_REGULAR, 12)
    font_badge = get_font(FONT_BOLD, 11)

    for cam, cam_c, filename, time_str, size_str, is_locked, is_selected in clips_data:
        # Selection highlight
        if is_selected:
            draw.rectangle([list_x0, item_y, list_x1, item_y + item_h], fill=(36, 44, 58, 255))
            # Left accent highlight bar
            draw.rectangle([list_x0, item_y, list_x0 + 4, item_y + item_h], fill=COLOR_ACCENT_BLUE)

        # Cam Badge (FRONT / REAR)
        draw_button(draw, [list_x0 + 14, item_y + 18, list_x0 + 72, item_y + 48], cam, font_badge, COLOR_BG_BUTTON, cam_c, radius=3)

        # Title & Time
        name_x = list_x0 + 82
        draw.text((name_x, item_y + 14), filename, fill=COLOR_TEXT_PRIMARY, font=font_clip_name)
        draw.text((name_x, item_y + 38), time_str, fill=COLOR_TEXT_SECONDARY, font=font_clip_sub)

        # Lock Badge if emergency
        size_x = list_x1 - 180
        if is_locked:
            draw.text((size_x - 70, item_y + 26), "LOCKED", fill=COLOR_ACCENT_GOLD, font=font_badge)

        # Size text
        draw.text((size_x, item_y + 26), size_str, fill=COLOR_TEXT_SECONDARY, font=font_clip_sub)

        # Save to USB Button
        draw_button(draw, [list_x1 - 105, item_y + 18, list_x1 - 12, item_y + 50], "Save to USB", font_btn_sm, COLOR_ACCENT_GREEN, COLOR_TEXT_PRIMARY, radius=3)

        # Divider line
        draw.line([(list_x0 + 10, item_y + item_h), (list_x1 - 10, item_y + item_h)], fill=COLOR_DIVIDER, width=1)
        item_y += item_h

    # 3. Right Preview Column
    # Header bar (y: 0..48)
    draw.rectangle([prev_x0, 0, WIDTH, 48], fill=COLOR_BG_PANEL)
    draw_button(draw, [prev_x0 + 10, 8, prev_x0 + 95, 40], "Hide List", font_btn_sm, COLOR_ACCENT_BLUE, COLOR_TEXT_PRIMARY, radius=3)

    # Selected clip title in header
    draw.text((prev_x0 + 110, 14), "20260710164009_089529A.TS", fill=COLOR_TEXT_PRIMARY, font=font_h)

    # Header action buttons: Rate, Pause, Save to USB
    draw_button(draw, [WIDTH - 300, 8, WIDTH - 230, 40], "0.33x", font_btn_sm, COLOR_BG_BUTTON, COLOR_ACCENT_GOLD, radius=3)
    draw_button(draw, [WIDTH - 222, 8, WIDTH - 140, 40], "Pause", font_btn_sm, COLOR_BG_BUTTON, COLOR_TEXT_PRIMARY, radius=3)
    draw_button(draw, [WIDTH - 132, 8, WIDTH - 12, 40], "Save to USB", font_btn_sm, COLOR_ACCENT_GREEN, COLOR_TEXT_PRIMARY, radius=3)

    # Preview video surface (y: 48..672, height: 624)
    # 16:9 ratio centered inside 1000x624:
    # 1000 * 9 / 16 = 562.5 height.
    vid_w = prev_w
    vid_h = int(vid_w * 9 / 16)
    vid_y0 = 48 + (624 - vid_h) // 2

    draw.rectangle([prev_x0, 48, WIDTH, 672], fill=(0, 0, 0, 255))
    preview_scene = render_dashcam_front_view(vid_w, vid_h)
    img.paste(preview_scene, (prev_x0, vid_y0))

    # Overlaid Scrub Bar (y: 620..668) at the bottom of the video container!
    scrub_h = 48
    scrub_y0 = 672 - scrub_h
    scrub_box = [prev_x0, scrub_y0, WIDTH, 672]
    # Translucent dark strip
    draw_rounded_rect(draw, scrub_box, radius=0, fill=(0, 0, 0, 185))

    # Seek bar slider
    bar_x0 = prev_x0 + 16
    bar_x1 = WIDTH - 170
    bar_y = scrub_y0 + scrub_h // 2
    # Inactive track
    draw_rounded_rect(draw, [bar_x0, bar_y - 3, bar_x1, bar_y + 3], radius=3, fill=(65, 72, 85, 255))
    # Active track (42% progress)
    prog_pct = 0.42
    prog_x = int(bar_x0 + (bar_x1 - bar_x0) * prog_pct)
    draw_rounded_rect(draw, [bar_x0, bar_y - 3, prog_x, bar_y + 3], radius=3, fill=COLOR_ACCENT_BLUE)
    # Scrub thumb circle
    draw.ellipse([prog_x - 7, bar_y - 7, prog_x + 7, bar_y + 7], fill=(255, 255, 255, 255), outline=COLOR_ACCENT_BLUE, width=2)

    # Time / Progress label
    font_prog = get_font(FONT_BOLD, 13)
    draw.text((bar_x1 + 16, bar_y - 8), "00:25 / 01:00 (42%)", fill=(255, 255, 255), font=font_prog)

    # Footer Bar below video (y: 672..720, height: 48)
    draw.rectangle([prev_x0, 672, WIDTH, HEIGHT], fill=COLOR_BG_PANEL)
    font_foot = get_font(FONT_REGULAR, 12)
    font_foot_bold = get_font(FONT_BOLD, 12)
    draw.text((prev_x0 + 14, 688), "Size: 312.4 MB | Duration: 01:00 | 4K UHD 3840x2160 HEVC (Novatek NT96670)", fill=COLOR_TEXT_SECONDARY, font=font_foot)
    draw.text((WIDTH - 290, 688), "USB Drive: Ready (/storage/usb0 - 58 GB free)", fill=COLOR_ACCENT_GOLD, font=font_foot_bold)

    return img.convert("RGB")

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Generating screenshots in {OUTPUT_DIR}...")

    # 1. Live View
    live_img = create_screenshot_live_front()
    live_path = os.path.join(OUTPUT_DIR, "01_live_view.png")
    live_img.save(live_path, "PNG", optimize=True)
    print(f"  ✓ Saved: {live_path} ({live_img.size[0]}x{live_img.size[1]})")

    # 2. Gallery Playback
    gal_img = create_screenshot_gallery()
    gal_path = os.path.join(OUTPUT_DIR, "02_gallery_playback.png")
    gal_img.save(gal_path, "PNG", optimize=True)
    print(f"  ✓ Saved: {gal_path} ({gal_img.size[0]}x{gal_img.size[1]})")

    # 3. Dual PIP View
    dual_img = create_screenshot_dual_pip()
    dual_path = os.path.join(OUTPUT_DIR, "03_dual_pip_view.png")
    dual_img.save(dual_path, "PNG", optimize=True)
    print(f"  ✓ Saved: {dual_path} ({dual_img.size[0]}x{dual_img.size[1]})")

    print("\nAll screenshots generated successfully.")

if __name__ == "__main__":
    main()
