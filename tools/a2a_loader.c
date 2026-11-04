/*
 * A2A plugin loader.
 *
 * The official OpenMinis APK starts nativeLibraryDir/liba2a.so as the gateway
 * process. This stub replaces that file and execs an external plugin when one
 * is installed, otherwise the bundled gateway at
 * <files>/builtin-plugins/a2a/ui/bin/agent2api-server or liba2a_server.so.
 *
 * The app's global shared dir is <files>/minis-global/shared, which is what
 * /var/minis/shared maps to inside the PRoot guest. Plugin layout:
 *   <files>/minis-global/shared/a2a-plugin/bin/agent2api-server
 *   <files>/minis-global/shared/a2a-plugin/ui/
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int is_file(const char *path) {
    struct stat st;
    return path && *path && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

int main(int argc, char **argv) {
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) {
        perror("readlink");
        return 127;
    }
    exe[n] = '\0';
    char self_dir[PATH_MAX];
    snprintf(self_dir, sizeof(self_dir), "%s", exe);
    char *slash = strrchr(self_dir, '/');
    if (slash) {
        *slash = '\0';
    } else {
        snprintf(self_dir, sizeof(self_dir), ".");
    }

    char files_dir[PATH_MAX] = "";
    char bundled_bin[PATH_MAX] = "";
    char bundled_ui[PATH_MAX] = "";
    const char *home = getenv("AGENT2API_PROXY_HOME");
    if (home && *home) {
        const char *marker = strstr(home, "/builtin-plugins/");
        if (marker) {
            size_t len = (size_t)(marker - home);
            if (len < sizeof(files_dir)) {
                memcpy(files_dir, home, len);
                files_dir[len] = '\0';
            }
        }
        char root[PATH_MAX];
        snprintf(root, sizeof(root), "%s", home);
        char *p = strrchr(root, '/');
        if (p) {
            *p = '\0'; /* <files>/builtin-plugins/a2a */
            snprintf(bundled_ui, sizeof(bundled_ui), "%s/ui", root);
            snprintf(bundled_bin, sizeof(bundled_bin),
                     "%s/ui/bin/agent2api-server", root);
        }
    }

    char ext_root[PATH_MAX] = "";
    char ext_bin[PATH_MAX] = "";
    char ext_ui[PATH_MAX] = "";
    if (files_dir[0]) {
        snprintf(ext_root, sizeof(ext_root), "%s/minis-global/shared/a2a-plugin",
                 files_dir);
        snprintf(ext_bin, sizeof(ext_bin), "%s/bin/agent2api-server", ext_root);
        snprintf(ext_ui, sizeof(ext_ui), "%s/ui", ext_root);
    }

    const char *server = NULL;
    const char *ui = NULL;
    if (ext_bin[0] && is_file(ext_bin)) {
        server = ext_bin;
        char marker[PATH_MAX];
        snprintf(marker, sizeof(marker), "%s/ui/index.html", ext_root);
        if (is_file(marker)) {
            ui = ext_ui;
        }
    } else if (bundled_bin[0] && is_file(bundled_bin)) {
        server = bundled_bin;
        ui = bundled_ui;
    } else {
        char fallback[PATH_MAX];
        snprintf(fallback, sizeof(fallback), "%s/liba2a_server.so", self_dir);
        if (is_file(fallback)) {
            server = fallback;
        }
    }

    if (!server) {
        fprintf(stderr, "a2a-loader: no gateway binary found\n");
        return 127;
    }
    chmod(server, 0755);
    if (ui) {
        setenv("AGENT2API_UI_DIR", ui, 1);
    }

    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!args) {
        return 127;
    }
    args[0] = (char *)server;
    for (int i = 1; i < argc; i++) {
        args[i] = argv[i];
    }
    args[argc] = NULL;
    execv(server, args);
    perror("execv");
    return 127;
}
