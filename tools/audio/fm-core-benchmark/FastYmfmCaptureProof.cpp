#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "ymfm_opn.h"

using Sample = std::array<int, 2>;

struct Event {
    std::uint64_t cycle;
    int port;
    int value;
};

struct Ymfm {
    ymfm::ymfm_interface interface;
    ymfm::ym2612 chip{interface};

    Ymfm() { chip.reset(); }

    Sample frame() {
        ymfm::ym2612::output_data output;
        chip.generate(&output);
        return {output.data[0], output.data[1]};
    }

    void write(int port, int value) { chip.write(port, value); }

    std::vector<std::uint8_t> save() {
        std::vector<std::uint8_t> bytes;
        ymfm::ymfm_saved_state state(bytes, true);
        chip.save_restore(state);
        return bytes;
    }

    void restore(std::vector<std::uint8_t> &bytes) {
        ymfm::ymfm_saved_state state(bytes, false);
        chip.save_restore(state);
    }
};

void reg(Ymfm &chip, int part, int address, int value) {
    chip.write(part * 2, address);
    chip.frame();
    chip.write(part * 2 + 1, value);
    chip.frame();
}

void program(Ymfm &chip) {
    reg(chip, 0, 0x22, 0x08);
    reg(chip, 0, 0x27, 0x00);
    reg(chip, 0, 0x2b, 0x00);
    for (int part = 0; part < 2; part++) {
        for (int channel = 0; channel < 3; channel++) {
            for (int op = 0; op < 4; op++) {
                int offset = channel + op * 4;
                reg(chip, part, 0x30 + offset, 0x71 + op);
                reg(chip, part, 0x40 + offset, op < 2 ? 0x23 : 0x10);
                reg(chip, part, 0x50 + offset, 0x5f);
                reg(chip, part, 0x60 + offset, 0x80);
                reg(chip, part, 0x70 + offset, 0x00);
                reg(chip, part, 0x80 + offset, 0x2a);
                reg(chip, part, 0x90 + offset, 0x00);
            }
            reg(chip, part, 0xb0 + channel, 0x34);
            reg(chip, part, 0xb4 + channel, 0xf3);
            reg(chip, part, 0xa4 + channel, 0x22 + channel);
            reg(chip, part, 0xa0 + channel, 0x69 + channel * 7);
        }
    }
    for (int channel = 0; channel < 6; channel++) {
        reg(chip, 0, 0x28, 0xf0 | (channel < 3 ? channel : channel + 1));
    }
}

std::uint64_t hashInt(std::uint64_t hash, int value) {
    std::uint32_t bits = static_cast<std::uint32_t>(value);
    for (int shift = 0; shift < 32; shift += 8) {
        hash ^= (bits >> shift) & 0xff;
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

std::uint64_t hashSample(std::uint64_t hash, const Sample &sample) {
    return hashInt(hashInt(hash, sample[0]), sample[1]);
}

std::vector<Event> readEvents(const char *path) {
    std::ifstream input(path);
    if (!input) std::exit(3);
    std::vector<Event> events;
    std::string line;
    while (std::getline(input, line)) {
        std::istringstream fields(line);
        Event event{};
        if (!(fields >> event.cycle >> event.port >> event.value) ||
                !fields.eof()) {
            std::exit(4);
        }
        events.push_back(event);
    }
    return events;
}

struct Replay {
    std::uint64_t checksum;
    std::uint64_t frames;
};

Replay replay(const std::vector<Event> &events, std::uint64_t terminal) {
    Ymfm chip;
    std::uint64_t nextFrameCycle = 24;
    std::uint64_t frames = 0;
    std::uint64_t hash = UINT64_C(14695981039346656037);
    auto advance = [&](std::uint64_t target) {
        while (nextFrameCycle <= target) {
            hash = hashSample(hash, chip.frame());
            frames++;
            nextFrameCycle += 24;
        }
    };
    for (const Event &event : events) {
        advance(event.cycle);
        chip.write(event.port, event.value);
    }
    advance(terminal);
    return {hash, frames};
}

std::vector<Sample> samples(Ymfm &chip, int frames) {
    std::vector<Sample> result;
    result.reserve(frames);
    for (int frame = 0; frame < frames; frame++) result.push_back(chip.frame());
    return result;
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    char *end = nullptr;
    std::uint64_t terminal = std::strtoull(argv[2], &end, 10);
    if (end == argv[2] || *end != '\0') return 2;
    std::vector<Event> events = readEvents(argv[1]);
    Replay first = replay(events, terminal);
    Replay second = replay(events, terminal);
    if (first.frames != terminal / 24 || first.frames != second.frames) return 5;

    Ymfm validation;
    program(validation);
    for (int frame = 0; frame < 256; frame++) validation.frame();
    auto snapshot = validation.save();
    auto expected = samples(validation, 128);
    validation.restore(snapshot);
    auto actual = samples(validation, 128);

    validation.restore(snapshot);
    reg(validation, 0, 0x28, 0xf0);
    auto control = samples(validation, 128);
    validation.restore(snapshot);
    reg(validation, 0, 0x28, 0x00);
    auto changed = samples(validation, 128);
    int negativeChanges = 0;
    for (std::size_t index = 0; index < control.size(); index++) {
        if (control[index] != changed[index]) negativeChanges++;
    }

    std::printf("{\"implementation\":\"cpp-ymfm\",\"frames\":%" PRIu64
                ",\"checksum\":%" PRIu64 ",\"deterministic\":%s,"
                "\"snapshot_errors\":%d,\"negative_control_changed_frames\":%d,"
                "\"subframe_mapping\":\"writes-at-cycles-24n-through-24n+23-before-ymfm-frame-n\","
                "\"fidelity_equivalent\":false}%c",
                first.frames, first.checksum,
                first.checksum == second.checksum ? "true" : "false",
                expected == actual ? 0 : 1, negativeChanges, '\n');
    return first.checksum == second.checksum && expected == actual && negativeChanges != 0 ? 0 : 6;
}
