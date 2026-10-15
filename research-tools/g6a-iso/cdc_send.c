/*
 * G6a: send whitelisted command frames to the Module over the CDC data interface (IF 3) and log what comes back.
 * Bulk only: EP 0x03 OUT, EP 0x83 IN. IF 2 (CDC control) is not claimed: no line coding, no DTR/RTS.
 * Allowed bodies (docs/research/command-catalog.md): 80 85 07 requestAllInfo, 80 84 BAND, 80 88 TUNEUP, 80 89 TUNEDOWN.
 * Build: gcc -O2 -o cdc_send cdc_send.c -lusb-1.0
 * Run:   sudo ./cdc_send <rx.bin> <wait_ms> <cmd> [<wait_ms> <cmd> ...] <tail_ms>
 *        cmd: rai | band | tuneup | tunedown. Each wait is the read time before that send.
 * Limits from the catalog: rai >= 10 s apart, band >= 2 s, tune >= 1 s; the tool refuses shorter gaps.
 */
#include <libusb-1.0/libusb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define VID 0x0483
#define PID 0x5740
#define IFACE 3

static double now(void) { struct timespec t; clock_gettime(CLOCK_REALTIME, &t); return t.tv_sec + t.tv_nsec / 1e9; }

static libusb_device_handle *h;
static FILE *rx;

static void read_for(int ms) {
    double end = now() + ms / 1000.0;
    unsigned char buf[64];
    while (now() < end) {
        int n = 0, r = libusb_bulk_transfer(h, 0x83, buf, sizeof buf, &n, 100);
        if (n > 0) { fwrite(buf, 1, n, rx); fflush(rx); }
        if (r && r != LIBUSB_ERROR_TIMEOUT) { fprintf(stderr, "read: %s\n", libusb_error_name(r)); return; }
    }
}

int main(int argc, char **argv) {
    struct { const char *name; unsigned char body[3]; int len, min_gap_ms; double last; } cmds[] = {
        {"rai", {0x80, 0x85, 0x07}, 3, 10000, 0}, {"band", {0x80, 0x84}, 2, 2000, 0},
        {"tuneup", {0x80, 0x88}, 2, 1000, 0}, {"tunedown", {0x80, 0x89}, 2, 1000, 0},
    };
    if (argc < 5 || argc % 2 == 0) { fprintf(stderr, "usage: %s rx.bin wait cmd [wait cmd ...] tail\n", argv[0]); return 2; }
    /* Validate the whole schedule before touching the device. */
    double t = 0;
    for (int i = 2; i + 1 < argc; i += 2) {
        t += atoi(argv[i]) / 1000.0;
        int k = -1;
        for (int j = 0; j < 4; j++) if (!strcmp(argv[i + 1], cmds[j].name)) k = j;
        if (k < 0) { fprintf(stderr, "not whitelisted: %s\n", argv[i + 1]); return 2; }
        if (cmds[k].last && (t - cmds[k].last) * 1000 < cmds[k].min_gap_ms) { fprintf(stderr, "%s too soon\n", argv[i + 1]); return 2; }
        cmds[k].last = t ? t : 1e-9;
    }
    for (int j = 0; j < 4; j++) cmds[j].last = 0;

    rx = fopen(argv[1], "wb");
    if (!rx || libusb_init(NULL)) return 1;
    h = libusb_open_device_with_vid_pid(NULL, VID, PID);
    if (!h) { fprintf(stderr, "Module not found\n"); return 1; }
    libusb_set_auto_detach_kernel_driver(h, 1);
    int r = libusb_claim_interface(h, IFACE);
    if (r) { fprintf(stderr, "claim IF %d: %s\n", IFACE, libusb_error_name(r)); return 1; }

    int seq = 0;
    for (int i = 2; i + 1 < argc; i += 2) {
        read_for(atoi(argv[i]));
        int k = 0;
        while (strcmp(argv[i + 1], cmds[k].name)) k++;
        unsigned char f[16] = {0xFA, 0x55, (unsigned char)seq++, 0, (unsigned char)(cmds[k].len + 1)};
        unsigned sum = 0;
        for (int b = 0; b < cmds[k].len; b++) { f[5 + b] = cmds[k].body[b]; sum += cmds[k].body[b]; }
        f[5 + cmds[k].len] = (unsigned char)(-sum);
        int len = 6 + cmds[k].len, n = 0;
        printf("%.6f tx %s:", now(), cmds[k].name);
        for (int b = 0; b < len; b++) printf(" %02x", f[b]);
        r = libusb_bulk_transfer(h, 0x03, f, len, &n, 2000);
        printf(" -> %s, %d bytes, rx offset %ld\n", r ? libusb_error_name(r) : "ok", n, ftell(rx));
        fflush(stdout);
        if (r) break;   /* a failed write stops further writes */
    }
    read_for(atoi(argv[argc - 1]));
    libusb_release_interface(h, IFACE);
    libusb_close(h);
    libusb_exit(NULL);
    fclose(rx);
    return r ? 1 : 0;
}
