/* hal_stub.c - Stub HAL service that stays alive forever.
 * Compiled as libtwoyi_hal_stub.so for Android package extraction.
 * Used as a placeholder for missing HAL services (audio-hal-2-0, keymaster, etc.)
 * to prevent audioserver/keystore crash-restart loops during container boot.
 */
#include <unistd.h>

int main(void) {
    for (;;) {
        pause();
    }
    return 0;
}
