# Sonic the Hedgehog 2 (Genesis) (REV01) Parallax (Deformation) Scrolling
(ROM offsets, RAM variables, and a reimplementation strategy)

This document describes how **Sonic 2 (Mega Drive / Genesis) REV01** implements background parallax via **horizontal line scroll** (a per-scanline hscroll table) plus per-plane vertical scroll (VSRAM). It is intended as a source of truth you can hand to a coding agent to compare against your Java engine implementation.

Primary reference: the **Sonic 2 disassembly** from `https://www.git.fragag.ca/s2disasm.git` (commit id `fa1059f5286babe59bc95d9b2111688d15c5e3e4`). Key routines and variables below come from `s2.asm` and `s2.constants.asm` in that repo.

Secondary references (cross-check) are used to validate Mega Drive VDP behaviour (register meanings, scroll table sizing, and word ordering). Suggested reference pages:
- YaVDP register notes (especially register $0B scrolling mode semantics)
- Mega Drive Development Wiki / similar VDP register references
- Discussion threads confirming hscroll word ordering (Plane A vs Plane B)

---

## 1. What “parallax scrolling” means in Sonic 2
Sonic 2 does not have multiple hardware background planes beyond **Plane A** and **Plane B**. Most of its “multiple background layers” effect is produced by:

(1) Writing **a horizontal scroll value for every scanline** into the VDP’s **HScroll Table** in VRAM, so different vertical bands scroll at different horizontal speeds.

(2) Updating **background “camera” positions** in RAM (Camera_BG_X_pos, Camera_BG2_X_pos, Camera_BG3_X_pos, etc) so the engine can stream (redraw) the correct tiles into Plane B for the different parallax regions.

(3) Writing vertical scroll values for Plane A and Plane B into **VSRAM** each frame (usually FG uses Camera_Y_pos, BG uses Camera_BG_Y_pos).

Parallax in Sonic 2 is therefore a mixture of:
- VDP configuration (line scroll enabled)
- A per-frame **software generator** that fills a scanline scroll buffer (per zone)
- Tile streaming logic driven by “camera” trackers and scroll flags

---

## 2. VDP configuration that makes line scroll possible

### 2.1 HScroll Table address and size (VRAM)
Sonic 2 sets the HScroll Table base to **VRAM $FC00** and treats it as **$0380 bytes** long (covers 224 scanlines, with 4 bytes per scanline: one word per plane). In `s2.constants.asm`:

- `VRAM_Horiz_Scroll_Table: equ $FC00`
- `VRAM_Horiz_Scroll_Table_Size: equ $380`
- `VRAM_Horiz_Scroll_Table_End: equ $FF7F`

Only the first **$380** bytes are relevant for the visible 224-line playfield; the rest is unused for this purpose.

### 2.2 HScroll mode (VDP Register $0B)
During **Level** gameplay, Sonic 2 enables **line scrolling** by writing **VDP Reg $0B = $03** during the Level init sequence (before drawing the initial background). (Other modes may use other values temporarily.)

Cross-check: YaVDP and other VDP references describe how Reg $0B controls horizontal scroll mode granularity.

### 2.3 The per-scanline entry format (word ordering)
Sonic 2 builds the hscroll table in RAM as **longwords**, where:

- upper word = Plane A (foreground) horizontal scroll
- lower word = Plane B (background) horizontal scroll

This longword stream is DMA copied into the VDP HScroll Table each VBlank.

This ordering is confirmed by the canonical “minimal” routine, which sets:
- Plane A scroll from `-Camera_X_pos` (full-rate foreground)
- Plane B scroll from `-Camera_BG_X_pos` (parallax background)

If your engine swaps these (or packs them as BG then FG), parallax will look close-but-wrong (including subtle vertical-looking misalignments if coupled with other offsets).

---

## 3. RAM variables (REV01) that drive scrolling
Addresses come from `s2.constants.asm`.

### 3.1 Camera positions (fixed point storage, usually consumed as word pixels)
Camera positions are stored as `ds.l` (32-bit). Most scroll math uses only the **low word** (pixel integer), but some routines treat them as 16.16 fixed.

Foreground camera:
- `Camera_X_pos` (ds.l)
- `Camera_Y_pos` (ds.l)

Background “camera” trackers:
- `Camera_BG_X_pos`, `Camera_BG_Y_pos` (ds.l)
- `Camera_BG2_X_pos`, `Camera_BG2_Y_pos` (ds.l)
- `Camera_BG3_X_pos`, `Camera_BG3_Y_pos` (ds.l)

2P variants exist (P2 copies).

### 3.2 Vertical scroll factors written to VSRAM
- `Vscroll_Factor` is a longword:
  - `Vscroll_Factor_FG` is the first word (Plane A)
  - `Vscroll_Factor_BG` is the second word (Plane B)

In `DeformBgLayer`, Sonic 2 sets:
- `Vscroll_Factor_FG = (word)Camera_Y_pos`
- `Vscroll_Factor_BG = (word)Camera_BG_Y_pos`

Then VInt writes this longword into VSRAM.

**Critical correctness note:** If you read the wrong word of Camera_* (for example the high word instead of low word), BG vertical placement will be “slightly off” or drift.

### 3.3 Horizontal scroll staging buffer in RAM
- `Horiz_Scroll_Buf: ds.b $400`

Only the first **$380 bytes** are DMA-copied to VRAM (matching the scroll table size). The buffer is oversized (alignment/headroom).

Per scanline consumes **4 bytes**:
- word 0: Plane A hscroll
- word 1: Plane B hscroll

224 scanlines * 4 bytes = $380.

---

## 4. Where the scroll tables are produced each frame (DeformBgLayer)

### 4.1 Key ROM routine offsets (REV01)
Top-level scroll generator:
- `DeformBgLayer` at **ROM $C3D0**

Zone jump table + zone routines:
- `SwScrl_Index` (zone ordered jump offsets)
- `SwScrl_Title` at ROM $C51E
- `SwScrl_EHZ` at ROM $C57E
- `SwScrl_RippleData` at ROM $C682
- `SwScrl_EHZ_2P` at ROM $C6C4
- `SwScrl_Lev2` at ROM $C7BA
- `SwScrl_MTZ` at ROM $C7F2
- `SwScrl_WFZ` at ROM $C82A
- `SwScrl_HTZ` at ROM $C964
- `SwScrl_HPZ` at ROM $CBA0
- `SwScrl_OOZ` at ROM $CC66
- `SwScrl_MCZ` at ROM $CD2C
- `SwScrl_CNZ` at ROM $D0C6
- `SwScrl_CPZ` at ROM $D27C
- `SwScrl_DEZ` at ROM $D382
- `SwScrl_ARZ` at ROM $D4AE
- `SwScrl_SCZ` at ROM $D5DE
- `SwScrl_Minimal` at ROM $D666

SwScrl_Index address note:
- The disasm commentary does not label `SwScrl_Index`’s address explicitly.
- It is directly between the end of `DeformBgLayer` and `SwScrl_Title` (ROM $C51E).
- It is a standard zone-ordered offset table used with `jmp (pc,d0.w)` semantics.
- If byte exact verification is needed, confirm against a built REV01 ROM and a symbol/map listing.

### 4.2 Frame execution order and responsibilities (high-level pseudocode)
`DeformBgLayer` does roughly:

```
if Deform_lock != 0:
    return

ScrollHoriz()           // updates Camera_X_pos and Camera_X_pos_diff
ScrollVerti()           // updates Camera_Y_pos and Camera_Y_pos_diff
RunDynamicLevelEvents() // can lock/clamp camera, alter scroll behaviour

Vscroll_Factor_FG = (word)Camera_Y_pos
Vscroll_Factor_BG = (word)Camera_BG_Y_pos

zone = (Current_Zone_Act >> 8) & $3F
jump SwScrl_Index[zone]
```

Each `SwScrl_<zone>` routine is responsible for:
(1) Updating background camera(s) (Camera_BG_X_pos etc) and scroll flags for background tile streaming
(2) Writing per scanline (Plane A, Plane B) hscroll words into `Horiz_Scroll_Buf` (at least $380 bytes)

---

## 5. When line scroll is enabled (Level init ordering)
Level mode explicitly enables line scrolling by setting VDP Reg $0B to $03 during setup, then runs camera/background initialization and performs an initial `DeformBgLayer` before drawing initial tiles.

If your engine draws Plane B (or “locks in” its baseline origin) before performing this early `DeformBgLayer` pass, it can produce a persistent “background slightly lower than expected” error (because BG vertical factors and BG camera trackers are not yet aligned to their intended state).

---

## 6. Camera initialisation for backgrounds (InitCameraValues and InitCam_*)

### 6.1 Core routine and offset
- `InitCameraValues` at ROM **$C258**
- Zone index table `InitCam_Index` at ROM **$C296**

This is called during level startup (as part of level size / camera setup).

### 6.2 Important REV01 behaviour (default BG = FG before zone-specific logic)
If a star post has not been hit, `InitCameraValues` starts by copying current camera positions into BG positions (word sized copies) and then calls a zone-specific init routine.

Therefore a zone init routine that is a no-op will leave:
- `Camera_BG_X_pos = Camera_X_pos`
- `Camera_BG_Y_pos = Camera_Y_pos`

This is extremely important for REV01 correctness (and is a common source of subtle misalignment if missed).

### 6.3 Zone init routines (REV01 math on words)
Selected via `InitCam_Index` (similar to SwScrl_Index).

Key routines:

- `InitCam_EHZ` at ROM $C2B8:
  Clears BG, BG2, BG3 camera positions (and TempArray_LayerDef), for P1 and P2.

- `InitCam_Std` at ROM $C2E4:
  `Camera_BG_Y_pos = Camera_Y_pos >> 2`
  `Camera_BG_X_pos = Camera_X_pos >> 3`

- `InitCam_OOZ` at ROM $C322:
  `Camera_BG_Y_pos = (Camera_Y_pos >> 3) + $50`
  `Camera_BG_X_pos = 0`

- `InitCam_MCZ` at ROM $C332:
  Clears BG X for both players.
  Act-dependent BG Y:
  Act 1:
    `Camera_BG_Y_pos = (Camera_Y_pos / 3) - $140`
  Act 2:
    `Camera_BG_Y_pos = (Camera_Y_pos / 6) - $10`

- `InitCam_CNZ` at ROM $C364:
  Clears BG X and BG Y (for P1 and P2).

- `InitCam_CPZ` at ROM $C372:
  `Camera_BG_Y_pos = Camera_Y_pos >> 2` (also for P2)
  `Camera_BG2_X_pos = Camera_X_pos >> 1`
  `Camera_BG_X_pos  = Camera_X_pos >> 2`

- `InitCam_ARZ` at ROM $C38C:
  If Act 2:
    `Camera_BG_Y_pos = (Camera_Y_pos - $E0) >> 1`
  Else:
    `Camera_BG_Y_pos = Camera_Y_pos - $180`
  BG X uses signed multiply:
    `Camera_BG_X_pos = (Camera_X_pos * $0119) >> 8`
  Also stored to `Camera_ARZ_BG_X_pos`.
  Clears BG2_Y and BG3_Y.

- `InitCam_SCZ` at ROM $C3C6:
  Clears BG X and BG Y.

REV01 quirk (Hidden Palace / HPZ):
`InitCam_HPZ` exists as a label, but HPZ-specific code is inside an `if gameRevision=0` conditional.
In REV01 it effectively becomes a no-op (falls through to an `rts`), so the earlier “BG = FG” default remains until scroll routines adjust it.

---

## 7. VBlank usage (what to replicate)
During vertical blank, Sonic 2 transfers:
- `Horiz_Scroll_Buf` (first $380 bytes) to VRAM $FC00 (HScroll table)
- `Vscroll_Factor` longword to VSRAM

Your renderer does not need to emulate DMA itself, but must replicate:
- word ordering (Plane A first, Plane B second)
- sign conventions (horizontal scroll uses negative camera X; vertical is applied as stored)
- per scanline application (or per-band, if you emulate by drawing strips)

---

## 8. Worked example (EHZ 1P) (how to translate a SwScrl routine)
This shows how to interpret one zone routine precisely, so a coding agent has a template for porting other zones.

### 8.1 SwScrl_EHZ overview (ROM $C57E)
The EHZ routine:
(1) Sets `Vscroll_Factor_BG` from `Camera_BG_Y_pos`
(2) Builds `Horiz_Scroll_Buf` as a sequence of (Plane A word, Plane B word) pairs
(3) Applies a ripple effect using `SwScrl_RippleData` (ROM $C682)
(4) Uses a gradual slope for the bottom region

Disassembly note: EHZ has a known original bug where the bottom 8 pixels are not assigned hscroll values.

### 8.2 EHZ banding concepts
Most bands are filled using `move.l d0,(a1)+` loops:
- Scanlines per band = `bandBytes / 4`
- Each scanline writes a single (PlaneA, PlaneB) longword

Early bands:
- Band 1: $58 bytes => 22 scanlines (Plane A = `-camX`, Plane B = 0)
- Band 2: $E8 bytes => 58 scanlines (Plane A = `-camX`, Plane B = `(-camX)>>6`)

Ripple band:
- Reads bytes from `SwScrl_RippleData`, sign-extends, adds to a base, and uses result for Plane B while Plane A stays constant.

Later bands:
- Additional constant fills and then more complex slope fills where Plane B changes gradually over scanlines.

Porting recommendation:
- Translate loop-for-loop (including the slope math) rather than trying to “recreate” EHZ parallax from a simplified model.

---

## 9. Practical strategy to reimplement (accurately, but in your engine)

### 9.1 Treat SwScrl_<zone> as the canonical parallax generator
Do not attempt to generalize parallax heuristically. Sonic 2’s background scrolling is highly zone-specific.
The most accurate approach is to port the relevant `SwScrl_...` routine(s) and have them output:

- `short[] hscrollA` (224 entries)
- `short[] hscrollB` (224 entries)
- `short vscrollA`, `short vscrollB`

Or a packed 224-longword buffer (A in high word, B in low word), matching the original.

### 9.2 Implement minimal shared primitives first
Implement a thin compatibility layer mirroring the original RAM semantics:
- 32-bit camera positions with a consistent `word()` view
- camera diffs (`Camera_X_pos_diff`, `Camera_Y_pos_diff`)
- background camera trackers (BG/BG2/BG3)
- a helper that writes N scanlines of constant (A,B)
- a helper for segmented fills (row-height driven routines, CPZ/CNZ style)
- scroll flags functions (if your streaming uses them)

### 9.3 Port in this order (to debug “background too low” early)
(1) `InitCameraValues` ($C258) and relevant init routines (confirm BG Y matches the game at start)
(2) `DeformBgLayer` ($C3D0)
(3) `SwScrl_Minimal` ($D666) (validates packing, sign, ordering)
(4) Your target zone routine (e.g. `SwScrl_EHZ` $C57E)

Once SwScrl_Minimal matches, “background too low” is usually a vertical factor or init-order issue.

### 9.4 Build an oracle capture (recommended)
From a reference emulator:
- at a known frame, capture:
  - `Camera_Y_pos`, `Camera_BG_Y_pos`
  - VSRAM plane A/B scroll words
  - VRAM $FC00..$FF7F (HScroll table, first $380 bytes)

Then run your engine with identical camera state and compare buffers word-for-word.

---

## 10. Common causes of “background is a bit lower than it should be”
(1) Reading the wrong word from 32-bit camera values (16.16 fixed mishandled).
(2) Missing zone init semantics (especially REV01 no-op init routines, where BG defaults to FG).
(3) FG/BG word order swapped in VSRAM or HScroll packing.
(4) Performing initial background draw before the initial `DeformBgLayer` pass Sonic 2 executes in Level init.
(5) Applying viewport/status bar offsets inconsistently (scroll values are in screen pixel space for the active playfield).

---

## 11. References (URLs)
Primary:
- https://www.git.fragag.ca/s2disasm.git (commit fa1059f5286babe59bc95d9b2111688d15c5e3e4)
  - s2.asm
  - s2.constants.asm

Cross-check:
- YaVDP documentation (VDP register $0B scroll mode semantics)
- Mega Drive Development Wiki / VDP registers pages
- Additional plane A/B word ordering confirmations (forums / docs)
