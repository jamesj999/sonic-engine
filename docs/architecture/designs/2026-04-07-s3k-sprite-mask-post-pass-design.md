# S3K Sprite Mask Post-Pass Design

**Problem**

The Gumball bonus stage still renders machine-internal sprite content in front of FG body tiles when it should be hidden. Diagnostics showed:

- FG tiles behind the leaking area are already high-priority.
- Startup Gumball stage object placement does not include static `0xEB` item objects inside the machine.
- Gumball body frame `0x16` is mostly high-priority and is not itself a broad low-priority underside layer.
- The ROM uses `Spritemask_flag` plus helper frame `0x17` to trigger a post-sprite-build mask path.

The current engine has tile-priority compositing, but no sprite-table mask pass.

**ROM / Hardware Basis**

- `Obj_GumballMachine` helper child `loc_610C6` sets `Spritemask_flag` each frame.
- After sprite assembly, the ROM checks `Spritemask_flag`, scans the sprite table for tile `0x7C0`, and mutates sprite entries.
- `Map_GumballBonus` frame `0x17` includes the mask-tile pair that participates in that process.
- Mega Drive hardware masking behavior depends on sprite order and X-position behavior, especially X=0 mask interactions. This is sprite-list behavior, not fragment-shader behavior.

**Chosen Approach**

Implement a CPU-side sprite-mask post-pass in the engine's sprite render pipeline.

Scope:

- Keep the existing tile-priority shader behavior unchanged.
- Build or capture sprite pieces in final bucket/order before draw submission.
- When `Spritemask_flag` is active for the frame, detect mask helper pieces generated from tile `0x7C0`.
- Apply a mask rule to later sprite pieces on overlapping scanlines before they are submitted for rendering.
- Limit initial verification to the Gumball machine and generic `Obj_SpriteMask` parity cases.

**Non-Goals**

- Full VDP SAT emulation.
- General shader-based rectangle masking.
- Reworking tile-priority decoding or bucket ordering again.

**Implementation Shape**

1. Add focused tests for:
   - detection of mask helper pieces in final sprite order
   - suppression of later sprite pixels/pieces after a mask helper
   - Gumball startup composition regression
2. Introduce a sprite-piece post-processing stage on the CPU side.
3. Extend the renderer pipeline so sprite commands can participate in per-frame ordered mask processing before final draw submission.
4. Wire `Spritemask_flag` producers to the post-pass inputs without introducing Gumball-only special cases.

**Success Criteria**

- Gumball startup internals no longer appear in front of the machine body FG where the ROM masks them.
- Existing shield and bumper fixes remain intact.
- No shader-only masking logic is reintroduced.
