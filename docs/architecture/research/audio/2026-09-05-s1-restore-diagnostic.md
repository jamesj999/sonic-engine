# S1 override/restore diagnostic (2026-09-05)

This is diagnostic evidence, not an authenticated reference fixture or a
production parity claim. The investigation never published a bundle or supplied
reference state or values to gameplay. Its engine probe used the original
canonical BK2 at row 0 and the existing `ProductionBk2AudioRunner` and
`CompleteRunAudioObserverLease` observation boundary.

## Fixed inputs

- S1 World REV01 SHA-1:
  `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`
- canonical complete-with-emeralds BK2 SHA-256:
  `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`
- sealed native raw SHA-256:
  `798c2197005c88abf99173629815220fe4d574274d9fa774be76fdeb37d57122`
  (25,700,433,659 bytes)
- native attestation SHA-256:
  `1796640fcb106bd50587f9e45af7643bf0cce790ac04754abfa0c2e97c5a064b`

The reader admits only the exact ABI 5 raw schema and identities above. It
validates the full raw digest, attestation digest and contents, request-to-
dispatch identity, monotonically ordered native writes, YM address/data latch
pairing, and the terminal `0xff` PSG key-off. These checks do not upgrade the
capture to production authority.

## Actual bounded result

The first canonical attempt exposed a missing GL lifetime before row 0:
`Sonic1TitleScreenDataLoader` palette cycling reached `glGenTextures` without a
current context. A direct-level experiment was rejected because it is not the
canonical movie startup. The existing `HeadlessGameBoot` constructor supplies
the hidden context used by `TraceCaptureTool`; only that constructor and
`close` lifetime wrap the unchanged `ProductionBk2AudioRunner`. Its `boot`
method is never called. Configuration is applied through the
constructor-established `EngineServices` instance, after construction and
before production startup; configuring the replaced pre-constructor singleton
would not establish the final runner inputs.

With that existing GL owner, the genuine bounded run completed rows 0 through
972. Native and OpenGGF request lists matched for every observed row 860--971,
including native request `$a0` at row 958. The first prerequisite divergence is
row 972: native records request `$b5`, while OpenGGF records none. The result is
`PREREQUISITE_DIVERGENCE`; the one-up request/admission and correlated restore
lifecycle/service end were not reached, and exact write values were not
compared. This identifies an upstream request-history difference, not an SMPS
register-parity result.

The final-source external result is
`${DIAGNOSTIC_ROOT}/s1-restore-openggf-diagnostic-20260905-i.json`, SHA-256
`2a3166b3f9e5cd0bde99cb2825fc9772b8c1a65fe2fcb6ff48e13987811d6daa`.
The earlier `-h.json` result is retained as prior-source evidence and is not
relabeled as the final run.
The native raw begins audio observation at row 860, so its earlier request
history remains unavailable; its captured baseline snapshot is comparison
evidence and must not hydrate OpenGGF.

The console transcript and exact failed invocation are retained outside the
repository at `${DIAGNOSTIC_ROOT}/canonical-title-probe-abort.txt`. The probe
transcript SHA-256 is
`44c75f9b29a42077a8977ee626e68575e2c74e71b9f157b1619a84ca75a90251`.
The probe
used the initial diagnostic source that configured the verified S1 ROM and
canonical BK2, enabled `TITLE_SCREEN_ON_STARTUP`, left the movie intact, and
called the unchanged `ProductionBk2AudioRunner` from row 0.

### Existing GL context reuse

`HeadlessGameBoot` already owns the hidden GLFW setup used by
`TraceCaptureTool`: its constructor initializes GLFW, creates an invisible
window, makes its context current, creates LWJGL capabilities, and initializes
`GraphicsManager`. Its `boot` method is not suitable here because it skips the
title route and loads a level directly. The diagnostic acquires only the
constructor/`close` GL lifetime around the unchanged runner; no title shim or
gameplay mutation is used. A missing hidden-context runtime remains a tool
failure and must never fall back to direct-level boot.

## Structural source boundary

The shipped `cfFadeInToPrevious` routine is independently useful for structural
checks. `docs/s1disasm/s1.sounddriver.asm` labels the routine at `loc_72B14` and
copies `$220` bytes of saved driver RAM before restoring voices. With shipped
`FixBugs = 0`, it omits the proposed YM2612 `$2b = 0` DAC-disable write. It then
iterates six FM tracks, calling `SetVoice` only for active, non-SFX-overridden
tracks, followed by three PSG tracks, calling `PSGNoteOff` only for active
tracks. Thus loop order, address/data ordering, omission of `$2b = 0`, and the
PSG key-off shape are source-owned; active slots, voice/operator values,
attenuation, pan and the resulting exact write sequence remain history-owned.

## Reproduction

After compiling, run `S1RestoreDiagnosticTool` with five ordered arguments:
sealed raw, attestation, verified ROM, canonical BK2, and a new output path.
It emits only a diagnostic prerequisite result and never publishes a fixture.
The stop gate is deliberately limited to this single-one-up bounded probe: it
requires ordered request, admission, restore-lifecycle, and service-end facts,
but does not claim a durable per-request correlation token across those domains.
