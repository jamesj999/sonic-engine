# Complete-run audio parity tools

`run_complete_audio_parity.sh` is the create-new orchestration boundary for the
`complete_run_audio.v2` capture format. It runs each fixed producer twice,
requires byte-identical duplicates, validates both captures through the fixed
`CompleteRunAudioTool` class, and publishes the comparison report as one
directory. It never deletes or overwrites a run.

Build the executable JAR on JDK 21 first. Run roots are restricted to a direct
child of `target/audio-parity/runs`:

```bash
tools/audio/run_complete_audio_parity.sh \
  --run-root "$PWD/target/audio-parity/runs/my-new-run" \
  --profile complete-run-profile-id \
  --rom /absolute/pinned-rom \
  --bk2 /absolute/pinned-movie.bk2 \
  --run-manifest /absolute/pinned-run-manifest.json \
  --reference-home /absolute/separate-bizhawk-install
```

The producer dispatcher is a closed in-process `S1`/`S2`/`S3K` registry; callers
cannot replace its class IDs or register implementations. Each profile pins
REFERENCE and OPENGGF independently and returns `PRODUCER_UNAVAILABLE` without
output for an unpinned kind. Built-in producers must write a fresh canonical
capture directory. Only the reference producer receives the separately
installed BizHawk home.

V2 keeps ten evidence layers independent. Each producer declares what it
observed, while the shared comparison inventory separately declares what may be
compared. Buffered BizHawk captures additionally store authenticated native
token/hook/PC/source events and raw snapshots in diagnostics sidecars. OpenGGF
callback producers omit native sidecars. A null layer is unobserved; a non-null
empty list is observed-empty.
Each built-in profile class statically registers its immutable profile; every
fresh CLI JVM lazily initializes only the profile class fixed in the closed
dispatcher, so no prior process or mutable registration command is required.

The fixed Java preflight authenticates the complete reference installation,
including entry types, permission modes, names, and every regular-file digest;
unknown additions and linked or special entries are rejected. The shell also
rechecks the caller installation and its private capture copy before and after
the producer runs. Any separate stock-control distribution is owned and pinned
inside the later built-in producer, never supplied as a replacement command.

Exit status is `0` for a match, `2` for usage/security refusal, `3` for a valid
semantic mismatch, and `4` for producer or capture failure. Ambient Java, shell,
Git, SSH, and dynamic-loader injection variables are rejected.

`producer-status <profile>` remains pair-wide and is what the parity wrapper
uses. `producer-kind-status <REFERENCE|OPENGGF> <profile>` checks one fixed
producer independently and reports that binding's exact unavailable reason.

`run_s1_ghz1_gameplay_audio_timeline.sh` has never been exercised end-to-end:
its hardcoded in-repo `OUTPUT_ROOT` is rejected by TraceChaser's
`output_policy.py`, which requires probe output to live outside both source
trees (the script's own usage text says this, but it never exposes an
external `--output-root` to satisfy it). Fixing that needs a review of
`S1GameplayAudioTimelineTool`'s validate/publish-reference contract, not a
one-line change; it is out of scope here. The S1 gameplay driver oracle
(`run_s1_audio_parity.sh --mode gameplay`) is the separate, working path for
gameplay-sourced S1 audio capture.
