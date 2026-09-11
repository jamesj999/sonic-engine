# Trace contract remediation suite comparison

This report records the durable baseline comparison requested during review.
The comparison is between the full Maven suite at the pre-visual-merge
snapshot and the full suite after the visual trace merge. It is evidence about
the trace-contract work, not a claim that the repository-wide baseline is
green.

## Commands and snapshots

Both runs used the repository's JDK 21 Maven configuration and the full suite:

```text
mvn -Dmse=off test
```

| run | commit snapshot | result |
| --- | --- | --- |
| baseline | `36be0aa44e4e1db9d2d586fff984e52ffd4fe053` | 14,256 tests; 24 failures; 7 errors; 35 skipped |
| post-merge comparison | `3f68acb06007647478428636724639f8ab92a9c5` | 14,264 tests; 23 failures; 9 errors; 35 skipped |

The raw logs were `/tmp/openggf-develop-final.log` and
`/tmp/openggf-remediation-final.log` at capture time. The post-merge run added
eight tests. Its two additional errors are the two
`TestGameLoopSpecialStageEntryPresentation` methods introduced by the visual
special-stage handoff merge; the six Tornado errors and the
`Sonic2SpecialStageBootstrapCadenceTest` error were present in the baseline
failure family as well.

## Failure-set comparison

One baseline failure disappeared:

```text
com.openggf.game.sonic3k.scroll.SwScrlMhzTest.providerUsesMhzDeformForMushroomHill
```

Two post-merge errors appeared:

```text
com.openggf.TestGameLoopSpecialStageEntryPresentation.heldBlackEntryRetainsGenericTransitionSfxOwnership
com.openggf.TestGameLoopSpecialStageEntryPresentation.heldWhiteEntryDoesNotEmitGenericTransitionSfxAgain
```

All other named failure/error methods were shared by the two snapshots. The
trace-contract remediation itself did not add a new timing or trace replay
failure. Later title-card commits (`9e4d4c34e` and `08d1f765a`) and the
accidental GHZ2 report are outside this comparison.
