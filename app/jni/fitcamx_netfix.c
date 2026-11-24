/*
 * FITCAMX for Gen5W — live555 Interface Override JNI Shim
 *
 * Bypasses live555's GroupsockHelper::ourIPAddress() 5.0-second 228.67.43.91:15947
 * UDP multicast loopback self-test and DNS fallback on Gen5W head units (such as
 * ecohmcse_us Ioniq 6 builds) where tearing down SoftAP Mode 2 blocks multicast
 * loopback on wlan0 even when --rtsp-tcp is used.
 *
 * Build command (reproducible without external NDK sysroot):
 *   clang --target=armv7a-linux-androideabi19 -march=armv7-a -mfloat-abi=softfp -mfpu=neon \
 *     -O2 -fPIC -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables \
 *     -c app/jni/fitcamx_netfix.c -o build/fitcamx_netfix.o
 *   ld.lld -shared --no-rosegment -z now -z max-page-size=4096 --hash-style=both --strip-all \
 *     -soname libfitcamx_netfix.so build/fitcamx_netfix.o \
 *     app/src/main/jniLibs/armeabi-v7a/libvlc.so \
 *     -o app/src/main/jniLibs/armeabi-v7a/libfitcamx_netfix.so
 */

typedef unsigned int uint32_t;
typedef int int32_t;

/* Exported globals in libvlc.so (live555 groupsock/GroupsockHelper.cpp) */
extern volatile uint32_t ReceivingInterfaceAddr;
extern volatile uint32_t SendingInterfaceAddr;

__attribute__((visibility("default")))
int32_t Java_fitcamx_app_Live555Bridge_nativeSetInterfaceAddr(
        void *env, void *clazz, int32_t ipv4NetOrder) {
    (void)env;
    (void)clazz;
    uint32_t addr = (uint32_t)ipv4NetOrder;
    ReceivingInterfaceAddr = addr;
    SendingInterfaceAddr = addr;
    __sync_synchronize();
    return (int32_t)ReceivingInterfaceAddr;
}

__attribute__((visibility("default")))
int32_t Java_fitcamx_app_Live555Bridge_nativeGetInterfaceAddr(
        void *env, void *clazz) {
    (void)env;
    (void)clazz;
    __sync_synchronize();
    return (int32_t)ReceivingInterfaceAddr;
}
