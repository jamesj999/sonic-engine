-- Read-only Sonic 1 REV01 BGM-window survey.
--
-- Purpose: enumerate the ROM-defined per-song windows of a complete-run movie
-- before any full driver capture is attempted. A window opens at a
-- Sound_PlayBGM dispatch (s1.sounddriver.asm:1498-1502, which reloads the
-- driver's music track RAM through InitMusicPlayback) and closes at the next
-- one, so the windows tile the movie from its first BGM request to its end.
-- This probe records, per window, the music id, the opening emulator frame,
-- and the number of UpdateMusic invocations it contains -- which is exactly
-- the tick count the per-song driver capture would record for it.
--
-- It hooks only Sound_PlayBGM and the UpdateMusic entry/return pair. It reads
-- no driver RAM, decodes no bus writes, and registers none of the twenty
-- PC-manifest write sites the driver probes use, so it runs the whole movie in
-- a fraction of the time a driver capture would and produces a few hundred
-- lines instead of gigabytes. Its output is a capture *plan*, never parity
-- data: nothing it emits is compared against the engine.
--
-- Run through run_bizhawk_lua.sh by path, like the driver probes.

local runtimePath = assert(os.getenv("OGGF_BIZHAWK_PROBE_RUNTIME"),
    "run through run_bizhawk_lua so OGGF_BIZHAWK_PROBE_RUNTIME is absolute")
runtimePath = runtimePath:gsub("\\", "/")
local ProbeRuntime = dofile(runtimePath)
local contractPath = ProbeRuntime.siblingPath(runtimePath, "audio/s1_audio_parity_contract.lua")
local AudioContract = dofile(contractPath)

local GAME_MODE = 0xF600
local UPDATE_MUSIC = 0x71B4C
local UPDATE_MUSIC_RETURN = 0x71C4C
local SOUND_PLAY_BGM = 0x71FD2
local SOUND_PLAY_SFX = 0x721C6
local SOUND_PLAY_SPECIAL = 0x7230C

-- Music pointer table and count, mirroring Sonic1SmpsConstants
-- (MUSIC_PTR_TABLE_ADDR 0x071A9C, MUSIC_COUNT 19) and Sonic1Music
-- (ID_BASE 0x81, ID_MAX 0x93). The asset range for a song is derived the same
-- way Sonic1SmpsLoader.calculateMusicDataSize does: the next table entry when
-- it lies within a plausible blob, otherwise the nearest higher entry.
local MUSIC_PTR_TABLE_ADDR = 0x071A9C
local MUSIC_COUNT = 19
local MUSIC_ID_BASE = 0x81
local MAX_BLOB_SIZE = 0x4000

local EXPECTED_ROM_SHA1 = "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b"

local function readCartU32(address)
    return ((memory.read_u8(address, "MD CART") << 24)
        | (memory.read_u8(address + 1, "MD CART") << 16)
        | (memory.read_u8(address + 2, "MD CART") << 8)
        | memory.read_u8(address + 3, "MD CART")) & 0xFFFFFFFF
end

local function musicAssetRange(musicId)
    local index = musicId - MUSIC_ID_BASE
    if index < 0 or index >= MUSIC_COUNT then return nil end
    local base = readCartU32(MUSIC_PTR_TABLE_ADDR + index * 4)
    local bound = base + MAX_BLOB_SIZE
    if index < MUSIC_COUNT - 1 then
        local next_ = readCartU32(MUSIC_PTR_TABLE_ADDR + (index + 1) * 4)
        if next_ > base and next_ < base + MAX_BLOB_SIZE then
            return base, next_
        end
    end
    for probe = index + 1, MUSIC_COUNT - 1 do
        local candidate = readCartU32(MUSIC_PTR_TABLE_ADDR + probe * 4)
        if candidate > base and candidate < bound then
            bound = candidate
            break
        end
    end
    return base, bound
end

local function assertMemoryDomains()
    local found = {}
    for _, name in pairs(memory.getmemorydomainlist()) do found[tostring(name)] = true end
    for _, required in ipairs({"MD CART", "68K RAM"}) do
        assert(found[required], "GPGX core must expose the " .. required .. " memory domain")
    end
end

assertMemoryDomains()
assert(memory.getmemorydomainsize("MD CART") == 524288,
    "S1 REV01 ROM must be exactly 524,288 bytes")
assert(gameinfo.getromname() == "Sonic The Hedgehog (W) (REV01) [!]",
    "BizHawk ROM name does not identify S1 World REV01")
assert(movie.isloaded(), "a complete-run BK2 must be loaded")
local movieSha256 = assert(os.getenv("OGGF_BIZHAWK_MOVIE_SHA256"),
    "run_bizhawk_lua must supply the actual BK2 SHA-256"):lower()

-- Emitted once up front so the plan file is self-describing even if the run is
-- cut short: every music id's ROM asset range, derived from the loaded cart.
local ranges = {}
for musicId = MUSIC_ID_BASE, MUSIC_ID_BASE + MUSIC_COUNT - 1 do
    local base, bound = musicAssetRange(musicId)
    ranges[string.format("0x%02X", musicId)] = {asset_base = base, asset_end = bound}
end

-- Frames at or near which a re-entered invocation was observed in an earlier
-- survey pass. Diagnostic only: the probe logs entry/return detail around them
-- with the caller's return address, which is what distinguishes UpdateMusic's
-- VBlank call site (sonic.asm:682) from its HBlank delayed-transfer call site
-- (sonic.asm:1062).
local REENTRY_WATCH = {}
for _, centre in ipairs({107741, 187449, 194165}) do
    for offset = -2, 2 do REENTRY_WATCH[centre + offset] = true end
end
local diagnosticContext = nil

local invocations = 0
local active = false
local activeStack = nil
local windows = {}
local current = nil
local dispatchTotal = 0
local emitted = false
local abandoned = {}

-- How many music tracks have the "SFX overriding" bit (PlaybackControl bit 2)
-- set at this instant. A window whose epoch has any is one the replay host
-- cannot describe: the overriding SFX was admitted before the epoch, so it
-- appears nowhere in the window's dispatch record and the replay starts with
-- none held. Recorded so a clean window can be chosen for a song rather than
-- the contamination being met at measurement time. See
-- docs/status/known-discrepancies.md, "S1 Per-Song Run Windows Inherit An SFX
-- Override From Before Their Epoch". Music track RAM is ten slots of $30 bytes
-- from SOUND_RAM + $40, PlaybackControl first.
local function overriddenMusicTracks()
    local count = 0
    for index = 0, 9 do
        local status = mainmemory.read_u8(SOUND_RAM + 0x40 + index * 0x30)
        if (status & 0x80) ~= 0 and (status & 0x04) ~= 0 then
            count = count + 1
        end
    end
    return count
end

local function openWindow(musicId, frame)
    local base, bound = musicAssetRange(musicId)
    current = {
        dispatches = 0,
        invocations = 0,
        epoch_overridden_tracks = overriddenMusicTracks(),
        music_id = musicId,
        open_frame = frame,
        ordinal = #windows,
        -- An id Sound_PlayBGM accepts but the music pointer table does not
        -- cover has no asset range, so it cannot open a single-song window.
        -- Recorded rather than asserted: which ids these are is exactly what
        -- this survey exists to discover.
        resolvable = base ~= nil
    }
    if base then
        current.asset_base = base
        current.asset_end = bound
    end
    windows[#windows + 1] = current
end

local function emitPlan(context)
    if emitted then return end
    emitted = true
    local header = movie.getheader()
    context.log(AudioContract.canonicalJson({
        asset_ranges = ranges,
        abandoned_invocations = abandoned,
        dispatch_total = dispatchTotal,
        dormant_invocations_before_first_window = windows[1] and windows[1].launch or 0,
        final_frame = emu.framecount(),
        movie = {
            archive_sha256 = movieSha256,
            core = header.Core,
            emulator = header.emuVersion,
            game = header.GameName,
            input_rows = movie.length(),
            opaque_header_hash = header.SHA1
        },
        rom_sha1 = EXPECTED_ROM_SHA1,
        schema = "openggf.s1_bgm_window_survey.v1",
        total_invocations = invocations,
        type = "survey_metadata",
        window_count = #windows
    }))
    for _, window in ipairs(windows) do
        window.type = "window"
        context.log(AudioContract.canonicalJson(window))
    end
    context.finish()
end

local hooks = {}

-- probe_runtime's hook wrapper calls finish() (client.exit()) before it
-- re-raises, so a hook error would otherwise leave BizHawk as a clean exit 0
-- with nothing recorded anywhere.
local function recordHookFailure(name, failure)
    local path = os.getenv("OGGF_OUT")
    if not path then return end
    local sidecar = io.open(path .. ".error", "a")
    if not sidecar then return end
    sidecar:write(string.format("hook %s failed at frame %d: %s\n",
        tostring(name), emu.framecount(), tostring(failure)))
    sidecar:close()
end

local function guard(hook)
    local inner = hook.callback
    hook.callback = function(...)
        local ok, failure = pcall(inner, ...)
        if not ok then
            recordHookFailure(hook.name, failure)
            error(failure, 0)
        end
    end
    return hook
end

hooks[#hooks + 1] = guard{
    name = "s1_survey_update_music_entry",
    address = UPDATE_MUSIC,
    callback = function()
        local stack = (emu.getregister("M68K A7") or 0) & 0xFFFFFFFF
        if active then
            if stack ~= activeStack then
                -- The shared parity contract's invocation lifecycle asserts
                -- here. The survey records instead: an UpdateMusic invocation
                -- that never reached its return hook, re-entered on a
                -- different stack, is an abandoned invocation, and enumerating
                -- where those happen across a whole run is part of what this
                -- survey is for. The abandoned invocation is dropped and the
                -- new one opened in its place.
                if diagnosticContext then
                    diagnosticContext.log(AudioContract.canonicalJson({
                        event = "reentry", frame = emu.framecount(),
                        return_address = memory.read_u32_be(stack & 0xFFFF, "68K RAM"),
                        stack = stack, type = "reentry_diagnostic"
                    }))
                end
                abandoned[#abandoned + 1] = {
                    frame = emu.framecount(),
                    game_mode = mainmemory.read_u8(GAME_MODE),
                    new_stack = stack,
                    open_stack = activeStack,
                    window_ordinal = current and current.ordinal or -1
                }
                activeStack = stack
            end
            return
        end
        active = true
        activeStack = stack
        invocations = invocations + 1
        if REENTRY_WATCH[emu.framecount()] and diagnosticContext then
            diagnosticContext.log(AudioContract.canonicalJson({
                event = "entry", frame = emu.framecount(),
                return_address = memory.read_u32_be(stack & 0xFFFF, "68K RAM"),
                stack = stack, type = "reentry_diagnostic"
            }))
        end
        if current then current.invocations = current.invocations + 1 end
    end
}

hooks[#hooks + 1] = guard{
    name = "s1_survey_update_music_return",
    address = UPDATE_MUSIC_RETURN,
    callback = function()
        if REENTRY_WATCH[emu.framecount()] and diagnosticContext then
            diagnosticContext.log(AudioContract.canonicalJson({
                event = "return", frame = emu.framecount(), open_stack = activeStack,
                stack = (emu.getregister("M68K A7") or 0) & 0xFFFFFFFF,
                type = "reentry_diagnostic"
            }))
        end
        active = false
        activeStack = nil
    end
}

hooks[#hooks + 1] = guard{
    name = "s1_survey_play_bgm",
    address = SOUND_PLAY_BGM,
    callback = function()
        local musicId = (emu.getregister("M68K D7") or 0) & 0xFF
        local frame = emu.framecount()
        if not current then
            -- The first BGM request is the first window's epoch; every
            -- UpdateMusic invocation before it is dormant launch play.
            openWindow(musicId, frame)
            current.launch = invocations
            current.game_mode = mainmemory.read_u8(GAME_MODE)
            return
        end
        current.close_frame = frame
        openWindow(musicId, frame)
        current.game_mode = mainmemory.read_u8(GAME_MODE)
    end
}

local function countDispatch()
    if not current then return end
    current.dispatches = current.dispatches + 1
    dispatchTotal = dispatchTotal + 1
end

hooks[#hooks + 1] = guard{
    name = "s1_survey_play_sfx",
    address = SOUND_PLAY_SFX,
    callback = countDispatch
}

hooks[#hooks + 1] = guard{
    name = "s1_survey_play_special",
    address = SOUND_PLAY_SPECIAL,
    callback = countDispatch
}

ProbeRuntime.run({
    stage = function() return true end,
    continueAfterMovie = true,
    hooks = hooks,
    onFrame = function(context)
        diagnosticContext = context
        local ok, failure = pcall(function()
        local frame = emu.framecount()
        if frame % 5000 == 0 or frame < 3 then
            context.log(AudioContract.canonicalJson({
                frame = frame, invocations = invocations, type = "heartbeat",
                window_count = #windows
            }))
        end
        if context.movieFinished() then
            if current then current.close_frame = emu.framecount() end
            emitPlan(context)
        end
        end)
        if not ok then
            recordHookFailure("onFrame", failure)
            error(failure, 0)
        end
    end
})
