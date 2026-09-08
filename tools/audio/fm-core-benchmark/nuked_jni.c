#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ym3438.h"

#define SNAPSHOT_MAGIC UINT32_C(0x4f47464d)
#define SNAPSHOT_VERSION UINT32_C(1)

typedef struct {
    ym3438_t chip;
    int32_t left;
    int32_t right;
    uint32_t cycle_in_frame;
} proof_context;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t size;
    uint32_t reserved;
    proof_context context;
} proof_snapshot;

static void throw_new(JNIEnv *env, const char *class_name, const char *message) {
    jclass type = (*env)->FindClass(env, class_name);
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

static proof_context *context(JNIEnv *env, jlong handle) {
    if (handle == 0) {
        throw_new(env, "java/lang/IllegalStateException", "native handle is closed");
        return NULL;
    }
    return (proof_context *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeCreate(JNIEnv *env, jclass type) {
    (void)type;
    proof_context *value = calloc(1, sizeof(*value));
    if (value == NULL) {
        throw_new(env, "java/lang/OutOfMemoryError", "could not allocate Nuked context");
        return 0;
    }
    OPN2_Reset(&value->chip);
    OPN2_SetChipType(ym3438_mode_ym2612 | ym3438_mode_readmode);
    return (jlong)(intptr_t)value;
}

JNIEXPORT void JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeWrite(
        JNIEnv *env, jclass type, jlong handle, jint port, jint data) {
    (void)type;
    proof_context *value = context(env, handle);
    if (value == NULL) return;
    if (port < 0 || port > 3 || data < 0 || data > 255) {
        throw_new(env, "java/lang/IllegalArgumentException", "invalid raw YM write");
        return;
    }
    OPN2_Write(&value->chip, (Bit32u)port, (Bit8u)data);
}

JNIEXPORT jint JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeClock(
        JNIEnv *env, jclass type, jlong handle, jint cycles,
        jintArray output, jint offset_samples) {
    (void)type;
    proof_context *value = context(env, handle);
    if (value == NULL) return 0;
    if (cycles < 0 || output == NULL || offset_samples < 0 || (offset_samples & 1) != 0) {
        throw_new(env, "java/lang/IllegalArgumentException", "invalid PCM transfer dimensions");
        return 0;
    }
    jlong frames = ((jlong)value->cycle_in_frame + cycles) / 24;
    jlong required = (jlong)offset_samples + frames * 2;
    if (required > (*env)->GetArrayLength(env, output)) {
        throw_new(env, "java/lang/IllegalArgumentException", "PCM output capacity is too small");
        return 0;
    }
    jint *samples = (*env)->GetPrimitiveArrayCritical(env, output, NULL);
    if (samples == NULL) return 0;
    jint position = offset_samples;
    jint produced = 0;
    Bit16s pins[2];
    for (jint index = 0; index < cycles; index++) {
        OPN2_Clock(&value->chip, pins);
        value->left += pins[0];
        value->right += pins[1];
        value->cycle_in_frame++;
        if (value->cycle_in_frame == 24) {
            samples[position++] = value->left;
            samples[position++] = value->right;
            value->left = 0;
            value->right = 0;
            value->cycle_in_frame = 0;
            produced++;
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, output, samples, 0);
    return produced;
}

JNIEXPORT jbyteArray JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeSnapshot(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    proof_context *value = context(env, handle);
    if (value == NULL) return NULL;
    proof_snapshot snapshot;
    memset(&snapshot, 0, sizeof(snapshot));
    snapshot.magic = SNAPSHOT_MAGIC;
    snapshot.version = SNAPSHOT_VERSION;
    snapshot.size = sizeof(snapshot);
    snapshot.context = *value;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)sizeof(snapshot));
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof(snapshot),
                (const jbyte *)&snapshot);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeRestore(
        JNIEnv *env, jclass type, jlong handle, jbyteArray bytes) {
    (void)type;
    proof_context *value = context(env, handle);
    if (value == NULL) return;
    if (bytes == NULL || (*env)->GetArrayLength(env, bytes) != (jsize)sizeof(proof_snapshot)) {
        throw_new(env, "java/lang/IllegalArgumentException", "invalid native snapshot length");
        return;
    }
    proof_snapshot snapshot;
    (*env)->GetByteArrayRegion(env, bytes, 0, (jsize)sizeof(snapshot), (jbyte *)&snapshot);
    if ((*env)->ExceptionCheck(env)) return;
    if (snapshot.magic != SNAPSHOT_MAGIC || snapshot.version != SNAPSHOT_VERSION
            || snapshot.size != sizeof(snapshot) || snapshot.context.cycle_in_frame >= 24) {
        throw_new(env, "java/lang/IllegalArgumentException", "invalid native snapshot header");
        return;
    }
    *value = snapshot.context;
}

JNIEXPORT void JNICALL
Java_com_openggf_tools_audio_benchmark_JniNukedProof_nativeDestroy(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    free((proof_context *)(intptr_t)handle);
}
