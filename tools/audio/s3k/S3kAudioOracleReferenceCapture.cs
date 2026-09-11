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
    /// S3K sound-driver oracle reference capture.
    ///
    /// Replays a BK2 movie through the headless GPGX host with the pinned
    /// patch-0001 audio-observer core and records per emulated frame. After
    /// driver boot, an ordinary frame contains one zVInt invocation
    /// (skdisasm Sound/Z80 Sound Driver.asm zVInt); pre-install frames contain
    /// none, and the initial zStopAllSound crosses frame boundaries. Records:
    ///   1. the 68k-side request mailboxes read *before* the frame advances
    ///      (zMusicNumber/zSFXNumber0/zSFXNumber1 at 1C0A-1C0C), i.e. the
    ///      driver inputs the coming invocation will consume;
    ///   2. the ordered YM2612/PSG write stream of the frame, decoded from
    ///      the observer's kind-3/kind-4 chip events with the same YM
    ///      address-latch rule the production observer uses (address writes
    ///      on subjects 0/2 latch; data writes on subjects 1/3 emit);
    ///   3. a post-frame snapshot of the driver's variable+track RAM
    ///      (Z80 1C00h..1FA0h, zDataStart..zTracksSaveEnd).
    ///
    /// Track attribution is deliberately out of band: the post-frame RAM
    /// snapshot carries the track structs. The write rows retain source CPU,
    /// but no finer service-ownership projection is used.
    ///
    /// Environment:
    ///   BIZHAWK_HOME                observer-core BizHawk home (assembled by
    ///                              tools/audio/run_s3k_audio_oracle_reference.sh)
    ///   S3K_ROM_PATH                locked-on S3K ROM
    ///   OGGF_S3K_ORACLE_MOVIE       BK2 movie path
    ///   OGGF_S3K_ORACLE_OUTPUT      output JSONL path (must not exist)
    ///   OGGF_S3K_ORACLE_FRAMES      tick count to capture (default 5400)
    ///   OGGF_S3K_ORACLE_MANIFEST    gpgx-audio-service-manifests-v1.json path
    /// </summary>
    internal static class S3kAudioOracleReferenceCapture
    {
        private const string Schema = "openggf.s3k_audio_oracle_reference.v1";
        private const string RomSha1 = "cfbf98c36c776677290a872547ac47c53d2761d6";
        private const int RamStart = 0x1C00;   // zDataStart
        private const int RamEnd = 0x1FA0;     // zTracksSaveEnd = z80_stack_end
        private const int MailboxMusic = 0x1C0A;
        private const int MailboxSfx0 = 0x1C0B;
        private const int MailboxSfx1 = 0x1C0C;

        private static int Main()
        {
            try
            {
                Run();
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("s3k-audio-oracle-capture: " + error.Message);
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
                    ["ticks"] = frames,
                    ["ram_window"] = new JObject
                    {
                        ["start"] = RamStart,
                        ["exclusive_end"] = RamEnd
                    },
                    ["sampling"] = "post_invocation",
                    ["mailbox_sampling"] = "pre_invocation",
                    ["writes_source"] = "gpgx_audio_trace_kind3_4_latch_decode",
                    ["observer_core_zst_sha256"] = coreSha256,
                    ["manifest"] = Path.GetFileName(manifest)
                }.ToString(Formatting.None) + "\n");

                byte port0Latch = 0;
                byte port1Latch = 0;
                ulong writeCount = 0;
                using (IEnumerator<Bk2Frame> rows = movie.OpenFrameStream().GetEnumerator())
                {
                    for (int frame = 0; frame < frames; frame++)
                    {
                        if (!rows.MoveNext())
                            throw new InvalidDataException("Movie ended at frame " + frame);
                        var mailbox = new[]
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

                        var writes = new JArray();
                        for (int i = 0; i < copied; i++)
                        {
                            GpgxAudioTraceEvent e = events[i];
                            if (e.Kind == 3)
                            {
                                if (e.Subject > 3)
                                    throw new InvalidDataException(
                                        "FM subject " + e.Subject + " at frame " + frame);
                                if (e.Subject == 0) { port0Latch = e.Value; continue; }
                                if (e.Subject == 2) { port1Latch = e.Value; continue; }
                                int port = e.Subject < 2 ? 0 : 1;
                                writes.Add(new JArray("ym", port,
                                    port == 0 ? port0Latch : port1Latch,
                                    e.Value, e.SourceCpu));
                                writeCount++;
                            }
                            else if (e.Kind == 4)
                            {
                                writes.Add(new JArray("psg", e.Value, e.SourceCpu));
                                writeCount++;
                            }
                        }

                        var ram = new StringBuilder((RamEnd - RamStart) * 2);
                        for (int offset = RamStart; offset < RamEnd; offset++)
                            ram.Append(host.ReadZ80RamByte(offset).ToString("x2"));

                        var tick = new JObject
                        {
                            ["row"] = "tick",
                            ["frame"] = frame,
                            ["lag"] = host.IsLagged
                        };
                        if (mailbox[0] != 0 || mailbox[1] != 0 || mailbox[2] != 0)
                            tick["mailbox"] = new JArray(mailbox[0], mailbox[1], mailbox[2]);
                        tick["writes"] = writes;
                        tick["ram"] = ram.ToString();
                        WriteBody(writer, body, tick);
                    }
                }
                Require(trace.Disable(), "Disable", frames, trace);

                body.TransformFinalBlock(new byte[0], 0, 0);
                writer.Write(new JObject
                {
                    ["row"] = "terminal",
                    ["ticks"] = frames,
                    ["write_count"] = writeCount,
                    ["body_sha256"] = Hex(body.Hash)
                }.ToString(Formatting.None) + "\n");
            }
            Console.WriteLine("s3k-audio-oracle-capture: " + frames + " ticks -> " + output);
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
