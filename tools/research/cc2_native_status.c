/* Fixed, owner-qualified read-only status captures. Reuses the bounded native
 * recorder as a compilation unit; the original probe's default stays fixed.
 * No JSON received from the printer selects a method, path or future request.
 */
#define _POSIX_C_SOURCE 200809L
static const unsigned char *status_request;
static const char *status_method;
static char status_fields[256];
#define FORMAT "cc2-native-readonly-capture-v1"
#define CC2_REQUEST_DATA status_request
#define CC2_REQUEST_METHOD status_method
#define CC2_SUMMARY_FIELDS status_fields
#define main cc2_probe_entry
#include "cc2_native_probe.c"
#undef main

#define FIELD_SELECTION "\"virtual_sdcard\":[\"file_path\",\"file_position\",\"file_size\",\"progress\",\"is_active\"],\"print_stats\":[\"filename\",\"state\",\"message\",\"print_duration\"]"
static const unsigned char objects_request[] = "{\"id\":1,\"method\":\"objects/list\",\"params\":{}}\003";
static const unsigned char snapshot_request[] = "{\"id\":1,\"method\":\"objects/query\",\"params\":{\"objects\":{" FIELD_SELECTION "},\"response_template\":{}}}\003";
static const unsigned char subscribe_request[] = "{\"id\":1,\"method\":\"objects/subscribe\",\"params\":{\"objects\":{" FIELD_SELECTION "},\"response_template\":{\"method\":\"cc2/status\"}}}\003";

static int boot_id(char value[37]) {
    char data[38];
    int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
    ssize_t size;
    size_t i;
    if (fd < 0) return -1;
    size = read(fd, data, sizeof(data));
    close(fd);
    if (size != 37 || data[36] != '\n') return -1;
    for (i = 0; i < 36; i++) {
        int dash = i == 8 || i == 13 || i == 18 || i == 23;
        if (dash ? data[i] != '-' : !((data[i] >= '0' && data[i] <= '9') || (data[i] >= 'a' && data[i] <= 'f'))) return -1;
    }
    memcpy(value, data, 36); value[36] = 0;
    return 0;
}

static int configure_status(const char *profile, const char *qualification) {
    char boot[37], expected[160], actual[160];
    struct stat st;
    int fd, size;
    ssize_t count;
    if (!strcmp(profile, "objects")) { status_request = objects_request; status_method = "objects/list"; }
    else if (!strcmp(profile, "snapshot")) { status_request = snapshot_request; status_method = "objects/query"; }
    else if (!strcmp(profile, "subscribe")) { status_request = subscribe_request; status_method = "objects/subscribe"; }
    else return -1;
    if (boot_id(boot)) return -1;
    size = snprintf(expected, sizeof(expected), "CC2_READONLY_QUALIFICATION_V1\n%s\n%s\n", profile, boot);
    fd = open(qualification, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid != geteuid() || (st.st_mode & 0777) != 0600 || st.st_size != size) {
        close(fd); return -1;
    }
    count = read(fd, actual, sizeof(actual));
    close(fd);
    if (count != size || memcmp(actual, expected, (size_t)size)) return -1;
    snprintf(status_fields, sizeof(status_fields), "\"profile\":\"%s\",\"boot_id\":\"%s\",\"owner_qualification_checked\":true,", profile, boot);
    return 0;
}

#ifndef CC2_STATUS_NO_MAIN
int main(int argc, char **argv) {
    const char *profile = NULL, *qualification = NULL;
    char *forward[24];
    int count = 1, i;
    if (argc == 2 && !strcmp(argv[1], "--version")) { puts(FORMAT); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--help")) {
        puts("cc2-native-status --profile objects|snapshot|subscribe --qualification FILE --socket PATH --output NEW_DIRECTORY [--window-ms 50..10000] [--timeout-ms 50..10000]");
        puts("One fixed request after an owner-provided, current-boot qualification; output remains unverified until inspected.");
        return 0;
    }
    forward[0] = argv[0];
    for (i = 1; i < argc; i += 2) {
        if (i + 1 >= argc || count + 2 >= 24) goto invalid;
        if (!strcmp(argv[i], "--profile") && !profile) profile = argv[i + 1];
        else if (!strcmp(argv[i], "--qualification") && !qualification) qualification = argv[i + 1];
        else { forward[count++] = argv[i]; forward[count++] = argv[i + 1]; }
    }
    if (!profile || !qualification || configure_status(profile, qualification)) goto invalid;
    forward[count] = NULL;
    return cc2_probe_entry(count, forward);
invalid:
    fputs("readonly_profile_or_current_boot_qualification_refused; no request sent\n", stderr);
    return 2;
}
#endif
