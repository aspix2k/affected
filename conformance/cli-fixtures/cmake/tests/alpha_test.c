#include "affected_fixture.h"

#include <stdio.h>

int main(int argc, char **argv) {
    FILE *marker;

    if (argc != 2 || affected_alpha_value() != 41) {
        return 1;
    }
    marker = fopen(argv[1], "w");
    if (marker == NULL) {
        return 2;
    }
    return fclose(marker) == 0 ? 0 : 3;
}
