# S3K `Screen_Y_wrap_value`: what sets it, and when

Research round, 2026-08-21. Follow-up to
[2026-08-21-s3k-object-culling-geometry.md](2026-08-21-s3k-object-culling-geometry.md),
which named this as its single largest open item: the S3K render cull (predicate S3K-5)
masks its vertical difference with `Screen_Y_wrap_value`, a variable where S2 has a fixed
literal, and no conversion can be correct without knowing what selects it.

Sources: `docs/skdisasm/sonic3k.asm` (S&K half), `docs/skdisasm/s3.asm` (S3 half), and
`docs/skdisasm/Lockon S3/` (the lock-on overlay). Line numbers are into those files.
**No runtime code was changed.**

## Headline: the constants file's comment is wrong, and so is the S2 analogy

`sonic3k.constants.asm:433` documents the variable as "either `$7FF` or `$FFF`". That is
incomplete in both directions. There are exactly **eight** writes in `sonic3k.asm` and
**four distinct values** across the locked-on ROM:

| Value | Meaning | Written by |
|---|---|---|
| `$FFFF` (`#-1`) | no masking at all | `Get_LevelSizeStart` (sonic3k.asm:38093), special-stage init (sonic3k.asm:10708) |
| `$FFF` | 12-bit wrap, `$1000` px | **`LevelSetup` (sonic3k.asm:102205) — the default for every level**, and `ICZ1BGE_Transition` (sonic3k.asm:110320) |
| `$7FF` | 11-bit wrap, `$800` px | `ICZ1_ScreenInit` (sonic3k.asm:110069), `SOZ2_ScreenInit` (114222), `SOZ2_ScreenEvent`'s `loc_561D8` (114251), `Slots_ScreenInit` (119055) |
| `$3FF` | 10-bit wrap, `$400` px | `Gumball_ScreenInit` — **S3 half only** (s3.asm:76100, lock-on copy at `Lockon S3/Screen Events.asm:1624`) |

**S2's fixed `$7FF` is not S3K's common case — S3K's default is `$FFF`.** Carrying S2's
literal across would halve the wrap period in every zone except ICZ1, SOZ2 and Slots,
including the entire AIZ → HCZ release slice.

## The selection rule

Three writes happen in sequence during a level load, and the last one wins.

**1. `Get_LevelSizeStart` (sonic3k.asm:38068), called from the level-load path at
sonic3k.asm:7759.** After reading the level's bounds out of `LevelSizes`, it sets *both*
axes to no-wrap:

```
move.w  #-1,(Screen_X_wrap_value).w
move.w  #-1,(Screen_Y_wrap_value).w      ; sonic3k.asm:38092-38093
```

This is transient. It is the value in force only between `Get_LevelSizeStart` and
`LevelSetup`, i.e. across `DeformBgLayer`, `LoadLevelLoadBlock` and `LoadLevelLoadBlock2`
(sonic3k.asm:7760-7762).

**2. `LevelSetup` (sonic3k.asm:102185), called as `j_LevelSetup` at sonic3k.asm:7764.**
Unconditionally, with no test of zone, act, or layout size:

```
move.w  #$FFF,(Screen_Y_wrap_value).w    ; sonic3k.asm:102205
move.w  #$FF0,(Camera_Y_pos_mask).w
move.w  #$7C,(Layout_row_index_mask).w
```

**This is the rule for the overwhelming majority of the game.** The three constants are
always written as a triple and are three encodings of the same vertical period:
`$FFF` = `$1000` px = 32 layout rows, and `$7C` = `(32-1) * 4`.

**3. The per-zone `*_ScreenInit`, dispatched immediately afterwards** from
`LevelSetupArray` (sonic3k.asm:102259) indexed by `Current_zone_and_act`
(sonic3k.asm:102214-102217: `ror.b #2,d0` then `lsr.w #3,d0`, giving 32 bytes per zone —
setup act 0, setup act 1, event act 0, event act 1). Only **three** of the S&K-half
`*_ScreenInit` routines touch the variable, and each lowers it to `$7FF`:

| Routine | sonic3k.asm | Zone index | Conditional? |
|---|---|---|---|
| `ICZ1_ScreenInit` (write at `loc_53648`) | 110051 / 110069 | 5, act 0 | No — `loc_53648` is the fall-through of the preceding `bne.s`, so the write is unconditional. The comment on the following line reads "We're in a looping level!" |
| `SOZ2_ScreenInit` | 114221 / 114222 | 8, act 1 | No |
| `Slots_ScreenInit` | 119054 / 119055 | 21 | No |

Every other `*_ScreenInit` — AIZ1/2, HCZ1/2, MGZ1/2, CNZ1/2, FBZ1/2, ICZ**2**, LBZ1/2,
MHZ1/2, SOZ**1**, LRZ1/2, SSZ1/2, DEZ1/2, DDZ, Ending, the five competition zones,
Pachinko, LRZ3, HPZ, DEZ3, HPZS — leaves `LevelSetup`'s `$FFF` standing. I verified this by
exhaustion: the eight writes listed above are *all* the writes in `sonic3k.asm`.

**4. Two routines change it mid-level, after load.**

- `ICZ1BGE_Transition` (sonic3k.asm:110285, reached via `bra.w` at 110171) sets it **back
  to `$FFF`** at sonic3k.asm:110320, together with `Camera_Y_pos_mask` `$FF0` and
  `Layout_row_index_mask` `$7C`, as part of the big-egg transition that also relocates both
  players and the camera. **So ICZ1 runs at `$7FF` and then switches to `$FFF` part-way
  through the act.** Any model that resolves the mask once at level load is wrong for ICZ1.
- `SOZ2_ScreenEvent`'s dispatch target `loc_561D8` (sonic3k.asm:114250-114251) re-asserts
  `$7FF` whenever that event-routine index runs.

**5. Special stages** set `#-1` at `loc_842C` (sonic3k.asm:10708), alongside zeroing both
camera copies.

## The `$3FF` case and the half question

`Gumball_ScreenInit` is **referenced** by the S&K-half `LevelSetupArray`
(sonic3k.asm:102335-102336, zone index 19) but has **no definition in `sonic3k.asm`**. It
resolves through the lock-on overlay: `Lockon S3/LockOn Pointers.asm:195` reserves the
symbol, and the body lives in `Lockon S3/Screen Events.asm:1623-1626`, which sets `$3FF` /
`$3F0` / `$1C` — a 10-bit, `$400` px, 8-row period. `s3.asm:76099-76102` carries the same
body for the standalone S3 build. So `$3FF` **is** reachable in the locked-on ROM, but only
by entering the Gumball Machine bonus stage, and only through an S3-half routine.

This is a clean example of the AGENTS_S3K "which half" caution: the *table* is S&K-half,
the *routine it points at* is S3-half, and reading only `sonic3k.asm` would have produced a
confident and wrong claim that S3K has three values.

## Item 3, closed: `$1701` is HPZS

The `Camera_X_pos_coarse_back` load-time special case at sonic3k.asm:37476-37483
(`clr.w` the variable, then re-derive it from `Camera_X_pos` only when
`Current_zone_and_act` equals `$1701`) is **zone `$17` = 23, act 1**.

Decoding zone 23 against `LevelSetupArray` at 32 bytes per zone: index 23 act 0 is
`DEZ3_ScreenInit` and act 1 is **`HPZS_ScreenInit`** (sonic3k.asm:120806) — the Hidden
Palace super-emerald shrine, the room entered from the HPZ teleporters. Zone 23 act 0 is
Death Egg Zone act 3. There is also a second `$1701` test at sonic3k.asm:37465-37468
selecting `$1780` instead of `$800` for `d6`, in the same object-load setup.

**This does not touch AIZ → HCZ or any main-route act.** It is reachable only via the
Hidden Palace shrine, so it can be left unmodelled for the release slice — but it is *not*
dead code, and a complete-run trace that visits the shrine would hit it.

## What this means for the engine — three concrete divergences

I did not change any of these. They are reported, not fixed.

**A. The engine derives the mask from layout height; the ROM writes a constant.**
`LevelManager.java:2853-2859` and `:3808-3812` compute
`wrapRange = isUnifiedCollisionModel() ? Camera.VERTICAL_WRAP_RANGE : cachedFgHeightPx`,
and `Camera.setVerticalWrapEnabled(boolean, int)` (`Camera.java:1023-1032`) stores
`verticalWrapMask = range - 1`. That is a *different mechanism* from the ROM's, which
writes `$FFF` for every zone regardless of that zone's actual layout height. The two agree
only where the layout happens to be 32 rows. A zone with a shorter layout gets a shorter
mask in the engine and `$FFF` in the ROM. This is the shape rule 113 exists to catch — a
value derived from data rather than read from the routine that owns it — and it is
load-bearing for S3K-5.

**B. The engine gates wrapping on `minY < 0`; the ROM's `and.w` is unconditional.**
`LevelManager.java:2852` enables wrap only when `currentLevel.getMinY() < 0`.
`Render_Sprites` masks unconditionally (sonic3k.asm:36360, 36487) — `$FFFF` is how the ROM
expresses "no wrap", not a disabled code path. With `Screen_Y_wrap_value` at `$FFF` and a
level whose `minY >= 0`, the engine takes `Camera.java:872`'s unwrapped branch while the
ROM still masks.

**C. The engine uses `width_pixels` for the vertical margin; the ROM uses
`height_pixels`.** `Camera.java:856-864` reads `sprite.getRenderFlagWidthPixels()` and
then `int yMargin = useS3kMargin ? widthPixels : 32`. The ROM reads
`height_pixels(a0)` — SST offset **6**, a separate byte from `width_pixels` at offset 7 —
at sonic3k.asm:36358 and 36459. `grep` finds **no** `getRenderFlagHeightPixels` anywhere in
`src/main/java`: the engine has no `height_pixels` equivalent at all, so the substitution is
structural, not a local slip. It is exact only for objects whose half-width and half-height
coincide.

The comment at `Camera.java:866-869` correctly states that S3K masks with
`Screen_Y_wrap_value` and cites sonic3k.asm:36360, so the *mechanism* was already known;
what was missing is that the value is a written constant with a `$FFF` default, not a
property of the layout.

## What I could NOT establish

1. **Whether AIZ's and HCZ's `cachedFgHeightPx` is `$1000`**, i.e. whether divergence A is
   currently latent or currently active on the release slice. Answering it needs a run, not
   a read, and this round was scoped to the disassembly.
2. **Whether `Screen_X_wrap_value` has a parallel story.** `Get_LevelSizeStart` sets it to
   `#-1` alongside the Y one (sonic3k.asm:38092) and I did not sweep its other writes. The
   X half of `Render_Sprites` does not mask, so it does not affect S3K-5, but it may affect
   something else.
3. **What `Camera_Y_pos_mask` and `Layout_row_index_mask` are consumed by**, beyond the
   observation that they are always written as a triple with `Screen_Y_wrap_value` and
   encode the same period. If the engine models the layout period once for all three
   consumers, fixing A may be a one-place change; if not, it is three.
4. **Whether ICZ1's mid-act `$7FF` → `$FFF` switch is observable in any committed trace.**
   Same class of question as the parked item 4 from the previous round: it needs a trace to
   answer.

Items 4 and 5 from the previous round (deferred-delete tail observability, and the S3K-4
sweep gap) remain parked and were not touched.
