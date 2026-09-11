# S3K SMPS meta-command reachability

## Finding

The S&K-loader-supported streams and both native SFX banks in the locked-on
Sonic 3&Knuckles ROM do not reach the three meta commands that were previously
described as discarded semantics in
`Sonic3kCoordFlagHandler.handleMetaCommand(...)`:

| Meta byte sequence | Native name | Shipped-ROM reachability | Engine disposition |
|---|---|---|---|
| `FF 01 <id>` | `SND_CMD` | Not reached | Consume the one operand to preserve stream alignment; do not claim native dispatch. |
| `FF 02 <flag>` | `MUS_PAUSE` | Not reached | Consume the one operand to preserve stream alignment; do not claim all-track halt/resume. |
| `FF 03 <ptr-lo> <ptr-hi> <count>` | `COPY_MEM` | Not reached | Consume the three operands to preserve stream alignment; do not claim shared-Z80-memory copying. |

This finding now includes the separate native SFX tables in both halves of the
locked-on ROM. The S&K table and bank cover IDs `33-DF` (173 entries), and the
S3-native table and bank cover the same 173 IDs. Every declared track header
and every track root in both banks is resolved by the fixed-point traversal;
no native graph leaves an unresolved root or frontier. The target commands
remain unreachable, so no native `SND_CMD`, `MUS_PAUSE`, or `COPY_MEM`
implementation is warranted by this ROM.

The handler now says this explicitly. It does not add behavior for a command
that has no caller in the supported ROM. A custom or modified stream that
reaches one of these commands remains an identified parity gap, rather than a
silently simulated command.

## Native contract

The S3K Z80 driver defines the extra-coordinate table at
`docs/skdisasm/Sound/Z80 Sound Driver.asm:2980-2990`. The dispatch at
`:3842-3853` consumes the `FF` prefix and subcommand before entering the
handler. The native routines define the following contracts:

* `SND_CMD` (`:3865-3878`) calls `zPlaySoundByIndex` with one ID operand. The
  native source documents S&K's `DC` CreditsK special case and DD–DF aliases.
  The ROM proof below verifies the S&K dispatch and the S3-native dispatch:
  S&K treats `DC` as CreditsK music and `DD-DF` as SFX, while the S3 driver
  treats `DC-DF` as SFX.
* `MUS_PAUSE` (`:3880-3919`) stores the operand as `zHaltFlag`. Nonzero clears
  the playing bit and keys off every track, then silences PSG; zero restores
  the playing bit for every track.
* `COPY_MEM` (`:3921-3944`) reads a little-endian Z80 source pointer and an
  eight-bit count, then copies from shared Z80 RAM into the current track's
  stream immediately after the three operands. Playback resumes after the
  copied bytes. The driver notes that this only works when the song/SFX was
  copied into Z80 RAM.

The local `docs/SMPS-rips/SMPSPlay` checkout contains configuration/install
placeholders rather than the SMPSPlay source tree. The command spellings and
operand contracts above are therefore pinned to the checked-in S3K Z80 source
of truth; the SMPSPlay/libvgm references remain useful for future driver-level
implementation if a supported ROM or custom stream reaches one of them.

## ROM inventory

The evidence uses the locked-on S3&K ROM with SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6` (CRC32 `63522553`). It enumerates
the actual loader tables rather than assuming a filename or a subset of songs:

| Loader table | IDs inspected | Loaded streams | `FF 01/02/03` reached |
|---|---:|---:|---:|
| S&K music | `01-33` | 51 | 0 |
| S3 music | `01-32` | 50 | 0 |
| S&K-loader SFX | `33-DB` | 169 | 0 |
| S&K native SFX table/bank | `33-DF` | 173 | 0 |
| S3-native SFX table/bank | `33-DF` | 173 | 0 |

Music blobs also contain zero raw `FF 01`, `FF 02`, or `FF 03` pairs. A raw
scan is not used to classify SFX: `Sonic3kSmpsLoader` returns a bank-backed
buffer containing voices and unrelated streams, so raw SFX bytes would produce
false positives. Instead, the test starts at every resolved loaded track entry
and computes a fixed point over note/duration bytes plus jump, call, counted
loop, conditional loop-exit, continuous, and terminal edges across each
loader-provided full Z80 bank; shared-bank targets are traversed inside the
same address space. Every edge must close with no unexplored frontier (or a cycle) before recording
live FF subcommands. The 51 S&K music, 50 S3 music, and 169 S&K-loader SFX
graphs observe live `FF 00`/`FF 07` commands but no `01/02/03` route. The
S3-native SFX table (including differing IDs 9B/AD) and DC–DF alias targets
are covered by the same ROM-offset-verified parser and strict full-bank
traversal.

### Native SFX table and alias proof

The test reads the S&K additional-data payload from compressed ROM offset
`0x0F7760`, decompresses it to Z80 load address `0x1300`, and reads the SFX
pointer table at Z80 address `0x167E` (additional-data offset `0x037E`). It
reads the S&K SFX bank from ROM `0x0F8000`. The S3 driver is raw in the second
locked-on half: its driver starts at combined ROM `0x2E6000`, its native SFX
pointer table is at combined ROM `0x2E767E` (the same Z80 offset `0x167E`), and
its native bank starts at combined ROM `0x2F8000`. Both tables contain exactly
173 little-endian pointers for `0x33-0xDF`.

The table targets for the source-differing entries are `0xF0AF` (bank offset
`0x70AF`) for `0x9B` and `0xF49C` (bank offset `0x749C`) for `0xAD` in both
halves. The payloads are not interchangeable: S&K `0x9B` declares two tracks
while S3 declares three, and the `0xAD` channel header is `0x04` in S&K versus
`0x02` in S3. The four native alias IDs `0xDC-0xDF` all target `0xFD94`
(bank offset `0x7D94`), the `0xDB` target, in both banks.

Dispatch is asserted from the ROM driver's type-check bytes, not inferred from
the alias pointer values. The S&K driver has the `CP $DC`/CreditsK branch
before the music/SFX boundaries (`$33` and `$E0`); the S3 driver has no such
branch. Consequently `DC` is classified as CreditsK music only for S&K,
`DD-DF` are S&K SFX, and `DC-DF` are S3 SFX. The checked-in Z80 source is
`docs/skdisasm/Sound/Z80 Sound Driver.asm` (`zPlaySoundByIndex` and
`z80_SFXPointers`); the ROM bytes are the assertion authority.

For every one of the 346 native entries (173 per bank), the test validates the
header and declared track count, resolves every track root, and runs a strict
full-bank control-flow fixed point. Calls are not treated as closed external
edges in this mode. Every jump, call, counted loop, conditional loop-exit,
continuous edge, terminal, and cycle closes with an empty frontier. No native
track reaches `FF 01`, `FF 02`, or `FF 03`; the existing loader-scoped set still
observes live `FF 00` and `FF 07` commands.

`EB` is the separate native conditional loop-exit command; it is included in
the graph edges and is not one of the three FF meta-command gaps. Its ROM layout
is an index byte at `pos+1`, a little-endian pointer at `pos+2`, and a
fall-through at `pos+4`; the mutation-sensitive companion tests protect all
five pointer/control-flow opcodes (`F6`, `F7`, `F8`, `EB`, `FC`). Strict graph
inventory treats exact bank-end falloff, unknown command bytes, unknown `FF`
subcommands, malformed roots, and out-of-bank pointers as frontiers. `FF 07`
is explicitly modeled as a zero-operand subcommand.

The characterization is maintained by
`TestSonic3kSmpsMetaCommandReachability`, run with:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.game.sonic3k.audio.smps.TestSonic3kSmpsMetaCommandReachability \
  -Ds3k.rom.path="/path/to/Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

The inventory is a static ROM-data proof and does not invoke the audio singleton
or use trace data to hydrate gameplay/audio state. The local
`docs/SMPS-rips/SMPSPlay` checkout still contains configuration/install
placeholders rather than source; the checked-in Z80 driver and the two ROM
halves therefore remain the source of truth.
