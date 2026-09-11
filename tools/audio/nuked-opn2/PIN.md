# Nuked-OPN2 reference pin

The engine's YM2612/YM3438 FM core (`com.openggf.audio.synth`) is a Java port of
Nuked-OPN2 at exactly the revision recorded here. The port derives from this
source and nothing else; `fetch-source.sh --output <dir>` reproduces the pinned
tree and verifies every file hash below before publishing it.

| Field | Value |
|---|---|
| Upstream | https://github.com/nukeykt/Nuked-OPN2 |
| Default branch | `master` |
| Commit | `335747d78cb0abbc3b55b004e62dad9763140115` |
| Tree | `6637a500d1da3b08cbc0cec1532ab305197b8978` |
| Upstream commit date | 2023-08-11 00:41:43 +0900 ("Update ym3438.c") |
| Source version strings | `ym3438.c` 1.0.12, `ym3438.h` 1.0.9 |
| Date fetched | 2026-08-29 |
| Licence | GNU Lesser General Public License v2.1 or (at your option) any later version (`LGPL-2.1-or-later`) |
| Copyright | Copyright (C) 2017-2022 Alexey Khokholov (Nuke.YKT) |

## File list (sha256)

| File | sha256 |
|---|---|
| `ym3438.c` | `8fa385546f0f2d1c975d097002af00cd729ae2ae097c068e9c883ce08ddf3a76` |
| `ym3438.h` | `8e60e35f77049d0e600ad1a47bfc3dfc8b832483e614104473a83c1f33cd7189` |
| `LICENSE` | `20c17d8b8c48a600800dfd14f95d5cb9ff47066a9641ddeab48dc54aec96e331` |
| `README.md` | `21634adf91e4e2a483adfb10084ce06f105225265cf869fe22fd4ab3dcd77bf1` |
| `ym3438.svg` | `c4ec292d3857048ecef2fb75e869269e753aa1d8f358ce34855b20a4d1e1a53c` |

The tree contains only these five files.

## Licence text

`LICENSE` is the verbatim GNU Lesser General Public License, Version 2.1,
February 1999 (Copyright (C) 1991, 1999 Free Software Foundation, Inc.). The
per-file headers in `ym3438.c` and `ym3438.h` grant the "either version 2.1 of
the License, or (at your option) any later version" option, so the effective
identifier is `LGPL-2.1-or-later`. A Java derivative must keep the copyright
notice and the licence grant in its file header and must remain distributable
under LGPL-2.1-or-later.

## Public API of the pinned revision

```
void   OPN2_Reset(ym3438_t *chip);
void   OPN2_SetChipType(Bit32u type);       /* global, not per chip */
void   OPN2_Clock(ym3438_t *chip, Bit16s *buffer);   /* one internal cycle; buffer[0]=MOL, buffer[1]=MOR */
void   OPN2_Write(ym3438_t *chip, Bit32u port, Bit8u data);
void   OPN2_SetTestPin(ym3438_t *chip, Bit32u value);
Bit32u OPN2_ReadTestPin(ym3438_t *chip);
Bit32u OPN2_ReadIRQPin(ym3438_t *chip);
Bit8u  OPN2_Read(ym3438_t *chip, Bit32u port);
```

Chip-type flags: `ym3438_mode_ym2612 = 0x01` (YM2612 DAC/output and status
behaviour), `ym3438_mode_readmode = 0x02` (status readable on any port). The
upstream repository has no `OPN2_GenerateResampled`, `OPN2_WriteBuffered`,
write-buffer queue or ladder-effect switch; those exist only in downstream
forks and are deliberately not part of this pin.
