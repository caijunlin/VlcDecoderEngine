#include <jni.h>
#include <android/log.h>
#include <vlc/vlc.h>
#include <vector>
#include <cstring>
#include <mutex>
#include <map>
#include <string>

#define TAG "VLC_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;

// 播放器上下文 (对应一个 URL)
struct PlayerContext {
    jobject frameHub = nullptr;        // 对应 Java 的 FrameHub
    jmethodID onFrameMethod = nullptr; // 回调方法 ID
    libvlc_media_player_t *mp = nullptr;
    std::vector<uint8_t> buffer;
    int actualWidth = 0;
    int actualHeight = 0;
};

// 全局上下文 (持有 LibVLC 和所有播放器)
struct GlobalContext {
    libvlc_instance_t *vlc = nullptr;
    // Map: URLString -> PlayerContext*
    std::map<std::string, PlayerContext *> players;
    std::mutex mutex; // 保护 players 集合的操作安全
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    return JNI_VERSION_1_6;
}

static JNIEnv *getJNIEnv(bool *needsDetach) {
    JNIEnv *env = nullptr;
    int status = g_vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (status == JNI_OK) {
        *needsDetach = false;
        return env;
    }
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *needsDetach = true;
            return env;
        }
    }
    return nullptr;
}

static unsigned format_setup_cb(void **opaque, char *chroma,
                                unsigned *width, unsigned *height,
                                unsigned *pitches, unsigned *lines) {
    auto *ctx = static_cast<PlayerContext *>(*opaque);
    memcpy(chroma, "RV16", 4);

    if (ctx->actualWidth > 0 && ctx->actualHeight > 0) {
        *width = ctx->actualWidth;
        *height = ctx->actualHeight;
    } else {
        ctx->actualWidth = *width;
        ctx->actualHeight = *height;
    }

    *pitches = (*width) * 2;
    *lines = *height;
    size_t size = (*pitches) * (*lines);
    try { ctx->buffer.resize(size); } catch (...) { return 0; }
    return 1;
}

static void *lock_cb(void *opaque, void **planes) {
    auto *ctx = static_cast<PlayerContext *>(opaque);
    planes[0] = ctx->buffer.data();
    return ctx;
}

static void unlock_cb(void *opaque, void *picture, void *const *planes) {}

static void display_cb(void *opaque, void *picture) {
    auto *ctx = static_cast<PlayerContext *>(opaque);
    if (!ctx || !ctx->frameHub) return;

    bool needsDetach = false;
    JNIEnv *env = getJNIEnv(&needsDetach);
    if (!env) return;

    if (ctx->onFrameMethod) {
        jobject buf = env->NewDirectByteBuffer(ctx->buffer.data(), ctx->buffer.size());
        if (buf) {
            env->CallVoidMethod(ctx->frameHub, ctx->onFrameMethod, buf, ctx->actualWidth,
                                ctx->actualHeight);
            env->DeleteLocalRef(buf);
        }
    }
    if (needsDetach) g_vm->DetachCurrentThread();
}

// 销毁单个 PlayerContext 的资源
static void destroyPlayerContext(JNIEnv *env, PlayerContext *ctx) {
    if (!ctx) return;
    if (ctx->mp) {
        libvlc_media_player_stop(ctx->mp);
        libvlc_media_player_release(ctx->mp);
        ctx->mp = nullptr;
    }
    if (ctx->frameHub) {
        env->DeleteGlobalRef(ctx->frameHub);
        ctx->frameHub = nullptr;
    }
    delete ctx;
}

// --- JNI 接口实现 ---

// 1. 初始化全局 LibVLC
extern "C" JNIEXPORT jlong JNICALL
Java_com_caijunlin_vlcdecoder_VlcBridge_nativeCreateVLC(JNIEnv *env, jobject thiz, jobjectArray args) {
    auto *globalCtx = new GlobalContext();

    // 解析全局参数
    int argc = env->GetArrayLength(args);
    std::vector<const char *> argv;
    std::vector<jstring> tempStrings;
    for (int i = 0; i < argc; ++i) {
        auto string = (jstring) env->GetObjectArrayElement(args, i);
        const char *rawString = env->GetStringUTFChars(string, 0);
        argv.push_back(rawString);
        tempStrings.push_back(string);
    }
    argv.push_back(nullptr);

    globalCtx->vlc = libvlc_new(argc, argv.data());

    for (size_t i = 0; i < tempStrings.size(); ++i) {
        env->ReleaseStringUTFChars(tempStrings[i], argv[i]);
        env->DeleteLocalRef(tempStrings[i]);
    }

    if (!globalCtx->vlc) {
        delete globalCtx;
        return 0;
    }

    LOGD("Global LibVLC Initialized. Handle: %p", globalCtx);
    return reinterpret_cast<jlong>(globalCtx);
}

// 2. 释放全局资源
extern "C" JNIEXPORT void JNICALL
Java_com_caijunlin_vlcdecoder_VlcBridge_nativeReleaseVLC(JNIEnv *env, jobject thiz, jlong handle) {
    auto *globalCtx = reinterpret_cast<GlobalContext *>(handle);
    if (!globalCtx) return;

    std::lock_guard<std::mutex> lock(globalCtx->mutex);

    // 释放所有正在播放的流
    for (auto const &[url, playerCtx]: globalCtx->players) {
        LOGD("Stopping stream: %s", url.c_str());
        destroyPlayerContext(env, playerCtx);
    }
    globalCtx->players.clear();

    // 释放 LibVLC
    if (globalCtx->vlc) {
        libvlc_release(globalCtx->vlc);
        globalCtx->vlc = nullptr;
    }

    delete globalCtx;
    LOGD("Global LibVLC Released.");
}

// 4. 开启指定 URL 的播流
extern "C" JNIEXPORT jboolean JNICALL
Java_com_caijunlin_vlcdecoder_VlcBridge_nativeStart(
        JNIEnv *env, jobject thiz,
        jlong handle,
        jstring url_,
        jobject hub,
        jint width, jint height,
        jobjectArray args) {

    auto *globalCtx = reinterpret_cast<GlobalContext *>(handle);
    // 检查 Handle 有效性
    if (!globalCtx || !globalCtx->vlc) {
        LOGE("nativeStart failed: LibVLC not initialized!");
        return false;
    }

    const char *url = env->GetStringUTFChars(url_, 0);
    std::string urlStr(url);

    std::lock_guard<std::mutex> lock(globalCtx->mutex);

    // 如果该 URL 已存在，先销毁旧的 (为了应用新的参数或Hub)
    if (globalCtx->players.find(urlStr) != globalCtx->players.end()) {
        LOGD("Restarting existing stream: %s", url);
        destroyPlayerContext(env, globalCtx->players[urlStr]);
        globalCtx->players.erase(urlStr);
    }

    // 创建新的播放器上下文
    auto *ctx = new PlayerContext();
    ctx->actualWidth = width;
    ctx->actualHeight = height;
    ctx->frameHub = env->NewGlobalRef(hub); // 持有 FrameHub 引用
    jclass clazz = env->GetObjectClass(hub);
    ctx->onFrameMethod = env->GetMethodID(clazz, "onRawFrame", "(Ljava/nio/ByteBuffer;II)V");

    // 创建 Media
    libvlc_media_t *media = libvlc_media_new_location(globalCtx->vlc, url);
    if (!media) {
        LOGE("Failed to create media for: %s", url);
        delete ctx;
        env->ReleaseStringUTFChars(url_, url);
        return false;
    }

    // 应用 Media 参数
    if (args != nullptr) {
        int count = env->GetArrayLength(args);
        for (int i = 0; i < count; ++i) {
            auto optStr = (jstring) env->GetObjectArrayElement(args, i);
            const char *opt = env->GetStringUTFChars(optStr, 0);
            libvlc_media_add_option(media, opt);
            env->ReleaseStringUTFChars(optStr, opt);
            env->DeleteLocalRef(optStr);
        }
    }

    ctx->mp = libvlc_media_player_new_from_media(media);
    libvlc_media_release(media);

    // 设置回调
    libvlc_video_set_callbacks(ctx->mp, lock_cb, unlock_cb, display_cb, ctx);
    libvlc_video_set_format_callbacks(ctx->mp, format_setup_cb, nullptr);

    // 启动
    if (libvlc_media_player_play(ctx->mp) != 0) {
        LOGE("Failed to play: %s", url);
        destroyPlayerContext(env, ctx);
        env->ReleaseStringUTFChars(url_, url);
        return false;
    }

    // 存入 Map
    globalCtx->players[urlStr] = ctx;

    LOGD("Stream started: %s", url);
    env->ReleaseStringUTFChars(url_, url);
    return true;
}

// 5. 停止并释放指定 URL 的流
extern "C" JNIEXPORT jboolean JNICALL
Java_com_caijunlin_vlcdecoder_VlcBridge_nativeReleaseMedia(
        JNIEnv *env, jobject thiz,
        jlong handle,
        jstring url_) {

    auto *globalCtx = reinterpret_cast<GlobalContext *>(handle);
    if (!globalCtx || !globalCtx->vlc) return false;

    const char *url = env->GetStringUTFChars(url_, 0);
    std::string urlStr(url);

    std::lock_guard<std::mutex> lock(globalCtx->mutex);

    auto it = globalCtx->players.find(urlStr);
    if (it != globalCtx->players.end()) {
        // 找到了，执行销毁
        destroyPlayerContext(env, it->second);
        globalCtx->players.erase(it);
        LOGD("Stream released: %s", url);
        env->ReleaseStringUTFChars(url_, url);
        return true;
    }

    LOGD("Stream not found to release: %s", url);
    env->ReleaseStringUTFChars(url_, url);
    return false;
}