#include <stdio.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

static int exists(const char *path) {
    FILE *file = fopen(path, "r");
    if (file == NULL) return 0;
    return fclose(file) == 0;
}

static void pause_millis(void) {
#ifdef _WIN32
    Sleep(100);
#else
    usleep(100000);
#endif
}

int main(void) {
    if (exists("../../mixed-fail-fast")) {
        for (int attempt = 0; attempt < 300; attempt++) {
            if (exists("../../mixed-dotnet.started")) return 2;
            pause_millis();
        }
        return 3;
    }
    FILE *marker = fopen("mixed-cmake.marker", "w");
    if (marker == NULL) return 1;
    fputs("cmake\n", marker);
    if (fclose(marker) != 0) return 1;
    return 0;
}
