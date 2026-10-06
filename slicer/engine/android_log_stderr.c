// Stand-in for Android's liblog in the fully static link-slicer test executable: the NDK ships liblog only as a
// shared library. Messages go to stderr. Builds for the app link the real liblog instead.
#include <stdarg.h>
#include <stdio.h>

int __android_log_write(int prio, const char* tag, const char* text)
{
    (void)prio;
    return fprintf(stderr, "%s: %s\n", tag ? tag : "", text ? text : "");
}

int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap)
{
    (void)prio;
    fprintf(stderr, "%s: ", tag ? tag : "");
    int written = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    return written;
}

int __android_log_print(int prio, const char* tag, const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int written = __android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return written;
}

void __android_log_assert(const char* cond, const char* tag, const char* fmt, ...)
{
    fprintf(stderr, "%s: assertion failed: %s\n", tag ? tag : "", cond ? cond : "");
    if (fmt) {
        va_list ap;
        va_start(ap, fmt);
        vfprintf(stderr, fmt, ap);
        va_end(ap);
        fputc('\n', stderr);
    }
    __builtin_trap();
}
