# S3K object culling and off-screen-deletion geometry

Research round, 2026-08-21. Source: `docs/skdisasm/sonic3k.asm` (S&K half) unless a line
says otherwise; `docs/skdisasm/s3.asm` (S3 half) checked for divergence and found
byte-identical for the shared helpers. Line numbers are into those files; `loc_XXXXX`
labels are ROM addresses, not lines.

This document exists because S3K's geometry cannot be inferred from S1/S2. It records what
the ROM actually does. **No conversions were landed and no runtime code was changed.**

## Vocabulary

S3K's SST field names are its own, and they are *not* S1's (`obRender`). From
`sonic3k.constants.asm`:

| Field | Offset | Note |
|---|---|---|
| `render_flags` | `4` | same spelling as S2, but see the bit table below |
| `height_pixels` | `6` | **S3K has a real half-height byte; S2 does not** |
| `width_pixels` | `7` | half-width, as S2 |
| `priority` | `8` | display-list bucket, units of `$80` |
| `y_radius` / `x_radius` | `$1E` / `$1F` | collision only — never used by the render cull |
| `respawn_addr` | — | back-reference into `Object_respawn_table` |

`render_flags` bits used by culling (`Render_Sprites`, sonic3k.asm:36318-36525):

- **bit 7** — "on-screen" flag. **Written by the render pass**: cleared for every listed
  object at sonic3k.asm:36338, re-set at sonic3k.asm:36370 (`loc_1ADB2`) and 36497
  (`loc_1AEE4`) only for objects that survived the cull. Object code reads it on the
  *following* frame.
- **bit 6** — multi-draw / childsprite object (sonic3k.asm:36342).
- **bit 5** — static mappings (sonic3k.asm:36375).
- **bit 2** — coordinate space. **The disassembly's comments on this bit are inverted at
  both sites** (sonic3k.asm:36344 and 36442 both read "is this to be positioned by screen
  coordinates? if it is, branch", but the branch conditions are opposite). Reading the
  code: bit 2 **set** = level coordinates, camera is subtracted and the object *is* culled;
  bit 2 **clear** = screen coordinates, and in the non-childsprite path the object skips the
  cull entirely (`beq.s loc_1ADB2`, sonic3k.asm:36345). Do not port the comment.

There is no `explicit_height` bit and no `BuildSprites_ApproxYCheck` in S3K, because S3K
does not need one: `height_pixels` is unconditionally present.

## The camera quantities

`Camera_X_pos_coarse_back` and `Camera_Y_pos_coarse_back` are set in the object-load
manager at sonic3k.asm:37546-37557:

```
move.w (Camera_Y_pos).w,d1 / subi.w #$80,d1 / andi.w #$FF80,d1 -> Camera_Y_pos_coarse_back
move.w (Camera_X_pos).w,d1 / subi.w #$80,d1 / andi.w #$FF80,d1 -> Camera_X_pos_coarse_back
```

i.e. `(camera - 128) & ~$7F` — the same `$80`-block quantisation of `camera-128` that S1
`out_of_range` and S2 `MarkObjGone` use. It is recomputed as the camera moves
(sonic3k.asm:37598-37646 for X, 37990-38029), and is *cleared to 0* on level load except in
zone/act `$1701` (sonic3k.asm:37476-37483) — a load-time special case worth knowing about
but not part of the predicate.

## The predicates

Five distinct shapes exist. They are numbered S3K-1 … S3K-5 here; the "S1/S2 #n"
column refers to the four predicates established for S1/S2 by the earlier round.

### S3K-1 — coarse X-only deletion window, bound `$280`

The dominant predicate: **61 occurrences** — *re-measured 2026-08-21, unchanged* — of the
comparison in sonic3k.asm. One refinement: **58** of the 61 end in `bhi` as shown below; the
other **three** (sonic3k.asm:81151, 91750, 92055) end in **`bls`**, branching when *in* range
and falling through to the off-screen handling. Same predicate, inverted branch — a sweep
anchored on `bhi` finds 58 and reads like a complete answer.

```
move.w  x_pos(a0),d0
andi.w  #$FF80,d0                       ; quantise object x to $80 blocks
sub.w   (Camera_X_pos_coarse_back).w,d0 ; 16-bit subtract, no sign extension
cmpi.w  #$280,d0                        ; 640
bhi     <delete>                        ; UNSIGNED compare
```

- Axis: **X only**. Quantisation: `$80` on the object, `$80` on `camera-128`.
- Signedness: **unsigned** (`bhi`), so a negative difference wraps high and deletes —
  this is what makes a one-sided compare into a two-sided window.
- Effective window: object block in `[camera-128, camera-128+640]`.
- Bound source: the literal `$280` in the compare. It is the same constant as S1/S2's 640.

Named entry points, all with this body:

| Routine | sonic3k.asm | Tail behaviour |
|---|---|---|
| `Sprite_OnScreen_Test` | 37262 | clear respawn bit, `Delete_Current_Sprite` |
| `Sprite_OnScreen_Test2` | 37281 | same, but takes `d0` pre-loaded by the caller |
| `Delete_Sprite_If_Not_In_Range` | 37301 | same; `rts` when in range (does **not** draw) |
| `Sprite_CheckDeleteTouch3` | 37369 | adds to collision list, then draws |
| `Sprite_CheckDelete` | 178939 | sets `status` bit 7, defers delete to next frame |
| `Sprite_CheckDelete2` | 178960 | sets `$38(a0)` bit 4 instead |
| `Sprite_CheckDeleteTouch` | 179012 | collision list + draw |
| `Sprite_CheckDeleteTouch2` | 179022 | collision list + draw, `$38` bit 4 tail |
| `Sprite_CheckDeleteSlotted` | 179047 | releases a tracking slot (`Remove_From_TrackingSlot`) |
| `Sprite_CheckDeleteTouchSlotted` | 179086 | slotted, with an early `status` bit-7 test |
| `Obj_WaitOffscreen` (`loc_85AD2`) | 180271 | see S3K-5 |

**Corrected 2026-08-21: there are five unused copies in that block, not four, and the line
citations were wrong.** They begin at sonic3k.asm:**179078, 179104, 179112, 179119, 179126**.
The previously cited 179068 is `Remove_From_TrackingSlot`'s header rather than a copy; 179116
and 179124 are the `bhi`/`rts` lines *inside* the copies beginning at 179112 and 179119; and
the copy at 179126 was missed. The disassembly marks them with `; unused` (179077) and
"Unused, seems to be identical to the above" (179102) and "Next three are unused, virtually
the same" (179112). One more unused copy sits at 37322 (its `; unused` marker is at 37321).

**S1/S2 counterpart: predicate 1** (`out_of_range` / `MarkObjGone`). Same quantities, same
quantisation, same signedness, same axis, same bound. The engine's existing
`ObjectRangeOps` models this and applies unchanged to S3K.

The distinction that does *not* exist in S1/S2 is the **tail**: S3K's variants differ in
whether they draw, add to the collision list, delete immediately, defer deletion by
installing `Delete_Current_Sprite` as the object's routine pointer, clear
`respawn_addr`'s bit 7, or release a tracking slot. Two objects with identical geometry can
therefore have materially different lifetimes. Any conversion must pick the tail as
deliberately as the bound.

### S3K-2 — coarse Y-only deletion window, bound `$200`

Same shape, Y axis, different bound. Four occurrences — *re-measured 2026-08-21, unchanged*
(the table's line numbers name each block's first instruction; the `andi.w` sits one line
below):

| sonic3k.asm | Owner |
|---|---|
| 68913 (`loc_32EF8`) | CNZ bumper (`Ani_Bumper`), collision-list + draw tail |
| 96288 (`loc_49B22`) | CNZ pachinko triangle bumper, draw tail |
| 96307 (`loc_49B4E`) | same object family, `rts` tail |
| 96845 (`loc_4A31A`) | CNZ, same shape |

```
move.w  y_pos(a0),d0
andi.w  #$FF80,d0
sub.w   (Camera_Y_pos_coarse_back).w,d0
cmpi.w  #$200,d0                        ; 512
bhi     <delete>
```

Window: object block in `[camera_y-128, camera_y-128+512]`.

**No S1/S2 counterpart.** Neither S1 nor S2 has a coarse vertical deletion predicate. This
is a genuine S3K addition, and it is concentrated in CNZ, a vertically-tall zone.

### S3K-3 — mixed coarse-X / fine-Y window (`Sprite_CheckDeleteXY`)

sonic3k.asm:178981, and the same body inlined in `Obj_FlickerMove` (sonic3k.asm:178995)
and `Sprite_CheckDeleteTouchXY` (sonic3k.asm:179032).

**Corrected 2026-08-21: there are seven sites, not three.** Beyond those three helpers the
identical geometry is inlined at **sonic3k.asm:128465** (`loc_61928`), **176267**
(`loc_8395E`, the signpost), **193223** (`loc_8D6E6`) and **197113** (`loc_90282`) — each
with the coarse-X `$280` half followed by the fine-Y `+$80` / `$200` half, verified by
reading all four. This more than doubles the population of the one predicate the decision
procedure below says a symmetric margin cannot express.

A fine-Y test also appears at **sonic3k.asm:133719** (`loc_6594A`) *without* the coarse-X
half — paired instead with a fine-X `Camera_X_pos - $80` compare, and tailing into a
star-post trigger rather than a deletion. It is **not** S3K-3; it is listed here so the next
sweep does not count it as one.

```
; X: exactly S3K-1
move.w  x_pos(a0),d0 / andi.w #$FF80,d0
sub.w   (Camera_X_pos_coarse_back).w,d0 / cmpi.w #$280,d0 / bhi -> delete
; Y: NOT quantised, and against the RAW camera
move.w  y_pos(a0),d0
sub.w   (Camera_Y_pos).w,d0
addi.w  #$80,d0
cmpi.w  #$200,d0                        ; 512, unsigned
bhi     -> delete
```

The Y half is **pixel-accurate, not block-quantised**, uses `Camera_Y_pos` (not the coarse
back copy), and biases by `+$80` before an unsigned compare against `$200`. Window:
`y_pos` in `[camera_y-128, camera_y+384]` — **asymmetric**: 128px of margin above the
camera origin, 384px below (the screen is 224 tall, so 160px below the screen bottom).

This is the closest thing S3K has to the engine's symmetric 2-D `isOnScreen(m)`, and it is
still not symmetric on either axis. **No S1/S2 counterpart** in this exact form.

### S3K-4 — offset-window variant, bound `$680`

One occurrence — *re-measured 2026-08-21, unchanged* — sonic3k.asm:95823-95830, inside `Obj_DEZGravityRoom`
(label at sonic3k.asm:95814; delete tail `loc_49638`):

```
move.w  x_pos(a0),d0
addi.w  #$400,d0                        ; bias BEFORE quantisation
andi.w  #$FF80,d0
sub.w   (Camera_X_pos_coarse_back).w,d0
cmpi.w  #$680,d0                        ; 1664
bhi     -> delete
```

The `+$400` pre-bias combined with the widened `$680` bound moves the window's left edge
`$400` further back: object block in `[camera-128-1024, camera-128+640]`. Same right edge
as S3K-1, 1024px more tolerance behind the camera. This is S3K-1 with a deliberately
asymmetric leash, not a different mechanism.

### S3K-5 — the render-flag predicate (bit 7 of `render_flags`)

The predicate with no geometry of its own: object code tests the bit the *render pass*
wrote on the previous frame.

```
tst.b   render_flags(a0)
bpl     <off-screen path>          ; bit 7 clear -> was not drawn last frame
```

**Re-measured 2026-08-21.** There are **160** `tst.b render_flags(a0)` sites in total.
**123** are followed within two instructions by a `bpl` — *unchanged, confirmed*.

**The "19 of those lead to a `Delete`" figure does not reproduce under either reading, and
is withdrawn.** Following each `bpl` target through its label to the routine it actually
reaches gives **49**; counting only targets whose *name* contains "Delete" gives **11**.
Neither is 19, so the original number cannot be reconstructed and should not be relied on.
49 is the defensible figure: `sonic3k.asm:33348` branches to `loc_1824E`, which is
`jmp (Delete_Current_Sprite).l` — a delete that a name-only match misses.

**And the `bpl` framing misses an inverted-spelling family**, the same failure mode as
keying on one game's vocabulary. **37** of the 160 sites are followed by **`bmi`** instead —
branching to *continue*, with the delete on the fall-through — and **8** of those delete.
The doc's own first example below is one of them, so it is not a member of the 123 it
illustrates. Two representative examples:

- `Obj_SuperTailsBirds_FlyAway`, sonic3k.asm:35104-35105 — `bmi` to continue,
  otherwise `jmp (Delete_Current_Sprite).l`, with the disassembly's own comment
  "If sprite is off-screen, delete it". The same routine reads bit 7 of *another*
  object (`render_flags(a1)`, sonic3k.asm:35145) to decide whether its target is still
  valid.
- `Obj_WaitOffscreen` (sonic3k.asm:180271) — the inverse gate. It installs a
  `$20`-by-`$20` empty placeholder (`Map_Offscreen`), sets `render_flags` bit 2, and each
  frame runs S3K-1; when bit 7 comes back **set**, it restores the object's saved routine
  pointer from `$34(a0)` and the real object begins. So `Obj_WaitOffscreen` is S3K-1
  *and* S3K-5 in sequence, with the `$20` half-extents feeding the render cull, not the
  deletion cull.

The geometry behind bit 7 is `Render_Sprites` itself (sonic3k.asm:36318):

```
; X, both paths (sonic3k.asm:36346-36355, 36444-36453 and 36473-36482)
d2 = width_pixels(a0)                   ; half-width, byte, zero-extended
d0 = x_pos - Camera_X_pos_copy
if (d0 + d2) < 0            -> not drawn ; right edge left of screen
if (d0 - d2) >= 320         -> not drawn ; left edge right of screen
; Y, both paths (sonic3k.asm:36356-36366 and 36485-36495)
d1 = y_pos - Camera_Y_pos_copy(4(a3))
d2 = height_pixels(a0)                  ; half-height, byte
d1 = (d1 + d2) & (Screen_Y_wrap_value)  ; $7FF or $FFF, per-level
if d1 >= (224 + 2*d2)       -> not drawn ; UNSIGNED (bhs)
```

**Differences from S2's `BuildSprites` that must not be papered over:**

1. **No `explicit_height` branch and no `ApproxYCheck`.** S3K always uses the real
   `height_pixels`; S2 falls back to an assumed ±32 when its flag is clear. Carrying S2's
   ±32 into S3K would be wrong at every object whose half-height is not `$20`.
2. **The vertical wrap mask is a variable**, `Screen_Y_wrap_value`, documented in
   `sonic3k.constants.asm:433` as "either `$7FF` or `$FFF`". S2's is a fixed 11-bit wrap.
   Which value is live depends on level setup; **I did not establish the rule that selects
   it**, and it must be read at runtime rather than assumed.
3. **The comparison is against `Camera_X_pos_copy` / `Camera_Y_pos_copy`**
   (sonic3k.asm:36324, and `4(a3)`), the VBlank-latched copies, not the live camera.
4. The childsprite path (bit 6, `loc_1AE58`, sonic3k.asm:36441) has its **own** Y check —
   a plain symmetric `[-height, 224+height)` test against VDP-space coordinates with
   `-128` biases and **no wrap mask** — used when bit 2 is clear. When bit 2 is set it
   falls through to `loc_1AEA2` (sonic3k.asm:36472), which is byte-for-byte the
   non-childsprite geometry. So the same object can be culled by two different vertical
   rules depending on one bit.
5. There is a **sprite budget**: `d7` starts at `$50-1` (80) and, once negative, objects
   still get bit 7 set (sonic3k.asm:36370 precedes the `tst.w d7` at 36371) but are not
   drawn. Bit 7 therefore means "passed the geometry test", not "actually drawn".
6. Competition mode uses a separate renderer, `Render_Sprites_CompetitionMode`
   (sonic3k.asm:36894), with a budget of `$50-2` and a vertical extent of `224/2` = 112.
   Out of scope for single-player traces, noted so it is not mistaken for the main path.

**S1/S2 counterpart: predicate 2**, but only structurally. The X half matches S2's shape;
the Y half does not, for reasons 1, 2 and 4 above.

> **Correction, 2026-08-21 (`3180f705d`).** `docs/skdisasm/sonic3k.asm` is **CRLF**
> (202,729 CRLF lines to 993 bare LF) while `s1disasm` and `s2disasm` are LF, so a
> `$`-anchored matcher sees a fraction of this file and returns a partial result that reads
> like a complete one. Both negatives in the section below were re-run line-ending-tolerantly
> with a known positive required to appear first. **Predicate 3's absence holds** and is now
> measured. **Predicate 4's absence does not**: S3K has it, as `Obj_Animal`'s `loc_2CAE4`
> (sonic3k.asm:61184-61194), instruction-for-instruction the S2 routine and reached by the
> same `tst.b subtype` selector. Details in
> [the family sweep](../audits/2026-08-21-onscreen-margin-family-sweep.md).
>
> **Follow-up, `24edd3db4`: the remaining figures have now been re-measured**, each with a
> known positive required to appear before any count or zero was believed, and with a
> nonexistent-field control required to return zero. Results are marked inline throughout —
> every figure is now either *re-measured, unchanged*, *corrected* with its old value shown,
> or *withdrawn*. Summary:
>
> | figure | was | now |
> |---|---|---|
> | S3K-1 occurrences | 61 | **61** — confirmed; 58 end in `bhi`, 3 in `bls` |
> | S3K-2 occurrences | 4 | **4** — confirmed |
> | S3K-3 sites | 3 | **7** — corrected; four more inlined copies |
> | S3K-4 occurrences | 1 | **1** — confirmed |
> | unused S3K-1 copies in the helper block | 4 | **5** — corrected, and the line cites were wrong |
> | `tst.b render_flags` + `bpl` | 123 | **123** — confirmed (of 160 sites; 37 use `bmi` instead) |
> | of those leading to a `Delete` | 19 | **withdrawn** — reproduces as 49 or 11, never 19 |
> | engine S3K `isOnScreen(<literal>)` sites | 30 | **30** — confirmed (32 including the X/Y variants) |

## What S1/S2 has that S3K does not

- **Predicate 3, `Obj_DeleteBehindScreen`'s bare sign test.** I searched every
  `sub.w (Camera_[XY]_pos_coarse_back)` in `sonic3k.asm` for a following
  `bmi`/`bpl`/`bcs`/`blt` within three instructions and found **none** — every coarse
  subtract in S3K is followed by a `cmpi.w` against an explicit bound. **S3K has no
  constant-free sign-test deletion predicate.**
- **Predicate 4, `Obj28_ChkDel`'s player-relative one-sided `$180`.** S3K has no
  general-purpose player-relative *deletion* helper. It does have player-relative *range*
  helpers, but they gate behaviour, not lifetime: `Check_InTheirRange`
  (sonic3k.asm:179934), `Check_InMyRange` (sonic3k.asm:179964) and `Check_PlayerInRange`
  (sonic3k.asm:179994) all take a **caller-supplied four-word bounds table**
  (`+xlo, +width, +ylo, +height` read through `(a2)+` / `(a1)+`) and return a boolean or an
  object pointer; there is no baked-in constant to port. `Check_CameraInRange`
  (sonic3k.asm:180433) is the camera-window equivalent, also table-driven, and it
  *delegates* deletion to `Delete_Sprite_If_Not_In_Range` (sonic3k.asm:180453) — i.e. back
  to S3K-1. Per-object bounds tables such as `HCZMiniboss_CameraRange`
  (sonic3k.asm:139241), `MGZMiniboss_CameraRange` (184837),
  `CNZMiniboss_BaseRange` (145651) and `HCZConveyor_BoundsData` (66292) feed these
  helpers; each is its own data, not an instance of a shared predicate.

## Which of the four S1/S2 predicates map across

| S1/S2 predicate | S3K analogue | Verdict |
|---|---|---|
| 1. `out_of_range` / `MarkObjGone`, coarse X, `$280` | **S3K-1** | Exact match, 61 sites. `ObjectRangeOps` is directly reusable. |
| 2. `BuildSprites` render cull | **S3K-5** (`Render_Sprites`) | X half matches; Y half does **not** — no `explicit_height`, variable wrap mask, separate childsprite rule. |
| 3. `Obj_DeleteBehindScreen` bare `bmi` | **none** | Absent from S3K. |
| 4. `Obj28_ChkDel` player-relative `$180` | **`Obj_Animal` `loc_2CAE4`** (sonic3k.asm:61184) | **Corrected**: exact match, same selector. The table-driven player-range helpers are a separate, behaviour-gating family. |
| — | **S3K-2** (coarse Y, `$200`) | No S1/S2 counterpart. |
| — | **S3K-3** (coarse X + fine asymmetric Y) | No S1/S2 counterpart. |
| — | **S3K-4** (`+$400` bias, `$680`) | No S1/S2 counterpart; a one-site variant of S3K-1. |

So: **five S3K predicates, of which two of the four S1/S2 predicates have analogues and
three S3K predicates are new.** The assumption that there would be four, or that the four
would be the same four, is false in both directions.

## Applying this to the blocked engine sites

I do not have the other lane's list of 16 site identities, so this is a decision procedure
rather than a site-by-site assignment. `src/main/java` currently holds **30** S3K
`isOnScreen(<literal>)` call sites (`grep -rn --include='*.java' "isOnScreen([0-9]"
src/main/java | grep -i sonic3k`) — *re-measured 2026-08-21 at `24edd3db4`, unchanged*.
Note the stated command matches only `isOnScreen`; including the `isOnScreenX` /
`isOnScreenY` variants gives **32**.

The procedure for each site is: **read the ROM routine the object actually calls**, and
classify by that, never by the margin the engine happens to pass.

- Calls `Sprite_OnScreen_Test`, `Sprite_CheckDelete*`, or
  `Delete_Sprite_If_Not_In_Range` → **S3K-1**. Margin is not a free parameter: the
  correct conversion is coarse-X against `$280`, and the engine's literal (64, 128, `0x80`
  …) is a fitted stand-in that should disappear, not be translated. Confirmed examples
  already carrying the ROM citation in a comment:
  `game/sonic3k/objects/HCZLargeFanObjectInstance.java:136` (`isOnScreen(64)`, comment
  cites `Sprite_OnScreen_Test` and explicitly says "the ROM uses a tighter on-screen
  window" — that comment is the tell that the 64 was chosen, not derived).
- Calls `Sprite_CheckDeleteXY`, `Sprite_CheckDeleteTouchXY` or `Obj_FlickerMove` →
  **S3K-3**. A single symmetric margin cannot express this; it needs the split
  coarse-X/fine-Y form.
- Is a CNZ object gated on vertical position → check for **S3K-2** before assuming S3K-1.
- Enters via `Obj_WaitOffscreen` → **S3K-1 followed by S3K-5**, with `$20` half-extents
  going to the *render* test. `game/sonic3k/objects/badniks/MonkeyDudeBadnikInstance.java:142`
  (`isOnScreen(0x20)`) and `badniks/TurboSpikerBadnikInstance.java:128` (`isOnScreen(0x20)`)
  both name `Obj_WaitOffscreen` in their comments and both collapse the two-stage ROM
  behaviour into one symmetric test with the placeholder's half-extent as the margin. The
  `0x20` is the placeholder size, not a culling bound.
- Reads "was I drawn" semantics → **S3K-5**, and this is the hazard: bit 7 is *produced by
  the render pass*, and the per-object render pass was measured today not to execute under
  headless trace replay. An S3K-5 conversion needs a producer for bit 7 that runs headless
  and computes it from `x_pos`/`y_pos`/`width_pixels`/`height_pixels`/`Screen_Y_wrap_value`
  and the latched camera copies, independent of whether anything is actually drawn — and
  it must reproduce the sprite-budget subtlety (bit 7 is set for objects the budget
  excludes).

## What I could not establish

1. ~~**`Screen_Y_wrap_value`'s selection rule.**~~ **CLOSED** by
   [2026-08-21-s3k-screen-y-wrap-value-rule.md](2026-08-21-s3k-screen-y-wrap-value-rule.md).
   The constants file's "`$7FF` or `$FFF`" (sonic3k.constants.asm:433) is wrong in both
   directions: there are four values (`$FFFF`, `$FFF`, `$7FF`, `$3FF`) and the default,
   written unconditionally by `LevelSetup` (sonic3k.asm:102205) for every zone, is
   **`$FFF`** — not S2's `$7FF`. Only ICZ1, SOZ2 and Slots lower it, and ICZ1 raises it
   back mid-act.
2. **The 16 blocked sites by identity.** Not in my inputs; the mapping above is a
   procedure, not an assignment. Someone holding the list should run the procedure per
   site.
3. ~~**Whether the `$1701` zone/act special case for `Camera_X_pos_coarse_back`
   (sonic3k.asm:37476-37483) affects any traced route.**~~ **CLOSED** by the follow-up
   doc: `$1701` is zone 23 act 1 = **HPZS**, the Hidden Palace super-emerald shrine
   (`HPZS_ScreenInit`, sonic3k.asm:120806). It touches no main-route act, so it does not
   affect AIZ → HCZ, but a complete-run trace that visits the shrine would hit it.
4. **`$38(a0)` bit 4 and `status` bit 7 semantics in the deferred-delete tails.** I
   recorded which tail each helper uses but did not chase what consumes those bits, so I
   cannot say whether the tail difference is observable in trace fields.
5. **Whether S3K-4's `+$400`/`$680` variant appears anywhere but `Obj_DEZGravityRoom`.**
   The instruction-sequence sweep found one site; a differently-spelled equivalent
   (bias in another register, bound built at runtime) would not have been caught.
6. **The disassembly's inverted bit-2 comments** are a fact about the comments; I read the
   branch logic and state the corrected reading above, but I did not cross-check it
   against a running ROM.
