# Credits

## Project

Created by **jamesj999** and **Raiscan**.

---

This project uses documentation, tools, and reference implementations from many talented members of the Sonic hacking and emulation communities.

**It would not have been possible without their hard work; we truly stand on the shoulders of giants.**

## Audio & Sound

| Contributor          | Contribution                                                                                                                    |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------|
| **ValleyBell**       | SMPSPlay - SMPS music playback tool and libvgm integration <br/> <br/> https://github.com/ValleyBell/SMPSPlay                   |
| **Tweaker**          | Hacking CulT music hacking guide <br/> <br/> https://www.hacking-cult.org/?r/4/80                                               |
| **drx**              | Hacking CulT <br/> <br/> https://www.hacking-cult.org                                                                           |
| **jsgroth**          | "Emulating the YM2612" blog series - FM synthesis reference <br/> <br/> https://jsgroth.dev/blog/posts/emulating-ym2612-part-1/ |
| **Maxim**            | SN76489 PSG documentation (SMS Power!) <br/> <br/> https://www.smspower.org/Development/SN76489                                                                             |
| **Stephan Dittrich** | Gens YM2612 Java port (`YM2612.java.example`), an earlier development reference                                                                                     |
| **Xeeynamo**         | SMPSPlay contributions (wave output, channel muting)                                                                            |
| **Eke-Eke**          | Genesis Plus GX - historical FM/PSG implementations used by OpenGGF, since replaced; reference emulation through BizHawk, and modifications to the blip_buf implementation used by `BlipDeltaBuffer` <br/><br/> https://github.com/ekeeke/Genesis-Plus-GX |
| **Jarek Burczynski, Tatsuyuki Satoh (MAME)** | Original `fm.c` YM2612 implementation underlying the Genesis Plus GX / libvgm core formerly ported in OpenGGF |
| **Nemesis, Sauraen** | YM2612 hardware tests and die-shot analysis; Nemesis's published envelope and phase research also informs the fast FM core <br/><br/> http://gendev.spritesmind.net/forum/viewtopic.php?t=386 |
| **Alexey Khokholov (Nuke.YKT)** | Nuked OPN2 - cycle-accurate YM3438/YM2612 emulator from the die shot (LGPL 2.1+), ported as `NukedOpn2` (see *Emulation cores* below) <br/><br/> https://github.com/nukeykt/Nuked-OPN2 |
| **Silicon Pr0n (digshadow); Matthew Gambrell, Olli Niemitalo** | YM3438 decap and die shot, and the OPL2 ROM dumps, credited in the Nuked OPN2 header |
| **Shay Green (blargg)** | blip_buf band-limited synthesis library (LGPL 2.1+), the model for `BlipDeltaBuffer` <br/><br/> http://www.slack.net/~ant/ |
| **MAME Team**        | Sound emulation cores used by SMPSPlay                                                                                          |
| **libvgm**           | Audio output and emulation libraries used by SMPSPlay; its GPGX-derived `ym2612.c` was the source of OpenGGF's former FM port <br/><br/> https://github.com/ValleyBell/libvgm |
| **flamewing**        | S3K Z80 sound driver documentation and bugfixes                                                                                 |
| **clownacy**         | SMPS sound driver disassembly work across S1, S2, and S3K                                                                       |
| **MarkeyJester**     | Original S3K Z80 sound driver disassembly                                                                                       |
| **Linncaki**         | S3K sound driver routines, pointers, and data identification                                                                    |
| **Xenowhirl**        | Sonic 2 Z80 sound driver disassembly                                                                                            |

### Emulation cores

OpenGGF's current audio implementations under
`src/main/java/com/openggf/audio/synth/` include the accurate Nuked OPN2 FM
port, the fast register-level FM implementation, and the PSG implementation
written from public SN76489 documentation. The table records their origins
and the supporting resamplers, based on repository history and source headers.

| Engine class | Origin | Notes |
|--------------|--------|-------|
| `Ym2612Chip` | Facade over `nuked.NukedOpn2` (below); no other emulator source consulted. | Engine glue only: write queue and bus pacing, per-frame pin sum and output scale, internal-rate resampling, SMPS voice unpack, Z80-driver DAC streaming, output-stage mutes and the rewind snapshot, all written from `docs/architecture/designs/2026-08-29-nuked-opn2-port-contract.md`. |
| `nuked.NukedOpn2` | `ym3438.c` / `ym3438.h` from Nuked OPN2 (Copyright 2017-2022 Alexey Khokholov), upstream commit `335747d7`, pinned in `tools/audio/nuked-opn2/PIN.md`. | A function-for-function port with `ym3438.c` line citations; every table and per-cycle stage is upstream's. LGPL 2.1 or later (`LICENSES/LGPL-2.1.txt`); the package NOTICE in `package-info.java` records how it combines with the GPL-3 engine. |
| `FastYm2612Chip` / `fast.FastYm2612Dsp` | OpenGGF's register-level FM implementation, developed from public hardware documentation and the [fast FM design](docs/architecture/designs/2026-09-06-fast-fm-core-design.md). | Sources include Yamaha manuals and Nemesis's envelope/phase research. The initial author used prose summaries of ymfm (Aaron Giles) and fmgen (cisc) techniques in the design; later timing corrections used public-facade PCM probes and published pipeline research. See the [validation record](docs/architecture/validation/audio/2026-09-06-fast-fm-release.md) for the source-exposure boundary. |
| `PsgChip` | None. Clean-room implementation written from the public SN76489 specification in `docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md` (Maxim's SMS Power! notes, the TI datasheet, the Sega hardware manual). | No emulator source was consulted: not the previous Genesis Plus GX-derived body of this class, not `psg.c`, libvgm, MAME or BizHawk. Output is band-limited through `BlipDeltaBuffer` (below). |
| `BlipDeltaBuffer` | `blip_buf.c` by Shay Green, as modified for Genesis Plus GX. | Library is LGPL 2.1 or later. |
| `BlipResampler` | Windowed-sinc resampler written for OpenGGF, "based on the same principles as" blip_buf. | Not a port. |

Earlier versions used Genesis Plus GX-derived FM and PSG implementations.
The former `Ym2612Chip` port, introduced on 2025-12-10 in `eae2da2ca`, descended
from Jarek Burczynski and Tatsuyuki Satoh's MAME `fm.c` through Genesis Plus GX
and libvgm. Those non-commercial chip implementations have been replaced;
the contributor acknowledgements above preserve that history. Genesis Plus GX
remains part of the external reference toolchain, and its modifications to
Shay Green's LGPL blip_buf remain credited separately above.

## Libraries and tools

### Runtime dependencies

| Library | Licence | Use |
|---------|---------|-----|
| **LWJGL 3** (core, OpenGL, GLFW, OpenAL, stb) <br/> https://www.lwjgl.org | BSD 3-Clause | Window, input, rendering, and audio output |
| **JOML** <br/> https://github.com/JOML-CI/JOML | MIT | Vector and matrix maths for the renderer |
| **Jackson** (`jackson-databind`, `jackson-dataformat-yaml`) <br/> https://github.com/FasterXML/jackson | Apache License 2.0 | `config.yaml`, trace metadata, and manifest parsing |
| **Apache Commons Lang** (`commons-lang3`) <br/> https://commons.apache.org/proper/commons-lang/ | Apache License 2.0 | General utilities |

### Test-time dependencies

| Library | Licence |
|---------|---------|
| **JUnit 5 (Jupiter)** <br/> https://junit.org/junit5/ | Eclipse Public License 2.0 |
| **Mockito** <br/> https://site.mockito.org | MIT |
| **ArchUnit** <br/> https://www.archunit.org | Apache License 2.0 |
| **SLF4J** (`slf4j-nop`) <br/> https://www.slf4j.org | MIT |

### Trace toolchain

| Project | Contribution |
|---------|--------------|
| **BizHawk** <br/> https://tasvideos.org/BizHawk | Emulator and BK2 movie format behind the optional pinned TraceChaser recorder in `tools/tracechaser/bizhawk-headless/`; its Genesis Plus GX core produces the reference physics, aux-state, and audio traces the `*TraceReplay` suites compare against. OpenGGF does not distribute BizHawk; TraceChaser verifies the official 2.11 release. |
| **TASVideos** <br/> https://tasvideos.org | Hosts BizHawk and the movie-format documentation the recorder relies on |
| **Genesis Plus GX** (Eke-Eke) | Emulation core inside BizHawk used for reference trace capture |

## Physics & Collision

| Contributor       | Contribution                                                                                                                         |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| **Sonic Retro**   | Sonic Physics Guide (SPG) - comprehensive physics documentation <br/> <br/> https://info.sonicretro.org/Sonic_Physics_Guide          |
| **LapperDev**     | SPGSonic2Overlay.Lua - sensors, hitboxes, and solid object visualization <br/> <br/> https://info.sonicretro.org/SPG:Overlay_Scripts |
| **MercurySilver** | SPGSonic2Overlay.Lua - terrain and misc additions                                                                                    |

## Disassembly & ROM Research

### Sonic 1 Disassembly (s1disasm)

https://github.com/sonicretro/s1disasm

| Contributor        | Contribution                                          |
|--------------------|-------------------------------------------------------|
| **Hivebrain**      | Created the original Sonic 1 disassembly              |
| **MainMemory**     | Major contributor, ongoing maintenance                 |
| **clownacy**       | Disassembly improvements                               |
| **flamewing**      | Disassembly contributions                              |
| **DevonArtmeier**  | Disassembly contributions                              |

### Sonic 2 Disassembly (s2disasm)

https://github.com/sonicretro/s2disasm

| Contributor        | Contribution                                                                        |
|--------------------|-------------------------------------------------------------------------------------|
| **Nemesis**        | Created original Sonic 2 disassembly (2004, SNASM68K), Nemesis compression research <br/><br/> https://info.sonicretro.org/SCHG:Nem%20s2 |
| **Xenowhirl**      | Ported to AS assembler, extensive annotation, Z80 sound driver disassembly (2007)   |
| **FraGag**         | Host/maintainer, constants/equates system, major refactoring                        |
| **shobiz**         | VDP command conversion, commenting, label cleanup                                   |
| **qiuu**           | RAM address equates, collision and level select commenting                           |
| **flamewing**      | Sound driver work, merged contributions                                             |
| **clownacy**       | Disassembly improvements, decompression tools, documentation                        |
| **MainMemory**     | Disassembly contributions                                                           |
| **Marzo (marzojr)**| Disassembly contributions                                                           |

### Sonic 3 & Knuckles Disassembly (skdisasm)

https://github.com/sonicretro/skdisasm

| Contributor        | Contribution                                                                        |
|--------------------|-------------------------------------------------------------------------------------|
| **MainMemory**     | Primary maintainer, split disassembly                                               |
| **flamewing**      | Thorough Z80 sound driver documentation and bugfixes                                |
| **MarkeyJester**   | Original Z80 sound driver disassembly                                               |
| **Linncaki**       | Sound driver routines, pointers, and data identification                            |
| **clownacy**       | Disassembly contributions                                                           |
| **Natsumi**        | Disassembly contributions                                                           |

### General ROM & Hardware Research

| Contributor        | Contribution                                                                         |
|--------------------|--------------------------------------------------------------------------------------|
| **Sonic Retro**    | SCHG documentation and community disassembly hosting <br/><br/> https://info.sonicretro.org |
| **Brett Kosinski** | Kosinski compression research <br/><br/> https://segaretro.org/Kosinski_compression  |

## Compression & Tools

| Contributor   | Contribution                                                                                    |
|---------------|-------------------------------------------------------------------------------------------------|
| **clownacy**  | Decompression tools                                                                             |
| **flamewing** | s2ssedit (Sonic 2 Special Stage Editor) - used as reference                                     |

## Communities

- **Sonic Retro** - The invaluable wiki, forums, and community resources
- **SMS Power!** - Sega hardware documentation
- **Hacking CulT** - Pioneering Sonic ROM hacking research

## AI-Assisted Development & Trace Testing

Special thanks to **[Tibo Sottiaux](https://x.com/thsottiaux) (`@thsottiaux`)** for announcing **17 Codex and ChatGPT Work usage-limit resets—14 direct and 3 banked—between 27 June and 29 July 2026**. Those resets enabled JamesJ999 and Raiscan to carry out agent-intensive OpenGGF development and trace testing at a scale that would not have been practical under conventional usage limits.

For that inclusive period, `ccusage` reports the following usage from **Raiscan's Codex activity alone**, as captured at 09:03 BST on 29 July:

| Usage category | Tokens |
|----------------|-------:|
| Cached input | 40,327,503,744 |
| Uncached input | 1,066,382,464 |
| Output (including reasoning) | 77,114,087 |
| **Total processed** | **41,471,000,295** |

Using the corrected replay deduplication and derived-token accounting in `ccusage` 20.0.19, that activity represents an estimated **$29,338.82 in API-equivalent inference value**. This is an estimate, not an invoice or an amount paid. It does not include JamesJ999's separate agent usage.

---

*If you contributed to resources used in this project and are not listed, please open an issue or PR!*
