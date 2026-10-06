// JNI bridge between the Link Slicer engine and the Android app (io.github.thelastfrogrammer.elink.NativeSlicer).
// Every call returns or throws: engine errors become java.lang.RuntimeException with the engine's message.
#include <jni.h>

#include <atomic>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "link_slicer.hpp"

namespace {

JavaVM* g_vm = nullptr;

std::string string_of(JNIEnv* env, jstring value)
{
    if (value == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars)
        env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> strings_of(JNIEnv* env, jobjectArray values)
{
    std::vector<std::string> result;
    if (values == nullptr)
        return result;
    const jsize count = env->GetArrayLength(values);
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(string_of(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}

jobjectArray string_array(JNIEnv* env, const std::vector<std::string>& values)
{
    jobjectArray array = env->NewObjectArray(jsize(values.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        jstring item = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(array, jsize(i), item);
        env->DeleteLocalRef(item);
    }
    return array;
}

void throw_java(JNIEnv* env, const char* message)
{
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), message);
}

std::string json_string(const std::string& value)
{
    std::ostringstream out;
    out << '"';
    for (unsigned char c : value) {
        switch (c) {
        case '"': out << "\\\""; break;
        case '\\': out << "\\\\"; break;
        case '\n': out << "\\n"; break;
        case '\r': out << "\\r"; break;
        case '\t': out << "\\t"; break;
        default:
            if (c < 0x20) {
                char buffer[8];
                snprintf(buffer, sizeof buffer, "\\u%04x", c);
                out << buffer;
            } else {
                out << c;
            }
        }
    }
    out << '"';
    return out.str();
}

linkslicer::Engine* engine_of(jlong handle) { return reinterpret_cast<linkslicer::Engine*>(handle); }

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_io_github_thelastfrogrammer_elink_NativeSlicer_create(JNIEnv* env, jclass, jstring resources, jstring work, jstring vendor)
{
    try {
        auto engine = std::make_unique<linkslicer::Engine>(string_of(env, resources), string_of(env, work));
        engine->load_vendor(string_of(env, vendor));
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) {
        throw_java(env, error.what());
        return 0;
    }
}

JNIEXPORT void JNICALL Java_io_github_thelastfrogrammer_elink_NativeSlicer_destroy(JNIEnv*, jclass, jlong handle)
{
    delete engine_of(handle);
}

JNIEXPORT jobjectArray JNICALL Java_io_github_thelastfrogrammer_elink_NativeSlicer_presets(JNIEnv* env, jclass, jlong handle, jint kind, jstring printer)
{
    try {
        auto presetKind = kind == 0 ? linkslicer::PresetKind::Printer : kind == 1 ? linkslicer::PresetKind::Process : linkslicer::PresetKind::Filament;
        return string_array(env, engine_of(handle)->presets(presetKind, string_of(env, printer)));
    } catch (const std::exception& error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

// listener: an object with "boolean progress(int percent, String text)"; returning false cancels the slice.
JNIEXPORT jstring JNICALL Java_io_github_thelastfrogrammer_elink_NativeSlicer_slice(JNIEnv* env, jclass, jlong handle,
    jobjectArray models, jstring printer, jstring process, jobjectArray filaments, jobjectArray keys, jobjectArray values,
    jstring output, jobject listener)
{
    linkslicer::Selection selection;
    selection.printer = string_of(env, printer);
    selection.process = string_of(env, process);
    selection.filaments = strings_of(env, filaments);
    const std::vector<std::string> overrideKeys = strings_of(env, keys), overrideValues = strings_of(env, values);
    for (size_t i = 0; i < overrideKeys.size() && i < overrideValues.size(); ++i)
        selection.overrides.emplace_back(overrideKeys[i], overrideValues[i]);

    std::atomic<bool> cancel{false};
    jobject listenerRef = listener ? env->NewGlobalRef(listener) : nullptr;
    jmethodID progressMethod = nullptr;
    if (listenerRef) {
        jclass listenerClass = env->GetObjectClass(listenerRef);
        progressMethod = env->GetMethodID(listenerClass, "progress", "(ILjava/lang/String;)Z");
        env->DeleteLocalRef(listenerClass);
    }
    // libslic3r may report progress from its worker threads, which have to be attached to the JVM for the call.
    auto progress = [&](int percent, const std::string& text) {
        if (!listenerRef || !progressMethod)
            return;
        JNIEnv* callEnv = nullptr;
        bool attached = false;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&callEnv), JNI_VERSION_1_6) == JNI_EDETACHED) {
#ifdef __ANDROID__
            const jint attach = g_vm->AttachCurrentThread(&callEnv, nullptr);
#else
            const jint attach = g_vm->AttachCurrentThread(reinterpret_cast<void**>(&callEnv), nullptr); // desktop jni.h takes void**
#endif
            if (attach != JNI_OK)
                return;
            attached = true;
        }
        jstring message = callEnv->NewStringUTF(text.c_str());
        jboolean keepGoing = callEnv->CallBooleanMethod(listenerRef, progressMethod, percent, message);
        if (callEnv->ExceptionCheck()) {
            callEnv->ExceptionClear();
            keepGoing = JNI_FALSE;
        }
        callEnv->DeleteLocalRef(message);
        if (!keepGoing)
            cancel = true;
        if (attached)
            g_vm->DetachCurrentThread();
    };

    std::string result;
    std::string failure;
    try {
        linkslicer::Result sliced = engine_of(handle)->slice(strings_of(env, models), selection, string_of(env, output), progress, &cancel);
        std::ostringstream json;
        json << "{\"gcode\":" << json_string(sliced.gcode_path) << ",\"print_time_s\":" << sliced.print_time_s
             << ",\"filament_mm\":" << sliced.filament_mm << ",\"filament_g\":" << sliced.filament_g
             << ",\"filament_cm3\":" << sliced.filament_cm3 << ",\"warnings\":[";
        for (size_t i = 0; i < sliced.warnings.size(); ++i)
            json << (i ? "," : "") << json_string(sliced.warnings[i]);
        json << "]}";
        result = json.str();
    } catch (const std::exception& error) {
        failure = error.what();
    }
    if (listenerRef)
        env->DeleteGlobalRef(listenerRef);
    if (!failure.empty()) {
        throw_java(env, failure.c_str());
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
