// SPDX-License-Identifier: AGPL-3.0-or-later
#include <jni.h>
#include <cstddef>
#include <cstdint>

extern "C" {
struct DoviRpuOpaque;
struct DoviData {
    const uint8_t *data;
    size_t len;
};

DoviRpuOpaque *dovi_parse_unspec62_nalu(const uint8_t *buf, size_t len);
void dovi_rpu_free(DoviRpuOpaque *ptr);
const char *dovi_rpu_get_error(const DoviRpuOpaque *ptr);
int32_t dovi_convert_rpu_with_mode(DoviRpuOpaque *ptr, uint8_t mode);
const DoviData *dovi_write_unspec62_nalu(DoviRpuOpaque *ptr);
void dovi_data_free(const DoviData *data);
}

namespace {
constexpr const char *kVersion = "silo-dovi-1/libdovi-2.3.1";
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_siloserver_silo_common_player_NativeDolbyVisionRpuConverter_nativeConvertToProfile81(
    JNIEnv *env,
    jclass,
    jbyteArray payload) {
    if (payload == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(payload);
    if (length < 2) return nullptr;
    jbyte *bytes = env->GetByteArrayElements(payload, nullptr);
    if (bytes == nullptr) return nullptr;
    DoviRpuOpaque *rpu = dovi_parse_unspec62_nalu(
        reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(payload, bytes, JNI_ABORT);
    if (rpu == nullptr) return nullptr;
    if (dovi_rpu_get_error(rpu) != nullptr || dovi_convert_rpu_with_mode(rpu, 2) != 0) {
        dovi_rpu_free(rpu);
        return nullptr;
    }
    const DoviData *output = dovi_write_unspec62_nalu(rpu);
    if (output == nullptr || output->data == nullptr || output->len == 0 ||
        output->len > static_cast<size_t>(INT32_MAX)) {
        if (output != nullptr) dovi_data_free(output);
        dovi_rpu_free(rpu);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output->len));
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(output->len),
            reinterpret_cast<const jbyte *>(output->data));
    }
    dovi_data_free(output);
    dovi_rpu_free(rpu);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_siloserver_silo_common_player_NativeDolbyVisionRpuConverter_nativeIsReady(
    JNIEnv *,
    jclass) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_siloserver_silo_common_player_NativeDolbyVisionRpuConverter_nativeVersion(
    JNIEnv *env,
    jclass) {
    return env->NewStringUTF(kVersion);
}
