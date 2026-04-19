// mcon_bridge: single-binary bridge from OhSnap MCON → virtual Xbox Wireless Controller.
//
// What it does:
//   1. Opens /dev/uhid and creates a virtual Xbox Wireless Controller
//      (VID:0x045E PID:0x02FD, model 1708 firmware 5.17 BT HID descriptor) so
//      Android + games (CODM) see a real Xbox controller.
//   2. Scans /dev/input/event* for an "OhSnap MCON" device.
//   3. Exclusively grabs that device so its events are not seen by other apps.
//   4. Reads MCON input_event structs, translates to Xbox HID reports,
//      writes them to /dev/uhid.
//   5. On disconnect/error: sends a neutral (all zero / centered) report,
//      destroys the uhid device, and exits cleanly.
//
// Written from scratch. Not derived from any Java/dex/app_process exploit.
//
// Build (from Linux host):
//   $NDK/aarch64-linux-android30-clang mcon_bridge.c -o mcon_bridge -static
// Deploy:
//   adb push mcon_bridge /data/local/tmp/ && adb shell chmod +x /data/local/tmp/mcon_bridge
// Run:
//   adb shell /data/local/tmp/mcon_bridge
//
// Permissions: /dev/uhid requires group 3011 (uhid). /dev/input/event* requires
// group 1004 (input). Android's `shell` user (uid 2000) has both.

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uhid.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// Xbox Wireless Controller Model 1708 firmware 5.17 BT HID report descriptor.
// 283 bytes, verified against the official dump.
// ---------------------------------------------------------------------------
static const uint8_t xbox_desc[] = {
    0x05,0x01,0x09,0x05,0xa1,0x01,0x85,0x01,0x09,0x01,0xa1,0x00,
    0x09,0x30,0x09,0x31,0x15,0x00,0x27,0xff,0xff,0x00,0x00,0x95,
    0x02,0x75,0x10,0x81,0x02,0xc0,0x09,0x01,0xa1,0x00,0x09,0x32,
    0x09,0x35,0x15,0x00,0x27,0xff,0xff,0x00,0x00,0x95,0x02,0x75,
    0x10,0x81,0x02,0xc0,0x05,0x02,0x09,0xc5,0x15,0x00,0x26,0xff,
    0x03,0x95,0x01,0x75,0x0a,0x81,0x02,0x15,0x00,0x25,0x00,0x75,
    0x06,0x95,0x01,0x81,0x03,0x05,0x02,0x09,0xc4,0x15,0x00,0x26,
    0xff,0x03,0x95,0x01,0x75,0x0a,0x81,0x02,0x15,0x00,0x25,0x00,
    0x75,0x06,0x95,0x01,0x81,0x03,0x05,0x01,0x09,0x39,0x15,0x01,
    0x25,0x08,0x35,0x00,0x46,0x3b,0x01,0x66,0x14,0x00,0x75,0x04,
    0x95,0x01,0x81,0x42,0x75,0x04,0x95,0x01,0x15,0x00,0x25,0x00,
    0x35,0x00,0x45,0x00,0x65,0x00,0x81,0x03,0x05,0x09,0x19,0x01,
    0x29,0x0f,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x0f,0x81,0x02,
    0x15,0x00,0x25,0x00,0x75,0x01,0x95,0x01,0x81,0x03,0x05,0x0c,
    0x0a,0xb2,0x00,0x15,0x00,0x25,0x01,0x95,0x01,0x75,0x01,0x81,
    0x02,0x15,0x00,0x25,0x00,0x75,0x07,0x95,0x01,0x81,0x03,0x05,
    0x0f,0x09,0x21,0x85,0x03,0xa1,0x02,0x09,0x97,0x15,0x00,0x25,
    0x01,0x75,0x04,0x95,0x01,0x91,0x02,0x15,0x00,0x25,0x00,0x75,
    0x04,0x95,0x01,0x91,0x03,0x09,0x70,0x15,0x00,0x25,0x64,0x75,
    0x08,0x95,0x04,0x91,0x02,0x09,0x50,0x66,0x01,0x10,0x55,0x0e,
    0x15,0x00,0x26,0xff,0x00,0x75,0x08,0x95,0x01,0x91,0x02,0x09,
    0xa7,0x15,0x00,0x26,0xff,0x00,0x75,0x08,0x95,0x01,0x91,0x02,
    0x65,0x00,0x55,0x00,0x09,0x7c,0x15,0x00,0x26,0xff,0x00,0x75,
    0x08,0x95,0x01,0x91,0x02,0xc0,0xc0,
};

// ---------------------------------------------------------------------------
// Xbox HID input report layout (17 bytes, Report ID 0x01 prefixed):
//   [0]     Report ID = 0x01
//   [1-2]   LX  (u16 LE, center 0x8000)
//   [3-4]   LY
//   [5-6]   RX
//   [7-8]   RY
//   [9-10]  LT  (10 bits in u16 LE, high 6 bits = padding 0)
//   [11-12] RT
//   [13]    Hat (low nibble 1-8, 0=center/null)
//   [14]    Buttons 1-8: bit0=A bit1=B bit3=X bit4=Y bit6=LB bit7=RB
//   [15]    Buttons 9-15: bit2=View bit3=Menu bit4=Xbox bit5=LS bit6=RS
//   [16]    Share bit (unused)
// ---------------------------------------------------------------------------
#define REPORT_SIZE 17
static uint8_t report[REPORT_SIZE];

// MCON stick drift is large; deadzone ±18 out of ±128 (~14%) swallows it.
#define STICK_DEADZONE 18

static int hat_x = 0, hat_y = 0;

static void report_init(void) {
    memset(report, 0, sizeof(report));
    report[0] = 0x01;
    report[2] = 0x80; // LX high byte = center
    report[4] = 0x80; // LY
    report[6] = 0x80; // RX
    report[8] = 0x80; // RY
    hat_x = 0; hat_y = 0;
}

static void set_stick(int offset, int mcon_val) {
    int signed_v = (mcon_val & 0xFF) - 128;
    if (signed_v > -STICK_DEADZONE && signed_v < STICK_DEADZONE) signed_v = 0;
    int out = signed_v * 256 + 32768;
    if (out < 0) out = 0;
    if (out > 65535) out = 65535;
    report[offset]     = (uint8_t)(out & 0xFF);
    report[offset + 1] = (uint8_t)((out >> 8) & 0xFF);
}

static void set_trigger(int offset, int mcon_val) {
    int v = (mcon_val & 0xFF) * 1023 / 255;
    report[offset]     = (uint8_t)(v & 0xFF);
    report[offset + 1] = (uint8_t)((v >> 8) & 0x03);
}

static uint8_t compute_hat(void) {
    if      (hat_x == 0  && hat_y == -1) return 1;
    else if (hat_x == 1  && hat_y == -1) return 2;
    else if (hat_x == 1  && hat_y == 0)  return 3;
    else if (hat_x == 1  && hat_y == 1)  return 4;
    else if (hat_x == 0  && hat_y == 1)  return 5;
    else if (hat_x == -1 && hat_y == 1)  return 6;
    else if (hat_x == -1 && hat_y == 0)  return 7;
    else if (hat_x == -1 && hat_y == -1) return 8;
    return 0;
}

static void set_bit(int byte_idx, int bit, int on) {
    if (on) report[byte_idx] |=  (uint8_t)(1u << bit);
    else    report[byte_idx] &= (uint8_t)~(1u << bit);
}

// Apply a Linux EV_KEY event (code = BTN_*, value = 0 released / 1 pressed).
static void apply_key(int code, int value) {
    int on = (value == 1);
    switch (code) {
        // MCON ships with A/B swapped vs Xbox convention — swap at bridge layer.
        case BTN_SOUTH: /*physical A*/ set_bit(14, 1, on); break;  // → Xbox B
        case BTN_EAST:  /*physical B*/ set_bit(14, 0, on); break;  // → Xbox A
        case BTN_NORTH: /*BTN_X*/  set_bit(14, 3, on); break;
        case BTN_WEST:  /*BTN_Y*/  set_bit(14, 4, on); break;
        case BTN_TL:    /*LB*/     set_bit(14, 6, on); break;
        case BTN_TR:    /*RB*/     set_bit(14, 7, on); break;
        case BTN_SELECT:/*View*/   set_bit(15, 2, on); break;
        case BTN_START: /*Menu*/   set_bit(15, 3, on); break;
        case BTN_MODE:  /*Xbox*/   set_bit(15, 4, on); break;
        case BTN_THUMBL:/*LS*/     set_bit(15, 5, on); break;
        case BTN_THUMBR:/*RS*/     set_bit(15, 6, on); break;
        // MCON also sends BTN_TL2/TR2 digital for triggers. Ignore — we use
        // analog ABS_BRAKE/ABS_GAS exclusively so stale digital state can't
        // jam a trigger on after a missed BT release event.
        case BTN_TL2:
        case BTN_TR2:
            break;
        default: break;
    }
}

static void apply_abs(int code, int value) {
    switch (code) {
        case ABS_X:     set_stick(1, value); break;
        case ABS_Y:     set_stick(3, value); break;
        case ABS_Z:     set_stick(5, value); break; // MCON right stick X
        case ABS_RZ:    set_stick(7, value); break; // MCON right stick Y
        case ABS_GAS:   set_trigger(11, value); break; // RT
        case ABS_BRAKE: set_trigger(9, value); break;  // LT
        case ABS_HAT0X: hat_x = value; report[13] = compute_hat(); break;
        case ABS_HAT0Y: hat_y = value; report[13] = compute_hat(); break;
        default: break;
    }
}

// ---------------------------------------------------------------------------
// /dev/uhid interaction
// ---------------------------------------------------------------------------
static int uhid_open(void) {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (fd < 0) { perror("open /dev/uhid"); return -1; }
    return fd;
}

static int uhid_create(int fd) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    strncpy((char *)ev.u.create2.name, "Xbox Wireless Controller",
            sizeof(ev.u.create2.name) - 1);
    strncpy((char *)ev.u.create2.phys, "00:00:00:00:00:00",
            sizeof(ev.u.create2.phys) - 1);
    strncpy((char *)ev.u.create2.uniq, "00:00:00:00:00:00",
            sizeof(ev.u.create2.uniq) - 1);
    ev.u.create2.rd_size = sizeof(xbox_desc);
    memcpy(ev.u.create2.rd_data, xbox_desc, sizeof(xbox_desc));
    ev.u.create2.bus = BUS_BLUETOOTH;
    ev.u.create2.vendor  = 0x045E;
    ev.u.create2.product = 0x02FD;
    ev.u.create2.version = 0x0100;
    ev.u.create2.country = 0;
    ssize_t n = write(fd, &ev, sizeof(ev));
    if (n < 0) { perror("UHID_CREATE2"); return -1; }
    return 0;
}

static int uhid_send(int fd, const uint8_t *buf, size_t sz) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = sz;
    memcpy(ev.u.input2.data, buf, sz);
    ssize_t n = write(fd, &ev, sizeof(ev));
    if (n < 0 && errno != EINTR) { perror("UHID_INPUT2"); return -1; }
    return 0;
}

static void uhid_destroy(int fd) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    (void)write(fd, &ev, sizeof(ev));
}

// ---------------------------------------------------------------------------
// Locate an input device by substring-matching its name.
// Returns the full device path on success (must free), NULL if not found.
// ---------------------------------------------------------------------------
static char *find_device(const char *name_substring) {
    DIR *d = opendir("/dev/input");
    if (!d) { perror("opendir /dev/input"); return NULL; }
    struct dirent *e;
    char name[256];
    char *found = NULL;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[256];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
            if (strstr(name, name_substring) != NULL) {
                found = strdup(path);
                close(fd);
                break;
            }
        }
        close(fd);
    }
    closedir(d);
    return found;
}

// List all input devices and print their names (for debug / device picker).
static void list_devices(void) {
    DIR *d = opendir("/dev/input");
    if (!d) { perror("opendir /dev/input"); return; }
    struct dirent *e;
    char name[256];
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[256];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
            printf("%s\t%s\n", path, name);
        }
        close(fd);
    }
    closedir(d);
}

// ---------------------------------------------------------------------------
// Signal handling — on SIGINT/SIGTERM, we want to neutralize state and destroy
// the uhid device cleanly so no buttons appear stuck.
// ---------------------------------------------------------------------------
static volatile sig_atomic_t g_stop = 0;
static void on_signal(int sig) { (void)sig; g_stop = 1; }

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------
int main(int argc, char **argv) {
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    // Subcommand: list devices (for UI to enumerate)
    if (argc >= 2 && strcmp(argv[1], "--list") == 0) {
        list_devices();
        return 0;
    }

    const char *target;
    if (argc >= 3 && strcmp(argv[1], "--device") == 0) {
        target = argv[2];           // explicit /dev/input/eventX
    } else if (argc >= 3 && strcmp(argv[1], "--name") == 0) {
        target = argv[2];           // substring to match in device name
    } else if (argc >= 2 && argv[1][0] != '-') {
        target = argv[1];           // positional: path or name substring
    } else {
        target = "OhSnap MCON";     // default fallback
    }

    char *mcon_path = NULL;
    if (target[0] == '/') {
        // Already a device path
        mcon_path = strdup(target);
    } else {
        mcon_path = find_device(target);
    }
    if (!mcon_path) {
        fprintf(stderr, "Device not found matching '%s'\n", target);
        return 1;
    }
    fprintf(stderr, "Device: %s\n", mcon_path);

    int mcon_fd = open(mcon_path, O_RDONLY | O_CLOEXEC);
    if (mcon_fd < 0) { perror("open MCON"); free(mcon_path); return 1; }

    if (ioctl(mcon_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "EVIOCGRAB failed: %s (already grabbed?)\n", strerror(errno));
        close(mcon_fd); free(mcon_path); return 1;
    }
    fprintf(stderr, "Grabbed %s exclusively.\n", mcon_path);

    int uhid_fd = uhid_open();
    if (uhid_fd < 0) { close(mcon_fd); free(mcon_path); return 1; }

    if (uhid_create(uhid_fd) < 0) {
        close(uhid_fd); close(mcon_fd); free(mcon_path); return 1;
    }
    fprintf(stderr, "Xbox Wireless Controller (1708) created.\n");

    report_init();
    uhid_send(uhid_fd, report, sizeof(report));

    // Main event loop. Use poll so we also drain uhid's stream (ignoring
    // output reports, but keeping its buffer empty so it doesn't block).
    struct pollfd pfd[2] = {
        { .fd = mcon_fd, .events = POLLIN },
        { .fd = uhid_fd, .events = POLLIN },
    };

    while (!g_stop) {
        int pr = poll(pfd, 2, -1);
        if (pr < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }

        if (pfd[0].revents & POLLIN) {
            struct input_event ev;
            ssize_t n = read(mcon_fd, &ev, sizeof(ev));
            if (n == sizeof(ev)) {
                if (ev.type == EV_KEY) {
                    apply_key(ev.code, ev.value);
                    uhid_send(uhid_fd, report, sizeof(report));
                } else if (ev.type == EV_ABS) {
                    apply_abs(ev.code, ev.value);
                    // Defer the actual HID send until SYN_REPORT so multi-axis
                    // updates in the same sync batch reach CODM atomically.
                } else if (ev.type == EV_SYN) {
                    if (ev.code == SYN_REPORT) {
                        uhid_send(uhid_fd, report, sizeof(report));
                    } else if (ev.code == SYN_DROPPED) {
                        // Kernel event buffer overflowed; our view of MCON
                        // state is unreliable. Reset to neutral — safer than
                        // sending stale button / stick state.
                        report_init();
                        uhid_send(uhid_fd, report, sizeof(report));
                    }
                }
            } else if (n < 0) {
                if (errno == ENODEV) {
                    fprintf(stderr, "MCON removed (BT disconnect)\n");
                    break;
                } else if (errno != EINTR) {
                    perror("read MCON");
                    break;
                }
            } else if (n == 0) {
                fprintf(stderr, "MCON EOF\n");
                break;
            } else {
                // Short read of an input_event is a kernel-level invariant
                // violation; just keep going.
            }
        }
        if (pfd[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "MCON poll error/hangup\n");
            break;
        }

        if (pfd[1].revents & POLLIN) {
            // Drain anything uhid sends us (UHID_START, UHID_OUTPUT, etc.).
            // We don't need to act on them but must read so the fd doesn't block.
            struct uhid_event uev;
            ssize_t n = read(uhid_fd, &uev, sizeof(uev));
            (void)n;
        }
        if (pfd[1].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "uhid poll error/hangup\n");
            break;
        }
    }

    // Cleanup: send neutral state so CODM doesn't see stuck inputs.
    report_init();
    uhid_send(uhid_fd, report, sizeof(report));
    uhid_destroy(uhid_fd);
    close(uhid_fd);
    ioctl(mcon_fd, EVIOCGRAB, 0);
    close(mcon_fd);
    free(mcon_path);
    fprintf(stderr, "Bridge stopped cleanly.\n");
    return 0;
}
