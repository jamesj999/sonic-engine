-- Read-only Sonic 1 REV01 whole-run driver observer, chained per song.
--
-- Same per-invocation record shape as s1_gameplay_driver_parity_probe.lua
-- (driver RAM music-slot state plus the ordered YM/PSG bus writes of that
-- UpdateMusic invocation, with the invocation's SFX and special-SFX dispatches
-- recorded as the engine host's request-sequence input), but instead of
-- capturing one window and stopping, it tiles a whole complete-run movie with
-- consecutive single-song windows and writes one capture file per window.
--
-- WINDOWS. A window opens at a Sound_PlayBGM dispatch and closes at the next
-- one. That boundary is the ROM's, not a chosen frame: Sound_PlayBGM reloads
-- the driver's music track RAM through InitMusicPlayback
-- (s1.sounddriver.asm:1498-1502), so the song, and with it the ROM asset range
-- every sequence position is normalized against, changes exactly there. The
-- invocation carrying the request is discarded, because the ROM has already
-- swapped songs by the time that invocation walks its tracks -- the same rule
-- the single-window gameplay probe uses to end its capture.
--
-- Each window is therefore single-song by construction, which is what lets
-- both sides keep the existing contract: the reference normalizes against one
-- song's range, and the engine host drives one music sequencer loaded from the
-- window's own epoch song. Chaining windows covers a whole run without either
-- side needing a multi-song contract.
--
-- ASSET RANGES. A window's range is derived from the ROM music pointer table
-- for the window's own music id, mirroring Sonic1SmpsLoader's blob sizing
-- (next table entry when it lies within a plausible blob, else the nearest
-- higher entry). No window carries a hard-coded address.
--
-- ABANDONED INVOCATIONS. UpdateMusic has two call sites, the VBlank handler
-- (sonic.asm:682) and the HBlank handler's delayed-transfer path
-- (sonic.asm:1062), which run at different stack depths. Over a whole run an
-- invocation can therefore be re-entered on a different stack before its
-- return is seen. The single-window probes never reach one; a whole-run
-- capture does. Such an invocation is dropped rather than recorded, and
-- counted in the window's metadata, because its track walk did not complete.
--
-- OUTPUT. OGGF_OUT receives a run manifest (one line per window plus a
-- summary); each window's ticks go to a sibling file named from OGGF_OUT with
-- the window ordinal and music id, so a single window can be published without
-- the rest.
--
-- Derived from s1_gameplay_driver_parity_probe.lua; the per-tick record shape
-- and every bus-capture path are unchanged, so a window this probe records is
-- byte-comparable with one the single-window probe records over the same span.

local runtimePath = assert(os.getenv("OGGF_BIZHAWK_PROBE_RUNTIME"),
    "run through run_bizhawk_lua so OGGF_BIZHAWK_PROBE_RUNTIME is absolute")
runtimePath = runtimePath:gsub("\\", "/")
local ProbeRuntime = dofile(runtimePath)
local contractPath = ProbeRuntime.siblingPath(runtimePath, "audio/s1_audio_parity_contract.lua")
local AudioContract = dofile(contractPath)

local SOUND_RAM = 0xF000
local GAME_MODE = 0xF600
local LEVEL_SELECT_ITEM = 0xFF82
local LEVEL_SELECT_SOUND = 0xFF84
local UPDATE_MUSIC = 0x71B4C
local UPDATE_MUSIC_RETURN = 0x71C4C
local PLAY_SEGA_RETURN = 0x71FD0
local SOUND_PLAY_BGM = 0x71FD2
local SOUND_PLAY_SFX = 0x721C6
-- Sound_PlaySpecial entry (s1.sounddriver.asm:1117, "Sound_D0toDF"): the
-- shipped FixBugs=0 dispatch in PlaySoundID (s1.sounddriver.asm:699-706)
-- routes ids $D0-$DF here directly -- a separate branch from Sound_PlaySFX,
-- not a fallthrough -- so a hook on SOUND_PLAY_SFX alone never observes a
-- special SFX request (e.g. the GHZ waterfall, id $D0). Confirmed by opcode
-- match: both routines open with the identical "tst.b
-- SMPS_RAM.f_1up_playing(a6)" (4a2e0027); RomOffsetFinder's reported offset
-- for this label is stale (verified by re-deriving PlaySoundID's SOUND_PLAY_SFX
-- offset the same way and finding it also wrong), so this address was found
-- by scanning for the opcode after Sound_PlaySFX and checking the following
-- bytes decode to the known Sound_PlaySpecial body (Go_SpecSoundIndex table
-- lookup, "subi.b #spec__First,d7" against $D0).
local SOUND_PLAY_SPECIAL = 0x7230C
-- Sound_E0toE4 (s1.sounddriver.asm:715), PlaySoundID's fourth dispatch branch,
-- reaching FadeOutMusic ($E0, :1360), PlaySegaSound ($E1), SpeedUpMusic ($E2,
-- :1568), SlowDownMusic ($E3, :1587) and StopAllSound ($E4) through
-- Sound_ExIndex (:722-726). These are driver commands the game issues exactly
-- as it issues SFX, and without them a window spanning a fade shows the engine
-- a fade it was never asked to perform. Verified by opcode: $71F8E is
-- "040700e0" (subi.b #$E0,d7, flg__First = $E0) followed by "e54f" (lsl.w #2,d7)
-- and "4efb7002" (jmp Sound_ExIndex(pc,d7.w)); the two bytes before it are the
-- "4e75" rts the disassembly labels locret_71F8C.
local SOUND_E0_TO_E4 = 0x71F8E
-- cfFadeInToPrevious (s1.sounddriver.asm:2166), the E4 coordination flag that
-- ends a 1-up jingle. It restores the whole of v_1up_ram (variables and every
-- music track) from the copy Sound_PlayBGM's 1-up branch made before it loaded
-- the jingle (:776-784), and it issues no Sound_PlayBGM of its own. So it is a
-- second ROM epoch that replaces the music track RAM wholesale, and a window
-- has to close here for the same reason it closes at a BGM dispatch: past this
-- point the tracks point into the *previous* song's data, outside the current
-- window's asset range. Verified by opcode: $72B14 is "204e" (movea.l a6,a0),
-- "43ee03a0" (lea v_1up_ram_copy(a6),a1) and "303c0087" (move.w #$87,d0, the
-- $220/4-1 longword count the listing names).
local CF_FADE_IN_TO_PREVIOUS = 0x72B14
-- UpdateMusic's .driverinput (s1.sounddriver.asm:169, "loc_71B82"), the point
-- past the Z80/DAC wait loop where a pass actually decrements the tempo and
-- goes on to walk the tracks. Diagnostic only, under OGGF_AUDIO_CAPTURE_DEBUG:
-- counting these per recorded invocation distinguishes an invocation that ran
-- the driver once from one that ran it twice, which the tick record cannot
-- show. Verified by opcode: "4df900fff000" is lea (v_snddriver_ram).l,a6.
local DRIVER_INPUT = 0x71B82
-- sonic1-complete-withemeralds.bk2 (SHA-256 f2e81793...) has 225,101 input
-- rows covering the entire pinned complete run; see ACCEPTED_MOVIES.
-- Secondary bound only: a frame budget from power-on, never movie end (see
-- the header). The window normally closes earlier, at the first post-epoch
-- music request. The span this describes still overlaps the never-executed S1
-- GHZ1 gameplay-audio timeline plan's [860,4975) window
-- (docs/architecture/plans/audio/2026-08-09-s1-ghz1-gameplay-audio-timeline-
-- plan.md), though that plan's own tool remains unexercised (see
-- tools/audio/README.md). The v2 fixture stopped at 3000 because
-- the capture appeared to self-terminate at frame 3219 (clean process exit
-- 0, no Lua error). That was this probe: the shared lifecycle's acceptBgm
-- raises "music $XX accepted after capture epoch" on any post-epoch music
-- request, and probe_runtime's hook wrapper calls finish() -- which runs
-- client.exit() -- before re-raising, so BizHawk left before the error
-- reached anyone. Frame 3219 is the movie's GHZ1 invincibility monitor
-- dispatching bgm_Invincible ($87, _Constants.asm:344). That is now the
-- defined window end (see WINDOW END above), and hook errors are written to
-- the capture's .error sidecar before the client exits.
-- No frame budget: a whole-run capture ends at movie end, and every window
-- boundary in between is a Sound_PlayBGM dispatch.
local MAX_WINDOW_INVOCATIONS = 36000
local CYCLE_SOUND_QUEUE = 0x71F02
local PLAY_SOUND_ID = 0x71F4C
local VALIDATE_ONLY = os.getenv("OGGF_AUDIO_CALLBACK_VALIDATE_ONLY") == "1"
local FORCE_PC_MANIFEST = os.getenv("OGGF_AUDIO_FORCE_PC_MANIFEST") == "1"
local CAPTURE_DEBUG = os.getenv("OGGF_AUDIO_CAPTURE_DEBUG") == "1"
-- Diagnostic bound for smoke-testing the chaining on a short prefix of a
-- movie. Unset for a real capture, which always runs to movie end; a capture
-- taken under this bound is a partial run and is not publishable.
local MAX_WINDOWS = tonumber(os.getenv("OGGF_AUDIO_MAX_WINDOWS") or "") or math.huge
-- Comma-separated window ordinals to write in full. Every window is still
-- opened, walked and reported in the run manifest, so the manifest always
-- describes the whole movie and any window is regenerable from it; only the
-- selected ordinals' ticks are written to disk. Unset means write them all.
local SELECTED_WINDOWS = nil
do
    local list = os.getenv("OGGF_AUDIO_WINDOWS")
    if list and list ~= "" then
        SELECTED_WINDOWS = {}
        for ordinal in list:gmatch("%d+") do
            SELECTED_WINDOWS[tonumber(ordinal)] = true
        end
    end
end

local function windowIsSelected(ordinal)
    return SELECTED_WINDOWS == nil or SELECTED_WINDOWS[ordinal] == true
end
local EXPECTED_ROM_SHA1 = "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b"
local EXPECTED_ROM_CRC32 = "afe05eee"
-- Every gameplay movie this probe will record, keyed by the SHA-256 the
-- launcher computed over the exact BK2 it handed EmuHawk (the caller cannot
-- substitute a claimed digest). Two different complete runs of the same ROM,
-- by different routes: the oracle's bar is any BK2, not one BK2, so a second
-- source is a second independent check on the same driver code rather than a
-- longer look at the same inputs. Both must open on GHZ1 for the epoch below
-- ($81) and for the engine host's single GHZ music sequencer.
local ACCEPTED_MOVIES = {
    -- sonic1-complete-withemeralds.bk2
    ["f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b"] = {rows = 225101},
    -- src/test/resources/traces/s1/_movies/s1-complete-run.bk2
    ["f744c814d8e00d6c367f7fe83bb663cab123b5a4ed385a320d71b74d63146bde"] = {rows = 195493}
}
local EXPECTED_MOVIE_OPAQUE_HASH = "09DADB5071EB35050067A32462E39C5F"
-- Music pointer table, mirroring Sonic1SmpsConstants.MUSIC_PTR_TABLE_ADDR and
-- MUSIC_COUNT and Sonic1Music.ID_BASE/ID_MAX. GHZ ($81, index 0) resolves to
-- $745DC..$74D44, the range the single-window probes hard-code, which is the
-- cross-check that this derivation matches the loader's.
local MUSIC_PTR_TABLE_ADDR = 0x071A9C
local MUSIC_COUNT = 19
local MUSIC_ID_BASE = 0x81
local MAX_BLOB_SIZE = 0x4000
-- Which of a track's twelve loop-counter slots the normalization reports is a
-- property of the song, not a constant: the F7 opcode names the slot it uses,
-- so only the slots reachable in the song's own bytecode are meaningful. The
-- single-window probes hard-code {0, 1}, which is GHZ's reachable set and was
-- correct while GHZ was the only song captured. This derivation mirrors
-- S1OpenGgfAudioCapture.parseReachableF7LoopIndices so both sides describe the
-- same slots for every song.
local activeLoopCounters = nil
local assetBase, assetEnd

local callbackAddresses = {
    [0xA04000] = "fm_port0_address",
    [0xA04001] = "fm_port0_data",
    [0xA04002] = "fm_port1_address",
    [0xA04003] = "fm_port1_data",
    [0xC00011] = "psg"
}

-- Complete shipped-ROM write-site fallback, derived from
-- docs/s1disasm/sonic.lst. Opcode bytes are verified against the loaded
-- `MD CART` domain before any capture is allowed.
local fallbackManifest = {
    {address = 0x7273A, operation = "fm0_address", source = "D0", expectedOpcode = "13c000a04000"},
    {address = 0x72752, operation = "fm0_data", source = "D1", expectedOpcode = "13c100a04001"},
    {address = 0x72770, operation = "fm1_address", source = "D0", expectedOpcode = "13c000a04002"},
    {address = 0x72788, operation = "fm1_data", source = "D1", expectedOpcode = "13c100a04003"},
    {address = 0x7225E, operation = "psg", source = "D0", expectedOpcode = "13c000c00011"},
    {address = 0x72268, operation = "psg", source = "D0", expectedOpcode = "13c000c00011"},
    {address = 0x723B6, operation = "psg", source = "D4", expectedOpcode = "13c400c00011"},
    {address = 0x723C0, operation = "psg", source = "D4", expectedOpcode = "13c400c00011"},
    {address = 0x7246A, operation = "psg", source = "$1F(A0)", expectedOpcode = "13e8001f00c00011"},
    {address = 0x724DC, operation = "psg", source = "$1F(A5)", expectedOpcode = "13ed001f00c00011"},
    {address = 0x72912, operation = "psg", source = "D0", expectedOpcode = "13c000c00011"},
    {address = 0x72918, operation = "psg", source = "D6", expectedOpcode = "13c600c00011"},
    {address = 0x72984, operation = "psg", source = "D6", expectedOpcode = "13c600c00011"},
    {address = 0x729AE, operation = "psg", source = "D0", expectedOpcode = "13c000c00011"},
    {address = 0x729BC, operation = "psg", source = "#$9F", expectedOpcode = "10bc009f"},
    {address = 0x729C0, operation = "psg", source = "#$BF", expectedOpcode = "10bc00bf"},
    {address = 0x729C4, operation = "psg", source = "#$DF", expectedOpcode = "10bc00df"},
    {address = 0x729C8, operation = "psg", source = "#$FF", expectedOpcode = "10bc00ff"},
    {address = 0x72DFA, operation = "psg", source = "$1F(A0)", expectedOpcode = "13e8001f00c00011"},
    {address = 0x72E16, operation = "psg", source = "-1(A4)", expectedOpcode = "13ecffff00c00011"}
}

local function verifyFallbackManifest()
    local seen = {}
    for _, site in ipairs(fallbackManifest) do
        assert(not seen[site.address], string.format("duplicate fallback PC $%06X", site.address))
        seen[site.address] = true
        local bytes = {}
        for offset = 0, (#site.expectedOpcode / 2) - 1 do
            bytes[#bytes + 1] = string.format("%02x", memory.read_u8(site.address + offset, "MD CART"))
        end
        assert(table.concat(bytes) == site.expectedOpcode,
            string.format("opcode mismatch at fallback PC $%06X", site.address))
    end
    assert(#fallbackManifest == 20, "fallback manifest must cover four FM and sixteen PSG write sites")
end

local dispatchManifest = {
    -- Sound_PlaySFX entry: tst.b SMPS_RAM.f_1up_playing(a6)
    {address = SOUND_PLAY_SFX, expectedOpcode = "4a2e0027"},
    -- Sound_PlaySpecial entry: tst.b SMPS_RAM.f_1up_playing(a6) (same field,
    -- same opcode -- see the SOUND_PLAY_SPECIAL comment above)
    {address = SOUND_PLAY_SPECIAL, expectedOpcode = "4a2e0027"},
    -- Sound_E0toE4 entry: subi.b #flg__First,d7 then lsl.w #2,d7
    {address = SOUND_E0_TO_E4, expectedOpcode = "040700e0e54f"},
    -- cfFadeInToPrevious entry: movea.l a6,a0 / lea v_1up_ram_copy(a6),a1
    {address = CF_FADE_IN_TO_PREVIOUS, expectedOpcode = "204e43ee03a0303c0087"}
}

local function verifyOpcodeSites(sites, label)
    for _, site in ipairs(sites) do
        local bytes = {}
        for offset = 0, (#site.expectedOpcode / 2) - 1 do
            bytes[#bytes + 1] = string.format("%02x", memory.read_u8(site.address + offset, "MD CART"))
        end
        assert(table.concat(bytes) == site.expectedOpcode,
            string.format("opcode mismatch at %s PC $%06X", label, site.address))
    end
end

-- The GPGX core exposes no "System Bus" domain; a read naming it silently
-- falls back to the current domain. Map 68k bus addresses into the real
-- domains instead, and fail loudly for anything outside RAM or cartridge.
local RAM_DOMAIN = "68K RAM"

local function assertMemoryDomains()
    local names = {}
    for _, name in pairs(memory.getmemorydomainlist()) do
        names[#names + 1] = tostring(name)
    end
    local found = {}
    for _, name in ipairs(names) do found[name] = true end
    for _, required in ipairs({"MD CART", RAM_DOMAIN}) do
        assert(found[required], string.format(
            "GPGX core must expose the %s memory domain; available: %s",
            required, table.concat(names, ", ")))
    end
end

local function readBusByte(address)
    address = address & 0xFFFFFF
    if address >= 0xFF0000 then
        return memory.read_u8(address & 0xFFFF, RAM_DOMAIN)
    end
    assert(address < 0x400000,
        string.format("unsupported fallback bus address $%06X", address))
    return memory.read_u8(address, "MD CART")
end

local function readManifestValue(site)
    local dataRegister = ({D0 = "M68K D0", D1 = "M68K D1",
        D4 = "M68K D4", D6 = "M68K D6"})[site.source]
    if dataRegister then return (emu.getregister(dataRegister) or 0) & 0xFF end
    local immediate = ({["#$9F"] = 0x9F, ["#$BF"] = 0xBF,
        ["#$DF"] = 0xDF, ["#$FF"] = 0xFF})[site.source]
    if immediate then return immediate end
    local register, displacement = site.source:match("^%$1F%((A[05])%)$")
    if register then displacement = 0x1F end
    if site.source == "-1(A4)" then register, displacement = "A4", -1 end
    assert(register ~= nil, "unsupported fallback operand " .. tostring(site.source))
    local address = ((emu.getregister("M68K " .. register) or 0) + displacement) & 0xFFFFFF
    return readBusByte(address)
end

local function rotateLeft(value, count)
    return ((value << count) | (value >> (32 - count))) & 0xffffffff
end

local function loadedRomIdentity()
    local size = memory.getmemorydomainsize("MD CART")
    assert(size == 524288, "S1 REV01 ROM must be exactly 524,288 bytes")

    local crc = 0xffffffff
    local sha = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0}
    local words = {}
    local function processShaBlock(byteAt)
        for word = 0, 15 do
            local offset = word * 4
            words[word] = ((byteAt(offset) << 24) | (byteAt(offset + 1) << 16)
                | (byteAt(offset + 2) << 8) | byteAt(offset + 3)) & 0xffffffff
        end
        for word = 16, 79 do
            words[word] = rotateLeft(words[word - 3] ~ words[word - 8]
                ~ words[word - 14] ~ words[word - 16], 1)
        end
        local a, b, c, d, e = sha[1], sha[2], sha[3], sha[4], sha[5]
        for word = 0, 79 do
            local f, k
            if word < 20 then
                f, k = (b & c) | ((~b) & d), 0x5a827999
            elseif word < 40 then
                f, k = b ~ c ~ d, 0x6ed9eba1
            elseif word < 60 then
                f, k = (b & c) | (b & d) | (c & d), 0x8f1bbcdc
            else
                f, k = b ~ c ~ d, 0xca62c1d6
            end
            local temporary = (rotateLeft(a, 5) + f + e + k + words[word]) & 0xffffffff
            e, d, c, b, a = d, c, rotateLeft(b, 30), a, temporary
        end
        sha[1] = (sha[1] + a) & 0xffffffff
        sha[2] = (sha[2] + b) & 0xffffffff
        sha[3] = (sha[3] + c) & 0xffffffff
        sha[4] = (sha[4] + d) & 0xffffffff
        sha[5] = (sha[5] + e) & 0xffffffff
    end

    for block = 0, (size / 64) - 1 do
        local base = block * 64
        processShaBlock(function(offset)
            local value = memory.read_u8(base + offset, "MD CART")
            crc = crc ~ value
            for _ = 1, 8 do
                crc = ((crc >> 1) ~ (((crc & 1) ~= 0) and 0xedb88320 or 0)) & 0xffffffff
            end
            return value
        end)
    end
    processShaBlock(function(offset)
        if offset == 0 then return 0x80 end
        if offset == 61 then return 0x40 end
        return 0
    end)
    local shaParts = {}
    for index = 1, 5 do shaParts[index] = string.format("%08x", sha[index]) end
    return {crc32 = string.format("%08x", (~crc) & 0xffffffff), sha1 = table.concat(shaParts)}
end

local function verifyIdentity()
    local rom = loadedRomIdentity()
    assert(rom.sha1 == EXPECTED_ROM_SHA1, "loaded ROM is not Sonic 1 World REV01 (SHA-1)")
    assert(rom.crc32 == EXPECTED_ROM_CRC32, "loaded ROM is not Sonic 1 World REV01 (CRC32)")
    assert(gameinfo.getromname() == "Sonic The Hedgehog (W) (REV01) [!]",
        "BizHawk ROM name does not identify S1 World REV01")
    assert(movie.isloaded(), "pinned S1 complete-run BK2 must be loaded")
    local launcherSha256 = assert(os.getenv("OGGF_BIZHAWK_MOVIE_SHA256"),
        "run_bizhawk_lua must supply the actual BK2 SHA-256"):lower()
    local accepted = ACCEPTED_MOVIES[launcherSha256]
    assert(accepted, "BK2 is not one of this probe's pinned S1 gameplay movies: " .. launcherSha256)
    assert(movie.length() == accepted.rows,
        "pinned S1 complete-run BK2 does not have its recorded input-row count")
    local header = movie.getheader()
    assert(header.Core == "Genplus-gx", "S1 parity BK2 must select Genesis Plus GX")
    assert(header.emuVersion == "Version 2.11", "S1 parity BK2 must select BizHawk 2.11")
    assert(header.GameName == "Sonic The Hedgehog (W) (REV01) [!]", "S1 parity BK2 game mismatch")
    assert(header.SHA1 == EXPECTED_MOVIE_OPAQUE_HASH, "S1 parity BK2 opaque identity mismatch")
    -- The pin is the ACCEPTED_MOVIES membership assert above, not a
    -- self-comparison: this only re-validates the digest's shape before it
    -- goes into the capture metadata.
    local movieSha256 = AudioContract.requireSha256(
        launcherSha256, launcherSha256, "launcher BK2")
    return rom, movieSha256
end

local function readU8(offset) return mainmemory.read_u8(SOUND_RAM + offset) end
local function readU16(offset) return mainmemory.read_u16_be(SOUND_RAM + offset) end
local function readU32(offset) return mainmemory.read_u32_be(SOUND_RAM + offset) end

local roles = {"DAC", "FM1", "FM2", "FM3", "FM4", "FM5", "FM6", "PSG1", "PSG2", "PSG3"}
local expectedVoiceControls = {6, 0, 1, 2, 4, 5, 6, 0x80, 0xa0, 0xc0}

local function readRomSnapshot()
    local fadeOutCount = readU8(0x04)
    local fadeInFlag = readU8(0x24)
    local fadeOut = fadeOutCount ~= 0
    local tracks = {}
    local trackDiagnostics = {}
    for index = 1, 10 do
        local base = 0x40 + (index - 1) * 0x30
        local status = readU8(base)
        local voiceControl = readU8(base + 0x01)
        if (status & 0x80) ~= 0 then
            local voiceControlMatches = voiceControl == expectedVoiceControls[index]
                or (index == 10 and voiceControl == 0xe0)
            assert(voiceControlMatches,
                string.format("active %s voice-control mismatch", roles[index]))
        end
        local loopCounters = {}
        for offset = 0, 11 do loopCounters[offset + 1] = readU8(base + 0x24 + offset) end
        local stackPointer = readU8(base + 0x0D)
        local returnStack = {}
        if (status & 0x80) ~= 0 then
            assert(stackPointer <= 0x30 and ((0x30 - stackPointer) % 4) == 0,
                string.format("active %s return-stack cursor is invalid", roles[index]))
            for offset = stackPointer, 0x2F, 4 do
                returnStack[#returnStack + 1] = readU32(base + offset)
            end
        end
        local dataPointer = readU32(base + 0x04)
        tracks[index] = {
            status = status,
            voiceControl = voiceControl,
            baseFrequency = readU16(base + 0x10),
            dataPointer = dataPointer,
            detune = readU8(base + 0x1E),
            duration = readU8(base + 0x0E),
            durationReload = readU8(base + 0x0F),
            loopCounters = loopCounters,
            panAmsFms = readU8(base + 0x0A),
            returnStack = returnStack,
            stackPointer = stackPointer,
            transpose = readU8(base + 0x08),
            voiceOrEnvelope = readU8(base + 0x0B),
            volume = readU8(base + 0x09),
            volumeEnvelopeIndex = readU8(base + 0x0C)
        }
        trackDiagnostics[index] = {
            ams_fms_pan = readU8(base + 0x0A),
            data_position = dataPointer >= assetBase and dataPointer < assetEnd
                and dataPointer - assetBase or -1,
            duration_countdown = readU8(base + 0x0E),
            duration_reload = readU8(base + 0x0F),
            envelope_cursor = readU8(base + 0x0C),
            envelope_or_voice = readU8(base + 0x0B),
            modulation_delay = readU8(base + 0x18),
            modulation_delta = AudioContract.s8(readU8(base + 0x1A)),
            modulation_enabled = (status & 0x08) ~= 0,
            modulation_speed = readU8(base + 0x19),
            modulation_steps = readU8(base + 0x1B),
            modulation_value = AudioContract.s16(readU16(base + 0x1C)),
            note_fill_countdown = readU8(base + 0x12),
            note_fill_reload = readU8(base + 0x13),
            overridden = (status & 0x04) ~= 0,
            resting = (status & 0x02) ~= 0,
            role = roles[index],
            status = status,
            tie_next = (status & 0x10) ~= 0,
            voice_control = voiceControl
        }
    end
    return {
        assetBase = assetBase,
        assetEnd = assetEnd,
        global = {
            fadeActive = (fadeOutCount ~= 0 or fadeInFlag ~= 0) and 1 or 0,
            fadeDelay = fadeOut and readU8(0x06) or readU8(0x25),
            fadeOut = fadeOut and 1 or 0,
            fadeSteps = fadeOut and fadeOutCount or readU8(0x26),
            speedUp = readU8(0x2A),
            tempoReload = readU8(0x02),
            tempoTimeout = readU8(0x01)
        },
        tracks = tracks
    }, {
        global = {
            communication = readU8(0x07), fade_in_flag = fadeInFlag,
            fade_out_counter = fadeOutCount, one_up = readU8(0x27), pause = readU8(0x03),
            priority = readU8(0x00), push = readU8(0x2C), queues = {readU8(0x0A), readU8(0x0B), readU8(0x0C)},
            ring_speaker = readU8(0x2B), sound_id = readU8(0x09), speed_up_reload = readU8(0x29),
            updating_dac = readU8(0x08), voice_selector = readU8(0x0E)
        },
        tracks = trackDiagnostics
    }
end

-- The sound-test movies' assertStableSoundTest() (GAME_MODE == Sound Test row,
-- no pause/fade/speed-up) does not apply here: this probe captures real GHZ1
-- gameplay, where the level-play GAME_MODE, real pause presses, real fades
-- (drowning, act-clear), and real speed shoes are all legitimate ROM state,
-- not contamination. There is nothing to assert in their place -- the engine
-- capture host replays whatever this probe observed, contaminated or not.

local function callbackArgument(value)
    local kind = type(value)
    if kind == "number" or kind == "string" or kind == "boolean" then return value end
    return {lua_type = kind, rendered = tostring(value)}
end

local function callbackArguments(...)
    local result = {}
    for index = 1, select("#", ...) do result[index] = callbackArgument(select(index, ...)) end
    return result
end

local function readCartU32(address)
    return ((memory.read_u8(address, "MD CART") << 24)
        | (memory.read_u8(address + 1, "MD CART") << 16)
        | (memory.read_u8(address + 2, "MD CART") << 8)
        | memory.read_u8(address + 3, "MD CART")) & 0xFFFFFFFF
end

-- Mirrors Sonic1SmpsLoader.calculateMusicDataSize: the next pointer-table
-- entry when it lies within a plausible blob, otherwise the nearest higher
-- entry, otherwise a generous bound.
local function musicAssetRange(musicId)
    local index = musicId - MUSIC_ID_BASE
    assert(index >= 0 and index < MUSIC_COUNT,
        string.format("music id $%02X is outside the S1 pointer table", musicId))
    local base = readCartU32(MUSIC_PTR_TABLE_ADDR + index * 4)
    if index < MUSIC_COUNT - 1 then
        local following = readCartU32(MUSIC_PTR_TABLE_ADDR + (index + 1) * 4)
        if following > base and following < base + MAX_BLOB_SIZE then
            return base, following
        end
    end
    local bound = base + MAX_BLOB_SIZE
    for probe = index + 1, MUSIC_COUNT - 1 do
        local candidate = readCartU32(MUSIC_PTR_TABLE_ADDR + probe * 4)
        if candidate > base and candidate < bound then
            bound = candidate
            break
        end
    end
    return base, bound
end

local function songByte(offset)
    return memory.read_u8(assetBase + offset, "MD CART")
end

local function songWord(offset)
    return ((songByte(offset) << 8) | songByte(offset + 1)) & 0xFFFF
end

-- S1 track-command parameter lengths, mirroring
-- S1OpenGgfAudioCapture.parameterLength. ED and EE take no parameter in S1.
local PARAMETER_LENGTH = {
    [0xE0] = 1, [0xE1] = 1, [0xE2] = 1, [0xE5] = 1, [0xE6] = 1, [0xE8] = 1,
    [0xE9] = 1, [0xEA] = 1, [0xEB] = 1, [0xEC] = 1, [0xEF] = 1, [0xF3] = 1,
    [0xF5] = 1, [0xFD] = 2, [0xF0] = 4
}

-- Walks the reachable track bytecode of the song at the open window's asset
-- range, following jump, call and return edges and both outcomes of every
-- loop, and collects the slot each reachable F7 names. Reads only the loaded
-- cart, never a disassembly or a fixture.
local function reachableLoopCounters()
    local length = assetEnd - assetBase
    assert(length > 8, "song asset range is too short to carry a header")

    local function relativeTarget(pointerOffset)
        local raw = songWord(pointerOffset)
        if raw >= 0x8000 then raw = raw - 0x10000 end
        return pointerOffset + 1 + raw
    end

    local pending = {}
    local function push(position, stack)
        pending[#pending + 1] = {position = position, stack = stack}
    end

    -- Header: voice pointer word, FM+DAC count, PSG count, two timing bytes,
    -- then four-byte FM/DAC entries and six-byte PSG entries.
    local fmCount = songByte(2)
    local psgCount = songByte(3)
    local offset = 0x06
    for _ = 1, fmCount do
        if offset + 3 < length then push(songWord(offset), {}) end
        offset = offset + 4
    end
    for _ = 1, psgCount do
        if offset + 5 < length then push(songWord(offset), {}) end
        offset = offset + 6
    end

    local visited = {}
    local found = {}
    local ordered = {}
    while #pending > 0 do
        local state = table.remove(pending, 1)
        local position, stack = state.position, state.stack
        local key = position .. ":" .. table.concat(stack, ",")
        if position >= 0 and position < length and not visited[key] then
            visited[key] = true
            local command = songByte(position)
            if command < 0xE0 then
                local next_ = position + 1
                if command >= 0x80 and next_ < length and songByte(next_) < 0x80 then
                    next_ = next_ + 1
                end
                push(next_, stack)
            elseif command == 0xF2 then
                -- Track stop: no successor.
            elseif command == 0xE3 then
                if #stack > 0 then
                    local resumed = {}
                    for index = 1, #stack - 1 do resumed[index] = stack[index] end
                    push(stack[#stack], resumed)
                end
            elseif command == 0xF6 and position + 2 < length then
                push(relativeTarget(position + 1), stack)
            elseif command == 0xF8 and position + 2 < length then
                local called = {}
                for index = 1, #stack do called[index] = stack[index] end
                called[#called + 1] = position + 3
                push(relativeTarget(position + 1), called)
            elseif command == 0xF7 and position + 4 < length then
                local slot = songByte(position + 1)
                if not found[slot] then
                    found[slot] = true
                    ordered[#ordered + 1] = slot
                end
                push(relativeTarget(position + 3), stack)
                push(position + 5, stack)
            else
                push(position + 1 + (PARAMETER_LENGTH[command] or 0), stack)
            end
        end
    end
    table.sort(ordered)
    return ordered
end

-- One window's accumulated capture. Windows are written out as they close, so
-- only the open window is ever held in memory: a whole run is far too large to
-- accumulate.
local windowOrdinal = -1
local windowMusicId = nil
-- The song the current window interrupted, which is the one cfFadeInToPrevious
-- restores: Sound_PlayBGM's 1-up branch copies the driver RAM before loading
-- the jingle, so the restore returns to whatever was playing then.
local previousWindowMusicId = nil
local windowOpenFrame = nil
local windowAbandoned = 0
local windowFile = nil
local windowRecordCount = 0
local windowManifest = {}
local runDormantInvocations = 0

local windowBoundary = "bgm_dispatch"

local function windowOutputPath(ordinal, musicId)
    local base = assert(os.getenv("OGGF_OUT"), "OGGF_OUT must be set")
    base = base:gsub("%.jsonl$", "")
    return string.format("%s-w%03d-id%02X.jsonl", base, ordinal, musicId)
end

local validation = {
    epochReached = false,
    callbackCount = 0,
    pcDataCount = 0,
    lastDataPc = nil,
    identityLogged = false
}
local invocationLifecycle = AudioContract.newInvocationLifecycle()
local driverInputPasses = 0
local currentOrdinal = nil
local currentOpenFrame = nil
local currentStreams = nil
local currentDispatches = nil
local emitted = false
local callbackProof = AudioContract.newCallbackProof()
local manifestProof = AudioContract.newCallbackProof()
local callbackInvalidReason = nil
local selectedSource = nil
local romIdentity
local movieIdentity

local function newStream()
    return {raw = {}, decoded = {}, decoder = AudioContract.newYmDecoder()}
end

local function beginCapturedInvocation(ordinal, openFrame)
    currentOrdinal = ordinal
    currentOpenFrame = openFrame
    currentDispatches = {}
    driverInputPasses = 0
    currentStreams = {}
    if selectedSource then
        currentStreams[selectedSource] = newStream()
    else
        currentStreams.memory_callback = newStream()
        currentStreams.pc_manifest = newStream()
    end
end

local function invalidateCallback(reason)
    if selectedSource == "memory_callback" then error(reason, 0) end
    callbackInvalidReason = callbackInvalidReason or tostring(reason)
end

local function observeCallbackProof(action)
    if callbackInvalidReason or selectedSource == "pc_manifest" then return false end
    local ok, failure = pcall(action)
    if not ok then invalidateCallback(failure) end
    return ok
end

local function recordBusEvent(source, rawEvent, busEvent)
    if selectedSource and selectedSource ~= source then return end
    assert(invocationLifecycle:isActive() and currentStreams ~= nil,
        "audio bus write occurred outside the active captured UpdateMusic invocation")
    local stream = assert(currentStreams[source], "selected audio stream is not open")
    local ok, decoded = pcall(function() return stream.decoder:feed(busEvent) end)
    if not ok and source == "memory_callback" and not selectedSource then
        invalidateCallback(decoded)
        return
    end
    if not ok then error(decoded, 0) end
    stream.raw[#stream.raw + 1] = rawEvent
    if decoded then stream.decoded[#stream.decoded + 1] = decoded end
end

local function finishInitialStreams()
    local manifestOk, manifestFailure = pcall(function()
        currentStreams.pc_manifest.decoder:finishTick()
        manifestProof:assertVerified()
    end)
    local callbackOk = false
    if not callbackInvalidReason then
        callbackOk = pcall(function()
            currentStreams.memory_callback.decoder:finishTick()
            callbackProof:assertVerified()
        end)
    end
    selectedSource = AudioContract.selectCaptureSource(
        FORCE_PC_MANIFEST, callbackOk, manifestOk)
    if selectedSource == "pc_manifest" and not manifestOk then error(manifestFailure, 0) end
    return currentStreams[selectedSource]
end

local function windowMetadataJson(recordCount, closeFrame)
    local header = movie.getheader()
    local callbackContract
    if selectedSource == "memory_callback" then
        callbackContract = {
            arguments = {"address", "value", "flags"},
            proof = callbackProof:counts(),
            source = "memory_callback"
        }
    else
        callbackContract = {manifest_sites = #fallbackManifest, source = "pc_manifest"}
    end
    return AudioContract.canonicalJson({
        capture = "s1_run_song_window_driver_reference",
        callback_contract = callbackContract,
        cycle_start = 0,
        diagnostic_fields = {
            global = {"priority", "pause", "fade flags", "queues", "sound id", "voice selector",
                "DAC update", "1-up", "speed-up reload", "communication", "ring speaker", "push"},
            track = {"resting", "note fill", "modulation phase", "raw status", "raw voice control"}
        },
        gating_fields = {
            global = {"tempo timeout", "tempo reload", "speed-up", "fade state", "dispatches"},
            track = {"active", "role", "hardware", "overridden", "do not attack", "modulation enabled",
                "sequence position", "transpose", "volume", "pan/AMS/FMS", "voice/envelope", "duration",
                "duration reload", "PSG envelope cursor", "base frequency", "detune", "live loop counters",
                "live return stack"}
        },
        movie = {
            archive_sha256 = movieIdentity,
            core = header.Core,
            emulator = header.emuVersion,
            game = header.GameName,
            input_rows = movie.length(),
            opaque_header_hash = header.SHA1
        },
        music_id = windowMusicId,
        period = 0,
        rom = romIdentity,
        schema = "openggf.s1_audio_parity_reference.v1",
        terminal_record_count = recordCount,
        type = "capture_metadata",
        window = {
            close_frame = closeFrame,
            open_frame = windowOpenFrame,
            ordinal = windowOrdinal
        }
    })
end

-- Ticks stream to a body file while the window is open, because a window's
-- records are far too large to hold in memory over a whole run, and the
-- metadata line cannot be written until the closing dispatch fixes the record
-- count. On close the final file is metadata followed by the body.
local function closeWindow(context, closeFrame)
    if windowOrdinal < 0 then return end
    local selected = windowFile ~= nil
    if selected then
        windowFile:close()
        windowFile = nil
    end
    windowManifest[#windowManifest + 1] = {
        abandoned_invocations = windowAbandoned,
        close_frame = closeFrame,
        music_id = windowMusicId,
        open_boundary = windowBoundary,
        open_frame = windowOpenFrame,
        ordinal = windowOrdinal,
        published = selected,
        terminal_record_count = windowRecordCount,
        type = "window"
    }
    context.log(AudioContract.canonicalJson(windowManifest[#windowManifest]))
    if not selected then return end
    local path = windowOutputPath(windowOrdinal, windowMusicId)
    local bodyPath = path .. ".body"
    local final = assert(io.open(path, "w"))
    final:write(windowMetadataJson(windowRecordCount, closeFrame), "\n")
    local body = assert(io.open(bodyPath, "r"))
    while true do
        local chunk = body:read(1024 * 1024)
        if not chunk then break end
        final:write(chunk)
    end
    body:close()
    final:close()
    os.remove(bodyPath)
end

local function openWindow(musicId, frame, boundary)
    previousWindowMusicId = windowMusicId
    windowOrdinal = windowOrdinal + 1
    windowBoundary = boundary or "bgm_dispatch"
    windowMusicId = musicId
    windowOpenFrame = frame
    windowAbandoned = 0
    windowRecordCount = 0
    assetBase, assetEnd = musicAssetRange(musicId)
    activeLoopCounters = reachableLoopCounters()
    windowFile = windowIsSelected(windowOrdinal)
        and assert(io.open(windowOutputPath(windowOrdinal, musicId) .. ".body", "w"))
        or nil
end

local function emitRunSummary(context)
    context.log(AudioContract.canonicalJson({
        dormant_invocations_before_first_window = runDormantInvocations,
        final_frame = emu.framecount(),
        movie_archive_sha256 = movieIdentity,
        rom = romIdentity,
        partial_run = MAX_WINDOWS ~= math.huge,
        schema = "openggf.s1_run_window_manifest.v1",
        type = "run_summary",
        window_count = #windowManifest
    }))
    context.finish()
end

local function closeCapturedInvocation(context)
    local function debugPhase(phase)
        if CAPTURE_DEBUG then
            context.log(AudioContract.canonicalJson({ordinal = currentOrdinal, phase = phase, type = "debug"}))
        end
    end
    assert(currentOrdinal == windowRecordCount, "audio-driver ordinal is not continuous")
    debugPhase("decoder_finish")
    local stream
    if selectedSource then
        stream = assert(currentStreams[selectedSource])
        stream.decoder:finishTick()
    else
        stream = finishInitialStreams()
    end
    debugPhase("contamination")
    debugPhase("snapshot")
    local snapshotOk, rawSnapshot, diagnostics = pcall(readRomSnapshot)
    if not snapshotOk then
        if CAPTURE_DEBUG then
            context.log(AudioContract.canonicalJson({snapshot_error = tostring(rawSnapshot), type = "debug"}))
        end
        error(rawSnapshot, 0)
    end
    -- The single-window probe asserts GHZ's own initialized track activity at
    -- tick 0. That is a property of one song, not of the driver, so a
    -- per-song window asserts only what holds for every song: the invocation
    -- opening a window runs against freshly initialized track RAM, so at
    -- least one track is playing.
    if currentOrdinal == 0 then
        local anyActive = false
        for index = 1, 10 do
            if (rawSnapshot.tracks[index].status & 0x80) ~= 0 then anyActive = true end
        end
        assert(anyActive, string.format(
            "music $%02X initialized no active track at its window epoch", windowMusicId))
    end
    debugPhase("normalize")
    local normalized = AudioContract.normalizeRom(rawSnapshot, activeLoopCounters)
    local stateHash = AudioContract.hashState(normalized)
    local eventHash = AudioContract.hashEvents(stream.decoded)
    local record = {
        dispatches = currentDispatches,
        diagnostic = {
            emulator_frame = emu.framecount(),
            game_mode = mainmemory.read_u8(GAME_MODE),
            interrupt_mask = ((emu.getregister("M68K SR") or 0) >> 8) & 7,
            invocation_open_frame = currentOpenFrame,
            raw_state = diagnostics
        },
        events = stream.decoded,
        ordinal = currentOrdinal,
        raw_bus = stream.raw,
        state = normalized,
        type = "tick"
    }
    if CAPTURE_DEBUG and driverInputPasses ~= 1 then
        context.log(AudioContract.canonicalJson({
            driver_input_passes = driverInputPasses,
            emulator_frame = emu.framecount(),
            ordinal = currentOrdinal,
            type = "driver_input_diagnostic",
            window_ordinal = windowOrdinal
        }))
    end
    if windowFile then
        windowFile:write(AudioContract.canonicalJson(record), "\n")
    end
    windowRecordCount = windowRecordCount + 1
    assert(windowRecordCount < MAX_WINDOW_INVOCATIONS,
        "per-song window exceeded the 36,000-invocation budget")
    debugPhase("close")
    local _ = stateHash
    local _ = eventHash
    currentOrdinal, currentOpenFrame, currentStreams, currentDispatches = nil, nil, nil, nil
end

local hooks = {}
-- probe_runtime's hook wrapper calls finish() (client.exit()) before it
-- re-raises, so a hook error would otherwise leave BizHawk as a clean exit 0
-- with nothing recorded anywhere. Every hook error is appended to the
-- capture's ".error" sidecar first; run_s1_audio_parity.sh reports it.
local function recordHookFailure(name, failure)
    local path = os.getenv("OGGF_OUT")
    if not path then return end
    local sidecar = io.open(path .. ".error", "a")
    if not sidecar then return end
    sidecar:write(string.format("hook %s failed at frame %d: %s\n",
        tostring(name), emu.framecount(), tostring(failure)))
    sidecar:close()
end

local function addHook(hook)
    local inner = hook.callback
    hook.callback = function(...)
        local ok, failure = pcall(inner, ...)
        if not ok then
            recordHookFailure(hook.name, failure)
            error(failure, 0)
        end
    end
    hooks[#hooks + 1] = hook
end

local function finishValidationInvocation(context)
    local stream = finishInitialStreams()
    context.log(AudioContract.canonicalJson({
        callback_count = validation.callbackCount,
        callback_invalid = callbackInvalidReason,
        callback_proof = callbackProof:counts(),
        event = "callback_validation_complete",
        fm_data_pc_count = validation.pcDataCount,
        manifest_event_count = #stream.raw,
        selected_source = selectedSource
    }))
    context.finish()
end

addHook({
    name = "s1_audio_update_music_entry",
    address = UPDATE_MUSIC,
    callback = function(context)
        local frame = emu.framecount()
        local sp = (emu.getregister("M68K A7") or 0) & 0xffffffff
        if VALIDATE_ONLY or CAPTURE_DEBUG then
            context.log(AudioContract.canonicalJson({
                active = invocationLifecycle:isActive(), event = "update_entry", frame = frame, sp = sp
            }))
        end
        local ok, errOrAction = pcall(function()
            -- UpdateMusic's two call sites (sonic.asm:682 VBlank, sonic.asm:1062
            -- HBlank delayed transfer) run at different stack depths, so over a
            -- whole run an invocation can be re-entered before its return is
            -- seen. The shared lifecycle raises on that; here the in-flight
            -- invocation is discarded and the new one opened in its place,
            -- because the discarded track walk never completed and recording a
            -- partial one would be wrong.
            if invocationLifecycle:isActive() and invocationLifecycle:openStackPointer() ~= sp then
                windowAbandoned = windowAbandoned + 1
                invocationLifecycle:close()
                currentOrdinal, currentOpenFrame = nil, nil
                currentStreams, currentDispatches = nil, nil
            end
            local action = invocationLifecycle:entry(sp, frame)
            if action == "open_capture" then beginCapturedInvocation(windowRecordCount, frame) end
            return action
        end)
        if not ok then
            context.log(AudioContract.canonicalJson({
                diag_error = tostring(errOrAction), event = "entry_error", frame = frame, sp = sp
            }))
            error(errOrAction, 0)
        end
    end
})

addHook({
    name = "s1_audio_update_music_return",
    address = UPDATE_MUSIC_RETURN,
    callback = function(context)
        if VALIDATE_ONLY or CAPTURE_DEBUG then
            context.log(AudioContract.canonicalJson({
                active = invocationLifecycle:isActive(), event = "update_return", frame = emu.framecount(),
                open_frame = invocationLifecycle:openEmulatorFrame()
            }))
        end
        local ok, action = pcall(function() return invocationLifecycle:close() end)
        if not ok then
            context.log(AudioContract.canonicalJson({
                diag_error = tostring(action), event = "close_error", frame = emu.framecount()
            }))
            error(action, 0)
        end
        if action == "close_capture" then
            local closeOk, closeErr = pcall(function()
                if VALIDATE_ONLY then finishValidationInvocation(context) else closeCapturedInvocation(context) end
            end)
            if not closeOk then
                context.log(AudioContract.canonicalJson({
                    diag_error = tostring(closeErr), event = "close_capture_error", frame = emu.framecount()
                }))
                error(closeErr, 0)
            end
        end
    end
})

addHook({
    name = "s1_audio_play_sega_abnormal_return",
    address = PLAY_SEGA_RETURN,
    callback = function()
        invocationLifecycle:playSegaAbnormalExit()
    end
})

addHook({
    name = "s1_audio_ghz_epoch",
    address = SOUND_PLAY_BGM,
    callback = function(context)
        local soundId = (emu.getregister("M68K D7") or 0) & 0xFF
        local frame = emu.framecount()
        if VALIDATE_ONLY then
            if not invocationLifecycle:isArmed() then
                local action = invocationLifecycle:acceptBgm(soundId)
                if action == "arm_tick_zero" then
                    validation.epochReached = true
                    context.log(AudioContract.canonicalJson({
                        event = "epoch", frame = emu.framecount(), sound_ram_root = SOUND_RAM
                    }))
                end
            end
            return
        end
        -- Every window opens the same way, the first included: this dispatch
        -- closes whatever window was open and starts one for the song just
        -- requested. Sound_PlayBGM is always reached from inside an UpdateMusic
        -- invocation (UpdateMusic -> CycleSoundQueue -> PlaySoundID), so the
        -- lifecycle stays active across the boundary and only its armed flag
        -- changes; the shared acceptBgm is not used here because it arms only
        -- on GHZ ($81) and raises on any post-epoch BGM, both single-window
        -- rules.
        assert(invocationLifecycle:isActive(),
            "Sound_PlayBGM dispatched outside an UpdateMusic invocation")
        local openInvocationFrame = invocationLifecycle:openEmulatorFrame()
        if windowOrdinal >= 0 then
            assert(windowRecordCount > 0, "music changed before any invocation was captured")
            closeWindow(context, frame)
        else
            runDormantInvocations = invocationLifecycle:launchInvocationCount()
        end
        -- The rest of the in-flight invocation's track walk belongs to the new
        -- song, so the window's tick 0 restarts from this dispatch.
        invocationLifecycle.armed = true
        validation.epochReached = true
        openWindow(soundId, openInvocationFrame)
        beginCapturedInvocation(0, openInvocationFrame)
    end
})

addHook({
    name = "s1_audio_sfx_dispatch",
    address = SOUND_PLAY_SFX,
    callback = function()
        -- The title screen dispatches the level-select chime before the GHZ
        -- capture epoch; pre-epoch dispatches are dormant, like pre-epoch
        -- UpdateMusic invocations.
        if not invocationLifecycle:isArmed() then return end
        local soundId = (emu.getregister("M68K D7") or 0) & 0xFF
        assert(soundId >= 0xA0 and soundId <= 0xCF,
            string.format("Sound_PlaySFX dispatched a non-normal-SFX id $%02X", soundId))
        assert(invocationLifecycle:isActive() and currentDispatches ~= nil,
            "SFX dispatched outside a captured UpdateMusic invocation")
        currentDispatches[#currentDispatches + 1] = soundId
    end
})

addHook({
    name = "s1_audio_special_sfx_dispatch",
    address = SOUND_PLAY_SPECIAL,
    callback = function()
        if not invocationLifecycle:isArmed() then return end
        local soundId = (emu.getregister("M68K D7") or 0) & 0xFF
        -- Shipped FixBugs=0 range check (s1.sounddriver.asm:699-706,
        -- "spec__Last+$10" -- checks $D0-$DF though only $D0 (Waterfall) has
        -- a real Go_SpecSoundIndex entry; anything else here would already
        -- have crashed the ROM before reaching this hook, so the range is
        -- asserted defensively rather than narrowed to $D0 -- see
        -- CLAUDE.md's FixBugs guidance).
        assert(soundId >= 0xD0 and soundId <= 0xDF,
            string.format("Sound_PlaySpecial dispatched a non-special-SFX id $%02X", soundId))
        assert(invocationLifecycle:isActive() and currentDispatches ~= nil,
            "special SFX dispatched outside a captured UpdateMusic invocation")
        -- Recorded into the same flat "dispatches" array as normal SFX: the
        -- id alone already disambiguates special SFX ($D0-$DF, above
        -- Sonic1Sfx.NORMAL_ID_MAX) from normal SFX ($A0-$CF) on the reader
        -- side (Sonic1AudioProfile.isSpecialSfx), so no new schema field is
        -- needed for the replay host to route this through the driver's
        -- special-SFX sequencer mode.
        currentDispatches[#currentDispatches + 1] = soundId
    end
})

-- Registered only under OGGF_AUDIO_CAPTURE_DEBUG, so a production capture
-- carries exactly the hooks it did before and two passes of the same movie
-- cannot differ by the presence of a diagnostic.
if CAPTURE_DEBUG then
    addHook({
        name = "s1_audio_driver_input",
        address = DRIVER_INPUT,
        callback = function()
            driverInputPasses = driverInputPasses + 1
        end
    })
end

addHook({
    name = "s1_audio_fade_in_to_previous",
    address = CF_FADE_IN_TO_PREVIOUS,
    callback = function(context)
        if not invocationLifecycle:isArmed() or VALIDATE_ONLY then return end
        local frame = emu.framecount()
        assert(invocationLifecycle:isActive(),
            "cfFadeInToPrevious ran outside an UpdateMusic invocation")
        assert(previousWindowMusicId ~= nil,
            "a 1-up restore has no song to return to; the run did not start at a BGM dispatch")
        -- Closes the jingle's window and opens one for the song being
        -- restored. The in-flight invocation is dropped for the same reason a
        -- BGM dispatch drops it: the ROM has already replaced the music track
        -- RAM, so the rest of this walk belongs to the restored song. The flag
        -- also tampers with the stack to avoid returning to its caller
        -- (s1.sounddriver.asm:2222-2223), so the walk does not resume where it
        -- left off either.
        assert(windowRecordCount > 0, "1-up restore before any invocation was captured")
        local resumed = previousWindowMusicId
        closeWindow(context, frame)
        openWindow(resumed, frame, "one_up_restore")
        beginCapturedInvocation(0, frame)
    end
})

addHook({
    name = "s1_audio_flag_dispatch",
    address = SOUND_E0_TO_E4,
    callback = function()
        if not invocationLifecycle:isArmed() then return end
        local soundId = (emu.getregister("M68K D7") or 0) & 0xFF
        -- PlaySoundID reaches this branch only for $E0-$E4 (its preceding
        -- "cmpi.b #flg__Last,d7 / bls.s" gate, s1.sounddriver.asm:709-710).
        assert(soundId >= 0xE0 and soundId <= 0xE4,
            string.format("Sound_E0toE4 dispatched a non-flag id $%02X", soundId))
        assert(invocationLifecycle:isActive() and currentDispatches ~= nil,
            "flag command dispatched outside a captured UpdateMusic invocation")
        -- Same flat "dispatches" array as the SFX branches: the id range
        -- already tells a reader which branch a dispatch came from, exactly as
        -- $D0-$DF distinguishes a special SFX from a normal one.
        currentDispatches[#currentDispatches + 1] = soundId
    end
})

for _, site in ipairs(fallbackManifest) do
    addHook({
        name = string.format("s1_audio_pc_manifest_%06x", site.address),
        address = site.address,
        callback = function(context)
            if not invocationLifecycle:isArmed() then return end
            local value = readManifestValue(site)
            local kind, port
            if site.operation == "psg" then
                kind = "psg"
                manifestProof:observePsg(value)
            else
                port = site.operation:match("^fm1") and 1 or 0
                kind = site.operation:match("_address$") and "address" or "data"
                if kind == "address" then
                    manifestProof:observeYmAddress(port, value)
                else
                    validation.pcDataCount = validation.pcDataCount + 1
                    local d0 = (emu.getregister("M68K D0") or 0) & 0xFF
                    local d1 = (emu.getregister("M68K D1") or 0) & 0xFF
                    manifestProof:observeFmDataPc(port, d0, d1)
                    manifestProof:observeYmData(port, value)
                    observeCallbackProof(function() callbackProof:observeFmDataPc(port, d0, d1) end)
                    validation.lastDataPc = {
                        d0 = d0, d1 = d1, frame = emu.framecount(), pc = site.address
                    }
                    if VALIDATE_ONLY then
                        context.log(AudioContract.canonicalJson({
                            event = "fm_data_pc", observed = validation.lastDataPc
                        }))
                    end
                end
            end
            local busEvent = {kind = kind, port = port, value = value}
            recordBusEvent("pc_manifest", {
                kind = kind, pc = site.address, port = port, source = "pc_manifest", value = value
            }, busEvent)
        end
    })
end

for address, operation in pairs(callbackAddresses) do
    addHook({
        name = string.format("s1_audio_callback_validation_write_%06x", address),
        address = address,
        kind = "write",
        callback = function(context, callbackAddress, value, flags, ...)
            if not invocationLifecycle:isArmed() then return end
            if selectedSource == "pc_manifest" or callbackInvalidReason then return end
            if select("#", ...) ~= 0 or callbackAddress ~= address
                    or type(value) ~= "number" or value ~= math.floor(value)
                    or value < 0 or value > 0xFF or type(flags) ~= "number"
                    or flags ~= math.floor(flags) or flags < 0 then
                invalidateCallback("BizHawk audio write callback arguments are malformed")
                return
            end
            validation.callbackCount = validation.callbackCount + 1
            local port = operation:match("port1") and 1 or 0
            if address == 0xC00011 then
                observeCallbackProof(function() callbackProof:observePsg(value) end)
            elseif operation:match("_address$") then
                observeCallbackProof(function() callbackProof:observeYmAddress(port, value) end)
            else
                observeCallbackProof(function() callbackProof:observeYmData(port, value) end)
            end
            if VALIDATE_ONLY then
                context.log(AudioContract.canonicalJson({
                    arguments = callbackArguments(callbackAddress, value, flags),
                    d0 = (emu.getregister("M68K D0") or 0) & 0xFF,
                    d1 = (emu.getregister("M68K D1") or 0) & 0xFF,
                    event = "write_callback",
                    frame = emu.framecount(),
                    operation = operation,
                    pc = (emu.getregister("M68K PC") or 0) & 0xFFFFFF,
                    preceding_data_pc = validation.lastDataPc
                }))
            else
                local busEvent
                if address == 0xC00011 then
                    busEvent = {kind = "psg", value = value}
                else
                    busEvent = {
                        kind = operation:match("_address$") and "address" or "data",
                        port = port,
                        value = value
                    }
                end
                local stream = currentStreams and currentStreams.memory_callback
                if CAPTURE_DEBUG and stream and #stream.raw < 8 then
                    context.log(AudioContract.canonicalJson({
                        address = callbackAddress, kind = busEvent.kind, port = busEvent.port,
                        type = "debug_bus", value = value
                    }))
                end
                recordBusEvent("memory_callback", {
                    address = callbackAddress,
                    flags = flags,
                    kind = busEvent.kind,
                    port = busEvent.port,
                    source = "memory_callback",
                    value = value
                }, busEvent)
            end
        end
    })
end

assertMemoryDomains()
verifyFallbackManifest()
verifyOpcodeSites(dispatchManifest, "dispatch")
romIdentity, movieIdentity = verifyIdentity()

ProbeRuntime.run({
    stage = function() return true end,
    continueAfterMovie = true,
    hooks = hooks,
    onFrame = function(context)
        if VALIDATE_ONLY and not validation.identityLogged then
            validation.identityLogged = true
            local header = movie.getheader()
            context.log(AudioContract.canonicalJson({
                event = "identity_api",
                movie_core = header.Core,
                movie_emu_version = header.emuVersion,
                movie_game_name = header.GameName,
                movie_length = movie.length(),
                movie_opaque_sha1 = header.SHA1,
                rom_hash = gameinfo.getromhash(),
                rom_name = gameinfo.getromname()
            }))
        end
        if context.movieFinished() then
            local function requireNeutral(player, controls)
                for control, pressed in pairs(controls) do
                    assert(not pressed, string.format("post-movie player %d input is not neutral: %s", player, control))
                end
            end
            requireNeutral(1, joypad.get(1))
            requireNeutral(2, joypad.get(2))
        end
        if VALIDATE_ONLY and emu.framecount() > 1100 then
            error("callback validation did not collect the required GHZ initialization window")
        end
        -- The run ends at movie end, not a frame budget: the windows tile the
        -- whole movie. The final window is closed between invocations, never
        -- mid-tick, matching every other window boundary.
        if not VALIDATE_ONLY and not emitted
                and (context.movieFinished() or #windowManifest >= MAX_WINDOWS)
                and not invocationLifecycle:isActive() then
            emitted = true
            if windowOrdinal >= 0 then
                assert(windowRecordCount > 0, "final window closed with no captured invocations")
                closeWindow(context, emu.framecount())
            end
            emitRunSummary(context)
        end
    end
})
