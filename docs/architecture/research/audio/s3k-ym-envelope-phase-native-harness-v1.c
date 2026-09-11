/*
 * Compact native lab harness for s3k-ym-envelope-phase-oracle-v1.json.
 * Compile against pinned GPGX 051d430d3d1b54625f9900c8f152d7f232e06daf:
 * gcc -std=c11 -O2 -I/path/to/Genesis-Plus-GX/core \
 *   -I/path/to/Genesis-Plus-GX/core/sound \
 *   s3k-ym-envelope-phase-native-harness-v1.c -lm -o envelope-harness
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define uint8 unsigned char
#define uint16 unsigned short
#define uint32 unsigned int
#define int8 signed char
#define int16 signed short
#define int32 signed int
typedef uint8_t UINT8;
typedef uint16_t UINT16;
typedef uint32_t UINT32;
typedef int8_t INT8;
typedef int16_t INT16;
typedef int32_t INT32;
#define INLINE static __inline__
#define LSB_FIRST 1
enum {
    YM2612_DISCRETE = 0,
    YM2612_INTEGRATED,
    YM2612_ENHANCED
};
#define save_param(param, size) do { \
    memcpy(&state[bufferptr], param, size); \
    bufferptr += (size); \
} while (0)
#define load_param(param, size) do { \
    memcpy(param, &state[bufferptr], size); \
    bufferptr += (size); \
} while (0)
#define _SHARED_H_

void gpgx_audio_trace_fm_channel_sample(
        unsigned int channel, signed int sample) {
    (void) channel;
    (void) sample;
}
#include "ym2612.c"

typedef struct {
    int sample;
    int port;
    int reg;
    int value;
} Write;

static const Write GROUP[] = {
    {0,1,129,255},{3,1,133,255},{6,1,137,255},{9,1,141,255},
    {15,1,181,192},{19,1,177,5},{22,1,49,7},{26,1,57,18},
    {29,1,53,34},{33,1,61,50},{37,1,81,10},{40,1,89,15},
    {44,1,85,15},{47,1,93,15},{51,1,97,0},{54,1,105,0},
    {58,1,101,0},{61,1,109,0},{65,1,113,0},{68,1,121,16},
    {72,1,117,16},{75,1,125,16},{79,1,129,15},{83,1,137,15},
    {86,1,133,15},{90,1,141,15},{95,1,65,33},{99,1,73,5},
    {102,1,69,5},{106,1,77,5},{114,0,40,5},{144,1,165,35},
    {147,1,161,63},{150,0,40,245}
};
static const int STORED_OPERATOR_ORDER[] = {0, 2, 1, 3};

static void update(int samples) {
    int buffer[2];
    while (samples-- > 0) YM2612Update(buffer, 1);
}

static void write_reg(int port, int reg, int value) {
    YM2612Write(port ? 2 : 0, reg);
    YM2612Write(port ? 3 : 1, value);
}

static void prepare_seed_voice(void) {
    const int offsets[] = {0, 4, 8, 12};
    for (int op = 0; op < 4; op++) {
        write_reg(1, 0x31 + offsets[op], 0x01);
        write_reg(1, 0x41 + offsets[op], 0x00);
        write_reg(1, 0x51 + offsets[op], 0x1A);
        write_reg(1, 0x61 + offsets[op], 0x1F);
        write_reg(1, 0x71 + offsets[op], 0x08);
        write_reg(1, 0x81 + offsets[op], 0x4F);
    }
    write_reg(1, 0xB1, 0x07);
    write_reg(1, 0xB5, 0xC0);
    write_reg(1, 0xA5, 0x23);
    write_reg(1, 0xA1, 0x3F);
    write_reg(0, 0x28, 0xF5);
}

static void replay(int timed, int *attenuation) {
    int frontier = 0;
    for (unsigned i = 0; i < sizeof(GROUP) / sizeof(GROUP[0]); i++) {
        int due = timed ? GROUP[i].sample : 0;
        update(due - frontier);
        frontier = due;
        if (i == 33) {
            for (int op = 0; op < 4; op++) {
                attenuation[op] = ym2612.CH[4].SLOT[op].volume;
            }
        }
        write_reg(GROUP[i].port, GROUP[i].reg, GROUP[i].value);
    }
    update(200 - frontier);
}

static void print_vector(const char *name, int samples, int release) {
    prepare_seed_voice();
    update(samples);
    if (release) {
        write_reg(0, 0x28, 0x05);
        update(24);
    }
    YM2612 seed = ym2612;
    printf("%s phase", name);
    for (int op = 0; op < 4; op++) {
        printf(" %u", seed.CH[4].SLOT[STORED_OPERATOR_ORDER[op]].state);
    }
    printf(" volume");
    for (int op = 0; op < 4; op++) {
        printf(" %d", seed.CH[4].SLOT[STORED_OPERATOR_ORDER[op]].volume);
    }
    int atomic[4];
    replay(0, atomic);
    ym2612 = seed;
    int timed[4];
    replay(1, timed);
    printf(" atomic");
    for (int op = 0; op < 4; op++) printf(" %d", atomic[STORED_OPERATOR_ORDER[op]]);
    printf(" timed");
    for (int op = 0; op < 4; op++) printf(" %d", timed[STORED_OPERATOR_ORDER[op]]);
    int minimum[4] = {1023, 1023, 1023, 1023};
    int maximum[4] = {0, 0, 0, 0};
    for (int counter = 1; counter <= 32; counter++) {
        for (int timer = 0; timer < 3; timer++) {
            ym2612 = seed;
            ym2612.OPN.eg_cnt = counter;
            ym2612.OPN.eg_timer = timer;
            int aligned[4];
            replay(1, aligned);
            for (int op = 0; op < 4; op++) {
                if (aligned[op] < minimum[op]) minimum[op] = aligned[op];
                if (aligned[op] > maximum[op]) maximum[op] = aligned[op];
            }
        }
    }
    printf(" window-min");
    for (int op = 0; op < 4; op++) printf(" %d", minimum[STORED_OPERATOR_ORDER[op]]);
    printf(" window-max");
    for (int op = 0; op < 4; op++) printf(" %d", maximum[STORED_OPERATOR_ORDER[op]]);
    printf("\n");
}

static void replay_overlap(int timed, int *attenuation) {
    int first = 0;
    int second = 0;
    int frontier = 0;
    while (first < 34 || second < 34) {
        int firstDue = first < 34 ? (timed ? GROUP[first].sample : 0)
                : 1000000;
        int secondDue = second < 34
                ? (timed ? 32 + GROUP[second].sample : 0) : 1000000;
        int useFirst = firstDue <= secondDue;
        int due = useFirst ? firstDue : secondDue;
        update(due - frontier);
        frontier = due;
        int index = useFirst ? first++ : second++;
        if (!useFirst && index == 33) {
            for (int op = 0; op < 4; op++) {
                attenuation[op] = ym2612.CH[4].SLOT[op].volume;
            }
        }
        write_reg(GROUP[index].port, GROUP[index].reg,
                GROUP[index].value);
    }
    update(350 - frontier);
}

static void print_overlap(void) {
    prepare_seed_voice();
    update(96);
    YM2612 seed = ym2612;
    int atomic[4];
    replay_overlap(0, atomic);
    ym2612 = seed;
    int timed[4];
    replay_overlap(1, timed);
    printf("overlap phase");
    for (int op = 0; op < 4; op++) {
        printf(" %u", seed.CH[4].SLOT[STORED_OPERATOR_ORDER[op]].state);
    }
    printf(" volume");
    for (int op = 0; op < 4; op++) {
        printf(" %d", seed.CH[4].SLOT[STORED_OPERATOR_ORDER[op]].volume);
    }
    printf(" atomic");
    for (int op = 0; op < 4; op++) printf(" %d", atomic[STORED_OPERATOR_ORDER[op]]);
    printf(" timed");
    for (int op = 0; op < 4; op++) printf(" %d", timed[STORED_OPERATOR_ORDER[op]]);
    int minimum[4] = {1023, 1023, 1023, 1023};
    int maximum[4] = {0, 0, 0, 0};
    for (int counter = 1; counter <= 32; counter++) {
        for (int timer = 0; timer < 3; timer++) {
            ym2612 = seed;
            ym2612.OPN.eg_cnt = counter;
            ym2612.OPN.eg_timer = timer;
            int aligned[4];
            replay_overlap(1, aligned);
            for (int op = 0; op < 4; op++) {
                if (aligned[op] < minimum[op]) minimum[op] = aligned[op];
                if (aligned[op] > maximum[op]) maximum[op] = aligned[op];
            }
        }
    }
    printf(" window-min");
    for (int op = 0; op < 4; op++) printf(" %d", minimum[STORED_OPERATOR_ORDER[op]]);
    printf(" window-max");
    for (int op = 0; op < 4; op++) printf(" %d", maximum[STORED_OPERATOR_ORDER[op]]);
    printf("\n");
}

int main(void) {
    YM2612Init();
    YM2612Config(YM2612_ENHANCED);
    YM2612ResetChip();
    YM2612 pristine = ym2612;
    print_vector("attack", 16, 0);
    ym2612 = pristine;
    print_vector("decay", 96, 0);
    ym2612 = pristine;
    print_vector("sustain", 256, 0);
    ym2612 = pristine;
    print_vector("near-release", 256, 1);
    ym2612 = pristine;
    print_overlap();
    return 0;
}
