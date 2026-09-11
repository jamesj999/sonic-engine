# Audio research

This collection supports investigation of Mega Drive audio behavior and
OpenGGF's audio implementation. It contains:

- provenance stubs (`.md`, one per page) for the external references about YM2612,
  SN76489, SMPS, and Sonic audio data that were consulted; the third-party pages
  themselves are not tracked, each stub links to the original;
- reference recordings captured from trusted emulators or source material;
- OpenGGF engine captures used for comparison;
- analysis images, raw PCM data, and intermediate diagnostic outputs;
- investigation notes, scripts, and code excerpts.

These files are documentation and research evidence, not runtime assets.
Gameplay and runtime asset bytes must continue to come from the user-supplied
ROM through the ROM-loading pipeline.

Current investigation notes include
`2026-08-08-s3k-smps-meta-command-reachability.md`, which records the
ROM-backed reachability inventory and native Z80 contract for S3K `FF` meta
commands.
