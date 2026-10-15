/*
 * G6a: read the Module's UAC1 iso stream (IF 1 alt 1, EP 0x81) with libusb and save raw PCM.
 * Requests sent: claim IF 1, SET_INTERFACE alt 1, then alt 0 at the end. No class or vendor requests, CDC untouched.
 * Build: gcc -O2 -o iso_capture iso_capture.c -lusb-1.0
 * Run:   sudo ./iso_capture <seconds> <out.pcm>   -> s16le, 2 channels, 48 kHz (per descriptor)
 */
#include <libusb-1.0/libusb.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define VID 0x0483
#define PID 0x5740
#define IFACE 1
#define EP 0x81
#define PKT 312      /* wMaxPacketSize */
#define NPKT 8       /* iso packets (1 ms frames) per transfer */
#define NXFER 8

static FILE *out;
static int running = 1, inflight;
static long packets, bytes, bad_packets, hist[PKT + 1];

static void cb(struct libusb_transfer *t) {
    if (t->status == LIBUSB_TRANSFER_COMPLETED) {
        for (int i = 0; i < t->num_iso_packets; i++) {
            struct libusb_iso_packet_descriptor *p = &t->iso_packet_desc[i];
            packets++;
            if (p->status != LIBUSB_TRANSFER_COMPLETED) { bad_packets++; continue; }
            hist[p->actual_length]++;
            bytes += p->actual_length;
            fwrite(libusb_get_iso_packet_buffer_simple(t, i), 1, p->actual_length, out);
        }
    } else {
        fprintf(stderr, "transfer status %d\n", t->status);
    }
    if (running && libusb_submit_transfer(t) == 0) return;
    inflight--;
}

int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: %s <seconds> <out.pcm>\n", argv[0]); return 2; }
    int seconds = atoi(argv[1]);
    out = fopen(argv[2], "wb");
    if (!out) { perror("fopen"); return 1; }

    libusb_context *ctx;
    if (libusb_init(&ctx)) return 1;
    libusb_device_handle *h = libusb_open_device_with_vid_pid(ctx, VID, PID);
    if (!h) { fprintf(stderr, "Module %04x:%04x not found\n", VID, PID); return 1; }
    libusb_set_auto_detach_kernel_driver(h, 1);
    int r = libusb_claim_interface(h, IFACE);
    if (r) { fprintf(stderr, "claim IF %d: %s\n", IFACE, libusb_error_name(r)); return 1; }
    r = libusb_set_interface_alt_setting(h, IFACE, 1);
    if (r) { fprintf(stderr, "alt 1: %s\n", libusb_error_name(r)); return 1; }

    struct libusb_transfer *x[NXFER];
    for (int i = 0; i < NXFER; i++) {
        x[i] = libusb_alloc_transfer(NPKT);
        libusb_fill_iso_transfer(x[i], h, EP, malloc(PKT * NPKT), PKT * NPKT, NPKT, cb, NULL, 1000);
        libusb_set_iso_packet_lengths(x[i], PKT);
        if (libusb_submit_transfer(x[i]) == 0) inflight++;
    }
    time_t end = time(NULL) + seconds;
    while (inflight && time(NULL) < end) libusb_handle_events(ctx);
    running = 0;
    for (int i = 0; i < NXFER; i++) libusb_cancel_transfer(x[i]);
    while (inflight) libusb_handle_events(ctx);

    libusb_set_interface_alt_setting(h, IFACE, 0);
    libusb_release_interface(h, IFACE);
    libusb_close(h);
    libusb_exit(ctx);
    fclose(out);

    printf("packets %ld, bad %ld, bytes %ld\n", packets, bad_packets, bytes);
    for (int n = 0; n <= PKT; n++) if (hist[n]) printf("len %d: %ld\n", n, hist[n]);
    return 0;
}
