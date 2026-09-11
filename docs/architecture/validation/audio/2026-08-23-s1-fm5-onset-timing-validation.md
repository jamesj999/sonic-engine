# Sonic 1 FM5 onset timing validation

## Outcome

The bounded Sonic 1 first-attack timing profile fixes the intermittent stale-
instrument leak heard at the first sample of an FM5 sound effect. It applies
only when the shipped 68K stream has an authenticated `SetVoice -> Note` or
`SetVoice -> Pan -> Note` shape. Other S1 paths, completion restoration, Sonic
2, and Sonic 3&K remain on their existing timing models.

Break Item's preceding non-writing `F0 ModSet` is handled only by bounded
capacity lookahead; the source timing program still begins at the first
SetVoice hardware write. Unsupported SetVoice-to-rest paths remain immediate
and leave a prefilled timed queue unchanged.

## Native authority

The retained diagnostic channel replays the two source-authenticated 30/31-
write programs from every exact 3,624-byte native YM state captured before the
corresponding S1 effect admission. The runtime program contains checked 68K
source costs, not captured inter-write delays. It applies the GPGX discrete-YM
BUSY rule at the actual service-cursor phase. Fresh A/B captures were
byte-identical.

- S1 oracle SHA-256:
  `703aeda6b776f3d1a872b55cec021eba5ef0bdafd8610c05aa690b24335d3a69`
- native patch SHA-256:
  `aa36d6e7b7c2e8fff7bd89d4e89ae54ea40cccc17eed64ee9cabad4fbc06bfce`
- compressed diagnostic core SHA-256:
  `f34616f5b9756cbe9cd5881f009eb3e27d2e53bf38d2c1b7ea1d5a2e833938c9`
- capture script SHA-256:
  `e187e2ca34f0c46a6213094d7a8059adad38136f6bca00e388e476c5eaa93f17`
- source program JSON SHA-256:
  `cc857e08a6f2b925c548cad4a56e0b54a407bf7c09931594319e2fb8cacbcf8a`

The matrix evaluates all 42 service-cursor residues against all 38 retained
states. Every residue improves the aggregate L1 key-on attenuation distance:
11,764 for former atomic publication becomes 9,188 for residues 14-21 and
9,221 for every other residue. Individual state outcomes are retained in the
oracle; the acceptance rule is deliberately per-residue aggregate, so no phase
can be selected after observing the result. This is a relative-write-timing
result; it does not claim absolute service-entry timing.

The shared diagnostic provenance change required a fresh S2 control capture.
Its A/B oracle is byte-identical at
`61a24ac2fac867ac7672e29434e5590ab6257ae03ca416c8fd00652a177a81b3`;
its instruction, write, projection, and FM5 streams are unchanged.

## Managed verification

The real S1 Break Item path emits the exact 30-register sequence and relative
cycle vector through the managed helper boundaries. A deliberate one-cycle
profile mutation makes that ROM-backed assertion fail, and restoring the
source value makes it pass. The exclusive owner reserves the existing 4,096
entry queue; an N-1 fixture aborts before timeline publication, service/write
ordinals, sequencer timing, or chip callbacks change.

The focused gates cover the source artifact/profile, S1 presentation and ROM
path, driver transaction/rewind architecture, pause/focus DAC behavior, and S2
and S3K controls. Exact final commands and counts are recorded after the frozen
implementation review and clean-HEAD package run.

JDK 21.0.11 focused results before the handoff commit:

- source program, S1 profile/census, real Break Item, and architecture guard:
  54 tests, zero failures/errors/skips;
- driver timeline/snapshot/pause, diagnostic observers, tempo/cadence, all
  three ROM presentation controls, and the unchanged S3K timing profile:
  149 tests, zero failures/errors/skips;
- independent review rerun: 52 tests, zero failures/errors/skips, with no
  Critical or Important finding;
- deterministic source-program regeneration: two generated JSON/Markdown
  pairs were byte-identical to each other and to the tracked artifacts.

The exact-parent detached baseline at
`e45b1f9e96c5e59a256140f32acafb2d3d15cb8f` and the candidate used the same
JDK 21 and authenticated absolute S1/S2/S3K ROM properties. Baseline:
15,488 tests, 55 failures, 56 errors, 19 skips; log SHA-256
`45615c8480d5278245f1c63f2b68744f0b10ebbcc3463399588b49d8b2f2b9ce`.
Candidate: 15,500 tests, 53 failures, 56 errors, 19 skips; log SHA-256
`f6ab2f8c4b6decf8d5a99aa3212b848a40ec2eb7eccbe6cde662682e755f23f5`.
The sorted baseline red ledger has 111 entries and SHA-256
`d86ccb70dbe8b0703fe2e0b6bb1659a3fda0dd942855a3de8331dcd7fc93847f`;
the candidate ledger has 109 entries and SHA-256
`cb284511f5ccb57a317b745bb43990320e9ef8b9dcbb82dcebda5fac051143e9`.
The candidate ledger is a strict subset: no baseline-passing test became red
and no retained failure/error changed status. The two unrelated order-sensitive
baseline failures that did not reproduce were
`TestCutsceneKnucklesAiz1Instance#exitHandoffReadsPreviousFrameRenderFlag`
and
`TestInitialPlayableProcessSpritesPass#nativeNeutralInputAndEpochProduceTheCapturedAiz1PlayerState`.

## Listening gate

Do not merge or push before a positive manual listen covering:

- first S1 FM5 effect after music has established an instrument;
- rapid repeats and the first replay after the prior effect completes;
- replay after a different FM5 effect;
- S1 pause/unpause and focus-loss/refocus DAC restoration;
- obvious onset regressions in S2 and S3K.
