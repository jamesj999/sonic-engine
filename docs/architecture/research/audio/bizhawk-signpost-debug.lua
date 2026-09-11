-- BizHawk Lua script to debug Signpost SFX (0xCF) in Sonic 2
-- Load this in BizHawk with Genesis Plus GX core

local log_file = io.open("signpost_ym2612_log.txt", "w")
local logging_enabled = false
local frame_count = 0

-- YM2612 register addresses (directly accessible in some cores)
-- For Genesis Plus GX, we monitor the Z80 RAM sound command area

-- Sound command is written to 0xFFFFF00A (68K) / mirrored areas
local SOUND_CMD_ADDR = 0xFFFFF00A

-- Track when Signpost SFX (0xCF) is triggered
local last_sound_cmd = 0

function log_msg(msg)
    if log_file then
        log_file:write(string.format("[%06d] %s\n", emu.framecount(), msg))
        log_file:flush()
    end
    print(msg)
end

function on_sound_command()
    local cmd = memory.read_u8(SOUND_CMD_ADDR, "68K RAM")
    if cmd ~= last_sound_cmd and cmd ~= 0 then
        log_msg(string.format("Sound command: 0x%02X", cmd))
        last_sound_cmd = cmd

        -- Start detailed logging when Signpost (0xCF) plays
        if cmd == 0xCF then
            log_msg("=== SIGNPOST SFX TRIGGERED ===")
            logging_enabled = true
            frame_count = 0
        end
    end
end

function dump_ym2612_state()
    if not logging_enabled then return end

    frame_count = frame_count + 1

    -- Stop logging after 120 frames (about 2 seconds)
    if frame_count > 120 then
        logging_enabled = false
        log_msg("=== LOGGING STOPPED ===")
        return
    end

    -- Try to read YM2612 state from Genesis Plus GX internal state
    -- This depends on the core exposing these values

    -- For now, just log that we're in the logging window
    if frame_count % 10 == 0 then
        log_msg(string.format("Frame %d of Signpost playback", frame_count))
    end
end

-- Alternative: Monitor writes to YM2612 ports
-- YM2612 is at 0xA04000-0xA04003
function setup_ym2612_logging()
    -- This may not work in all BizHawk versions
    -- event.onmemorywrite(callback, address, size, domain)

    log_msg("Attempting to set up YM2612 write monitoring...")

    -- Try to hook YM2612 address register writes
    local success, err = pcall(function()
        event.onmemorywrite(function(addr, val)
            if logging_enabled then
                log_msg(string.format("YM2612 write: addr=0x%04X", addr))
            end
        end, 0xA04000, "System Bus")
    end)

    if not success then
        log_msg("Could not set up memory write hook: " .. tostring(err))
        log_msg("Will use polling method instead")
    end
end

-- Main frame callback
function on_frame()
    on_sound_command()
    dump_ym2612_state()
end

-- Manual trigger function - call from BizHawk console
function trigger_signpost()
    memory.write_u8(SOUND_CMD_ADDR, 0xCF, "68K RAM")
    log_msg("Manually triggered Signpost SFX")
end

-- Initialize
log_msg("Signpost SFX Debug Script Loaded")
log_msg("Commands:")
log_msg("  trigger_signpost() - Manually trigger the Signpost SFX")
log_msg("  logging_enabled = true/false - Toggle detailed logging")
log_msg("")
log_msg("Waiting for Signpost SFX (0xCF) to play...")

setup_ym2612_logging()

-- Register frame callback
event.onframeend(on_frame)
