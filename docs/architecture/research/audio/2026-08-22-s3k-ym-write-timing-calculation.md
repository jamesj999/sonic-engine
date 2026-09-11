# Locked-on S3K YM write timing calculation

## Scope and normalization

The executable companion is
[`s3k-ym-write-timing-calculation-v1.json`](s3k-ym-write-timing-calculation-v1.json).
It is the canonical calculation for the first audited FM5 path. Runtime code
does not read it. Every reusable `executed_paths` entry is a list of actual Z80
opcode rows with an execution count and the authoritative T-state cost for that
opcode. Write rows name one of those checked paths; they never carry a copied
subtotal. Each later write is derived with checked integer arithmetic as
`sum(count * authoritative_t_states) * master_cycles_per_z80_t_state`, plus
only the separately declared GPGX bank-read wait rows. The parser rejects an
unknown opcode label, a T-state value which differs from its opcode, an empty
later-write path, or a per-wait timing override.

Every primitive row also owns a `Z80 Sound Driver.asm:first-last` source
location. The test validates those locations against the shipped source and
freezes the complete ordered `(path, source, opcode, count, T-states)` table by
SHA-256. This is intentionally stricter than checking totals: changing a
17-T-state `CALL` into a cost-equivalent 12-T-state taken `JR` plus a
5-T-state non-taken `RET` fails even though the derived write cycle is
unchanged.

The source tree itself is a local research input and is intentionally ignored
by Git. Clean-checkout tests therefore do not open `docs/skdisasm` or validate
against its filesystem line count. The tracked calculation carries the
citation grammar and inclusive numeric bounds (`1..3506`); tests validate every
row against that self-contained schema, then apply the ordered digest and
cost-equivalent poison checks. This preserves auditable citations without
making CI depend on an untracked disassembly or embedding source text.

The frozen clock header is 15 master cycles per Z80 T-state, 1,008 master
cycles per YM2612 internal sample (`42 * 24`), and the selected GPGX average of
three Z80 T-states per uncontended banked read. Tests consume those parsed
values and reject drift in any one of them. Slot zero is always the empty,
zero-relative anchor at the first hardware data write. Work before that anchor
is named by `source_prefix_path`; work between independently normalized
segments is named by `cross_segment_advance_path` and its explicit bank waits.

The shipped source selects `fix_sndbugs = 0` at
`docs/skdisasm/Sound/Z80 Sound Driver.asm:15-16`. Consequently the calculation
keeps both nominally pointless NOPs and takes the unfixed call/return paths. It
does not model the optional repaired driver.

## Executed source paths

All T-state totals below use the Z80 cycle table used by the pinned Genesis Plus
GX core. Taken/not-taken control flow is part of each row; a row is not an
instruction-count estimate.

| Owner | Executed shipped path and timing contribution |
|---|---|
| `zWriteFMIorII` | `BIT 7,(IX+d)` 20, `RET NZ` not taken 5, `BIT 2,(IX+d)` 20, `RET NZ` not taken 5, `ADD A,(IX+d)` 19, `BIT 2,(IX+d)` 20, and, for FM5, `JR NZ` taken 12 plus `SUB 4` 7. Source: driver lines 562-570. |
| `zWriteFMI` | `LD (nn),A` 13, the shipped `fix_sndbugs=0` `NOP` 4, `LD A,C` 4, `LD (nn),A` 13, `RET` 10. Source: lines 582-590. This is used for register `$28` even when the track is FM5. |
| `zWriteFMII` | After the reduced-path `SUB 4`, `LD (nn),A` 13, shipped `NOP` 4, `LD A,C` 4, `LD (nn),A` 13, `RET` 10. Source: lines 595-615. |
| `zSetMaxRelRate` / `zFMOperatorWriteLoop` | `LD A,n` 7 and `LD C,n` 7 fall into `LD B,n` 7. Each loop uses `PUSH AF` 11, `CALL` 17, `POP AF` 10, `ADD A,n` 7, and `DJNZ` 13 taken / 8 final. `zFMOperatorWriteLoop.next-portII-data` enumerates these and the called port-II instructions separately; their checked sum is 210 T-states, or 3,150 master cycles, between maximum-release data writes. Source: lines 2676-2699. |
| `zSendFMInstrument` | Panning is written first, then feedback/algorithm, twenty non-TL fields, and four TL fields. Shipped `fix_sndbugs=0` uses one twenty-entry `CALL zSendFMInstrData` / `DJNZ` loop rather than the repaired SSG-EG attack-rate split. Source: lines 1531-1575. |
| `zSendFMInstrData` | `LD A,(DE)` 7, `INC DE` 6, banked `LD C,(HL)` 7, `INC HL` 6, then shipped `CALL zWriteFMIorII` 17 and `RET` 10. The bank access has its wait recorded separately. Source: lines 1588-1598. |
| `zSendTL` | `LD A,(HL)` is followed by `OR A` and `JP P`. A carrier executes the additional `ADD A,(IX+Volume)` 19 T-states. The shipped branch then executes `AND $7F`. Thus every carrier bit adds exactly 19 T-states = 285 master cycles to its stored-operator slot. Source: lines 3178-3209. |
| `zKeyOnOff` | `LD A,$28` 7, then the shipped branch is `CALL zWriteFMI` 17 and `RET` 10 rather than the repaired tail jump. Source: lines 1168-1176. |
| Frequency and key-on | Non-FM3 `zFMSendFreq` checks the override and special-mode bits, writes `$A4`, then on the shipped branch calls again for `$A0` and returns. The data-write delta is 180 T-states (2,700 master cycles). The later note-on setup and `zKeyOnOff` total 192 T-states (2,880 master cycles). Source: lines 815-832 and 1168-1176. |
| Completion and restore | Shipped `cfStopTrack` performs its junk stores, keys off, resolves the overridden FM5 music track, takes the nonnegative voice-index branch, runs `bankswitchToMusicS3`, calls `zGetFMInstrumentOffset`, and restores through `zSendFMInstrument`; it then switches back to SFX. `cfStopTrack-keyoff-to-restored-panning-data` lists the executed `RET`, pointer-table `RST` body, bank-switch stores/rotates, branch, call, and panning-write opcodes rather than a 1,078-T-state row. Source: lines 309-326, 426-456, 1449-1480, 1531-1536, 2110-2160, and 3443-3506. The executable audited segment is the Special Stage FM5 restore path and includes the key-off plus the complete 26-write restore upload. |

The panning-to-algorithm and instrument-loop deltas are 215, 251, and 238
T-states. Each contains one banked voice-byte access, so their executable rows
split those numbers into 212+3, 248+3, and 235+3. The first TL delta is 340+3
T-states for Blue Sphere's noncarrier stored operator. Its remaining three
stored operators take the volume-add branch and are 252+3 T-states each.

The longer source paths are executable in the same way. Admission is 238
primitive T-states. Maximum release to panning is 426 primitive T-states plus
one program-bank wait, giving 429. Voice upload to `zKeyOnOff` is 528 primitive
T-states plus three program reads, giving 537. `zKeyOnOff` through note,
modulation, frequency preparation, and the first `zFMSendFreq` data write is
2,030 primitive T-states plus four program reads, giving 2,042. Completion
key-off through the restored panning data write is 1,078 primitive T-states.
These rows cite the shipped `fix_sndbugs=0` driver spans in the JSON; none is an
oracle-derived or arbitrary subtotal.

For the maximum-release-to-panning path specifically, the shipped trace has
three `CALL`s (`zGetFMInstrumentPointer`, `zSendFMInstrument`, and
`zWriteFMIorII` at lines 3379, 3382, and 1535), two taken conditional `JR`s
(the zero-updating-SFX branch to `zGetFMInstrumentOffset` and the FM5 port-II
branch at lines 1465 and 569), and three non-taken conditional `RET`s (the
nonzero voice-index test and the two `zWriteFMIorII` guards at lines 1473, 564,
and 566). Their contribution is `3*17 + 2*12 + 3*5 = 90` T-states. A previous
cost-equivalent `4/1/2` encoding also summed to 90 but did not describe the
shipped instruction path; the frozen source-row expectation now rejects that
substitution.

## Segment partition and checked result

The admission-preparation service is separate: key-off plus four SSG-EG clears
has relative writes `[0, 3570, 6720, 9870, 13020]`.

The audited first-attack service partitions as follows:

- `SFX_MAX_RELEASE`: indices 0-3, relative `[0, 3150, 6300, 9450]`.
- `FM_VOICE_UPLOAD`: indices 4-29, with a 6,435-cycle cross-segment advance;
  its final combined cycle is 107,325.
- `KEY_OFF`: index 30, with an 8,055-cycle cross-segment advance.
- `FREQUENCY_AND_KEY_ON`: indices 31-33, with advances
  `[30630, 2700, 2880]`.
- `COMPLETION_RESTORE`: an independently normalized segment with cumulative
  writes `[0, 16170, 19395, 23160, 26730, 30300, 33870, 37440, 41010,
  44580, 48150, 51720, 55290, 58860, 62430, 66000, 69570, 73140, 76710,
  80280, 83850, 87420, 90990, 96135, 99675, 103215, 107040]`.

The checked composite ends at 151,590 master cycles = 10,106 Z80 T-states.
The JSON test derives each individual advance, each cumulative segment vector,
the cross-segment advances, and the composite independently before comparing
the immutable production profile.

## Bank waits and DMA ruling

Genesis Plus GX `core/memz80.c:z80_request_68k_bus_access` first waits to
`dma_endCycles` when an applicable VDP DMA owns the bus, then applies its
documented average approximation of three Z80 T-states per 68k-bus access
(pinned GPGX commit `051d430d3d1b54625f9900c8f152d7f232e06daf`, lines
95-117). The calculation selects that GPGX parity dialect; it does not claim a
physical-console access always waits exactly three T-states.

The retained post-`fm_update` oracle contains twelve isolated 34-write FM5
groups. Every one of their 408 writes reports `dma_stall_count = 0`, and the
underlying bounded capture reports zero nonzero DMA markers. The independently
calculated uncontended composite equals every oracle group at all 34 relative
write positions. Therefore no captured DMA stall is hidden in a timing
constant. A simultaneous-DMA path remains outside this bounded profile.

## Immutability and game boundary

`Sonic3kYmServiceTimingProfile` freezes the audited port-II, four-operator,
banked first-attack variants. Carrier masks cover all sixteen stored-operator
layouts because `zSendTL` timing depends on those semantic bits. S1 and S2 keep
the canonical `YmServiceTimingProfile.none()`; no Z80 or 68k constants cross
game boundaries. This task only carries the profile through immutable config
copies and deliberately does not change runtime write publication or
scheduling.
