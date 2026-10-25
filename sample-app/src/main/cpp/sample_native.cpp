#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL
Java_io_vt_sampleapp_NativeBridge_nativeGreeting(JNIEnv *env, jclass) {
    return env->NewStringUTF("native-ok");
}
