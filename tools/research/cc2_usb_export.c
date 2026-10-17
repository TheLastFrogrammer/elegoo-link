/* Read-only CC2 USB export v1. No shell, socket, pathname or command API.
 * The owner supplies a finalized private bundle. Only four fixed filenames
 * can be read. Wire CRC32 detects corruption; it is not authentication.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define VERSION "cc2-usb-export-v1"
#define DEADLINE_MS 120000
static volatile sig_atomic_t stopped;
static const char *names[] = {"identity.json", "request.bin", "summary.json", "console.rx"};
static const size_t limits[] = {2048, 128, 8192, 4U * 1024U * 1024U};
static void stop(int sig) { (void)sig; stopped = 1; }
static int64_t clock_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int ready(int fd, short events, int64_t deadline) {
    while (!stopped) {
        int64_t left = deadline - clock_ms();
        struct pollfd p = {fd, events, 0};
        int n;
        if (left <= 0) return 0;
        n = poll(&p, 1, left > INT_MAX ? INT_MAX : (int)left);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || (p.revents & (POLLHUP | POLLERR | POLLNVAL))) return -1;
        if (p.revents & events) return 1;
    }
    return -1;
}
static int send_bytes(int fd, const char *data, size_t size, int64_t deadline) {
    while (size) {
        ssize_t n;
        if (ready(fd, POLLOUT, deadline) != 1) return -1;
        n = write(fd, data, size);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (n <= 0) return -1;
        data += n; size -= (size_t)n;
    }
    return 0;
}
/* IEEE CRC32, deliberately a corruption check rather than a security MAC. */
static uint32_t crc_update(uint32_t crc, const unsigned char *data, size_t size) {
    size_t i;
    for (i = 0; i < size; i++) {
        unsigned j;
        crc ^= data[i];
        for (j = 0; j < 8; j++) crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc;
}
static int unchanged(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_size == b->st_size &&
        a->st_mtim.tv_sec == b->st_mtim.tv_sec && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec &&
        a->st_ctim.tv_sec == b->st_ctim.tv_sec && a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}
static int export_file(int device, int fd, int index, int64_t deadline) {
    struct stat before, after;
    unsigned char raw[256];
    char line[530];
    uint32_t crc = 0xffffffffU;
    size_t sent = 0;
    int n;
    static const char hex[] = "0123456789abcdef";
    if (fstat(fd, &before) || !S_ISREG(before.st_mode) || before.st_size < 0 ||
        (uint64_t)before.st_size > limits[index] || before.st_uid != geteuid() ||
        (before.st_mode & 0777) != 0600 || lseek(fd, 0, SEEK_SET) < 0) return -1;
    n = snprintf(line, sizeof(line), "FILE %s %ld\n", names[index], (long)before.st_size);
    if (n <= 0 || send_bytes(device, line, (size_t)n, deadline)) return -1;
    while (sent < (size_t)before.st_size) {
        size_t i, want = (size_t)before.st_size - sent;
        ssize_t count;
        if (want > sizeof(raw)) want = sizeof(raw);
        count = read(fd, raw, want);
        if (count < 0 && errno == EINTR && !stopped) continue;
        if (count <= 0 || stopped || clock_ms() >= deadline) return -1;
        crc = crc_update(crc, raw, (size_t)count);
        memcpy(line, "DATA ", 5);
        for (i = 0; i < (size_t)count; i++) {
            line[5 + i * 2] = hex[raw[i] >> 4];
            line[6 + i * 2] = hex[raw[i] & 15];
        }
        line[5 + (size_t)count * 2] = '\n';
        if (send_bytes(device, line, 6 + (size_t)count * 2, deadline)) return -1;
        sent += (size_t)count;
    }
    if (fstat(fd, &after) || !unchanged(&before, &after)) return -1;
    n = snprintf(line, sizeof(line), "END %08x\n", (unsigned)(crc ^ 0xffffffffU));
    return n <= 0 ? -1 : send_bytes(device, line, (size_t)n, deadline);
}
static int open_bundle(const char *path, int files[4]) {
    struct stat st;
    int directory = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    int i, optional = 0;
    if (directory < 0) return -1;
    if (fstat(directory, &st) || st.st_uid != geteuid() || (st.st_mode & 0777) != 0700) goto fail;
    for (i = 0; i < 4; i++) {
        files[i] = openat(directory, names[i], O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        if (files[i] < 0) {
            if (i && errno == ENOENT) continue;
            goto fail;
        }
        if (fstat(files[i], &st) || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
            (st.st_mode & 0777) != 0600 || st.st_size < 0 || (uint64_t)st.st_size > limits[i]) goto fail;
        if (i) optional++;
    }
    if (optional != 0 && optional != 3) goto fail;
    close(directory);
    return 0;
fail:
    close(directory);
    return -1;
}
int main(int argc, char **argv) {
    int device = -1, files[4] = {-1, -1, -1, -1}, result = 1, saved = 0, i;
    struct termios original, raw;
    struct stat st;
    const char *path = NULL, *bundle = NULL;
    struct sigaction action;
    if (argc == 2 && !strcmp(argv[1], "--version")) { puts(VERSION); return 0; }
    for (i = 1; i < argc; i += 2) {
        if (i + 1 == argc) goto usage;
        if (!strcmp(argv[i], "--device") && !path) path = argv[i + 1];
        else if (!strcmp(argv[i], "--bundle") && !bundle) bundle = argv[i + 1];
        else goto usage;
    }
    if (!path || !bundle) goto usage;
    memset(&action, 0, sizeof(action)); action.sa_handler = stop;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL);
    if (open_bundle(bundle, files)) goto done;
    device = open(path, O_RDWR | O_NONBLOCK | O_NOCTTY | O_NOFOLLOW | O_CLOEXEC);
    if (device < 0 || fstat(device, &st) || !S_ISCHR(st.st_mode) || !isatty(device) ||
        flock(device, LOCK_EX | LOCK_NB) || tcgetattr(device, &original)) goto done;
    saved = 1; raw = original;
    raw.c_iflag = 0; raw.c_oflag = 0; raw.c_lflag = 0;
    raw.c_cflag &= ~(CSIZE | PARENB | CSTOPB);
#ifdef CRTSCTS
    raw.c_cflag &= ~CRTSCTS;
#endif
    raw.c_cflag |= CS8 | CREAD | CLOCAL;
    raw.c_cc[VMIN] = 1; raw.c_cc[VTIME] = 0;
    if (cfsetispeed(&raw, B115200) || cfsetospeed(&raw, B115200) || tcsetattr(device, TCSANOW, &raw)) goto done;
    while (!stopped) {
        char request[42], response[80];
        size_t used = 0;
        int64_t deadline;
        int n;
        /* No startup banner: the Pi starts every transfer with a fresh nonce. */
        while (!stopped) {
            int r = ready(device, POLLIN, clock_ms() + 5000);
            if (r < 0) goto done;
            if (r == 1) break;
        }
        deadline = clock_ms() + 5000;
        while (used < sizeof(request)) {
            ssize_t count;
            if (ready(device, POLLIN, deadline) != 1) goto done;
            count = read(device, request + used, 1);
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count != 1) goto done;
            if (request[used++] == '\n') break;
        }
        if (used != 41 || memcmp(request, "CC2USB1 ", 8) || request[40] != '\n') goto done;
        for (i = 8; i < 40; i++) if (!((request[i] >= '0' && request[i] <= '9') ||
                                                     (request[i] >= 'a' && request[i] <= 'f'))) goto done;
        request[40] = 0;
        deadline = clock_ms() + DEADLINE_MS;
        n = snprintf(response, sizeof(response), "%s BEGIN\n", request);
        if (n <= 0 || send_bytes(device, response, (size_t)n, deadline)) goto done;
        for (i = 0; i < 4; i++) if (files[i] >= 0 && export_file(device, files[i], i, deadline)) goto done;
        n = snprintf(response, sizeof(response), "%s DONE\n", request);
        if (n <= 0 || send_bytes(device, response, (size_t)n, deadline)) goto done;
        /* A peer cannot request an unbounded tight loop of exports. */
        { struct timespec pause = {1, 0}; nanosleep(&pause, NULL); }
    }
    result = 0;
done:
    if (device >= 0) {
        if (saved) tcsetattr(device, TCSANOW, &original);
        close(device);
    }
    for (i = 0; i < 4; i++) if (files[i] >= 0) close(files[i]);
    if (result) fputs("cc2-usb-export: unavailable or incomplete; no printer action performed\n", stderr);
    return result;
usage:
    fputs("Usage: cc2-usb-export --device /dev/ttyACM0 --bundle PRIVATE_DIRECTORY\n", stderr);
    return 2;
}
