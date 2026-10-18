/* Test-only TCP adapter to the shared stream core. Never package this binary. */
#define _POSIX_C_SOURCE 200809L
#include <sys/socket.h>
#include <stddef.h>
#include <errno.h>
static ssize_t fixture_send(int fd, const void *data, size_t size, int flags) {
    static unsigned calls;
    if (++calls % 3 == 1) { errno = EAGAIN; return -1; }
    return send(fd, data, size > 3 ? 3 : size, flags);
}
#define CC2_SEND fixture_send
#define CC2_CAPTURE_NO_MAIN
#define CC2_STATUS_NO_MAIN
#include "../cc2_native_status.c"
#include <netinet/in.h>

int main(int argc, char **argv) {
    struct sockaddr_in address;
    struct capture capture;
    int fd, flags;
    if (argc != 6 || configure_status(argv[4], argv[5])) return 2;
    if (create_output(&capture, argv[2])) return 2;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons((unsigned short)atoi(argv[1]));
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0 || connect(fd, (struct sockaddr *)&address, sizeof(address))) return 2;
    flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK)) return 2;
    capture_connected(&capture, fd, 1000, atoi(argv[3]));
    close(fd);
    return finish_output(&capture, atoi(argv[3]));
}
