#include <stdio.h>

int main(int argc, char **argv) {
    FILE *marker;

    if (argc != 2) {
        return 1;
    }
    marker = fopen(argv[1], "w");
    if (marker == NULL) {
        return 2;
    }
    return fclose(marker) == 0 ? 0 : 3;
}
