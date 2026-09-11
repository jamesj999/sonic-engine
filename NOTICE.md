# NOTICE

OpenGGF is distributed under the GNU General Public License, version 3
(`LICENSE`). This file lists the third-party components that carry their own
licence terms and notices, which must accompany every copy of OpenGGF,
including the executable JAR (`META-INF/openggf/`) and the release archives.

## Nuked OPN2

The FM sound core in `src/main/java/com/openggf/audio/synth/nuked/` is a Java
port of Nuked OPN2, a cycle-accurate Yamaha YM3438 / YM2612 emulator.

- Copyright (C) 2017-2022 Alexey Khokholov (Nuke.YKT)
- Upstream: https://github.com/nukeykt/Nuked-OPN2, commit
  `335747d78cb0abbc3b55b004e62dad9763140115` (`ym3438.c` 1.0.12,
  `ym3438.h` 1.0.9), pinned and hash-verified in
  `tools/audio/nuked-opn2/PIN.md`
- Licence: GNU Lesser General Public License, version 2.1 or (at your option)
  any later version (`LGPL-2.1-or-later`); the licence text is in
  `LICENSES/LGPL-2.1.txt`
- Modified 2026-08 by the OpenGGF contributors: translated from C to Java;
  chip type made per instance; state copy helpers and value equality added.
  Each modified file records this in its header (LGPL-2.1 section 2(b)).

The ported files remain licensed under LGPL-2.1-or-later and may be extracted
and reused under that licence on their own. OpenGGF as a combined work is
conveyed under GPL-3 by applying the upgrade option of LGPL-2.1 section 3 to
the combination; the LGPL headers and the upstream copyright notice are
retained in every ported file.

Full contributor and third-party acknowledgements are in `CREDITS.md`.
