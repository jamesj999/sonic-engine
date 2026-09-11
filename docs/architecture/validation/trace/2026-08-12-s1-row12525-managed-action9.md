# S1 row 12525 managed action-9 validation

Date: 2026-08-12

Branch: `bugfix/ai-audio-retry-only-direct-parent`

Starting commit: `81714c337`

## Scope and source result

This bounded change fixes the managed Sonic 1 observer configuration and
correlation at the row-12525 `$72C24` `cfStopSpecialFM4` adjusted return. It
does not modify the native patch, observer ABI, core artifacts, capability
identity, or the inherited Task 5 identity files.

The source audit established that `$72C24` and `$72E04` are equivalent
`CLOSE_IF_RETURN_OUTSIDE` boundaries. At the observed `$72C24` callback,
`A7=$00FFFDAE` and `[A7]=$00071C38`; `$71C38` is a declared legal
`UpdateMusic` continuation, so the row takes action 9's KEEP branch. The bound
kind-4 root was entered with `A7=$00FFFDB2`, giving the source-exact managed
identity relation `callback A7 + 4 == root A7`.

## Implementation

- Every reviewed managed `CLOSE_IF_RETURN_OUTSIDE` hook now generates the
  existing action-9 alternatives for typed asynchronous kinds 2 and 3, while
  preserving action 5 for ordinary top-kind-4 execution.
- Prepublication callbacks retain conditional A7/return identity and correlate
  KEEP or outside-close behavior without publishing discarded evidence.
- Published and boundary paths match the exact root token plus the audited A7
  relation. Invalid A7/return proof, frame rejection, reset, and cutoff
  transitions fail closed and restore managed state transactionally.

The generated configuration is pinned at 285 hooks and 16,412 declared
snapshot bytes. At each `$72C24`/`$72E04` PC there are exactly three choices:
action 5 with expected kind 4, and action 9 with expected kinds 2 and 3. All
three retain opcode `4e75`, proof range 2/count 1, predicate slice
`first=3,count=7`, and zero flags.

## TDD and verification

The first valid RED was:

```text
FAIL S1CompleteRunAudioReferenceCaptureTests pin every reviewed REV01 boundary
Expected <3> but was <1>.
```

The behavior RED reproduced kind 4 directly parenting kind 2 at `$72C24` and
failed before return evaluation with:

```text
Fake native M68K visit had no active-kind alternative.
```

After implementation and independent review, these fresh commands passed:

```bash
BIZHAWK_HOME="$REPO_ROOT/docs/BizHawk-2.11-linux-x64" \
  tools/bizhawk-headless/test.sh --jobs 1 --no-gates \
  --filter 'S1CompleteRunAudioReferenceCaptureTests'

BIZHAWK_HOME="$REPO_ROOT/docs/BizHawk-2.11-linux-x64" \
  tools/bizhawk-headless/test.sh --jobs 1 --no-gates \
  --filter 'CompleteRunAudioObserverTests'
```

Results:

- S1 managed synthetics: 41 passed, 0 failed; the one opt-in real prefix gate
  skipped because `OPENGGF_S1_AUDIO_PREFIX` was not enabled.
- Shared observer synthetics: 25 passed, 0 failed.
- The filtered runner also reported its unrelated `GpgxHost` case skipped
  because `S1_ROM_PATH` was not set.
- `git diff --check` passed for the scoped files.

The matrix covers both adjusted-return PCs, kinds 2 and 3, KEEP and outside
close, published and prepublication paths, ordinary action-5 behavior at
`$72C24`, exact token/A7 identity rejection, invalid pointer/return proof,
reset/power/combined reset after action-9 promotion, and malformed missing END
or promotion rollback in both epochs.

Independent review verdict after the added config-accounting and action-9
reset/rollback cases: SPEC PASS and QUALITY PASS.

## Authoritative real proof

The managed harness was rebuilt through `tools/bizhawk-headless/test.sh` from
commit `7ba71fc889dd91f39347d117864c9cf16740e9ca`, followed by the separately
reviewed callback-stack prerequisite commit `71a8d889c35d0977a055ffd883b1bc9dcc744124`.
The resulting normally built production/test executable SHA-256 values were
`9b73d5380ab493938c38d522bcd2a1ef4d9809d16c8e26935d038c27a6ee8a89`
and `4411aae9bbbf508a9cecd5aa61fff3906c26169dc3674b7acb5b12d24bc5fc34`.
The native diagnostic install was not rebuilt or changed. Its compressed core
remained SHA-256
`a383b3762fc8000a0354b54397832208728863f559905ec6e8d163e66ab1bb35`;
the sorted aggregate of every installed regular-file SHA-256 remained
`be7850247e8b011fa85c20f558edd9349cbac44360a5217c41eacc2215e28e1f`,
and it contained no symlinks.

The exact inputs were the regular-file Sonic 1 World REV01 ROM, SHA-1
`69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`, and the in-place complete-run
BK2, SHA-256
`f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`
(225,101 frames). The strict opt-in selector was:

```bash
OPENGGF_S1_AUDIO_ROW12525=1 \
S1_ROM_PATH=<S1-REV01> S1_AUDIO_BK2_PATH=<complete-run-bk2> \
BIZHAWK_HOME=target/audio-parity/native/task4-reserve-consume-diagnostic-install \
tools/bizhawk-headless/test.sh --jobs 1 \
  --filter 'S1CompleteRunAudioReferenceCaptureTests prove row 12525 action 9 keep and promotion'
```

It passed. The selector discovers the kind-4 root and kind-2 child by PC and
topology before binding later events relationally; token 5/6 values are
secondary assertions, not selectors. At row 12525 it proves:

- root token 5 kind 4 begins at `$71B4C`, then child token 6 kind 2 begins at
  `$77` with immutable begin parent 5/depth 1;
- `$72C24` emits marker value 0 through action 9 while retaining child token 6
  with parent 5/depth 1; the managed callback has `A7=$00FFFDAE`, return
  `$00071C38`, and exactly the same native marker correlation at ordinal 219;
- `$71C4C` then ends root token 5 and the immediately adjacent action-8 event
  promotes child token 6 at ordinals 223/224 to current parent 0/depth 0
  without rewriting its immutable begin ancestry; the later `$AC` closes child
  token 6 at ordinal 230.

The final exact-gate log is
`target/audio-parity/native/row12525-71a8d889c-exact-gate-final.log` (61 lines,
2,977 bytes), SHA-256
`c9169ac7644eaf3eae8b9cc9f09571e81117d5f44d7822b0f05320018a446bfd`.

Only after that gate was green, one configured-terminal run used the existing
row-8775 selector plus `OPENGGF_S1_AUDIO_TERMINAL_PROBE=1`. It crossed row
12525 and stopped at the sole next exact frontier, row 21766. This is the
test-only real-proof aggregate `StringWriter` capacity frontier, not a native
observer fault, production `NoReplacePublisher` fault, or semantic audio
mismatch: `StringBuilder.Append` rejected that aggregate writer while
`Session.ProcessFrame` published the row transaction. The run was not retried
and no sink contract was changed. No final raw reference was published. Log:
`target/audio-parity/native/row12525-71a8d889c-bounded-terminal.log` (73 lines,
4,920 bytes), SHA-256
`b0a1473e3d827cf037bb4759ec266bf25a01b28b003b268a31695bd99586c9d7`.

Fresh prerequisite verification passed 41 non-opt-in S1 managed synthetics and
25 observer synthetics. Two independent re-reviews found no critical or
important issue after the FF-RAM range hardening. The proof does not claim a
reference-vs-OpenGGF semantic MATCH or complete-game reference publication.

## Immediate selective proof-sink correction

The follow-up row-21766 infrastructure change is test-only. The real-prefix
helper no longer accumulates every published record in one `StringWriter` or
calls `ToString()` over the complete run. A selective `TextWriter` reconstructs
one LF-terminated JSONL line at a time, parses every line (including discarded
lines), accepts only the declared raw record types and capture-row range, and
retains only the baseline, requested proof rows, and requested terminal. The
row-8775 gate retains rows 1548 and 8775; the row-12525 gate retains row 12525.
Their relational assertions are otherwise unchanged.

The writer has explicit ceilings of 262,144 characters per line, 131,074
retained records, and 16,777,216 retained characters. Synthetic coverage uses
deliberately smaller limits and writes 10,003 records while retaining exactly
three. It also rejects CRLF, a partial final line, malformed JSON, unknown
record types, missing/out-of-range/oversized rows, duplicate baseline or
terminal, data after terminal, the wrong terminal boundary, and retained
record/character overflow. Any such failure poisons the writer; because the
capture `Session` owns that writer, recovery requires a fresh writer and fresh
session rather than retrying the advanced session.

The test-first RED was a compiler failure (`CS0246`) because
`SelectiveJsonlProofWriter` did not exist. After the implementation, the fresh
synthetic commands were:

```bash
BIZHAWK_HOME="$REPO_ROOT/docs/BizHawk-2.11-linux-x64" \
  tools/bizhawk-headless/test.sh --jobs 1 --no-gates \
  --filter 'S1CompleteRunAudioReferenceCaptureTests'

BIZHAWK_HOME="$REPO_ROOT/docs/BizHawk-2.11-linux-x64" \
  tools/bizhawk-headless/test.sh --jobs 1 --no-gates \
  --filter 'CompleteRunAudioObserverTests'
```

Results were 43 S1 synthetics passing and 25 Observer synthetics passing, with
the two opt-in real S1 gates skipped and no failures. No configured-terminal
capture was run for this correction. Therefore row 21766 remains the last
observed infrastructure frontier until the separate real-proof continuation;
this result does not claim a new native/semantic frontier or a terminal.

## Fresh configured-terminal continuation

Starting at selective-sink commit `348aa119624ad514656ccbc4f013fcffb1a1c3a1`,
one fresh configured-terminal session used the unchanged Task 4 diagnostic
install and the same verified inputs. Before launch, the Sonic 1 World REV01
ROM SHA-1 was
`69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`, the complete-run BK2 SHA-256
was `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`,
the compressed diagnostic core SHA-256 was
`a383b3762fc8000a0354b54397832208728863f559905ec6e8d163e66ab1bb35`,
and the sorted installed-regular-file aggregate remained
`be7850247e8b011fa85c20f558edd9349cbac44360a5217c41eacc2215e28e1f`.
The install contained no symlinks. The optional row-12525 selector was not
repeated because the terminal test flow did not require it; no managed or
native observer contract had changed after its exact passing proof.

The exact bounded command was:

```bash
OPENGGF_S1_AUDIO_PREFIX=1 OPENGGF_S1_AUDIO_TERMINAL_PROBE=1 \
S1_ROM_PATH=<S1-REV01> S1_AUDIO_BK2_PATH=<complete-run-bk2> \
BIZHAWK_HOME=target/audio-parity/native/task4-reserve-consume-diagnostic-install \
tools/bizhawk-headless/test.sh --jobs 1 \
  --filter 'S1CompleteRunAudioReferenceCaptureTests consume one deferred child begin during row 8775 wait service'
```

The row-21766 aggregate-writer failure did not recur and the selective writer
did not fault. The one session stopped at the next exact native observer
frontier, movie row 119247, with status `-3` and
`first_fault=4:1:77:6:1:0:4`. In the packed first-fault contract this is reason
4 (`SERVICE`), source CPU 1 (Z80), PC `$0077`, active service kind 6, native
stack-entry count 1, and continuation count/limit 0/4. The sole root entry's
stored depth is 0; the packed `active_depth` field reports the current stack
count. The tuple alone does not name the failed service predicate. Combined
with the manifest and native action order, however, it selects token 11's
`TAIL_POP_PUSH`, whose expected top kind 6 passes. At stack count 1 it has no
parent, so its parent-kind guard cannot reject; the exact reachable reason-4
predicate is the pending deferred reservation guard.

The native lifecycle diagnostic records the earlier root kind-6 begin at
`$003A` (ordinal 32), deferred kind-4 child begin at `$71B82` (ordinal 37),
child end at `$71C4C` (ordinal 46), kind-6 end at `$0077` (ordinal 54), and
the first root kind-2 begin at the same `$0077` (ordinal 55). The diagnostic
tail, which is a separate last-16-event excerpt rather than the fault site,
has token 185 end at `$AC` (ordinal 1692), token 186 begin at `$0077`
(ordinal 1693), and chip events at ordinals 1694-1695. Pending managed
evidence was empty.

The failed session was not retried. It did not reach `Complete(225101)`, and
no final raw reference or partial output was published. The preserved log is
`target/audio-parity/native/row21766-348aa1196-configured-terminal.log` (69
lines, 7,765 bytes), SHA-256
`71e9549406dfa7bb7b9baacf913558878c4371beb7dd7ad224fe3cfc02dcbe9e`.
This is a reference-observer frontier, not a reference-vs-OpenGGF semantic
MATCH. The separate production deferred-retention issue was neither exercised
as a claimed fix nor changed by this proof.
