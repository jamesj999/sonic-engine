/*
 * Copyright (C) 2017-2022 Alexey Khokholov (Nuke.YKT)
 * Java port Copyright (C) 2026 the OpenGGF contributors
 * Modified 2026-08 by the OpenGGF contributors: translated from C to Java;
 * chip type made per instance; state copy helpers and value equality added.
 *
 * This file is part of Nuked OPN2 (Java port).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

/**
 * Java port of Nuked OPN2, a cycle-accurate Yamaha YM3438 / YM2612 emulator.
 *
 * <p>The port derives from exactly one source: Nuked-OPN2 upstream commit
 * {@code 335747d78cb0abbc3b55b004e62dad9763140115} ({@code ym3438.c} 1.0.12,
 * {@code ym3438.h} 1.0.9), pinned and hash-verified by
 * {@code tools/audio/nuked-opn2/PIN.md}. Every ported function and table
 * carries a {@code // ym3438.c:NNN} citation to that revision. The package is
 * a pure chip model: it has no dependency on the rest of the engine, no
 * static mutable state, and no notion of a host sample rate or write queue.
 * The upstream revision ships no resampler, write buffer or ladder-effect
 * switch, so none is modelled here; whatever glue the engine needs to drive
 * {@link com.openggf.audio.synth.nuked.NukedOpn2#clock(int[])} at a host rate
 * belongs to the adapter in {@code com.openggf.audio.synth}, not to this
 * package.
 *
 * <h2>NOTICE: licensing</h2>
 * OpenGGF as a whole is distributed under the GNU General Public License,
 * version 3 (see {@code LICENSE} at the repository root). The files in this
 * package are a derivative work of Nuked OPN2, Copyright (C) 2017-2022 Alexey
 * Khokholov (Nuke.YKT), and remain licensed under the GNU Lesser General
 * Public License, version 2.1 or (at your option) any later version
 * ({@code LGPL-2.1-or-later}); the LGPL text is provided at
 * {@code LICENSES/LGPL-2.1.txt}. The files stay under that licence and may be
 * extracted and reused under it on their own; the per-file LGPL headers and
 * the upstream copyright notice are retained for that purpose. The combined
 * work — OpenGGF with this package linked in — is conveyed under GPL-3 by
 * applying the upgrade option of LGPL-2.1 section 3 to the combination, not
 * by relicensing these files. Modifications relative to upstream are recorded
 * in each file header (LGPL-2.1 section 2(b)): translation into Java, the
 * per-instance chip type (upstream keeps a file-scope global), and the
 * {@code NukedOpn2State} copy helpers and value equality. The root
 * {@code NOTICE.md} carries the same notice for distributions.
 */
package com.openggf.audio.synth.nuked;
