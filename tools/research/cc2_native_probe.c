/* Native first-contact recorder for an already accessible stock CC2 Unix socket.
 * One fixed inventory request; no firmware installation, TCP, G-code or retries.
 * Raw output is private and UNVERIFIED until inspected off the printer.
 * Build: cc -std=c11 -Os -Wall -Wextra -Werror cc2_native_probe.c -o cc2-native-probe
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
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#ifndef CC2_SEND
#define CC2_SEND send
#endif
#define MAX_RX (4U * 1024U * 1024U)
#define MAX_FRAME (256U * 1024U)
#define MAX_MESSAGES 512U
#define FORMAT "cc2-native-first-probe-v1"
static const unsigned char request[] =
    "{\"id\":1,\"method\":\"list_endpoints\",\"params\":{}}\003";
static volatile sig_atomic_t stopped;

struct capture {
    int directory, receive_file, request_file, summary_file;
    size_t received, saved, sent, messages, pending;
    const char *error;
    int window_finished;
};

static void on_signal(int number) { (void)number; stopped = 1; }

static int64_t now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Returns 1 for readiness, 0 for deadline, -1 for interruption/I/O failure. */
static int wait_fd(int fd, short events, int64_t deadline) {
    for (;;) {
        struct pollfd p = {fd, events, 0};
        int64_t now = now_ms(), remaining;
        int result;
        if (stopped || now < 0) return -1;
        remaining = deadline - now;
        if (remaining <= 0) return 0;
        result = poll(&p, 1, remaining > INT_MAX ? INT_MAX : (int)remaining);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0 || (p.revents & POLLNVAL)) return -1;
        if (result == 0) continue;
        /* HUP/ERR must reach recv()/SO_ERROR, rather than spinning. */
        if (p.revents & (events | POLLHUP | POLLERR)) return 1;
    }
}

static int write_all(int fd, const void *data, size_t length, size_t *saved) {
    const unsigned char *p = data;
    while (length) {
        ssize_t n = write(fd, p, length);
        if (n < 0 && errno == EINTR && !stopped) continue;
        if (n <= 0) return -1;
        p += n; length -= (size_t)n;
        if (saved) *saved += (size_t)n;
    }
    return 0;
}

static int private_file(int directory, const char *name) {
    struct stat st;
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) ||
        (st.st_mode & 0777) != 0600 || st.st_uid != geteuid()) {
        close(fd); return -1;
    }
    return fd;
}

static void close_output(struct capture *c) {
    if (c->receive_file >= 0) close(c->receive_file);
    if (c->request_file >= 0) close(c->request_file);
    if (c->summary_file >= 0) close(c->summary_file);
    if (c->directory >= 0) close(c->directory);
}

static int create_output(struct capture *c, const char *path) {
    struct stat st;
    memset(c, 0, sizeof(*c));
    c->directory = c->receive_file = c->request_file = c->summary_file = -1;
    umask(0077);
    if (mkdir(path, 0700)) return -1;
    c->directory = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (c->directory < 0 || fstat(c->directory, &st) ||
        (st.st_mode & 0777) != 0700 || st.st_uid != geteuid()) goto fail;
    c->receive_file = private_file(c->directory, "console.rx");
    c->request_file = private_file(c->directory, "request.bin");
    c->summary_file = private_file(c->directory, "summary.json");
    if (c->receive_file < 0 || c->request_file < 0 || c->summary_file < 0) goto fail;
    if (write_all(c->request_file, request, sizeof(request) - 1, NULL)) goto fail;
    return 0;
fail:
    close_output(c);
    return -1;
}

static int connect_unix(const char *path, int timeout_ms, const char **error) {
    struct sockaddr_un address;
    struct stat st;
    int fd, flags, result, socket_error;
    socklen_t size = sizeof(socket_error);
    int64_t start = now_ms();
    *error = "socket_unavailable";
    if (start < 0) { *error = "clock_failed"; return -1; }
    if (strlen(path) >= sizeof(address.sun_path)) { *error = "socket_path_too_long"; return -1; }
    if (lstat(path, &st) || !S_ISSOCK(st.st_mode)) {
        *error = "not_a_unix_socket"; return -1;
    }
    fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK)) goto fail;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, strlen(path) + 1);
    result = connect(fd, (struct sockaddr *)&address, sizeof(address));
    if (result && errno != EINPROGRESS && errno != EAGAIN && errno != EINTR) goto fail;
    if (result) {
        /* AF_UNIX EAGAIN is a full backlog, not an in-progress connection.
         * It must fail rather than be mistaken for SO_ERROR == 0. */
        if (errno == EAGAIN) goto fail;
        result = wait_fd(fd, POLLOUT, start + timeout_ms);
        if (result <= 0) { *error = stopped ? "interrupted" : "connect_timeout"; goto fail; }
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &size) || socket_error) goto fail;
    }
    *error = NULL;
    return fd;
fail:
    close(fd);
    return -1;
}

/* Only this stream core is used by the test-only loopback harness. Production
 * always obtains its descriptor from connect_unix(), with no inherited-FD mode. */
static void capture_connected(struct capture *c, int fd, int timeout_ms, int window_ms) {
    int64_t start = now_ms(), deadline;
    if (start < 0) { c->error = "clock_failed"; return; }
    deadline = start + timeout_ms;
    while (c->sent < sizeof(request) - 1) {
        int ready = wait_fd(fd, POLLOUT, deadline);
        ssize_t n;
        if (ready <= 0) {
            c->error = stopped ? "interrupted" : ready == 0 ? "send_timeout" : "socket_io_failed";
            return;
        }
        n = CC2_SEND(fd, request + c->sent, sizeof(request) - 1 - c->sent, MSG_NOSIGNAL);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) continue;
        if (n <= 0) { c->error = "socket_io_failed"; return; }
        c->sent += (size_t)n;
    }
    start = now_ms();
    if (start < 0) { c->error = "clock_failed"; return; }
    deadline = start + window_ms;
    for (;;) {
        unsigned char buffer[4096];
        size_t i, keep, available = MAX_RX - c->received;
        int ready = wait_fd(fd, POLLIN, deadline);
        ssize_t n;
        if (ready == 0) {
            if (c->pending) c->error = "window_ended_partial_frame";
            else c->window_finished = 1;
            return;
        }
        if (ready < 0) { c->error = stopped ? "interrupted" : "socket_io_failed"; return; }
        n = recv(fd, buffer, available < sizeof(buffer) ? available + 1 : sizeof(buffer), 0);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) continue;
        if (n < 0) { c->error = "socket_io_failed"; return; }
        if (n == 0) { c->error = c->pending ? "eof_partial_frame" : "peer_closed"; return; }
        keep = (size_t)n > available ? available : (size_t)n;
        c->received += (size_t)n;
        if (write_all(c->receive_file, buffer, keep, &c->saved)) { c->error = "capture_io_failed"; return; }
        if ((size_t)n > available) { c->error = "receive_limit"; return; }
        for (i = 0; i < (size_t)n; i++) {
            if (buffer[i] == 3) {
                c->pending = 0;
                if (++c->messages > MAX_MESSAGES) { c->error = "message_limit"; return; }
            } else if (++c->pending > MAX_FRAME) { c->error = "frame_limit"; return; }
        }
    }
}

static int finish_output(struct capture *c, int window_ms) {
    char summary[1536];
    int n, status;
    if (fsync(c->request_file) || fsync(c->receive_file)) c->error = "capture_io_failed";
    n = snprintf(summary, sizeof(summary),
        "{\"format\":\"%s\",\"capture_status\":\"%s\","
        "\"transport\":\"local_unix_socket\",\"transport_status\":\"%s\","
        "\"request_method\":\"list_endpoints\",\"request_id\":1,\"requested_window_ms\":%d,"
        "\"transmitted_bytes\":%zu,\"received_bytes\":%zu,\"saved_bytes\":%zu,"
        "\"framed_messages\":%zu,\"pending_frame_bytes\":%zu,"
        "\"response_verified\":false,\"firmware_identity_verified\":false,"
        "\"printer_file_identity_verified\":false,\"error\":%s%s%s}\n",
        FORMAT, c->error ? "incomplete" : "unverified",
        c->window_finished ? "window_finished" : "stopped", window_ms,
        c->sent, c->received, c->saved, c->messages, c->pending,
        c->error ? "\"" : "", c->error ? c->error : "null", c->error ? "\"" : "");
    status = c->error ? 1 : 0;
    if (n < 0 || (size_t)n >= sizeof(summary) ||
        write_all(c->summary_file, summary, (size_t)n, NULL) || fsync(c->summary_file)) status = 2;
    if (status != 2 && write_all(STDOUT_FILENO, summary, (size_t)n, NULL)) status = 2;
    close_output(c);
    return status;
}

#ifndef CC2_CAPTURE_NO_MAIN
static int duration(const char *s) {
    char *end;
    long number;
    if (!s[0] || strspn(s, "0123456789") != strlen(s)) return -1;
    errno = 0;
    number = strtol(s, &end, 10);
    return errno || *end || number < 50 || number > 10000 ? -1 : (int)number;
}

int main(int argc, char **argv) {
    const char *socket_path = NULL, *output = NULL, *error = NULL;
    struct capture c;
    struct sigaction action;
    int timeout_ms = 5000, window_ms = 5000, i, fd, seen = 0;
    for (i = 1; i < argc; i++) {
        int bit;
        if (!strcmp(argv[i], "--version") && argc == 2) { puts(FORMAT); return 0; }
        if (!strcmp(argv[i], "--help") && argc == 2) {
            puts("cc2-native-probe --socket PATH --output NEW_PRIVATE_DIRECTORY [--timeout-ms 50..10000] [--window-ms 50..10000]");
            puts("Requires an existing owner shell. One fixed list_endpoints request; inspect private output off the printer.");
            return 0;
        }
        if (i + 1 >= argc) goto arguments;
        if (!strcmp(argv[i], "--socket")) { bit = 1; socket_path = argv[++i]; }
        else if (!strcmp(argv[i], "--output")) { bit = 2; output = argv[++i]; }
        else if (!strcmp(argv[i], "--timeout-ms")) { bit = 4; timeout_ms = duration(argv[++i]); }
        else if (!strcmp(argv[i], "--window-ms")) { bit = 8; window_ms = duration(argv[++i]); }
        else goto arguments;
        if (seen & bit) goto arguments;
        seen |= bit;
    }
    if (!socket_path || !output || timeout_ms < 0 || window_ms < 0 || !*socket_path || !*output) goto arguments;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) || sigaction(SIGTERM, &action, NULL)) return 2;
    action.sa_handler = SIG_IGN;
    if (sigaction(SIGPIPE, &action, NULL)) return 2;
    if (create_output(&c, output)) {
        fputs("capture_output_unavailable; use a new private directory with an existing writable parent\n", stderr);
        return 2;
    }
    fd = connect_unix(socket_path, timeout_ms, &error);
    if (fd < 0) c.error = stopped ? "interrupted" : error;
    else {
        capture_connected(&c, fd, timeout_ms, window_ms);
        close(fd);
    }
    return finish_output(&c, window_ms);
arguments:
    fputs("invalid_arguments; see --help\n", stderr);
    return 2;
}
#endif
