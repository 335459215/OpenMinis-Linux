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
 *
 * Android 10+ may deny execve() on app_data_file (W^X). The stub retries via
 * an anonymous memfd + fexecve before falling back to the APK's native lib.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_memfd_create
#define SYS_memfd_create 279
#endif
#ifndef SYS_execveat
#define SYS_execveat 281
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

extern char **environ;

static int is_file(const char *path) {
    struct stat st;
    return path && *path && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static void exec_memfd(const char *path, char **args) {
    int fd = (int)syscall(SYS_memfd_create, "a2a-server", 0);
    if (fd < 0) {
        return;
    }
    int in = open(path, O_RDONLY);
    if (in < 0) {
        close(fd);
        return;
    }
    char buf[65536];
    ssize_t n;
    while ((n = read(in, buf, sizeof(buf))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(fd, buf + off, (size_t)(n - off));
            if (w <= 0) {
                close(in);
                close(fd);
                return;
            }
            off += w;
        }
    }
    close(in);
    lseek(fd, 0, SEEK_SET);
    syscall(SYS_execveat, fd, "", args, environ, AT_EMPTY_PATH);
    close(fd);
}

static void try_exec(const char *path, char **args) {
    execv(path, args);
    int e = errno;
    if (e == EACCES || e == EPERM) {
        exec_memfd(path, args);
        e = errno;
    }
    fprintf(stderr, "a2a-loader: exec %s failed: %s\n", path, strerror(e));
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

    char saved_ui[PATH_MAX] = "";
    const char *orig_ui = getenv("AGENT2API_UI_DIR");
    if (orig_ui && *orig_ui) {
        snprintf(saved_ui, sizeof(saved_ui), "%s", orig_ui);
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
            *p = '\0';
            snprintf(bundled_ui, sizeof(bundled_ui), "%s/ui", root);
            snprintf(bundled_bin, sizeof(bundled_bin),
                     "%s/ui/bin/agent2api-server", root);
        }
    }

    char ext_root[PATH_MAX] = "";
    char ext_bin[PATH_MAX] = "";
    char ext_ui[PATH_MAX] = "";
    char ext_index[PATH_MAX] = "";
    if (files_dir[0]) {
        snprintf(ext_root, sizeof(ext_root), "%s/minis-global/shared/a2a-plugin",
                 files_dir);
        snprintf(ext_bin, sizeof(ext_bin), "%s/bin/agent2api-server", ext_root);
        snprintf(ext_ui, sizeof(ext_ui), "%s/ui", ext_root);
        snprintf(ext_index, sizeof(ext_index), "%s/ui/index.html", ext_root);
    }

    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!args) {
        return 127;
    }
    args[0] = NULL;
    args[argc] = NULL;

    if (ext_bin[0] && is_file(ext_bin)) {
        args[0] = ext_bin;
        for (int i = 1; i < argc; i++) {
            args[i] = argv[i];
        }
        if (is_file(ext_index)) {
            setenv("AGENT2API_UI_DIR", ext_ui, 1);
        }
        try_exec(ext_bin, args);
        if (saved_ui[0]) {
            setenv("AGENT2API_UI_DIR", saved_ui, 1);
        } else {
            unsetenv("AGENT2API_UI_DIR");
        }
    }

    if (bundled_bin[0] && is_file(bundled_bin)) {
        args[0] = bundled_bin;
        for (int i = 1; i < argc; i++) {
            args[i] = argv[i];
        }
        char bundled_index[PATH_MAX];
        snprintf(bundled_index, sizeof(bundled_index), "%s/index.html", bundled_ui);
        if (is_file(bundled_index)) {
            setenv("AGENT2API_UI_DIR", bundled_ui, 1);
        }
        try_exec(bundled_bin, args);
    }

    char fallback[PATH_MAX];
    snprintf(fallback, sizeof(fallback), "%s/liba2a_server.so", self_dir);
    if (is_file(fallback)) {
        args[0] = fallback;
        for (int i = 1; i < argc; i++) {
            args[i] = argv[i];
        }
        if (saved_ui[0]) {
            setenv("AGENT2API_UI_DIR", saved_ui, 1);
        } else {
            unsetenv("AGENT2API_UI_DIR");
        }
        try_exec(fallback, args);
    }

    fprintf(stderr, "a2a-loader: no runnable gateway found\n");
    return 127;
}
