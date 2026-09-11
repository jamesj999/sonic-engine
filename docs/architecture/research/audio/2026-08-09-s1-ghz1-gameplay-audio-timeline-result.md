# Sonic 1 GHZ1 Gameplay-Audio Timeline Result

**Date:** 2026-08-09

**Result:** deterministic valid admission-timing mismatch after raw request parity

## Run and pinned identities

The hardened runner completed two independent captures from each producer and
returned the documented semantic-mismatch exit code `3`:

```bash
DISPLAY=:0 tools/audio/run_s1_ghz1_gameplay_audio_timeline.sh \
  --rom './Sonic The Hedgehog (W) (REV01) [!].gen' \
  --bizhawk-home docs/BizHawk-2.11-linux-x64
```

The final detailed evidence remains ignored under
`target/audio-parity/s1-ghz1-gameplay/run.avvaEipc/`.

| Input | Verified identity |
|---|---|
| ROM | Sonic 1 World REV01; SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`; CRC32 `afe05eee` |
| BK2 | Complete-with-emeralds; SHA-256 `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`; 225,101 rows |
| EmuHawk | Linux x64 2.11; SHA-256 `b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3` |
| Core assembly | `BizHawk.Emulation.Cores.dll`; SHA-256 `0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7` |
| Genesis Plus GX | `gpgx.wbx.zst`; SHA-256 `c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12` |
| Segment | BK2 frames `[860,4975)`; 4,115 frame records; GHZ `$81` baseline |

| Producer | Duplicate SHA-256 | Bytes | Requests | Admissions |
|---|---|---:|---:|---:|
| BizHawk reference | `6696221b907cc37333898d26fa44e9837788e158c6288919b06dadc42ed1f84c` | 2,127,539 | 174 | 154 |
| OpenGGF | `ab5f1cea17a9a589b9aa7290cb3b222bdd760d26d95d97d848b4023ce683c2fb` | 2,135,765 | 175 | 175 |

`cmp` passed for both duplicate pairs. Detailed streams, logs, and reports are
not repository artifacts.

## Corrected semantic boundary

Schema v2 separates the cause from its later presentation:

- a request is emitted at `QueueSound` on the ROM and at the numeric
  `AudioManager.playSfx`/`playMusic` entry in OpenGGF, before any ring transform;
- an admission is emitted at ROM `PlaySoundID`/SMPS initialization and at the
  OpenGGF production presentation/contention boundary, with the same ordinal;
- requests carry `raw_sound_id`; admissions and owners carry the resolved ID,
  requested roles, arbitration, and final owners.

This removes the former, invalid classification of the first result as a
gameplay-caller scheduling mismatch. The callers already agree.

## First mismatch

The first result is `ADMISSION_EXTRA` at frame 958, admission 0:

| Event | BizHawk REV01 | OpenGGF |
|---|---|---|
| Jump request | frame 958, ordinal 1, raw `$A0` | frame 958, ordinal 1, raw `$A0` |
| Jump admission | frame 959, ordinal 1, resolved `$A0`, PSG1 | frame 958, ordinal 1, resolved `$A0`, PSG1 |

No event realignment is applied. The comparator therefore stops at the first
causal difference: OpenGGF presents the already-matching jump request one frame
earlier than the shipped sound driver's queue consumption. This result does not
authorize a gameplay timing change or a chip-port change.

Ring resolution independently proves the split identity contract. Both
producers emit ordinal 2 at frame 972 with raw `$B5`; both admit resolved `$CE`
on FM4, on frame 973 for REV01 and frame 972 for OpenGGF.

## Contention evidence

Both required contention classes are present in both complete streams; the
comparator would otherwise report capture failure before a parity mismatch.

- Music/SFX takeover and restoration: jump ordinal 1 takes PSG1 from GHZ `$81`
  (reference frame 959, OpenGGF 958); `$81` owns PSG1 again at reference frame
  985 and OpenGGF frame 984. Across the complete streams there are 108 and 104
  acquired music-owner displacements respectively.
- SFX/SFX contention: resolved ring ordinal 4 displaces the older resolved ring
  ordinal 2 on FM4 (reference frame 983, OpenGGF 982). The complete streams
  contain 65 and 86 identity-changing SFX-owner contention decisions respectively.

The same-ID OpenGGF path now reports the old SFX ordinal through the existing
authoritative lock arbitration rather than reporting music/null after releasing
the old lock.

## Publication and verification notes

A preliminary in-sandbox BizHawk launch failed before Lua execution because no
X display was accessible. The nonzero producer path invoked only the trusted
`discard-reference` command; no staging capture or output survived. The real run
then completed on the existing display outside the sandbox. Negative tests also
prove altered core binaries are rejected and that even a complete staging stream
cannot publish through the failed-producer path.

Human listening remains required for jump onset, ring stereo alternation,
waterfall contention, invincibility/extra-life restoration, and YM2612/PSG
balance; the semantic result alone does not establish audible parity.
