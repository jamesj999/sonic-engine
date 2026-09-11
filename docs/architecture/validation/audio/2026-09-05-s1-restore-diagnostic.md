# S1 restore diagnostic validation (2026-09-05)

This validates a bounded diagnostic, not an authenticated reference fixture or
S1 SMPS restore parity. The candidate was merged locally with develop
`a0b63f61daf1f397c232aa27137605e4fead6092`. Nothing was published or used to
hydrate gameplay.

## Final-source runtime measurement

The final source creates the established hidden `HeadlessGameBoot` GL lifetime,
configures its `EngineServices`, and runs the unchanged
`ProductionBk2AudioRunner` from canonical BK2 row 0. It never calls
`HeadlessGameBoot.boot`.

```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.openggf.tools.audio.parity.s1.S1RestoreDiagnosticTool \
  -Dexec.args="${NATIVE_RAW} ${ATTESTATION} '${S1_ROM}' ${CANONICAL_BK2} ${DIAGNOSTIC_ROOT}/s1-restore-openggf-diagnostic-20260905-i.json"
```

The output SHA-256 is
`2a3166b3f9e5cd0bde99cb2825fc9772b8c1a65fe2fcb6ff48e13987811d6daa`.
It reports matching request lists for rows 860--971, including `$a0` at row
958, then stops at row 972: native `$b5`, OpenGGF no request. No one-up
request, accepted admission, restore lifecycle, or following sequencer-tick
service end was reached; exact restore writes were withheld. The native stream
has no request history before row 860, so a matching observed window would not
establish equivalent saved voice, volume, or global state.

The prior `-h.json` output is retained only as prior-source evidence. The
initial zero-row abort transcript is retained externally as
`${DIAGNOSTIC_ROOT}/canonical-title-probe-abort.txt`, SHA-256
`44c75f9b29a42077a8977ee626e68575e2c74e71b9f157b1619a84ca75a90251`.

## Verification

Commands used JDK 21, `-Dmse=off`, and absolute verified S1, S2, and S3K ROM
paths where the suite accepted them.

- Focused diagnostic and unavailable-oracle checks: 11 tests, 0 failures, 0
  errors, 0 skips. Log:
  `target/s1-restore-diagnostic-postmerge/focused-final2.log`; XML is in
  `target/s1-restore-diagnostic-postmerge/focused-final-reports/`.
- Fresh ordinary suite: 16,676 tests, 0 failures, 0 errors, 43 skips, `BUILD
  SUCCESS` in 5:57. Log:
  `target/s1-restore-diagnostic-postmerge/ordinary-final.log`; XML was copied
  immediately to
  `target/s1-restore-diagnostic-postmerge/ordinary-final-reports/`.
- Initial structural guards: 609 tests, 2 failures, both from direct singleton
  access:
  `TestProductionSingletonClosureGuard.productionCodeDoesNotUseForbiddenProcessSingletonsOutsideEngineServices`
  and
  `TestProductionSingletonClosureGuard.productionCodeOnlyUsesRawGetInstanceAtEngineServicesBootstrapBridge`.
  The first ordinary suite was green, but its XML was not archived before the
  guard run and is not presented as retained final evidence.
- After routing configuration through the constructor-established
  `EngineServices`, structural guards ran 609 tests with 0 failures, 0 errors,
  and 0 skips. Log and retained XML:
  `target/s1-restore-diagnostic-postmerge/guards-final2.log` and
  `target/s1-restore-diagnostic-postmerge/guards-final2-reports/`.

The stop gate requires, in order, a one-up `$88` request, its accepted `$88`
admission, a `RESTORE` lifecycle with `REGISTRY` scope and `MUSIC_OVERRIDE`
source, and a later `SEQUENCER_TICK` service end. Negative controls cover
admission before request and unrelated lifecycle and service kinds. This is a
single-one-up bounded probe; it does not claim durable cross-domain token
correlation.

The candidate is integrated into develop. Combined post-merge verification
preserves every candidate outcome; push and cleanup status are tracked in the
[group ledger](2026-09-05-sol-smps-parity-delivery-group.md).
