#include <stdio.h>

int main(void) {
    FILE *marker = fopen("mixed-meson.marker", "w");
    if (marker == NULL) return 1;
    fputs("ran\n", marker);
    if (fclose(marker) != 0) return 1;
    return 0;
}
