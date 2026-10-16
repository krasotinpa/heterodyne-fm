/*
 * Isochronous IN reader over usbfs for the Module's audio (IF 1 alt 1, EP 0x81), see docs/adr/0001-audio-path.md.
 * Java opens the device, claims IF 1 and selects alt 1; this code only submits and reaps iso URBs on that fd.
 * ponytail: one device at a time (static state); a second open() fails with -EBUSY.
 */
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

#define EP 0x81
#define PKT 312   /* wMaxPacketSize */
#define NPKT 8    /* 1 ms packets per URB */
#define NURB 32   /* 256 ms in flight */

static int fd_ = -1, inflight;
static struct usbdevfs_urb *urbs[NURB];
static long bad;

static int submit(struct usbdevfs_urb *u) {
    memset(u, 0, sizeof *u);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = EP;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = (unsigned char *)u + sizeof *u + NPKT * sizeof(struct usbdevfs_iso_packet_desc);
    u->buffer_length = PKT * NPKT;
    u->number_of_packets = NPKT;
    for (int i = 0; i < NPKT; i++) u->iso_frame_desc[i].length = PKT;
    if (ioctl(fd_, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    inflight++;
    return 0;
}

JNIEXPORT jint JNICALL Java_fm_heterodyne_app_audio_UsbIso_open(JNIEnv *env, jclass c, jint fd) {
    (void)env; (void)c;
    if (fd_ >= 0) return -EBUSY;
    fd_ = fd;
    bad = 0;
    for (int i = 0; i < NURB; i++) {
        urbs[i] = calloc(1, sizeof(struct usbdevfs_urb) + NPKT * sizeof(struct usbdevfs_iso_packet_desc) + PKT * NPKT);
        if (!urbs[i]) return -ENOMEM;
        int r = submit(urbs[i]);
        if (r) return r;
    }
    return 0;
}

/* Waits up to 100 ms for an URB, copies its good packets, resubmits it. No completion returns 0. */
JNIEXPORT jint JNICALL Java_fm_heterodyne_app_audio_UsbIso_read(JNIEnv *env, jclass c, jbyteArray out) {
    (void)c;
    struct usbdevfs_urb *u;
    if (fd_ < 0) return -EBADF;
    struct pollfd completion = { .fd = fd_, .events = POLLOUT };
    int r = poll(&completion, 1, 100);
    if (r == 0) return 0;
    if (r < 0) return (errno == EINTR || errno == EAGAIN) ? 0 : -errno;
    r = ioctl(fd_, USBDEVFS_REAPURBNDELAY, &u);
    if (r < 0) return (errno == EINTR || errno == EAGAIN) ? 0 : -errno;
    inflight--;
    jint n = 0, cap = (*env)->GetArrayLength(env, out);
    unsigned char *base = u->buffer;
    for (int i = 0; i < u->number_of_packets; i++) {
        struct usbdevfs_iso_packet_desc *p = &u->iso_frame_desc[i];
        if (p->status) { bad++; continue; }
        if (n + (jint)p->actual_length > cap) break;
        (*env)->SetByteArrayRegion(env, out, n, p->actual_length, (jbyte *)base + i * PKT);
        n += p->actual_length;
    }
    r = submit(u);
    return r ? r : n;
}

JNIEXPORT jlong JNICALL Java_fm_heterodyne_app_audio_UsbIso_badPackets(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return bad;
}

/* Discards and reaps every URB; Java then selects alt 0, releases IF 1 and closes the connection. */
JNIEXPORT void JNICALL Java_fm_heterodyne_app_audio_UsbIso_close(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    if (fd_ < 0) return;
    for (int i = 0; i < NURB; i++) if (urbs[i]) ioctl(fd_, USBDEVFS_DISCARDURB, urbs[i]);
    struct usbdevfs_urb *u;
    while (inflight > 0) {
        if (ioctl(fd_, USBDEVFS_REAPURB, &u) == 0) inflight--;
        else if (errno != EINTR) break;   /* ENODEV after unplug: the kernel already dropped the URBs */
    }
    for (int i = 0; i < NURB; i++) { free(urbs[i]); urbs[i] = NULL; }
    inflight = 0;
    fd_ = -1;
}
