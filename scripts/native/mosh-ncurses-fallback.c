/*
 * Compatibility for the rjyo/mosh-android v1.0.0 x86_64 and armv7 ncurses
 * archives. They reference ncurses' optional built-in terminfo lookup but do
 * not contain its definition. Haven ships an external xterm-256color database,
 * so reporting "no built-in fallback" is the intended behaviour.
 */

#include <stddef.h>

const void *_nc_fallback2(const char *name) {
    (void)name;
    return NULL;
}
