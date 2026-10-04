#include <jni.h>
#include <lame.h>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <new>

namespace {
struct Encoder {
    lame_t state;
    int channels;
    bool flushed = false;
    Encoder(lame_t s, int c) : state(s), channels(c) {}
    ~Encoder() { lame_close(state); }
};
std::mutex lock;
std::unordered_map<jlong, std::unique_ptr<Encoder>> encoders;
jlong nextId = 1;
void fail(JNIEnv* env, const char* message) {
    auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}
jlong create(JNIEnv* env, jobject, jint rate, jint outputRate, jint channels, jint bitrate) {
    if (rate < 8000 || rate > 96000 || (outputRate != 32000 && outputRate != 44100 && outputRate != 48000) ||
        (channels != 1 && channels != 2) ||
        (bitrate != 128 && bitrate != 192 && bitrate != 256 && bitrate != 320)) {
        fail(env, "Unsupported MP3 sample rate, channels, or bitrate"); return 0;
    }
    lame_t state = lame_init();
    if (!state) { fail(env, "Could not allocate MP3 encoder"); return 0; }
    if (lame_set_in_samplerate(state, rate) < 0 || lame_set_out_samplerate(state, outputRate) < 0 ||
        lame_set_num_channels(state, channels) < 0 || lame_set_brate(state, bitrate) < 0 ||
        lame_set_VBR(state, vbr_off) < 0 || lame_set_quality(state, 2) < 0 ||
        lame_set_bWriteVbrTag(state, 0) < 0 || lame_init_params(state) < 0) {
        lame_close(state); fail(env, "Could not configure CBR MP3 encoder"); return 0;
    }
    try {
        auto encoder = std::make_unique<Encoder>(state, channels);
        state = nullptr; // encoder owns state, including on map allocation failure.
        std::lock_guard<std::mutex> guard(lock);
        auto id = nextId++;
        encoders.emplace(id, std::move(encoder));
        return id;
    } catch (const std::bad_alloc&) {
        if (state) lame_close(state);
        fail(env, "Could not allocate MP3 encoder state"); return 0;
    }
}
jint encode(JNIEnv* env, jobject, jlong id, jshortArray pcm, jint samples, jbyteArray output) {
    std::lock_guard<std::mutex> guard(lock);
    auto it = encoders.find(id);
    if (it == encoders.end() || it->second->flushed || !pcm || !output || samples < 0 ||
        samples > 8192 || env->GetArrayLength(pcm) < samples * it->second->channels ||
        env->GetArrayLength(output) <
            ((samples * lame_get_out_samplerate(it->second->state) + lame_get_in_samplerate(it->second->state) - 1) /
             lame_get_in_samplerate(it->second->state)) * 5 / 4 + 7200) {
        fail(env, "Invalid MP3 encoder input or closed encoder"); return -1;
    }
    auto input = env->GetShortArrayElements(pcm, nullptr);
    if (!input) return -1;
    auto bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) { env->ReleaseShortArrayElements(pcm, input, JNI_ABORT); return -1; }
    auto& encoder = *it->second;
    int result = encoder.channels == 2
        ? lame_encode_buffer_interleaved(encoder.state, input, samples,
            reinterpret_cast<unsigned char*>(bytes), env->GetArrayLength(output))
        : lame_encode_buffer(encoder.state, input, input, samples,
            reinterpret_cast<unsigned char*>(bytes), env->GetArrayLength(output));
    env->ReleaseShortArrayElements(pcm, input, JNI_ABORT);
    env->ReleaseByteArrayElements(output, bytes, 0);
    if (result < 0) fail(env, "LAME PCM encoding failed");
    return result;
}
jint flush(JNIEnv* env, jobject, jlong id, jbyteArray output) {
    std::lock_guard<std::mutex> guard(lock);
    auto it = encoders.find(id);
    if (it == encoders.end() || it->second->flushed || !output || env->GetArrayLength(output) < 7200) {
        fail(env, "Invalid MP3 flush or closed encoder"); return -1;
    }
    auto bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) return -1;
    int result = lame_encode_flush(it->second->state, reinterpret_cast<unsigned char*>(bytes), env->GetArrayLength(output));
    it->second->flushed = true;
    env->ReleaseByteArrayElements(output, bytes, 0);
    if (result < 0) fail(env, "LAME flush failed");
    return result;
}
void close(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(lock);
    encoders.erase(id); // IDs, not dereferenced pointers: repeated close is harmless.
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto cls = env->FindClass("com/example/simplemediadownloader/Mp3EncoderBridge");
    if (!cls) return JNI_ERR;
    JNINativeMethod methods[] = {
        {const_cast<char*>("nativeCreate"), const_cast<char*>("(IIII)J"), reinterpret_cast<void*>(create)},
        {const_cast<char*>("nativeEncode"), const_cast<char*>("(J[SI[B)I"), reinterpret_cast<void*>(encode)},
        {const_cast<char*>("nativeFlush"), const_cast<char*>("(J[B)I"), reinterpret_cast<void*>(flush)},
        {const_cast<char*>("nativeClose"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(close)},
    };
    return env->RegisterNatives(cls, methods, 4) == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
