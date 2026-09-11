# S3K vertical wrap: the trigger's provenance, and the `0x3FF` BG mask

Research round, 2026-08-21, at commit 73fffdc62. Closes two open items from
[2026-08-21-s3k-vertical-wrap-engine-measurement.md](2026-08-21-s3k-vertical-wrap-engine-measurement.md):
the uncited `VERTICAL_WRAP_BG_MASK = 0x3FF`, and whether `minY < 0` is ROM-accurate as the
engine's wrap trigger.

Sources: `docs/skdisasm/sonic3k.asm` (S&K half) and `docs/s1disasm/`.
**No runtime code was changed.**

## `0x3FF` is genuine, ROM-owned, and belongs to Sonic 1 — I was looking in the wrong disassembly

The previous round flagged `Camera.VERTICAL_WRAP_BG_MASK = 0x3FF` as a candidate invented
constant because no `$3FF` BG-wrap write exists in `sonic3k.asm`. **That suspicion was wrong,
and the reason is instructive.** The constant lives in the shared `Camera` class, which is why
I searched the S3K disassembly for it — but it has exactly one caller,
`SwScrlLz.java:92` (`bgY &= Camera.getVerticalWrapBgMask()`), and `SwScrlLz` is **Sonic 1**
Labyrinth Zone scroll.

Its owner is `docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm`, in both wrap branches of
the vertical camera scroll:

```
SV_TopBoundary:                                 ; line 233
        cmp.w   (v_limittop2).w,d1
        bgt.s   SV_SetScreen
        cmpi.w  #-$100,d1                       ; does level wrap vertically? (top boundary set to -$100)
        bgt.s   .noWrap
        andi.w  #$7FF,d1                        ; wrap expected new camera Y-position
        andi.w  #$7FF,(v_player+obY).w          ; wrap Sonic vertically
        andi.w  #$7FF,(v_screenposy).w          ; wrap camera Y-position
        andi.w  #$3FF,(v_bgscreenposy).w        ; line 241 — wrap background Y-position
```

and again at line 266 in `SV_BottomBoundary`. The value is correct and the comment in
`Camera.java:86` ("AND mask for BG Y") describes it accurately. **What is missing is only the
citation**, not the constant. It should cite
`docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:241,266`.

**Correction to the previous round.** That doc listed `VERTICAL_WRAP_BG_MASK` as the third
independent derivation of *the S3K period*. It is not — it is S1's BG wrap, reached only from
S1 code, and it does not participate in the S3K period at all. **The S3K period is modelled
twice, not three times**: `Camera.verticalWrapRange`/`verticalWrapMask`, and
`LevelTilemapManager`'s own `Math.floorMod(worldY, getLayerLevelHeightPx(0))`
(`LevelTilemapManager.java:1473-1481` and `:1552-1560`). That is a smaller fix than I
reported, and the ownership point stands unchanged: two derivations from the same layout
height, neither reading the ROM constant, able to disagree where the ROM's single triple
cannot.

`Deform_LZ` itself (`_inc/DeformLayers (REV01).asm:172-246`) applies no mask — it calls
`BGScroll_XY` and writes `v_bgscreenposy` straight through. The masking happens only in the
scroll routine's wrap branches above, which is consistent with `SwScrlLz.java:91` gating its
mask on `camera.isVerticalWrapEnabled()`.

## The wrap trigger: `minY < 0` is not invented, but S3K uses `== -$100`, and the render path uses no trigger at all

This is the more important of the two items, and the answer is three-part rather than the
"wrong threshold or invented concept" binary it was briefed as.

### S3K's player and camera paths gate on an exact `-$100`

```
loc_10C26:                                              ; sonic3k.asm:21988
        cmpi.w  #-$100,(Camera_min_Y_pos).w    ; is vertical wrapping enabled?
        bne.s   loc_10C36                      ; if not, branch
        move.w  (Screen_Y_wrap_value).w,d0
        and.w   d0,y_pos(a0)                   ; perform wrapping of Sonic's y position
```

The disassembly names the predicate itself — *"is vertical wrapping enabled?"*. It is an
**equality** test, not a sign test: every one of the ten `cmpi.w #-$100,(Camera_min_Y_pos).w`
sites branches on `bne`/`beq` (sonic3k.asm:21564, 21989, 24457, 25708, 26233, 29202, 30431,
31317, 32893, 38444). `Camera_min_Y_pos` is level data, loaded from the `LevelSizes` table at
sonic3k.asm:38086-38087.

`MoveCameraY` carries the same gate before masking the camera's own delta
(sonic3k.asm:38444-38446).

Sonic 1 uses the identical constant for the identical purpose
(`SV_TopBoundary`, `cmpi.w #-$100,d1`, "does level wrap vertically? (top boundary set to
-$100)"), so this is a cross-game convention rather than an S3K quirk.

### S3K's object-load manager gates on a *sign test* instead

```
        tst.w   (Camera_min_Y_pos).w           ; sonic3k.asm:37560
        bpl.s   loc_1B84A
```

and likewise at sonic3k.asm:37687 and 37708. **S3K therefore has two different predicates on
the same variable**: an exact `== -$100` in the player/camera paths and a `< 0` sign test in
the object-load manager. They disagree for any level whose `Camera_min_Y_pos` is negative but
not exactly `-$100`. I found no such level — every act measured last round is either `>= 0`
or exactly `-256` — so in practice they coincide, which is the same latency structure as
divergence A: two things that must agree, nothing making them agree.

### `Render_Sprites` has no trigger at all

`and.w (Screen_Y_wrap_value).w,d1` at sonic3k.asm:36360 and 36487 is **ungated**. There is no
`Camera_min_Y_pos` test anywhere in `Render_Sprites`. The render cull masks on every frame in
every act, and `$FFFF` is how a non-wrapping level expresses "no wrap" — which is exactly why
`Get_LevelSizeStart` writes `#-1` rather than leaving a flag clear.

### What that means for the engine

`LevelManager.java:2852`'s `currentLevel.getMinY() < 0` is **ROM-derived, not invented** — but
it matches the *looser* of S3K's two predicates while the player and camera paths use the
*stricter* one. The engine then applies that single gate to **both** the position-wrapping
paths and the render-visibility path (`Camera.java:865`, `if (verticalWrapEnabled)`).

So divergence **B** splits cleanly:

- **For player/camera position wrapping: B is a slightly wrong threshold.** `< 0` where the
  ROM says `== -$100`. Currently harmless because no measured act is negative-but-not-`-$100`;
  a future act that is would wrap in the engine and not in the ROM.
- **For the render-visibility path: B is an invented gate.** The ROM's `Render_Sprites`
  masks unconditionally; the engine masks only when the level wraps. This is the branch the
  brief anticipated, and it is confined to the render path rather than to wrapping generally.

That distinction matters for the S3K-5 producer: the producer must mask with
`Screen_Y_wrap_value` **unconditionally**, and must not inherit `verticalWrapEnabled`. The
existing `Camera.java:865` gate is exactly the thing it needs to not copy.

The engine's `getMinY()` does correspond to the ROM's `Camera_min_Y_pos`: last round measured
`-256` for MGZ1, ICZ1, SOZ2 and SSZ1, which is `-$100` exactly.

## What I could not establish

1. **Whether any S3K act has `Camera_min_Y_pos` negative but not `-$100`.** I checked the 26
   main-route acts by measurement last round and found none; I did not read the `LevelSizes`
   table entry by entry, so bonus/special/competition stages are unchecked. If one exists, the
   ROM's own two predicates disagree there and the engine matches only one of them.
2. **Why the object-load manager uses a sign test when everything else uses equality.** Both
   are in the same ROM; I recorded the difference rather than explaining it.
3. **Whether `Deform_LZ`'s unmasked `v_bgscreenposy` write can race the scroll routine's
   masked one** within a frame. `SwScrlLz` applies the mask at a different point than the ROM
   does (in the deform, gated on the camera flag, rather than in the scroll wrap branch), and
   I did not check whether the orderings are equivalent.
4. **The trace-observability question for B** stays parked as instructed, and is now better
   posed: it should ask specifically about the render path, since that is where B is an
   invented gate rather than a wrong threshold.
