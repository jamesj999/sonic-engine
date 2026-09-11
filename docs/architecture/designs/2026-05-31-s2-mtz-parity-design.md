# S2 Metropolis Zone Parity Design

## Goal

Bring Sonic 2 Metropolis Zone closer to ROM-accurate parity by closing known implementation drift around dynamic boss discovery metadata, MTZ3 level-event sidekick bounds, and Obj66 spring-wall solid routine behavior, then validate against focused tests and MTZ trace replay frontiers.

## Scope

- Use ROM-loaded runtime assets only. Art, mappings, DPLCs, PLCs, animation data, and object assets must continue to flow through the Sonic 2 ROM loaders.
- Preserve the existing implemented static MTZ object and badnik factories while correcting discovery/profile drift for dynamic boss objects.
- Model the MTZ3 boss-entry event write to both `Camera_Min_Y_pos` and `Tails_Min_Y_pos` with a semantic sidekick minimum-Y bound, not a trace or frame carve-out.
- Validate Obj66 MTZ spring wall behavior against the shared solid-profile offscreen gate path and add the ROM-equivalent bypass if confirmed by the existing trace-frontier notes.
- Keep trace replay data comparison-only. Do not hydrate gameplay state from traces.

## Non-Goals

- Do not rewrite the full MTZ boss unless focused validation exposes a concrete ROM mismatch.
- Do not introduce fallback reads from `docs/` disassembly files for runtime assets.
- Do not alter unrelated S1/S3K physics behavior.

## Acceptance

- `ObjectDiscoveryTool` no longer reports MTZ dynamic boss IDs `0x53` and `0x54` as unimplemented when the corresponding engine implementations exist.
- MTZ3 routine 6 can clamp sidekick lower camera access through a persisted `SidekickCpuController` min-Y bound, captured by rewind state.
- Obj66 exposes the correct solid profile behavior for the shared offscreen solid gate.
- Focused MTZ event, art, profile, and trace tests are run, with any remaining trace frontier documented in `docs/status/trace-frontier-log.md`.
