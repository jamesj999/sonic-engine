# S3K Collapse and Dash native audio audit

This audit compares locked-on Sonic 3 & Knuckles SFX `$59` (Collapse) and
`$B6` (Dash/spindash release) with the current OpenGGF SMPS path. The source
ROM is SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6`; the diagnostic GPGX core
is the current pinned raw artifact
`f57b7a94237653879fb99af197937500a8b591f801f56284b4d2f53ca7ea6b0c`
(compressed
`e65315743a6a122843907a85314e380eee03fdc06bf0885b44c3dbc3bab88c6d`).

The opt-in `GpgxZ80AudioCapabilityTests capture injected S3K SFX lifecycles`
test boots neutral gameplay, issues native StopAll, verifies all seven SFX RAM
slots idle, writes the requested ID to `zSFXNumber0`, and records the observer's
ordered YM/PSG writes plus the exact 48-byte RAM image of each affected track.
It uses the bounded test-only `GpgxHost.WriteZ80RamByte` API; no runtime state is
hydrated from this evidence.

## Source and lifecycle

Collapse has three short FM parts and a PSG3/noise part. The PSG bytecode uses
modulation `$01,$01,$0F,$05`, noise `$E7`, and five tied B3 notes of `$18`
updates, adding three attenuation units after each one
(`Sound/SFX/59 - Collapse.asm:30-39`). Native FM4/FM5/FM3 stop on relative
frames 17/18/19. PSG3 begins on frame 1, restarts its tied burst on frames
25/49/73/97, and stops on frame 121; two following frames are quiet.

Dash uses FM5 modulation `$01,$01,$C5,$1A` for E6 `$0F`, while PSG3 rests for
six updates then uses tone 1D, modulation `$01,$02,$05,$FF`, noise `$E7`, and
E6 `$4F` (`Sound/SFX/B6 - Dash.asm:10-24`). Native FM5 stops on frame 16. The
PSG note begins on frame 7, reaches its envelope hold, and stops on frame 86;
two following frames are quiet.

These counts disprove the initial “duration parser” hypothesis: OpenGGF
already produced the same 122- and 87-frame request-through-terminal
lifecycles.

## First divergent chip write

At Collapse's first PSG update native writes:

```text
DF E7 C8 04 F0
```

Before the correction OpenGGF wrote:

```text
DF FF E7 C9 03 C8 04 F0
```

The same duplicate-frequency shape appeared at Dash's PSG attack. The shipped
`zUpdatePSGTrack` prepares modulation, calls `zUpdateFreq`, calls
`zDoModulation`, and only then emits one two-byte PSG period
(`Z80 Sound Driver.asm:4058-4096`). OpenGGF instead emitted the base period and
then the modulated period. The extra `FF` came from treating the physical noise
latch as a second contention owner even though SMPS PSG3/noise is one logical
track; shipped `cfSetPSGNoise` emits `DF` followed directly by the noise byte
(`Z80 Sound Driver.asm:3541-3573`, `fix_sndbugs=0`).

After the correction, both first attacks match the native ordered bytes.
Replaying every observed PSG byte into a small effective-register model also
matches every native frame: Collapse's 124-row digest is
`d85bbd997725b5804d5990cb222f13a1c367ce2e76b628ab5ec61c515d81c584`;
Dash's 89-row digest is
`0b7d78978c85bc7c021789c333594b96f905bbf2e64f1b2b3921751f2af1e093`.
The model deliberately ignores redundant writes while retaining tone-2
period, noise mode, tone-2 attenuation, and noise attenuation.
