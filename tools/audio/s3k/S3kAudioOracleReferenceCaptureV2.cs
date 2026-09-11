using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace OpenGGF.BizHawk.Headless
{
    /// <summary>
    /// S3K sound-driver oracle reference capture, v2.
    ///
    /// v1 (<see cref="S3kAudioOracleReferenceCapture"/>) sampled the driver's
    /// variable+track RAM with an out-of-band <c>ReadZ80RamByte</c> walk after
    /// <c>host.Advance()</c> returned, i.e. at the *video frame* boundary. Its
    /// metadata declared <c>sampling: "post_invocation"</c>, but that claim only
    /// holds on frames where the Z80 happens to be idle when the frame ends. On
    /// a music-load frame the driver's work overruns the frame, so the walk
    /// observes a strict prefix of zUpdateMusic's own track-iteration order,
    /// truncated at a varying point. That is what stalled the S3K oracle at
    /// tick 138 (movie frame 252, the title-music load).
    ///
    /// v2 removes the out-of-band read entirely. The driver RAM is captured by
    /// the observer core itself, as a completion snapshot on the driver's own
    /// service boundaries, so the bytes are sampled by the emulated CPU at the
    /// exact instruction where the driver returns from its vertical-interrupt
    /// service and are delivered inline, in order, in the event stream.
    ///
    /// Boundary: Z80 PC 0084h, the <c>ret</c> that ends zVInt
    /// (skdisasm "Sound/Z80 Sound Driver.asm":520; the disassembly's own
    /// <c>;loc_85</c> comment at line 522 pins the address). This is the
    /// outermost "driver returned from its V-int service" point:
    ///
    ///   - zVInt is the RST 38h target at 0038h (line 469-470). It calls
    ///     zUpdateEverything at 0042h; that call returns to 0045h (line 482).
    ///   - 0045h is *not* usable as a once-per-interrupt boundary. On the PAL
    ///     double-update path zVInt jumps back to .doupdate at 003Dh and runs
    ///     zUpdateEverything a second time within one interrupt (lines 485-495),
    ///     so 0045h is reached twice. And while paused, zPauseUnpause pops its
    ///     own return address (lines 2232-2242) so zUpdateSFXTracks and
    ///     zUpdateMusic never execute, yet 0045h is still reached.
    ///   - 0084h is reached exactly once per zVInt, unconditionally.
    ///
    /// Within the captured window the two boundaries differ by exactly one byte,
    /// zPalDblUpdCounter at 1C04h (written at 0053h/005Ah), plus unk_1C17 at
    /// 1C17h on the PAL re-entry path. The driver assembles as SonicDriverVer 4
    /// (sonic3k.asm:27, the S&amp;K half, which is the live Z80 driver in the
    /// locked-on ROM), so the SonicDriverVer==3 PAL tempo bug and the
    /// <c>ld (unk_1C21), a</c> write are not present. fix_sndbugs = 0
    /// ("Sound/Z80 Sound Driver.asm":16), so the shipped-ROM arms are assembled
    /// throughout.
    ///
    /// Tick semantics: one tick is one *completed driver service*, not one video
    /// frame. Two service kinds qualify, and each row names which it was in its
    /// <c>service</c> field: the one-shot <c>DriverInit</c> that runs before the
    /// first vertical interrupt (kind 6, completing where the first zVInt
    /// begins), and every <c>zVInt</c> return (kind 3). Giving the driver init
    /// its own row keeps the boot write burst grouped exactly as the v1
    /// consumer's frame-to-service projection grouped it; without it the boot
    /// burst and the first vertical interrupt would share one row.
    ///
    /// When the driver overruns a frame the tick belongs to the service that
    /// completed, not to the frame boundary that interrupted it. A frame may
    /// therefore carry zero ticks, and on the PAL double-update path it still
    /// carries exactly one, because 0084h is reached once per interrupt however
    /// many times zUpdateEverything ran inside it. This is the same model the S2
    /// oracle already uses, where one tick is one completed zUpdateMusic service
    /// recovered from the observer's service stream.
    ///
    /// Writes are partitioned by the same service-completion boundary rather
    /// than by frame boundary: a tick carries every YM/PSG write emitted since
    /// the previous tick's boundary, in stream order. Every write the movie
    /// produces therefore still appears exactly once and in the same relative
    /// order as v1 — only the grouping boundary moves. Writes emitted after the
    /// final completed service are reported as a residual count in the terminal
    /// row rather than silently dropped.
    ///
    /// Environment:
    ///   BIZHAWK_HOME                observer-core BizHawk home (assembled by
    ///                              tools/audio/run_s3k_audio_oracle_reference_v2.sh)
    ///   S3K_ROM_PATH                locked-on S3K ROM
    ///   OGGF_S3K_ORACLE_MOVIE       BK2 movie path
    ///   OGGF_S3K_ORACLE_OUTPUT      output JSONL path (must not exist)
    ///   OGGF_S3K_ORACLE_FRAMES      movie frames to replay (default 5400)
    ///   OGGF_S3K_ORACLE_MANIFEST    gpgx-audio-service-manifest-s3k-oracle-v2.json
    /// </summary>
    internal static class S3kAudioOracleReferenceCaptureV2
    {
        private const string Schema = "openggf.s3k_audio_oracle_reference.v2";
        private const string RomSha1 = "cfbf98c36c776677290a872547ac47c53d2761d6";
        private const int RamStart = 0x1C00;   // zDataStart (fix_sndbugs = 0)
        private const int RamEnd = 0x1FA0;     // zTracksSaveEnd = z80_stack_end
        private const int RamLength = RamEnd - RamStart;
        private const int MailboxMusic = 0x1C0A;
        private const int MailboxSfx0 = 0x1C0B;
        private const int MailboxSfx1 = 0x1C0C;

        // Observer event kinds (native/gpgx-audio-observer/0001-*.patch:392-402).
        private const int EventServiceEnd = 2;
        private const int EventFmWrite = 3;
        private const int EventPsgWrite = 4;
        private const int EventSnapshotBegin = 5;
        private const int EventSnapshotChunk = 6;
        private const int EventSnapshotEnd = 7;

        // Manifest identities (fixtures/gpgx-audio-service-manifest-s3k-oracle-v2.json).
        private const int VIntServiceKindId = 3;       // kind 3 "VInt"
        private const int DriverInitServiceKindId = 6;  // kind 6 "DriverInit"
        private const int DriverRamRangeId = 3;         // Z80_RAM [1C00h, 1FA0h)

        private static int Main()
        {
            try
            {
                Run();
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("s3k-audio-oracle-capture-v2: " + error.Message);
                Console.Error.WriteLine(error.StackTrace);
                return 1;
            }
        }

        private static void Run()
        {
            string rom = RequiredEnvironment("S3K_ROM_PATH");
            string moviePath = RequiredEnvironment("OGGF_S3K_ORACLE_MOVIE");
            string output = RequiredEnvironment("OGGF_S3K_ORACLE_OUTPUT");
            string manifest = RequiredEnvironment("OGGF_S3K_ORACLE_MANIFEST");
            string bizhawkHome = RequiredEnvironment("BIZHAWK_HOME");
            int frames = int.Parse(
                Environment.GetEnvironmentVariable("OGGF_S3K_ORACLE_FRAMES") ?? "5400");
            if (frames <= 0) throw new InvalidDataException("Frame count must be positive.");
            if (File.Exists(output) || Directory.Exists(output))
                throw new IOException("Oracle output already exists: " + output);

            string romSha1 = HashFileSha1(rom);
            if (romSha1 != RomSha1)
                throw new InvalidDataException(
                    "S3K oracle capture requires the pinned locked-on ROM; got SHA-1 " + romSha1);
            string movieSha256 = HashFileSha256(moviePath);
            string manifestSha256 = HashFileSha256(manifest);
            string coreSha256 = HashFileSha256(
                Path.Combine(bizhawkHome, "dll", "gpgx.wbx.zst"));

            Bk2Movie movie = Bk2Reader.Read(moviePath);
            if (frames > movie.FrameCount)
                throw new InvalidDataException("Movie has only " + movie.FrameCount
                    + " frames; " + frames + " requested.");

            using (var host = GpgxHost.Open(rom, movie.SyncSettings))
            using (var writer = new StreamWriter(output, false, new UTF8Encoding(false)))
            using (SHA256 body = SHA256.Create())
            {
                IGpgxAudioTraceApi trace = host.CreateAudioTraceApi();
                GpgxAudioServiceManifest.Load(manifest, "s3k", trace);

                writer.Write(new JObject
                {
                    ["row"] = "metadata",
                    ["schema"] = Schema,
                    ["rom_sha1"] = romSha1,
                    ["rom_crc32"] = "63522553",
                    ["movie"] = new JObject
                    {
                        ["name"] = Path.GetFileName(moviePath),
                        ["sha256"] = movieSha256,
                        ["frame_count"] = movie.FrameCount
                    },
                    ["frames"] = frames,
                    ["ram_window"] = new JObject
                    {
                        ["start"] = RamStart,
                        ["exclusive_end"] = RamEnd
                    },
                    // v1 declared "post_invocation" while sampling at the frame
                    // boundary. v2 names the instruction it actually samples at.
                    ["sampling"] = "zvint_return_completion_snapshot",
                    ["sampling_pc"] = 0x84,
                    ["sampling_source"] =
                        "skdisasm Sound/Z80 Sound Driver.asm:520 (zVInt ret; ;loc_85 at :522)",
                    ["tick_semantics"] = "one_completed_zvint_service",
                    ["writes_partition"] = "service_completion_boundary",
                    ["mailbox_sampling"] = "frame_entry",
                    ["writes_source"] = "gpgx_audio_trace_kind3_4_latch_decode",
                    ["observer_core_zst_sha256"] = coreSha256,
                    ["manifest"] = Path.GetFileName(manifest),
                    ["manifest_sha256"] = manifestSha256,
                    ["retires"] = "s3k-aiz1-intro-reference-v1.jsonl.gz"
                }.ToString(Formatting.None) + "\n");

                byte port0Latch = 0;
                byte port1Latch = 0;
                ulong writeCount = 0;
                int ticks = 0;

                // Accumulated across the current service-completion boundary.
                var pendingWrites = new JArray();
                var ram = new byte[RamLength];
                var ramSeen = new bool[RamLength];
                bool ramCollecting = false;
                bool ramComplete = false;

                // Mailboxes read at the entry of the frame in which the service
                // completes. Retained from v1 so the per-tick request inputs keep
                // their v1 meaning; the pre-consumption request sidecar remains
                // the authority on what the driver actually consumed.
                int[] mailbox = { 0, 0, 0 };

                using (IEnumerator<Bk2Frame> rows = movie.OpenFrameStream().GetEnumerator())
                {
                    for (int frame = 0; frame < frames; frame++)
                    {
                        if (!rows.MoveNext())
                            throw new InvalidDataException("Movie ended at frame " + frame);
                        mailbox = new[]
                        {
                            (int)host.ReadZ80RamByte(MailboxMusic),
                            (int)host.ReadZ80RamByte(MailboxSfx0),
                            (int)host.ReadZ80RamByte(MailboxSfx1)
                        };
                        S1TraceCaptureRunner.ApplyFrame(rows.Current, host);
                        Require(trace.BeginFrame(), "BeginFrame", frame, trace);
                        host.Advance();
                        Require(trace.EndFrame(), "EndFrame", frame, trace);

                        uint count, overflow, copied;
                        Require(trace.EventCount(out count, out overflow),
                            "EventCount", frame, trace);
                        if (overflow != 0)
                            throw new InvalidDataException(
                                "Observer overflow at frame " + frame);
                        var events = count == 0 ? null
                            : new GpgxAudioTraceEvent[checked((int)count)];
                        Require(trace.Drain(events, count, out copied),
                            "Drain", frame, trace);
                        if (copied != count)
                            throw new InvalidDataException("Drain miscount at frame " + frame);

                        for (int i = 0; i < copied; i++)
                        {
                            GpgxAudioTraceEvent e = events[i];
                            switch (e.Kind)
                            {
                                case EventFmWrite:
                                    if (e.Subject > 3)
                                        throw new InvalidDataException(
                                            "FM subject " + e.Subject + " at frame " + frame);
                                    if (e.Subject == 0) { port0Latch = e.Value; break; }
                                    if (e.Subject == 2) { port1Latch = e.Value; break; }
                                    {
                                        int port = e.Subject < 2 ? 0 : 1;
                                        pendingWrites.Add(new JArray("ym", port,
                                            port == 0 ? port0Latch : port1Latch,
                                            e.Value, e.SourceCpu));
                                        writeCount++;
                                    }
                                    break;

                                case EventPsgWrite:
                                    pendingWrites.Add(new JArray("psg", e.Value, e.SourceCpu));
                                    writeCount++;
                                    break;

                                case EventSnapshotBegin:
                                    if (e.Subject != DriverRamRangeId) break;
                                    Array.Clear(ram, 0, ram.Length);
                                    Array.Clear(ramSeen, 0, ramSeen.Length);
                                    ramCollecting = true;
                                    ramComplete = false;
                                    break;

                                case EventSnapshotChunk:
                                    if (e.Subject != DriverRamRangeId || !ramCollecting) break;
                                    CopyPayload(e, ram, ramSeen, frame);
                                    break;

                                case EventSnapshotEnd:
                                    if (e.Subject != DriverRamRangeId || !ramCollecting) break;
                                    if (e.Offset != RamLength)
                                        throw new InvalidDataException(
                                            "Driver-RAM snapshot ended at offset " + e.Offset
                                            + " (expected " + RamLength + ") at frame " + frame);
                                    for (int b = 0; b < RamLength; b++)
                                        if (!ramSeen[b])
                                            throw new InvalidDataException(
                                                "Driver-RAM snapshot missed byte " + b
                                                + " at frame " + frame);
                                    ramCollecting = false;
                                    ramComplete = true;
                                    break;

                                case EventServiceEnd:
                                    // Two service kinds carry the driver-RAM
                                    // snapshot and so define a tick boundary: the
                                    // one-shot driver init that precedes the first
                                    // vertical interrupt, and every zVInt return.
                                    // Every other completion is ignored.
                                    if (e.ServiceKindId != VIntServiceKindId
                                        && e.ServiceKindId != DriverInitServiceKindId) break;
                                    if (!ramComplete)
                                        throw new InvalidDataException(
                                            "service kind " + e.ServiceKindId
                                            + " completed without a driver-RAM snapshot at frame "
                                            + frame + "; the manifest must attach range "
                                            + DriverRamRangeId
                                            + " to that kind and its completion hooks.");
                                    var tick = new JObject
                                    {
                                        ["row"] = "tick",
                                        ["tick"] = ticks,
                                        ["frame"] = frame,
                                        ["lag"] = host.IsLagged,
                                        ["service"] = e.ServiceKindId == DriverInitServiceKindId
                                            ? "driver_init" : "vint"
                                    };
                                    if (mailbox[0] != 0 || mailbox[1] != 0 || mailbox[2] != 0)
                                        tick["mailbox"] = new JArray(mailbox[0], mailbox[1], mailbox[2]);
                                    tick["writes"] = pendingWrites;
                                    tick["ram"] = Hex(ram);
                                    WriteBody(writer, body, tick);
                                    ticks++;
                                    pendingWrites = new JArray();
                                    ramComplete = false;
                                    break;
                            }
                        }
                    }
                }
                Require(trace.Disable(), "Disable", frames, trace);

                body.TransformFinalBlock(new byte[0], 0, 0);
                writer.Write(new JObject
                {
                    ["row"] = "terminal",
                    ["ticks"] = ticks,
                    ["frames"] = frames,
                    ["write_count"] = writeCount,
                    // Writes emitted after the last completed zVInt service. They
                    // belong to no tick and are reported rather than dropped.
                    ["residual_write_count"] = pendingWrites.Count,
                    ["body_sha256"] = Hex(body.Hash)
                }.ToString(Formatting.None) + "\n");

                Console.WriteLine("s3k-audio-oracle-capture-v2: " + ticks + " ticks over "
                    + frames + " frames -> " + output);
            }
        }

        /// <summary>
        /// Copies one snapshot chunk's payload. The native observer packs up to
        /// eight bytes per chunk into the event's payload field
        /// (0001-buffer-z80-audio-events.patch, emit_snapshot_ranges).
        /// </summary>
        private static void CopyPayload(GpgxAudioTraceEvent e, byte[] ram, bool[] seen, int frame)
        {
            if (e.PayloadLength > 8)
                throw new InvalidDataException("Snapshot chunk payload length "
                    + e.PayloadLength + " at frame " + frame);
            if (e.Offset + e.PayloadLength > ram.Length)
                throw new InvalidDataException("Snapshot chunk overruns the window at frame "
                    + frame);
            byte[] payload = BitConverter.GetBytes(e.Payload);
            if (!BitConverter.IsLittleEndian) Array.Reverse(payload);
            for (int j = 0; j < e.PayloadLength; j++)
            {
                ram[e.Offset + j] = payload[j];
                seen[e.Offset + j] = true;
            }
        }

        private static void Require(int status, string call, int frame,
            IGpgxAudioTraceApi trace)
        {
            if (status == 0) return;
            GpgxAudioObserverAdapter.FirstFault fault;
            trace.GetFirstFault(out fault);
            throw new InvalidDataException(call + " returned " + status + " at frame "
                + frame + "; first fault reason=" + fault.Reason + " cpu=" + fault.SourceCpu
                + " pc=0x" + fault.Pc.ToString("x") + " kind=" + fault.ActiveKind
                + " depth=" + fault.ActiveDepth);
        }

        private static void WriteBody(StreamWriter writer, SHA256 sha, JObject row)
        {
            string line = row.ToString(Formatting.None) + "\n";
            byte[] bytes = Encoding.UTF8.GetBytes(line);
            sha.TransformBlock(bytes, 0, bytes.Length, null, 0);
            writer.Write(line);
        }

        private static string RequiredEnvironment(string name)
        {
            string value = Environment.GetEnvironmentVariable(name);
            if (string.IsNullOrEmpty(value))
                throw new InvalidOperationException(name + " is required.");
            return value;
        }

        private static string HashFileSha1(string path)
        {
            using (SHA1 sha = SHA1.Create())
            using (FileStream stream = File.OpenRead(path))
                return Hex(sha.ComputeHash(stream));
        }

        private static string HashFileSha256(string path)
        {
            using (SHA256 sha = SHA256.Create())
            using (FileStream stream = File.OpenRead(path))
                return Hex(sha.ComputeHash(stream));
        }

        private static string Hex(byte[] value)
        {
            var result = new StringBuilder(value.Length * 2);
            for (int i = 0; i < value.Length; i++) result.Append(value[i].ToString("x2"));
            return result.ToString();
        }
    }
}
