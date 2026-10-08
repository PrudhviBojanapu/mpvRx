#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <errno.h>
#include <wchar.h>
#include <limits.h>
#include <libgen.h>
#include <sys/stat.h>
#include <time.h>

static unsigned long long hash_url_c(const char *str) {
    unsigned long long hash = 14695981039346656037ULL;
    while (*str) {
        hash ^= (unsigned char)(*str++);
        hash *= 1099511628211ULL;
    }
    return hash;
}

static int extract_youtube_id_c(const char *url, char *out_id, size_t max_len) {
    if (!url || max_len < 12) return 0;
    const char *v = strstr(url, "v=");
    if (v) {
        v += 2;
        size_t len = 0;
        while (v[len] && v[len] != '&' && v[len] != '?' && v[len] != '#' && len < 11) {
            out_id[len] = v[len];
            len++;
        }
        if (len == 11) {
            out_id[11] = '\0';
            return 1;
        }
    }
    const char *be = strstr(url, "youtu.be/");
    if (be) {
        be += 9;
        size_t len = 0;
        while (be[len] && be[len] != '?' && be[len] != '/' && be[len] != '#' && len < 11) {
            out_id[len] = be[len];
            len++;
        }
        if (len == 11) {
            out_id[11] = '\0';
            return 1;
        }
    }
    const char *sh = strstr(url, "/shorts/");
    if (sh) {
        sh += 8;
        size_t len = 0;
        while (sh[len] && sh[len] != '?' && sh[len] != '/' && sh[len] != '#' && len < 11) {
            out_id[len] = sh[len];
            len++;
        }
        if (len >= 10 && len <= 12) {
            out_id[len] = '\0';
            return 1;
        }
    }
    return 0;
}

/*
 * libytdl: A native bridge for yt-dlp on Android 10+
 * This executable hosts the Python interpreter by loading libpython.so dynamically.
 * It bypasses the "no exec from data directory" restriction by living in lib/.
 */

typedef int (*Py_BytesMain_t)(int argc, char **argv);
typedef void (*Py_SetProgramName_t)(const wchar_t *);

int main(int argc, char *argv[]) {
    // 1. Resolve our own executable directory via /proc/self/exe
    char exe_path[PATH_MAX] = {0};
    char native_lib_dir[PATH_MAX] = {0};
    ssize_t len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (len > 0) {
        exe_path[len] = '\0';
        char exe_dir_copy[PATH_MAX];
        strncpy(exe_dir_copy, exe_path, sizeof(exe_dir_copy) - 1);
        char *d = dirname(exe_dir_copy);
        if (d) {
            strncpy(native_lib_dir, d, sizeof(native_lib_dir) - 1);
        }
    }

    // 2. Discover the app files directory
    // Priority: env var PYTHONHOME/YTDL_DIR -> known package paths
    const char *data_dirs[] = {
        "/data/data/app.gyrolet.mpvrx.debug/files",
        "/data/data/app.gyrolet.mpvrx/files",
        "/data/user/0/app.gyrolet.mpvrx.debug/files",
        "/data/user/0/app.gyrolet.mpvrx/files",
    };
    char app_files_dir[PATH_MAX] = {0};
    char ytdl_dir[PATH_MAX] = {0};

    char *env_pyhome = getenv("PYTHONHOME");
    if (env_pyhome && access(env_pyhome, F_OK) == 0) {
        strncpy(ytdl_dir, env_pyhome, sizeof(ytdl_dir) - 1);
        char pyhome_copy[PATH_MAX];
        strncpy(pyhome_copy, env_pyhome, sizeof(pyhome_copy) - 1);
        char *d = dirname(pyhome_copy);
        if (d) strncpy(app_files_dir, d, sizeof(app_files_dir) - 1);
    } else {
        for (size_t i = 0; i < sizeof(data_dirs) / sizeof(data_dirs[0]); i++) {
            char candidate[PATH_MAX];
            snprintf(candidate, sizeof(candidate), "%s/ytdl/yt-dlp", data_dirs[i]);
            if (access(candidate, F_OK) == 0) {
                strncpy(app_files_dir, data_dirs[i], sizeof(app_files_dir) - 1);
                snprintf(ytdl_dir, sizeof(ytdl_dir), "%s/ytdl", data_dirs[i]);
                break;
            }
        }
    }

    // Fast path: Check for preloaded yt-dlp single-json dump cache
    int is_dump_json = 0;
    const char *target_url = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-J") == 0 || strcmp(argv[i], "--dump-single-json") == 0 ||
            strcmp(argv[i], "-j") == 0 || strcmp(argv[i], "--dump-json") == 0) {
            is_dump_json = 1;
        }
        if (strncmp(argv[i], "http://", 7) == 0 || strncmp(argv[i], "https://", 8) == 0) {
            target_url = argv[i];
        }
    }

    if (is_dump_json && target_url && ytdl_dir[0] != '\0') {
        char cache_file[PATH_MAX] = {0};
        char yt_id[32] = {0};
        int found = 0;

        if (extract_youtube_id_c(target_url, yt_id, sizeof(yt_id))) {
            snprintf(cache_file, sizeof(cache_file), "%s/cache/yt_%s.json", ytdl_dir, yt_id);
            if (access(cache_file, R_OK) == 0) found = 1;
        }
        if (!found) {
            unsigned long long h = hash_url_c(target_url);
            snprintf(cache_file, sizeof(cache_file), "%s/cache/%llx.json", ytdl_dir, h);
            if (access(cache_file, R_OK) == 0) found = 1;
        }

        if (found) {
            struct stat st;
            if (stat(cache_file, &st) == 0 && st.st_size > 100) {
                time_t now = time(NULL);
                if ((now - st.st_mtime) < 7200) {
                    FILE *fp = fopen(cache_file, "rb");
                    if (fp) {
                        char buf[16384];
                        size_t n;
                        while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) {
                            fwrite(buf, 1, n, stdout);
                        }
                        fclose(fp);
                        fflush(stdout);
                        return 0; // FAST CACHE HIT (<2ms instant return)!
                    }
                }
            }
        }
    }

    // 3. Set up environment variables for Python runtime
    setenv("_PYTHON_SYSCONFIGDATA_NAME", "_sysconfigdata__android_aarch64-linux-android", 0);

    if (ytdl_dir[0] != '\0') {
        setenv("PYTHONHOME", ytdl_dir, 1);
        char python_path[PATH_MAX * 2];
        if (native_lib_dir[0] != '\0') {
            snprintf(python_path, sizeof(python_path), "%s/python313.zip:%s:%s", ytdl_dir, ytdl_dir, native_lib_dir);
        } else {
            snprintf(python_path, sizeof(python_path), "%s/python313.zip:%s", ytdl_dir, ytdl_dir);
        }
        setenv("PYTHONPATH", python_path, 1);
    }

    if (app_files_dir[0] != '\0') {
        char cert_path[PATH_MAX];
        snprintf(cert_path, sizeof(cert_path), "%s/cacert.pem", app_files_dir);
        setenv("SSL_CERT_FILE", cert_path, 1);
    }

    if (native_lib_dir[0] != '\0') {
        const char *cur_ld = getenv("LD_LIBRARY_PATH");
        if (cur_ld && strlen(cur_ld) > 0) {
            char new_ld[PATH_MAX * 2];
            snprintf(new_ld, sizeof(new_ld), "%s:%s", native_lib_dir, cur_ld);
            setenv("LD_LIBRARY_PATH", new_ld, 1);
        } else {
            setenv("LD_LIBRARY_PATH", native_lib_dir, 1);
        }
    }

    // 4. Resolve python shared library path
    char python_lib[PATH_MAX] = {0};
    char *env_py_lib = getenv("YTDL_PYTHON");
    if (env_py_lib && strlen(env_py_lib) > 0) {
        strncpy(python_lib, env_py_lib, sizeof(python_lib) - 1);
    } else if (native_lib_dir[0] != '\0') {
        snprintf(python_lib, sizeof(python_lib), "%s/libpython.so", native_lib_dir);
    } else {
        strncpy(python_lib, "libpython.so", sizeof(python_lib) - 1);
    }

    void *handle = dlopen(python_lib, RTLD_NOW | RTLD_GLOBAL);
    if (!handle && native_lib_dir[0] != '\0') {
        char direct_py[PATH_MAX];
        snprintf(direct_py, sizeof(direct_py), "%s/libpython.so", native_lib_dir);
        handle = dlopen(direct_py, RTLD_NOW | RTLD_GLOBAL);
    }
    if (!handle) {
        handle = dlopen("libpython.so", RTLD_NOW | RTLD_GLOBAL);
    }

    if (!handle) {
        fprintf(stderr, "libytdl: CRITICAL: Could not load libpython.so (%s): %s\n", python_lib, dlerror());
        return 127;
    }

    // Optional: Set program name
    Py_SetProgramName_t Py_SetProgramName = (Py_SetProgramName_t)dlsym(handle, "Py_SetProgramName");
    if (Py_SetProgramName) {
        wchar_t wprog[512];
        mbstowcs(wprog, argv[0], 511);
        wprog[511] = L'\0';
        Py_SetProgramName(wprog);
    }

    // Find Py_BytesMain (Standard entry point for Python 3.8+)
    Py_BytesMain_t Py_BytesMain = (Py_BytesMain_t)dlsym(handle, "Py_BytesMain");
    if (!Py_BytesMain) {
        fprintf(stderr, "libytdl: CRITICAL: Could not find Py_BytesMain in libpython.so\n");
        dlclose(handle);
        return 127;
    }

    // 5. Determine script path
    char script_path[PATH_MAX] = {0};
    char *env_script = getenv("YTDL_SCRIPT");
    if (env_script && strlen(env_script) > 0 && access(env_script, F_OK) == 0) {
        strncpy(script_path, env_script, sizeof(script_path) - 1);
    } else if (ytdl_dir[0] != '\0') {
        snprintf(script_path, sizeof(script_path), "%s/yt-dlp", ytdl_dir);
    }

    // Check if argv already specifies a script or if we need to insert script_path
    // When invoked by mpv's ytdl_hook, argv[1] is an option like "--no-warnings" or "-J"
    int need_insert_script = (script_path[0] != '\0');
    if (argc > 1 && strcmp(argv[1], script_path) == 0) {
        need_insert_script = 0;
    }

    // Check if quickjs runtime is available and if --js-runtimes is already present
    char qjs_path[PATH_MAX] = {0};
    int has_js_runtimes = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--js-runtimes") == 0 || strncmp(argv[i], "--js-runtimes=", 14) == 0) {
            has_js_runtimes = 1;
            break;
        }
    }
    if (native_lib_dir[0] != '\0') {
        snprintf(qjs_path, sizeof(qjs_path), "%s/libqjs.so", native_lib_dir);
        if (access(qjs_path, F_OK) != 0) {
            qjs_path[0] = '\0';
        }
    }

    int extra_args = 0;
    if (need_insert_script) extra_args += 1;
    char qjs_arg[PATH_MAX + 16] = {0};
    if (need_insert_script && !has_js_runtimes && qjs_path[0] != '\0') {
        snprintf(qjs_arg, sizeof(qjs_arg), "quickjs:%s", qjs_path);
        extra_args += 2; // --js-runtimes <qjs_arg>
    }

    int new_argc = argc + extra_args;
    char **python_argv = malloc((new_argc + 1) * sizeof(char *));
    if (!python_argv) return 1;

    int idx = 0;
    python_argv[idx++] = "python";
    if (need_insert_script) {
        python_argv[idx++] = script_path;
        if (!has_js_runtimes && qjs_path[0] != '\0') {
            python_argv[idx++] = "--js-runtimes";
            python_argv[idx++] = qjs_arg;
        }
    }
    for (int i = 1; i < argc; i++) {
        python_argv[idx++] = argv[i];
    }
    python_argv[idx] = NULL;

    int result = Py_BytesMain(new_argc, python_argv);
    free(python_argv);
    return result;
}
