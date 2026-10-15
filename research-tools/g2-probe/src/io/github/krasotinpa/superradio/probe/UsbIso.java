package io.github.krasotinpa.superradio.probe;

/** usbfs iso reader for EP 0x81 (jni/usbiso.c). Calls must come from one thread. */
final class UsbIso {
    static { System.loadLibrary("usbiso"); }

    static native int open(int fd);
    static native int read(byte[] out);
    static native long badPackets();
    static native void close();
}
