#include "affected_fixture.h"

#include <stdio.h>

int main(int argc, char **argv) {
    FILE *fixture;
    FILE *marker;
    const char *marker_path;

    if ((argc != 2 && argc != 3) || affected_beta_value() != 43) {
        return 1;
    }
    if (argc == 3) {
        fixture = fopen(argv[1], "r");
        if (fixture == NULL) {
            return 2;
        }
        if (fclose(fixture) != 0) {
            return 3;
        }
    }
    marker_path = argv[argc - 1];
    marker = fopen(marker_path, "w");
    if (marker == NULL) {
        return 4;
    }
    return fclose(marker) == 0 ? 0 : 5;
}
