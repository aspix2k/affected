#include "affected_fixture.h"

#include <stdio.h>
#include <time.h>

int main(int argc, char **argv) {
    const clock_t delay = CLOCKS_PER_SEC / 2;
    const clock_t started = clock();
    FILE *marker;

    if (argc != 2 || affected_alpha_extended_value() != 42 || started == (clock_t)-1) {
        return 1;
    }
    while (clock() - started < delay) {
    }
    marker = fopen(argv[1], "w");
    if (marker == NULL) {
        return 2;
    }
    return fclose(marker) == 0 ? 0 : 3;
}
