#!/usr/bin/env python3
"""
Synchronized Binary Patcher, Assembler, DEX Verifier & APK v2 Signer for Axware Stream (versionCode = 17).
Reverts the 57872.png UI changes and restores the clean "Midnight Slate & Electric Rose-Cyan Studio" UI
while preserving all functional fixes:
1. Perbaikan 1 — Volume Mikrofon & Audio Internal (Perceptual Curve, Real-Time Control, Post-Volume Metering):
   - Quadratic perceptual volume gain curve (`vol * vol`) in `Pcm.applyVolume` and `Pcm.mix`.
   - Post-volume metering in `AxwareAudioSource.loopSingle`, `AxwareAudioSource.loopInternalToRing`, and
     `AxwareAudioSource.loopMix`.
   - Always use `AxwareAudioSource` in both `StreamService.startScreenLive` and `StreamService.prepareCamera`,
     and default `AudioSourceKind` to `MIX` (`Mikrofon + internal`).
   - Real-time volume slider updates in `StreamService$onCreate$1$1.emit` applied to both `stream?.audioSource`
     and `AudioTester.source`, with both `Volume mikrofon` and `Volume audio internal` sliders always visible
     in `Setelan › Audio` at 1% step precision.
2. Perbaikan 2 — Overlay Alert Web/Sociabuzz Tidak Menjadi Merah Saat Follow/Share/Repost:
   - Keep `OverlayRenderer.onEvent` and `OverlayRenderer.pumpAlerts` restricted to `ElementType.ALERT`
     only (never `ElementType.WEB`), and filter only `n._e.type === "ALERT"` in `overlay.html` (`runAlerts`).
   - Use Midnight Slate glass (`0xEB10172A`) instead of red (`0xEEFF2D55`) in `OverlayRenderer.drawBox`
     when previewing `ElementType.WEB` via the manual `Pratinjau alert` toggle.
3. Midnight Slate & Electric Rose-Cyan Studio UI/UX, Floating Capsule Navigation Dock,
   Alert Preview Animation, and Floating Live Comments Window without '×' button.
"""
import hashlib
import os
import struct
import zipfile
import zlib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12

PRISTINE_ZIP = "/working_dir/c_ae1d43de730027fa/user_uploaded_files/ttl=63d/user_uploaded/AxwareStream.zip"
SRC_ROOT = "/working_dir/c_441c94d66e265829/work/SOURCE/AxwareStream"
OUT_APK = "/working_dir/c_441c94d66e265829/work/FOLDER APK/Axware Stream.apk"

def patch_u8(buf, off, old_val, new_val, desc):
    actual = buf[off]
    assert actual == old_val, f"{desc} @{off}: expected 0x{old_val:02x}, got 0x{actual:02x}"
    buf[off] = new_val

def patch_u16(buf, off, old_val, new_val, desc):
    actual = struct.unpack_from("<H", buf, off)[0]
    assert actual == old_val, f"{desc} @{off}: expected 0x{old_val:04x}, got 0x{actual:04x}"
    struct.pack_into("<H", buf, off, new_val)

def patch_u32(buf, off, old_val, new_val, desc):
    actual = struct.unpack_from("<I", buf, off)[0]
    assert actual == old_val, f"{desc} @{off}: expected 0x{old_val:08x}, got 0x{actual:08x}"
    struct.pack_into("<I", buf, off, new_val)

def patch_u64(buf, off, old_val, new_val, desc):
    actual = struct.unpack_from("<Q", buf, off)[0]
    assert actual == old_val, f"{desc} @{off}: expected 0x{old_val:016x}, got 0x{actual:016x}"
    struct.pack_into("<Q", buf, off, new_val)

def patch_bytes(buf, off, old_bytes, new_bytes, desc):
    assert len(old_bytes) == len(new_bytes), f"{desc}: length mismatch {len(old_bytes)} != {len(new_bytes)}"
    actual = bytes(buf[off : off + len(old_bytes)])
    assert actual == old_bytes, f"{desc} @{off}: expected {old_bytes.hex(' ')}, got {actual.hex(' ')}"
    buf[off : off + len(new_bytes)] = new_bytes

def patch_f32_hi16(buf, insn_off, old_f, new_f, desc):
    op = buf[insn_off]
    assert op == 0x15, f"{desc} @{insn_off}: expected const/high16 (0x15), got 0x{op:02x}"
    old_hi = struct.unpack("<I", struct.pack("<f", old_f))[0] >> 16
    new_hi = struct.unpack("<I", struct.pack("<f", new_f))[0] >> 16
    patch_u16(buf, insn_off + 2, old_hi, new_hi, desc)

def patch_f32_const(buf, insn_off, old_f, new_f, desc):
    op = buf[insn_off]
    assert op == 0x14, f"{desc} @{insn_off}: expected const (0x14), got 0x{op:02x}"
    old_u = struct.unpack("<I", struct.pack("<f", old_f))[0]
    new_u = struct.unpack("<I", struct.pack("<f", new_f))[0]
    patch_u32(buf, insn_off + 2, old_u, new_u, desc)

def update_dex_headers(buf):
    hdr_size = struct.unpack_from("<I", buf, 32)[0]
    assert hdr_size == len(buf), f"DEX size mismatch: {hdr_size} != {len(buf)}"
    sha1 = hashlib.sha1(buf[32:]).digest()
    buf[12:32] = sha1
    chk = zlib.adler32(buf[12:]) & 0xFFFFFFFF
    struct.pack_into("<I", buf, 8, chk)
    return bytes(buf)

with zipfile.ZipFile(PRISTINE_ZIP, "r") as zf:
    orig_apk_bytes = zf.read("APK/AxwareStream.apk")

with open("/tmp/pristine_orig.apk", "wb") as f:
    f.write(orig_apk_bytes)

with zipfile.ZipFile("/tmp/pristine_orig.apk", "r") as zf:
    orig_dex1 = zf.read("classes.dex")
    orig_dex2 = zf.read("classes2.dex")
    orig_dex3 = zf.read("classes3.dex")
    orig_arsc = zf.read("resources.arsc")
    orig_am = zf.read("AndroidManifest.xml")
    orig_qr = zf.read("res/Qr.xml")
    orig_rv = zf.read("res/RV.xml")
    orig_bp = zf.read("assets/dexopt/baseline.prof")

# ============================================================================
# 1. Patch classes3.dex
# ============================================================================
dex3 = bytearray(orig_dex3)

# BuildConfig.VERSION_CODE: 8 -> 17
patch_u8(dex3, 10576933, 8, 17, "BuildConfig.VERSION_CODE 8 -> 17")

# Make LiveScreen$lambda$6$0 (2071), LiveScreen$lambda$7 (2072), LiveScreen$lambda$8 (2073),
# and StudioScreenKt.simulate (3058) public static final (0x19) instead of private static final (0x1a)
patch_u8(dex3, 10109686, 0x1A, 0x19, "LiveScreen$lambda$6$0 access_flags -> public static final")
patch_u8(dex3, 10109691, 0x1A, 0x19, "LiveScreen$lambda$7 access_flags -> public static final")
patch_u8(dex3, 10109696, 0x1A, 0x19, "LiveScreen$lambda$8 access_flags -> public static final")
patch_u8(dex3, 10118202, 0x1A, 0x19, "StudioScreenKt.simulate access_flags -> public static final")

# Repurpose unused field_ids[1269] (0x04f5, Ax.$stable) type_idx from I (4) to MutableState (179)
patch_u16(dex3, 485970, 4, 179, "field_ids[1269] Ax.$stable type -> Landroidx/compose/runtime/MutableState;")

# Rewrite Ax.<clinit> (@1764604, insns at 1764620, 214 words = 428 bytes)
# Midnight Slate & Electric Rose-Cyan Studio palette + Ax.$stable MutableState<Boolean>(false)
old_ax_clinit = bytes(orig_dex3[1764620 : 1764620 + 428])
new_ax_clinit = bytes.fromhex(
    "22 00 94 03 70 10 f4 0b 00 00 69 00 fd 04 "
    "18 00 13 09 06 ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 f8 04 "
    "18 00 2a 17 10 ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 02 05 "
    "18 00 3c 22 18 ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 03 05 "
    "18 00 52 30 22 ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 04 05 "
    "18 00 66 3f 2e ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 fe 04 "
    "18 00 55 2d ff ff 00 00 00 00 71 20 2b 01 10 00 0b 00 68 00 f6 04 "
    "14 02 55 2d ff 2e 71 10 2a 01 02 00 0b 02 68 02 f7 04 "
    "18 02 fe f2 00 ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 fa 04 "
    "14 02 fe f2 00 26 71 10 2a 01 02 00 0b 02 68 02 fb 04 "
    "18 02 fc fa f8 ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 05 05 "
    "18 02 b8 a3 94 ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 00 05 "
    "68 00 ff 04 "
    "18 02 81 b9 10 ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 01 05 "
    "18 02 0b 9e f5 ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 06 05 "
    "18 02 24 bf fb ff 00 00 00 00 71 20 2b 01 32 00 0b 02 68 02 fc 04 "
    "62 04 18 00 12 22 23 22 d8 27 "
    "71 20 27 01 10 00 0c 00 "
    "12 01 4d 00 02 01 18 00 8b 6b ff ff 00 00 00 00 "
    "71 20 2b 01 10 00 0b 00 71 20 27 01 10 00 0c 00 "
    "12 11 4d 00 02 01 71 10 2b 3b 02 00 0c 05 13 0b 0e 00 12 0c 16 06 00 00 16 08 00 00 12 0a "
    "77 09 1d 01 04 00 0c 00 69 00 f9 04 "
    "71 00 17 08 00 00 "
    "0c 00 "
    "69 00 f5 04 "
    "00 00 00 00 "
    "0e 00"
)
patch_bytes(dex3, 1764620, old_ax_clinit, new_ax_clinit, "Ax.<clinit> Midnight Slate palette & Ax.$stable MutableState init")

# AppRoot$lambda$3 (@1500968, insns at 1500984, pc=22..87 at offset 1501028, 66 words = 132 bytes):
# Transform bottom NavigationBar into a Floating Capsule Dock:
# Modifier.padding(12.dp).clip(CircleShape), containerColor = Ax.Surface2, tonalElevation = 0.dp
old_approot_nav = bytes(orig_dex3[1501028 : 1501028 + 132])
new_approot_nav = bytes.fromhex(
    "62 00 17 00 "
    "1f 00 c8 00 "
    "15 01 40 41 "
    "71 10 66 01 01 00 "
    "0a 01 "
    "71 20 59 00 10 00 "
    "0c 00 "
    "71 00 77 00 00 00 "
    "0c 01 "
    "1f 01 d9 00 "
    "71 20 17 01 10 00 "
    "0c 08 "
    "62 00 fd 04 "
    "6e 10 01 0c 00 00 "
    "0b 01 "
    "12 00 "
    "71 10 66 01 00 00 "
    "0a 05 "
    "22 00 72 01 "
    "70 20 61 03 b0 00 "
    "13 04 36 00 "
    "14 06 98 ce 88 5b "
    "71 54 0b 01 36 c0 "
    "0c 07 "
    "1f 07 0c 0c "
    "14 09 06 0c 03 00 "
    "13 0a 14 00 "
    "07 80 "
    "16 03 00 00 "
    "12 06 "
    "07 c8 "
    "77 0b af 00 00 00 "
    "00 00 "
    "28 04"
)
patch_bytes(dex3, 1501028, old_approot_nav, new_approot_nav, "AppRoot$lambda$3 Floating Capsule NavigationBar Dock")

# AppRoot$lambda$3$0 NavigationBarItem indicatorColor -> Ax.getAccentSoft
patch_u16(dex3, 1500762 + 2, 0x0BF5, 0x0BF6, "AppRoot NavigationBarItem indicatorColor -> Ax.AccentSoft")

# OverlayPreviewKt.OverlayPreview (@1564012, insns at 1564028):
old_op_399 = bytes(orig_dex3[1564826 : 1564826 + 72])
new_op_399 = bytes.fromhex(
    "39 05 06 00 "
    "62 07 f5 04 "
    "71 10 18 08 07 00 "
    "0a 07 "
    "72 10 dc 00 0b 00 "
    "0c 04 "
    "62 0e 13 00 "
    "6e 10 ca 00 0e 00 "
    "0c 0e "
    "33 e4 09 00 "
    "6e 10 0a 02 03 00 "
    "0c 04 "
    "72 20 e4 00 4b 00 "
    "1f 04 47 01 "
    "d8 0e 07 01 "
    "b5 8e "
    "6e 20 86 02 e4 00 "
    "12 1e"
)
patch_bytes(dex3, 1564826, old_op_399, new_op_399, "OverlayPreview effectiveAlertPreview & setWebPlaceholder")
patch_bytes(dex3, 1565878, bytes.fromhex("39 05 14 00"), bytes.fromhex("29 00 14 00"), "OverlayPreview always update Canvas lambda")

# OverlayPreviewKt.drawCheckerboard (@1566112, insns at 1566128): Midnight Slate checkerboard colors
patch_u64(dex3, 1566128 + 2, 0x00000000FF15151D, 0x00000000FF0B101E, "drawCheckerboard cell A 0xFF0B101E")
patch_u64(dex3, 1566294 + 2, 0x00000000FF1B1B25, 0x00000000FF131C33, "drawCheckerboard cell B 0xFF131C33")

# LiveScreenKt.PreviewBox (@1656868, insns at 1656884):
patch_bytes(dex3, 1658588, bytes.fromhex("38 13 3c 00"), bytes.fromhex("00 00 00 00"), "PreviewBox render OverlayPreview when !live.streaming")
old_pb_1423 = bytes(orig_dex3[1659730 : 1659730 + 54])
new_pb_1423 = bytes.fromhex(
    "72 10 d5 00 0e 00 "
    "1a 05 ee 78 "
    "1a 06 32 89 "
    "62 00 f5 04 "
    "71 10 18 08 00 00 "
    "0a 07 "
    "22 08 64 02 "
    "70 20 72 07 08 00 "
    "1f 08 07 0c "
    "08 09 0e 00 "
    "13 0a 36 00 "
    "77 06 66 04 05 00"
)
patch_bytes(dex3, 1659730, old_pb_1423, new_pb_1423, "PreviewBox add Pratinjau alert SwitchRow on Beranda Live")

# CommonKt.<clinit> Palette
patch_u64(dex3, 1518240 + 2, 0x00000000FFFE2C55, 0x00000000FFFF2D55, "Palette[2] Electric Rose")
patch_u64(dex3, 1518264 + 2, 0x00000000FF25F4EE, 0x00000000FF00F2FE, "Palette[3] Electric Cyan")
patch_u64(dex3, 1518288 + 2, 0x00000000FFFF5C7A, 0x00000000FFFF6B8B, "Palette[4] Rose Coral")
patch_u64(dex3, 1518312 + 2, 0x00000000FFFFC94D, 0x00000000FFFBBF24, "Palette[5] Gold")
patch_u64(dex3, 1518336 + 2, 0x00000000FFFFB020, 0x00000000FFF59E0B, "Palette[6] Warn")
patch_u64(dex3, 1518360 + 2, 0x00000000FF2BD98B, 0x00000000FF10B981, "Palette[7] Ok")
patch_u64(dex3, 1518488 + 2, 0x00000000FF8A8A9E, 0x00000000FF94A3B8, "Palette[12] Muted Slate")

# CommonKt.BrandMark (0.36f squircle radius factor, 0xFF10172A Slate-Navy surface)
patch_f32_const(dex3, 1520564, 0.30000001192092896, 0.36000001430511475, "BrandMark radius factor 0.36f")
patch_u64(dex3, 1520600 + 2, 0x00000000FF15151C, 0x00000000FF10172A, "BrandMark bg 0xFF10172A")

# CommonKt.SectionCard (26.dp super-rounded bento corners, 0.55f border alpha, 20.dp padding, 14.dp spacing)
patch_f32_hi16(dex3, 1534760, 20.0, 26.0, "SectionCard corner radius 26.dp")
patch_f32_const(dex3, 1534856, 0.6000000238418579, 0.550000011920929, "SectionCard border alpha 0.55f")
patch_f32_hi16(dex3, 1534910, 16.0, 20.0, "SectionCard padding 20.dp")
patch_f32_hi16(dex3, 1534934, 10.0, 14.0, "SectionCard vertical spacing 14.dp")

# CommonKt.StatTile (22.dp bento card radius, 34.dp icon box, 14.dp icon box radius)
patch_f32_hi16(dex3, 1540542, 16.0, 22.0, "StatTile corner radius 22.dp")
patch_f32_const(dex3, 1540638, 0.699999988079071, 0.550000011920929, "StatTile border alpha 0.55f")
patch_f32_hi16(dex3, 1540692, 12.0, 14.0, "StatTile padding 14.dp")
patch_f32_hi16(dex3, 1541060, 28.0, 34.0, "StatTile icon box 34.dp")
patch_f32_hi16(dex3, 1541080, 9.0, 14.0, "StatTile icon box radius 14.dp")
patch_f32_const(dex3, 1541120, 0.14000000059604645, 0.1599999964237213, "StatTile icon tint alpha 0.16f")

# CommonKt.Segmented (26.dp full capsule track, 5.dp track padding, 22.dp full capsule active pill, 11.dp vertical padding)
patch_f32_hi16(dex3, 1537082, 14.0, 26.0, "Segmented outer capsule radius 26.dp")
patch_f32_hi16(dex3, 1537146, 4.0, 5.0, "Segmented track padding & spacing 5.dp")
patch_f32_hi16(dex3, 1537616, 10.0, 22.0, "Segmented inner capsule pill radius 22.dp")
patch_f32_hi16(dex3, 1537832, 9.0, 11.0, "Segmented vertical padding 11.dp")

# CommonKt.Pill
patch_f32_const(dex3, 1531508, 0.1599999964237213, 0.18000000715255737, "Pill bg alpha 0.18f")
patch_f32_hi16(dex3, 1531552, 10.0, 12.0, "Pill horizontal padding 12.dp")
patch_f32_hi16(dex3, 1531564, 4.0, 6.0, "Pill vertical padding 6.dp")

# CommonKt.AxField & DropdownField (18.dp rounded corners)
patch_f32_hi16(dex3, 1519380, 14.0, 18.0, "AxField corner radius 18.dp")
patch_f32_hi16(dex3, 1526786, 14.0, 18.0, "DropdownField corner radius 18.dp")
patch_f32_hi16(dex3, 1527016, 10.0, 12.0, "DropdownField vertical padding 12.dp")

# LiveCommentsKt (22.dp list box, 16.dp preview bubble)
patch_f32_hi16(dex3, 1554482, 14.0, 22.0, "LiveCommentsPanel box corner radius 22.dp")
patch_f32_hi16(dex3, 1558484, 10.0, 16.0, "PreviewCommentsOverlay bubble corner radius 16.dp")

# AudioMetersKt (8.dp capsule bar, 18.dp buttons)
patch_f32_hi16(dex3, 1511316, 5.0, 8.0, "LevelMeter bar corner radius 8.dp")
patch_f32_hi16(dex3, 1506808, 12.0, 18.0, "AudioTestCard buttons corner radius 18.dp")

# LiveScreenKt (28.dp capsule GoLiveButton, 28.dp PreviewBox, 20.dp StreamStatus, 18.dp BudgetCard button)
patch_f32_hi16(dex3, 1649156, 18.0, 28.0, "GoLiveButton full capsule corner radius 28.dp")
patch_f32_hi16(dex3, 1657812, 20.0, 28.0, "PreviewBox corner radius 28.dp")
patch_f32_hi16(dex3, 1665694, 14.0, 20.0, "StreamStatus corner radius & h-padding 20.dp")
patch_f32_const(dex3, 1665786, 0.699999988079071, 0.550000011920929, "StreamStatus border alpha 0.55f")
patch_f32_hi16(dex3, 1641460, 12.0, 18.0, "BudgetCard button corner radius 18.dp")

# TikTokKt (26.dp TikTokAccountCard, 16.dp buttons, 14.dp key icon box, 12.dp Ganti akun chip, 18.dp dialog buttons)
patch_f32_hi16(dex3, 1594182, 20.0, 26.0, "TikTokAccountCard corner radius 26.dp")
patch_f32_const(dex3, 1594282, 0.699999988079071, 0.550000011920929, "TikTokAccountCard border alpha 0.55f")
patch_f32_hi16(dex3, 1594698, 14.0, 18.0, "TikTokAccountCard padding 18.dp")
patch_f32_hi16(dex3, 1595220, 12.0, 16.0, "TikTokAccountCard buttons corner radius & spacing 16.dp")
patch_f32_hi16(dex3, 1596312, 6.0, 12.0, "TikTokAccountCard Ganti akun chip corner radius 12.dp")
patch_f32_const(dex3, 1598076, 0.699999988079071, 0.550000011920929, "TikTokAccountCard divider alpha 0.55f")
patch_f32_hi16(dex3, 1598616, 10.0, 14.0, "TikTokAccountCard key icon box radius 14.dp")
patch_f32_hi16(dex3, 1590940, 12.0, 18.0, "UsernameDialog button corner radius 18.dp")
patch_f32_hi16(dex3, 1589946, 12.0, 18.0, "StreamKeyDialog button corner radius 18.dp")

# WebAlertKt (18.dp button, 20.dp preview box, Midnight Slate Surface2 background)
patch_f32_hi16(dex3, 1606966, 12.0, 18.0, "WebElementFields button corner radius 18.dp")
patch_f32_hi16(dex3, 1604852, 12.0, 20.0, "WebPreviewDialog box corner radius 20.dp")
patch_u64(dex3, 1604884 + 2, 0x00000000FF2A2A35, 0x00000000FF18223C, "WebPreviewDialog box bg Ax.Surface2")

# ScreenLayoutEditorKt (18.dp Reset button & Electric Rose-Gold mock game colors)
patch_f32_hi16(dex3, 1575796, 12.0, 18.0, "ScreenLayoutControls reset button corner radius 18.dp")
patch_u64(dex3, 1580946 + 2, 0x00000000FFFFC94D, 0x00000000FFFBBF24, "drawMockGame sun Ax.Gold")
patch_u64(dex3, 1581214 + 2, 0x00000000FFFE2C55, 0x00000000FFFF2D55, "drawMockGame HUD bar Ax.Accent")

# InteractionScreenKt (20.dp EventRow, 18.dp SimulateCard buttons, Midnight Slate typeColor)
patch_f32_hi16(dex3, 1627642, 14.0, 20.0, "EventRow corner radius 20.dp")
patch_f32_hi16(dex3, 1627738, 10.0, 14.0, "EventRow horizontal padding 14.dp")
patch_f32_hi16(dex3, 1627750, 8.0, 12.0, "EventRow vertical padding 12.dp")
patch_f32_hi16(dex3, 1626840, 12.0, 18.0, "SimulateCard buttons corner radius 18.dp")
patch_u64(dex3, 1627286 + 2, 0x00000000FFFFB020, 0x00000000FFF59E0B, "InteractionScreen.typeColor DONATION")
patch_u64(dex3, 1627320 + 2, 0x00000000FFFF5C7A, 0x00000000FFFF6B8B, "InteractionScreen.typeColor LIKE")

# StudioScreenKt (22.dp SceneChip, 14.dp inner preview, 20.dp main buttons/Sociabuzz/PickChip, 18.dp secondary buttons/rows, 16.dp ElementList/ToolIcon)
patch_f32_hi16(dex3, 1751002, 16.0, 22.0, "SceneChip corner radius 22.dp")
patch_f32_hi16(dex3, 1751200, 8.0, 14.0, "SceneChip inner preview corner radius 14.dp")
patch_f32_hi16(dex3, 1758962, 14.0, 20.0, "StudioScreen action buttons corner radius 20.dp")
patch_f32_hi16(dex3, 1739130, 12.0, 18.0, "SociabuzzCard item row corner radius 18.dp")
patch_f32_hi16(dex3, 1740532, 14.0, 20.0, "SociabuzzCard button corner radius 20.dp")
patch_f32_hi16(dex3, 1728086, 12.0, 16.0, "ElementList row corner radius 16.dp")
patch_f32_hi16(dex3, 1736960, 12.0, 18.0, "SceneSettings buttons corner radius 18.dp")
patch_f32_hi16(dex3, 1745464, 12.0, 18.0, "ImageElementFields buttons corner radius 18.dp")
patch_f32_hi16(dex3, 1750026, 12.0, 20.0, "PickChip corner radius 20.dp")
patch_f32_hi16(dex3, 1750172, 9.0, 10.0, "PickChip vertical padding 10.dp")
patch_f32_hi16(dex3, 1761478, 12.0, 16.0, "ToolIcon corner radius 16.dp")
patch_f32_hi16(dex3, 1761542, 6.0, 8.0, "ToolIcon padding 8.dp")

# SettingsScreenKt (18.dp buttons & clickable rows + always-visible volume sliders + 1% slider precision)
patch_f32_hi16(dex3, 1681752, 12.0, 18.0, "ServerCard button corner radius 18.dp")
patch_f32_hi16(dex3, 1677164, 12.0, 18.0, "AccountCard rows & button corner radius 18.dp")
patch_f32_hi16(dex3, 1675830, 12.0, 18.0, "AboutCard row corner radius 18.dp")
patch_bytes(dex3, 1684332, bytes.fromhex("32 21 ec 00"), bytes.fromhex("00 00 00 00"), "SettingsScreen always show volume sliders even when audio == MIC")
patch_bytes(dex3, 1684384, bytes.fromhex("33 21 6b 00"), bytes.fromhex("00 00 00 00"), "SettingsScreen always show mic volume slider")
patch_f32_hi16(dex3, 1672382, 20.0, 100.0, "SettingsScreen micVolume slider 1% precision (100f)")
patch_f32_hi16(dex3, 1672486, 20.0, 100.0, "SettingsScreen internalVolume slider 1% precision (100f)")

dex3_bytes = update_dex_headers(dex3)

# ============================================================================
# 2. Patch classes2.dex
# ============================================================================
dex2 = bytearray(orig_dex2)

# (a) StreamerChatService.notification() (@4883012, insns at 4883028):
old_notif_action = bytes.fromhex("1a 00 fe 7c 1f 00 97 21 6e 40 b1 99 25 10 0c 05")
patch_bytes(dex2, 4883184, old_notif_action, bytes(16), "StreamerChatService.notification remove Tutup action")

# (b) StreamerChatService.show() (@4888508, insns at 4888524):
old_header_block = bytes.fromhex(
    "1b 0f 9d 02 01 00 71 20 c3 ef f1 00 0c 0f 15 09 90 41 6e 20 50 10 9f 00 "
    "22 09 3d 21 70 20 79 ef 19 00 6e 20 48 10 9f 00 "
    "07 b9 1f 09 41 02 22 08 9e 02 12 ec 70 40 b6 0f a8 4c 1f 08 43 02 6e 30 ba 0f 95 08 "
    "07 e8 1f 08 41 02 6e 20 b9 0f 85 00 "
    "1f 0f 41 02 6e 20 b9 0f f5 00"
)
new_header_block = bytes.fromhex(
    "22 09 db 00 "
    "70 10 75 05 09 00 "
    "70 20 a7 ef 31 00 "
    "0a 08 "
    "6e 20 78 05 89 00 "
    "14 08 ff ff ff 1f "
    "6e 20 76 05 89 00 "
    "1f 09 d8 00 "
    "6e 20 37 10 9e 00 "
    "07 b9 1f 09 41 02 22 08 9e 02 12 ec 70 40 b6 0f a8 4c 1f 08 43 02 6e 30 ba 0f 95 08 "
    "07 e8 1f 08 41 02 6e 20 b9 0f 85 00 "
    "00 00 00 00"
)
patch_bytes(dex2, 4888848, old_header_block, new_header_block, "StreamerChatService.show remove × button & round toggle pill")

# (c) StreamerChatService & FloatingOverlayService$BubbleView colors & corner radii:
patch_u32(dex2, 4886274 + 2, 0xFFFE2C55, 0xFFFF2D55, "StreamerChatService GIFT color")
patch_u32(dex2, 4886366 + 2, 0xFF25F4EE, 0xFF00F2FE, "StreamerChatService FOLLOW color")
patch_u32(dex2, 4886398 + 2, 0xFF2BD98B, 0xFF10B981, "StreamerChatService SHARE color")
patch_u32(dex2, 4886462 + 2, 0xFF9A9AA8, 0xFF94A3B8, "StreamerChatService JOIN color")
patch_u32(dex2, 4885440 + 2, 0xFF9A9AA8, 0xFF94A3B8, "StreamerChatService joinText tail color")
patch_u32(dex2, 4885642 + 2, 0xFF25F4EE, 0xFF00F2FE, "StreamerChatService rowText CHAT user color")
patch_u16(dex2, 4885664 + 2, 0xC94D, 0xBF24, "StreamerChatService rowText fallback gold low16")
patch_f32_hi16(dex2, 4883648, 9.0, 12.0, "StreamerChatService sizeBtn corner radius 12.dp")
patch_u32(dex2, 4883666 + 2, 0x26FFFFFF, 0x1FFFFFFF, "StreamerChatService sizeBtn bg 0x1FFFFFFF")
patch_u32(dex2, 4888328 + 2, 0xFF8E8E9C, 0xFF94A3B8, "StreamerChatService filter off text color")
patch_f32_hi16(dex2, 4888350, 10.0, 50.0, "StreamerChatService filter chip capsule radius 50.dp")
patch_u32(dex2, 4888432 + 2, 0x40FFFFFF, 0x26FFFFFF, "StreamerChatService filter off stroke color")
patch_f32_hi16(dex2, 4888594, 14.0, 22.0, "StreamerChatService window corner radius 22.dp")
patch_u32(dex2, 4888612 + 2, 0xD9101016, 0xEB0B101E, "StreamerChatService window bg 0xEB0B101E")
patch_u32(dex2, 4888638 + 2, 0x40FFFFFF, 0x2EFFFFFF, "StreamerChatService window border 0x2EFFFFFF")
patch_f32_hi16(dex2, 4888660, 10.0, 12.0, "StreamerChatService box padH 12.dp")
patch_f32_hi16(dex2, 4888674, 6.0, 8.0, "StreamerChatService box padTop 8.dp")
patch_f32_hi16(dex2, 4888698, 8.0, 10.0, "StreamerChatService box padBottom 10.dp")
patch_u32(dex2, 4888772 + 2, 0xFFFE2C55, 0xFFFF2D55, "StreamerChatService header title color")
patch_f32_hi16(dex2, 4889460, 12.0, 50.0, "StreamerChatService unread chip capsule radius 50.dp")
patch_u32(dex2, 4889478 + 2, 0xFFFE2C55, 0xFFFF2D55, "StreamerChatService unread chip color")
patch_f32_hi16(dex2, 4889856, 2.0, 4.0, "StreamerChatService grip bar radius 4.dp")
patch_u32(dex2, 4889976 + 2, 0xFF9A9AA8, 0xFF94A3B8, "StreamerChatService sizeLabel color")
patch_u32(dex2, 4890958 + 2, 0xFF8E8E9C, 0xFF94A3B8, "StreamerChatService emptyView color")
patch_f32_hi16(dex2, 4886774, 4.0, 6.0, "StreamerChatService BadgeSpan radius 6.dp")
patch_bytes(
    dex2,
    4863144,
    bytes.fromhex("1a 01 78 0e 71 10 f0 03 01 00 0a 01"),
    bytes.fromhex("14 01 55 2d ff ff 00 00 00 00 00 00"),
    "FloatingOverlayService$BubbleView.<init> direct 0xFFFF2D55 color",
)

# (d) OverlayElement (<init>, fromJson, create) & Templates colors and corner radii:
patch_u64(dex2, 4783750 + 2, 0x00000000FFFE2C55, 0x00000000FFFF2D55, "OverlayElement.<init> default accentColor")
patch_u64(dex2, 4775534 + 2, 0x00000000FFFE2C55, 0x00000000FFFF2D55, "OverlayElement.fromJson default accentColor")
patch_u64(dex2, 4772664 + 2, 0x00000000CCFE2C55, 0x00000000CCFF2D55, "OverlayElement.create LIVE_TICKER bgColor")
patch_u32(dex2, 4772758 + 2, 0xFBEEEF87, 0xFBFFEF87, "OverlayElement.create LIVE_TICKER cornerRadius 24f")
patch_u64(dex2, 4772850 + 2, 0x00000000EEFE2C55, 0x00000000EEFF2D55, "OverlayElement.create ALERT bgColor")
patch_u64(dex2, 4773366 + 2, 0x00000000FF25F4EE, 0x00000000FF00F2FE, "OverlayElement.create LEADERBOARD accentColor")
patch_bytes(dex2, 4774250, bytes.fromhex("13 11 00 00"), bytes.fromhex("1a 11 8c 2b"), "OverlayElement.create WEB align CENTER")
patch_u32(dex2, 4774346 + 2, 0xFFEFFF83, 0xFFFFFF83, "OverlayElement.create WEB cornerRadius 24f & align")
patch_u32(dex2, 4774576 + 2, 0x55FE2C55, 0x55FF2D55, "OverlayElement.create SHAPE bgColor")
patch_u64(dex2, 4774586 + 2, 0x00000000FFFE2C55, 0x00000000FFFF2D55, "OverlayElement.create SHAPE borderColor")
patch_u32(dex2, 4774842 + 2, 0xFFEEE787, 0xFFFFE787, "OverlayElement.create RUNNING_TEXT cornerRadius 24f")
patch_u64(dex2, 4807982 + 2, 0x00000000FF25F4EE, 0x00000000FF00F2FE, "Templates.justChatting Goal Diamond accentColor")
patch_u64(dex2, 4808268 + 2, 0x00000000FF25F4EE, 0x00000000FF00F2FE, "Templates.justChatting Follow Alert borderColor")
patch_u64(dex2, 4809220 + 2, 0x00000000EEFE2C55, 0x00000000EEFF2D55, "Templates.liveSelling Banner bgColor")
patch_u64(dex2, 4809592 + 2, 0x00000000FF25F4EE, 0x00000000FF00F2FE, "Templates.liveSelling Viewers Counter accentColor")

# (e) OverlayRenderer.typeColor (@4788880), drawList (@4795992), drawLeaderboard (@4794788):
patch_u32(dex2, 4788918 + 2, 0xFFB0B0BE, 0xFF94A3B8, "OverlayRenderer.typeColor default Muted")
patch_u16(dex2, 4788932 + 2, 0xC94D, 0xBF24, "OverlayRenderer.typeColor SUBSCRIBE Gold")
patch_u32(dex2, 4788938 + 2, 0xFFFF5C7A, 0xFFFF6B8B, "OverlayRenderer.typeColor LIKE")
patch_u32(dex2, 4788954 + 2, 0xFF2BD98B, 0xFF10B981, "OverlayRenderer.typeColor SHARE Ok")
patch_u32(dex2, 4788962 + 2, 0xFF25F4EE, 0xFF00F2FE, "OverlayRenderer.typeColor FOLLOW Cyan")
patch_u32(dex2, 4788970 + 2, 0xFFFE2C55, 0xFFFF2D55, "OverlayRenderer.typeColor GIFT Accent")
patch_f32_hi16(dex2, 4797124, 3.0, 2.0, "OverlayRenderer.drawList badge capsule pill radius bh / 2f")
patch_f32_hi16(dex2, 4795408, 4.0, 3.0, "OverlayRenderer.drawLeaderboard row radius rowH / 3f")

# (f) OverlayRenderer.drawBox (@4790840, insns at 4790856):
# Perbaikan 2: Use Midnight Slate glass (0xEB10172A) instead of red (0xEEFF2D55) when previewing WEB
old_drawbox_46 = bytes(orig_dex2[4790948 : 4790948 + 80])
new_drawbox_46 = bytes.fromhex(
    "6e 10 19 eb 0d 00 "
    "0b 09 "
    "04 94 "
    "13 06 18 00 "
    "c5 64 "
    "16 07 00 00 "
    "31 04 04 07 "
    "39 04 0f 00 "
    "6e 10 36 eb 0d 00 "
    "0c 04 "
    "62 05 53 58 "
    "33 54 31 00 "
    "17 09 2a 17 10 eb "
    "15 00 c0 41 "
    "54 b4 aa 58 "
    "70 30 68 eb 9b 0a "
    "0a 05 "
    "6e 20 60 04 54 00 "
    "71 10 dc 03 05 00 "
    "0a 05"
)
patch_bytes(dex2, 4790948, old_drawbox_46, new_drawbox_46, "OverlayRenderer.drawBox Midnight Slate WEB preview card color & 24f radius")

# (g) OverlayRenderer.drawElement (@4791288, insns at 4791304):
old_de_157 = bytes(orig_dex2[4791618 : 4791618 + 136])
new_de_157 = (
    bytes.fromhex(
        "38 01 08 00 "
        "6e 10 60 eb 01 00 "
        "0c 09 "
        "28 2b "
        "00 00 "
    )
    + bytes(orig_dex2[4791640 : 4791724])
    + bytes.fromhex(
        "38 09 86 00 "
        "38 01 0a 00 "
        "6e 10 61 eb 01 00 "
        "0b 05 "
        "9c 05 0b 05 "
        "85 51 "
        "28 05 "
        "84 b1 "
        "d5 11 ff 07 "
        "82 11 "
        "a9 01 01 10"
    )
)
patch_bytes(dex2, 4791618, old_de_157, new_de_157, "OverlayRenderer.drawElement cyclic pop-in animation during alertPreview")
patch_bytes(dex2, 4791874, bytes.fromhex("1b 01 90 02 01 00"), bytes.fromhex("1b 01 91 02 01 00"), "OverlayRenderer.drawElement default alert text with amount")
patch_bytes(dex2, 4793294, bytes.fromhex("29 00 eb 00"), bytes.fromhex("29 00 ae fc"), "OverlayRenderer.drawElement route ElementType.WEB to ALERT animation renderer")

# NOTE: OverlayRenderer.onEvent (@4798300) and OverlayRenderer.pumpAlerts (@4798586) are intentionally
# kept pristine so ONLY ElementType.ALERT triggers activeAlerts on live events (never ElementType.WEB)!

# (j) OverlayRenderer.drawWeb (@4797524):
old_drawweb_fill = bytes.fromhex(
    "54 80 aa 58 14 04 ee f4 25 22 6e 20 60 04 40 00 54 80 ac 58 54 84 aa 58 6e 30 a5 03 09 04"
)
new_drawweb_fill = bytes.fromhex(
    "54 84 aa 58 "
    "14 05 fe f2 00 26 "
    "6e 20 60 04 54 00 "
    "15 06 c0 41 "
    "6e 54 a8 03 09 66 "
    "00 00 00 00"
)
patch_bytes(dex2, 4797690, old_drawweb_fill, new_drawweb_fill, "OverlayRenderer.drawWeb rounded fill")
patch_u32(dex2, 4797724 + 2, 0xCC25F4EE, 0xCC00F2FE, "OverlayRenderer.drawWeb stroke color 0xCC00F2FE")
patch_bytes(dex2, 4797794, bytes.fromhex("6e 30 a5 03 09 03"), bytes.fromhex("6e 53 a8 03 09 66"), "OverlayRenderer.drawWeb rounded dashed stroke")

# (k) Perbaikan 1 — Audio Volume Fixes in classes2.dex:
patch_bytes(
    dex2,
    4753288,
    bytes.fromhex("1a 00 e3 95 71 20 83 f5 03 00"),
    bytes.fromhex("c8 55 00 00 00 00 00 00 00 00"),
    "Pcm.applyVolume quadratic perceptual gain curve (vol = vol * vol)",
)
patch_bytes(
    dex2,
    4753376,
    bytes.fromhex("1a 00 04 d7 71 20 83 f5 04 00"),
    bytes.fromhex("c8 55 c8 77 00 00 00 00 00 00"),
    "Pcm.mix quadratic perceptual gain curve (micVol *= micVol, intVol *= intVol)",
)

old_loop_single = bytes(orig_dex2[4750778 : 4750778 + 82])
new_loop_single = old_loop_single[58:82] + old_loop_single[0:58]
patch_bytes(dex2, 4750778, old_loop_single, new_loop_single, "AxwareAudioSource.loopSingle applyVolume before AudioMeter")

old_loop_int = bytes(orig_dex2[4750486 : 4750486 + 46])
new_loop_int = (
    old_loop_int[12:40]
    + bytes.fromhex("52 70 df 57 c8 00 c8 05 c8 06 00 00")
    + old_loop_int[40:46]
)
patch_bytes(dex2, 4750486, old_loop_int, new_loop_int, "AxwareAudioSource.loopInternalToRing scale AudioMeter by internalVolume^2")

old_loop_mix = bytes(orig_dex2[4750642 : 4750642 + 46])
new_loop_mix = (
    old_loop_mix[12:40]
    + bytes.fromhex("52 90 e4 57 c8 00 c8 03 c8 05 00 00")
    + old_loop_mix[40:46]
)
patch_bytes(dex2, 4750642, old_loop_mix, new_loop_mix, "AxwareAudioSource.loopMix scale AudioMeter by micVolume^2")

patch_bytes(
    dex2,
    4873720,
    bytes.fromhex("32 60 21 00"),
    bytes.fromhex("00 00 00 00"),
    "StreamService.startScreenLive always use AxwareAudioSource even when audio == MIC",
)
old_prep_cam = bytes(orig_dex2[4871934 : 4871934 + 42])
new_prep_cam = bytes.fromhex(
    "70 20 fd d0 32 00 "
    "07 23 "
    "22 04 81 20 "
    "62 05 13 59 "
    "12 06 "
    "6e 10 65 ed 00 00 "
    "0a 07 "
    "15 08 80 3f "
    "12 09 "
    "12 0a "
    "76 07 d3 e9 04 00 "
    "00 00"
)
patch_bytes(dex2, 4871934, old_prep_cam, new_prep_cam, "StreamService.prepareCamera use AxwareAudioSource with s.micVolume")

patch_u8(dex2, 10467198, 0x0A, 0x09, "AudioTester.source access_flags -> public static")
old_oncreate_emit = bytes(orig_dex2[4869032 : 4869032 + 36])
new_oncreate_emit = bytes.fromhex(
    "12 00 "
    "38 08 06 00 "
    "6e 10 e1 ec 08 00 "
    "0c 00 "
    "39 00 04 00 "
    "62 00 c6 57 "
    "20 01 81 20 "
    "38 01 13 00 "
    "1f 00 81 20 "
    "00 00"
)
patch_bytes(dex2, 4869032, old_oncreate_emit, new_oncreate_emit, "StreamService$onCreate$1$1.emit update stream?.audioSource or AudioTester.source")

patch_u16(dex2, 4820758 + 2, 0x5913, 0x5914, "AudioSourceKind$Companion.parse default MIX")
patch_u16(dex2, 4838806 + 2, 0x5913, 0x5914, "StreamSettings.<init> default AudioSourceKind.MIX")

dex2_bytes = update_dex_headers(dex2)

# ============================================================================
# 3. Patch classes.dex (ThemeKt.<clinit> scheme & shapes: 14, 16, 20, 24, 28.dp)
# ============================================================================
dex1 = bytearray(orig_dex1)
patch_u64(dex1, 15295456 + 2, 0x00000000FFFFD6DE, 0x00000000FFFFDCE3, "ThemeKt.onPrimaryContainer")
patch_u64(dex1, 15295486 + 2, 0x00000000FF00201F, 0x00000000FF002022, "ThemeKt.onSecondary")
patch_u64(dex1, 15295612 + 2, 0x00000000FF111118, 0x00000000FF0B101E, "ThemeKt.surfaceContainerLow")
patch_u64(dex1, 15295654 + 2, 0x00000000FF22222E, 0x00000000FF223052, "ThemeKt.outlineVariant")
patch_f32_hi16(dex1, 15296918, 8.0, 14.0, "ThemeKt.shapes.extraSmall 14.dp")
patch_f32_hi16(dex1, 15296944, 12.0, 16.0, "ThemeKt.shapes.small 16.dp")
patch_f32_hi16(dex1, 15296970, 16.0, 20.0, "ThemeKt.shapes.medium 20.dp")
patch_f32_hi16(dex1, 15296996, 20.0, 24.0, "ThemeKt.shapes.large 24.dp")

dex1_bytes = update_dex_headers(dex1)

# ============================================================================
# 4. Update assets/dexopt/baseline.prof with new DEX CRC32s
# ============================================================================
crc1 = zlib.crc32(dex1_bytes) & 0xFFFFFFFF
crc2 = zlib.crc32(dex2_bytes) & 0xFFFFFFFF
crc3 = zlib.crc32(dex3_bytes) & 0xFFFFFFFF

bp_body = bytearray(zlib.decompress(orig_bp[17:]))
patch_u32(bp_body, 8, 0xB5AA8E99, crc1, "baseline.prof classes.dex CRC32")
patch_u32(bp_body, 35, 0x29F1801A, crc2, "baseline.prof classes2.dex CRC32")
patch_u32(bp_body, 63, 0x9966DD85, crc3, "baseline.prof classes3.dex CRC32")
bp_comp = zlib.compress(bytes(bp_body), 1)
bp_bytes = orig_bp[:9] + struct.pack("<II", len(bp_body), len(bp_comp)) + bp_comp

# ============================================================================
# 5. Patch AndroidManifest.xml (versionCode: 8 -> 17), resources.arsc,
#    res/Qr.xml, res/RV.xml, and overlay/overlay.html
# ============================================================================
am = bytearray(orig_am)
patch_u32(am, 5752, 8, 17, "AndroidManifest.xml versionCode 8 -> 17")
am_bytes = bytes(am)

arsc = bytearray(orig_arsc)
patch_u32(arsc, 294240, 0xFF0E0E14, 0xFF0B101E, "resources.arsc ic_bg")
patch_u32(arsc, 294224, 0xFFFE2C55, 0xFFFF2D55, "resources.arsc ic_accent")
patch_u32(arsc, 294320, 0xFF09090D, 0xFF060913, "resources.arsc window_bg")
arsc_bytes = bytes(arsc)

qr = bytearray(orig_qr)
qr_bytes = bytes(qr).replace(struct.pack("<I", 0xFF25F4EE), struct.pack("<I", 0xFF00F2FE)).replace(
    struct.pack("<I", 0xFFFE2C55), struct.pack("<I", 0xFFFF2D55)
)
assert len(qr_bytes) == len(qr)

rv = bytearray(orig_rv)
patch_u32(rv, 496, 0xFF1C1C24, 0xFF18223C, "res/RV.xml gradient start")
patch_u32(rv, 596, 0xFF09090D, 0xFF060913, "res/RV.xml gradient end")
rv_bytes = bytes(rv)

overlay_html_bytes = open(f"{SRC_ROOT}/app/src/main/resources/overlay/overlay.html", "rb").read()

replacements = {
    "assets/dexopt/baseline.prof": bp_bytes,
    "classes.dex": dex1_bytes,
    "classes2.dex": dex2_bytes,
    "classes3.dex": dex3_bytes,
    "AndroidManifest.xml": am_bytes,
    "resources.arsc": arsc_bytes,
    "res/Qr.xml": qr_bytes,
    "res/RV.xml": rv_bytes,
    "overlay/overlay.html": overlay_html_bytes,
}

# ============================================================================
# 6. Repackage APK with 16 KB page alignment for .so and 4-byte alignment
#    for other stored entries, then sign with APK Signature Scheme v2
# ============================================================================
eocd_off = orig_apk_bytes.rfind(b"PK\x05\x06")
total_entries = struct.unpack_from("<H", orig_apk_bytes, eocd_off + 10)[0]
cd_size, cd_off = struct.unpack_from("<II", orig_apk_bytes, eocd_off + 12)
sb_size2 = struct.unpack_from("<Q", orig_apk_bytes, cd_off - 24)[0]
sb_start = cd_off - 8 - sb_size2

orig_pairs = {}
pos = sb_start + 8
while pos < cd_off - 24:
    plen = struct.unpack_from("<Q", orig_apk_bytes, pos)[0]
    pid = struct.unpack_from("<I", orig_apk_bytes, pos + 8)[0]
    orig_pairs[pid] = orig_apk_bytes[pos + 12 : pos + 8 + plen]
    pos += 8 + plen

cd_entries = []
cpos = cd_off
for _ in range(total_entries):
    assert orig_apk_bytes[cpos : cpos + 4] == b"PK\x01\x02"
    ver_made, ver_need, flag, method, mtime, mdate, crc, csize, usize, nlen, elen, clen, disk, iattr, eattr, lhoff = (
        struct.unpack_from("<HHHHHHIIIHHHHHII", orig_apk_bytes, cpos + 4)
    )
    name = orig_apk_bytes[cpos + 46 : cpos + 46 + nlen].decode("utf-8")
    cd_extra = orig_apk_bytes[cpos + 46 + nlen : cpos + 46 + nlen + elen]
    cd_comment = orig_apk_bytes[cpos + 46 + nlen + elen : cpos + 46 + nlen + elen + clen]
    assert orig_apk_bytes[lhoff : lhoff + 4] == b"PK\x03\x04"
    lh_nlen, lh_elen = struct.unpack_from("<HH", orig_apk_bytes, lhoff + 26)
    lh_extra = orig_apk_bytes[lhoff + 30 + lh_nlen : lhoff + 30 + lh_nlen + lh_elen]
    raw_comp_data = orig_apk_bytes[lhoff + 30 + lh_nlen + lh_elen : lhoff + 30 + lh_nlen + lh_elen + csize]
    cd_entries.append({
        "name": name, "ver_made": ver_made, "ver_need": ver_need, "flag": flag,
        "method": method, "mtime": mtime, "mdate": mdate, "crc": crc,
        "csize": csize, "usize": usize, "cd_extra": cd_extra, "cd_comment": cd_comment,
        "disk": disk, "iattr": iattr, "eattr": eattr, "lh_extra": lh_extra,
        "raw_comp_data": raw_comp_data,
    })
    cpos += 46 + nlen + elen + clen

sec1_buf = bytearray()
new_cd_buf = bytearray()

for e in cd_entries:
    name = e["name"]
    name_bytes = name.encode("utf-8")
    new_lhoff = len(sec1_buf)
    if name in replacements:
        uncomp = replacements[name]
        usize = len(uncomp)
        crc = zlib.crc32(uncomp) & 0xFFFFFFFF
        if e["method"] == 0:
            comp = uncomp
        else:
            co = zlib.compressobj(6, zlib.DEFLATED, -15)
            comp = co.compress(uncomp) + co.flush()
        csize = len(comp)
    else:
        comp = e["raw_comp_data"]
        csize = e["csize"]
        usize = e["usize"]
        crc = e["crc"]

    if e["method"] == 0:
        align = 16384 if name.endswith(".so") else 4
        pad_needed = (align - ((new_lhoff + 30 + len(name_bytes)) % align)) % align
        lh_extra = b"\x00" * pad_needed
    else:
        lh_extra = b""

    lh = struct.pack(
        "<4sHHHHHIIIHH",
        b"PK\x03\x04",
        e["ver_need"], e["flag"], e["method"], e["mtime"], e["mdate"],
        crc, csize, usize, len(name_bytes), len(lh_extra),
    )
    sec1_buf.extend(lh)
    sec1_buf.extend(name_bytes)
    sec1_buf.extend(lh_extra)
    if e["method"] == 0:
        if name.endswith(".so"):
            assert len(sec1_buf) % 16384 == 0, f"Unaligned .so entry {name} at {len(sec1_buf)}"
        else:
            assert len(sec1_buf) % 4 == 0, f"Unaligned stored entry {name} at {len(sec1_buf)}"
    sec1_buf.extend(comp)

    cd_hdr = struct.pack(
        "<4sHHHHHHIIIHHHHHII",
        b"PK\x01\x02",
        e["ver_made"], e["ver_need"], e["flag"], e["method"], e["mtime"], e["mdate"],
        crc, csize, usize, len(name_bytes), len(e["cd_extra"]), len(e["cd_comment"]),
        e["disk"], e["iattr"], e["eattr"], new_lhoff,
    )
    new_cd_buf.extend(cd_hdr)
    new_cd_buf.extend(name_bytes)
    new_cd_buf.extend(e["cd_extra"])
    new_cd_buf.extend(e["cd_comment"])

# Build APK Signing Block (12288 bytes = 3 * 4096)
v2_orig = bytearray(orig_pairs[0x7109871A])
pkds_val = orig_pairs[0x504B4453]
base_pairs_len = (8 + 4 + len(v2_orig)) + (8 + 4 + len(pkds_val))
brew_len = 12280 - 24 - base_pairs_len - 12
assert brew_len > 0
brew_val = b"\x00" * brew_len

new_sb_start = len(sec1_buf)
new_cd_off = new_sb_start + 12288

eocd_for_digest = struct.pack(
    "<4sHHHHIIH",
    b"PK\x05\x06", 0, 0, total_entries, total_entries, len(new_cd_buf), new_sb_start, 0
)
eocd_final = struct.pack(
    "<4sHHHHIIH",
    b"PK\x05\x06", 0, 0, total_entries, total_entries, len(new_cd_buf), new_cd_off, 0
)

chunk_digests = []
for sec in (bytes(sec1_buf), bytes(new_cd_buf), eocd_for_digest):
    off = 0
    while off < len(sec):
        chunk = sec[off : off + 1048576]
        h = hashlib.sha256()
        h.update(b"\xa5" + struct.pack("<I", len(chunk)) + chunk)
        chunk_digests.append(h.digest())
        off += 1048576

top_h = hashlib.sha256()
top_h.update(b"\x5a" + struct.pack("<I", len(chunk_digests)) + b"".join(chunk_digests))
new_merkle_digest = top_h.digest()

sd_len = struct.unpack_from("<I", v2_orig, 8)[0]
signed_data = bytearray(v2_orig[12 : 12 + sd_len])
signed_data[16:48] = new_merkle_digest

p12_bytes = open(f"{SRC_ROOT}/app/axware-release.jks", "rb").read()
priv_key, cert, _ = pkcs12.load_key_and_certificates(p12_bytes, b"axware2026")
new_sig = priv_key.sign(bytes(signed_data), padding.PKCS1v15(), hashes.SHA256())
assert len(new_sig) == 256
cert.public_key().verify(new_sig, bytes(signed_data), padding.PKCS1v15(), hashes.SHA256())

v2_new = bytearray(v2_orig)
v2_new[12 : 12 + sd_len] = signed_data
sig_off = 12 + sd_len + 16
assert len(v2_new[sig_off : sig_off + 256]) == 256
v2_new[sig_off : sig_off + 256] = new_sig

sig_block = bytearray()
sig_block.extend(struct.pack("<Q", 12280))
for pid, pval in ((0x7109871A, bytes(v2_new)), (0x504B4453, pkds_val), (0x42726577, brew_val)):
    sig_block.extend(struct.pack("<QI", 4 + len(pval), pid))
    sig_block.extend(pval)
sig_block.extend(struct.pack("<Q", 12280))
sig_block.extend(b"APK Sig Block 42")
assert len(sig_block) == 12288

final_apk = bytes(sec1_buf) + bytes(sig_block) + bytes(new_cd_buf) + eocd_final
os.makedirs(os.path.dirname(OUT_APK), exist_ok=True)
with open(OUT_APK, "wb") as f:
    f.write(final_apk)

print(f"SUCCESS: Built & signed {OUT_APK} ({len(final_apk):,} bytes)")
print(f"RSA-2048 signature verified against release cert SHA-256: {hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()}")
