# Mapping and callback oracles
S3K mapping frame tables store relative `dc.w` offsets. Decode each word as a **signed 16-bit displacement from the table base**, and audit negative/backward pointers and shared-frame references. Do not zero-extend the word. When the first frame pointer cannot prove the offset-table length, pass a disassembly-verified explicit frame count to the loader rather than guessing from that pointer.

Add a real-ROM table-shape regression for each affected table: assert exact frame count plus representative piece count, dimensions, and tile indices, including a backward/shared frame when present. A runaway address, oversized allocation, or OOM is a decoder/metadata failure; fix the generic signed decoder or verified table metadata, never heap limits, memory workarounds, or object-specific address exceptions. Run both the full ROM-conditional art crawler and `TestPatternSpriteRendererCorruptionGuard` after the focused regression.


## Shared routines, callbacks, and lifetime


Before assigning a timer, animation, or callback semantic to an object field, trace the complete reachable control-flow graph. Follow every tail `jmp`, shared helper such as `Obj_Wait`, callback/function pointer stored in the object, and every routine that reads or writes the same bytes. Record the field width at each access and all competing consumers. A nearby animation script or comment does not own the behavior merely because it is easier to read.

Trace lifetime outside the object's own `update()` too. Inspect `ObjectManager` pre/post-update culling, remembered-placement unload, fixed power-up slot rules, `isPersistent()`, and the object's out-of-range reference. The absence of a ROM `out_of_range` tail is behavior: a fixed-slot or player-bound aggregate must not inherit ordinary spawn-anchor culling. Add a manager-level test that moves the owner and camera beyond the normal window, then verify both continued registration and the ROM-defined semantic deletion condition.

Calculate countdown edges with native signed arithmetic. For `subq.w #1,field` followed by `bmi`, an initial word value `N` fires after `N+1` decrements: the update that reaches zero does not branch; the next update reaches `$FFFF` and does. Do not replace this with an unsigned `<= 0` check or derive the delay from an animation script that shares the callback.

When raw animation `$F4` and `Obj_Wait` can invoke the same callback, compare both reachable paths from the actual entry state; the earliest path owns the observed transition, even if the later callback remains reachable but redundant. Add a focused RED test covering the last non-firing update and the exact firing update, plus the competing consumer's later boundary. If implementation, local comments, and the disassembly oracle conflict, stop and obtain independent disassembly adjudication before changing either the expected value or the code.
