/* Owner-local, bounded .gcode range recorder. No network or console API.
 * The operator supplies an explicit directory root; every path component is
 * opened without following symlinks. Output is a new private range bundle.
 * Stable metadata is observed, not proof of finalization or current-job bytes.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_FILE (256U * 1024U * 1024U)
#define MAX_RANGE (1024U * 1024U)
#define MAX_PATH 1024
#define FORMAT "cc2-native-file-range-v1"
static volatile sig_atomic_t interrupted;
static void stop(int sig) { (void)sig; interrupted = 1; }

static int64_t now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int number(const char *value, uint64_t maximum, uint64_t *out) {
    char *end;
    unsigned long long n;
    if (!*value || strspn(value, "0123456789") != strlen(value) || (value[0] == '0' && value[1])) return -1;
    errno = 0; n = strtoull(value, &end, 10);
    if (errno || *end || n > maximum) return -1;
    *out = n; return 0;
}
static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *p = data;
    while (size && !interrupted) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n; size -= (size_t)n;
    }
    return size ? -1 : 0;
}
static uint32_t crc32_update(uint32_t crc, const unsigned char *data, size_t size) {
    size_t i;
    for (i = 0; i < size; i++) {
        unsigned bit;
        crc ^= data[i];
        for (bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc;
}
static int same(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_size == b->st_size &&
        a->st_mtim.tv_sec == b->st_mtim.tv_sec && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec &&
        a->st_ctim.tv_sec == b->st_ctim.tv_sec && a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}
/* Opens a path beneath an already opened root. Empty, dot and dot-dot
 * components, symlinks, long/deep paths and special files are refused. */
static int walk(int root, const char *path, int directory) {
    char copy[MAX_PATH + 1], *part, *next;
    int fd = dup(root), depth = 0;
    if (fd < 0) return -1;
    if (!*path || *path == '/' || strlen(path) > MAX_PATH || path[strlen(path) - 1] == '/') goto fail;
    strcpy(copy, path); part = copy;
    while (part) {
        int child, last;
        next = strchr(part, '/'); last = next == NULL;
        if (next) *next++ = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..") || ++depth > 32) goto fail;
        child = openat(fd, part, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC |
                       ((!last || directory) ? O_DIRECTORY : 0));
        if (child < 0) goto fail;
        close(fd); fd = child; part = next;
    }
    return fd;
fail:
    close(fd); return -1;
}
static int private_file(int directory, const char *name) {
    struct stat st;
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid != geteuid() || (st.st_mode & 0777) != 0600) {
        close(fd); return -1;
    }
    return fd;
}
static int current_boot(char boot[37]) {
    int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
    char data[38]; ssize_t n;
    if (fd < 0) return -1;
    n = read(fd, data, sizeof(data)); close(fd);
    if (n != 37 || data[36] != '\n' || strspn(data, "0123456789abcdef-") != 36) return -1;
    memcpy(boot, data, 36); boot[36] = 0; return 0;
}
int main(int argc, char **argv) {
    const char *root_path = NULL, *name = NULL, *output = NULL, *error = "source_unavailable";
    uint64_t offset = 0, length = MAX_RANGE, settle = 1000;
    size_t copied = 0, want = 0, i;
    int root = -1, source = -1, directory = -1, payload = -1, summary = -1, slash = -1, seen = 0, result = 1;
    struct stat before, after, root_st, dir_st;
    struct sigaction action;
    char boot[37], path_hex[MAX_PATH * 2 + 1], record[4096];
    uint32_t crc = 0xffffffffU;
    int64_t start, deadline;
    if (argc == 2 && !strcmp(argv[1], "--version")) { puts(FORMAT); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--help")) {
        puts("cc2-native-file --root ABSOLUTE_OWNER_SELECTED_DIRECTORY --name RELATIVE.gcode --output NEW_DIRECTORY [--offset BYTES] [--length 1..1048576] [--settle-ms 200..5000]");
        puts("Owner-local range capture only. Root is explicit local configuration, never selected over USB. No job identity or file-finalization attestation.");
        return 0;
    }
    for (i = 1; i < (size_t)argc; i += 2) {
        int bit;
        if (i + 1 >= (size_t)argc) goto invalid;
        if (!strcmp(argv[i], "--root")) { bit = 1; root_path = argv[i + 1]; }
        else if (!strcmp(argv[i], "--name")) { bit = 2; name = argv[i + 1]; }
        else if (!strcmp(argv[i], "--output")) { bit = 4; output = argv[i + 1]; }
        else if (!strcmp(argv[i], "--offset")) { bit = 8; if (number(argv[i + 1], MAX_FILE, &offset)) goto invalid; }
        else if (!strcmp(argv[i], "--length")) { bit = 16; if (number(argv[i + 1], MAX_RANGE, &length) || !length) goto invalid; }
        else if (!strcmp(argv[i], "--settle-ms")) { bit = 32; if (number(argv[i + 1], 5000, &settle) || settle < 200) goto invalid; }
        else goto invalid;
        if (seen & bit) goto invalid;
        seen |= bit;
    }
    if (!root_path || !name || !output || root_path[0] != '/' || root_path[1] == 0 || strlen(name) < 7 ||
        strlen(name) > MAX_PATH || strcasecmp(name + strlen(name) - 6, ".gcode")) goto invalid;
    memset(&action, 0, sizeof(action)); action.sa_handler = stop; sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) || sigaction(SIGTERM, &action, NULL)) goto done;
    slash = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (slash < 0) goto done;
    root = walk(slash, root_path + 1, 1);
    if (root < 0 || fstat(root, &root_st)) goto done;
    source = walk(root, name, 0);
    if (source < 0 || fstat(source, &before) || !S_ISREG(before.st_mode) ||
        before.st_size <= 0 || (uint64_t)before.st_size > MAX_FILE || offset >= (uint64_t)before.st_size || current_boot(boot)) goto done;
    error = "source_changed_or_not_quiet";
    { struct timespec delay = {(time_t)(settle / 1000), (long)(settle % 1000) * 1000000};
      if (nanosleep(&delay, NULL) || interrupted) goto done; }
    if (fstat(source, &after) || !same(&before, &after)) goto done;
    /* Pin the selected directory entry again: a same-name replacement during
     * settling must not silently become this range's source. */
    { int check = walk(root, name, 0);
      int bad = check < 0 || fstat(check, &after) || !same(&before, &after);
      if (check >= 0) close(check);
      if (bad) goto done; }
    umask(0077);
    error = "output_unavailable";
    if (mkdir(output, 0700)) goto done;
    directory = open(output, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0 || fstat(directory, &dir_st) || dir_st.st_uid != geteuid() || (dir_st.st_mode & 0777) != 0700) goto done;
    payload = private_file(directory, "range.bin"); summary = private_file(directory, "range.json");
    if (payload < 0 || summary < 0) goto done;
    want = (size_t)((uint64_t)before.st_size - offset < length ? (uint64_t)before.st_size - offset : length);
    start = now_ms(); if (start < 0) goto done; deadline = start + 30000;
    error = "source_io_or_deadline";
    while (copied < want && !interrupted) {
        unsigned char buffer[4096];
        size_t count = want - copied;
        ssize_t n;
        if (now_ms() < 0 || now_ms() >= deadline) goto finish;
        if (count > sizeof(buffer)) count = sizeof(buffer);
        n = pread(source, buffer, count, (off_t)(offset + copied));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || write_all(payload, buffer, (size_t)n)) goto finish;
        crc = crc32_update(crc, buffer, (size_t)n); copied += (size_t)n;
    }
    if (interrupted) { error = "interrupted"; goto finish; }
    error = "source_changed_during_capture";
    if (fstat(source, &after) || !same(&before, &after)) goto finish;
    { int check = walk(root, name, 0);
      int bad = check < 0 || fstat(check, &after) || !same(&before, &after);
      if (check >= 0) close(check);
      if (bad) goto finish; }
    error = NULL;
finish:
    for (i = 0; i < strlen(name); i++) {
        static const char hex[] = "0123456789abcdef";
        path_hex[i * 2] = hex[(unsigned char)name[i] >> 4]; path_hex[i * 2 + 1] = hex[(unsigned char)name[i] & 15];
    }
    path_hex[i * 2] = 0;
    if (fsync(payload)) error = "capture_io_failed";
    { int n = snprintf(record, sizeof(record),
        "{\"format\":\"%s\",\"capture_status\":\"%s\",\"boot_id\":\"%s\",\"relative_path_hex\":\"%s\","
        "\"root_dev\":\"%ju\",\"root_ino\":\"%ju\",\"file_dev\":\"%ju\",\"file_ino\":\"%ju\","
        "\"file_size\":%ju,\"mtime_sec\":%jd,\"mtime_nsec\":%ld,\"ctime_sec\":%jd,\"ctime_nsec\":%ld,"
        "\"offset\":%" PRIu64 ",\"requested_bytes\":%" PRIu64 ",\"copied_bytes\":%zu,\"settle_ms\":%" PRIu64 ",\"crc32\":\"%08x\","
        "\"finalization_verified\":false,\"job_binding_verified\":false,\"error\":%s%s%s}\n",
        FORMAT, error ? "incomplete" : "complete", boot, path_hex,
        (uintmax_t)root_st.st_dev, (uintmax_t)root_st.st_ino, (uintmax_t)before.st_dev, (uintmax_t)before.st_ino,
        (uintmax_t)before.st_size, (intmax_t)before.st_mtim.tv_sec, before.st_mtim.tv_nsec,
        (intmax_t)before.st_ctim.tv_sec, before.st_ctim.tv_nsec, offset, length, copied, settle, (unsigned)(crc ^ 0xffffffffU),
        error ? "\"" : "", error ? error : "null", error ? "\"" : "");
      if (n <= 0 || (size_t)n >= sizeof(record) || write_all(summary, record, (size_t)n) || fsync(summary)) goto done; }
    result = error ? 1 : 0;
done:
    if (summary >= 0) close(summary);
    if (payload >= 0) close(payload);
    if (directory >= 0) close(directory);
    if (source >= 0) close(source);
    if (root >= 0) close(root);
    if (slash >= 0) close(slash);
    fputs(result ? "file_range_unavailable_or_incomplete\n" : "file_range_captured; inspect private output off printer\n", result ? stderr : stdout);
    return result;
invalid:
    fputs("invalid_file_range_arguments; see --help\n", stderr);
    return 2;
}
