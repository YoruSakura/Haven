#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "MoshPtyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_sh_haven_core_mosh_MoshPtyBridge_nativeForkPty(
    JNIEnv *env,
    jclass clazz,
    jstring command,
    jobjectArray arguments,
    jobjectArray environment,
    jint rows,
    jint cols
) {
    (void)clazz;
    jintArray result = (*env)->NewIntArray(env, 2);
    jint values[2] = {-1, ENOMEM};
    if (result == NULL) return NULL;

    const char *command_chars = (*env)->GetStringUTFChars(env, command, NULL);
    if (command_chars == NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, values);
        return result;
    }

    jsize argc = (*env)->GetArrayLength(env, arguments);
    jsize envc = (*env)->GetArrayLength(env, environment);
    char **argv = calloc((size_t)argc + 1U, sizeof(char *));
    char **envp = calloc((size_t)envc + 1U, sizeof(char *));
    jstring *arg_refs = calloc((size_t)argc, sizeof(jstring));
    jstring *env_refs = calloc((size_t)envc, sizeof(jstring));
    if (argv == NULL || envp == NULL || arg_refs == NULL || env_refs == NULL) {
        goto cleanup;
    }

    for (jsize i = 0; i < argc; ++i) {
        arg_refs[i] = (jstring)(*env)->GetObjectArrayElement(env, arguments, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, arg_refs[i], NULL);
        if (argv[i] == NULL) goto cleanup;
    }
    for (jsize i = 0; i < envc; ++i) {
        env_refs[i] = (jstring)(*env)->GetObjectArrayElement(env, environment, i);
        envp[i] = (char *)(*env)->GetStringUTFChars(env, env_refs[i], NULL);
        if (envp[i] == NULL) goto cleanup;
    }

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short)rows;
    size.ws_col = (unsigned short)cols;

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &size);
    if (pid < 0) {
        values[0] = -1;
        values[1] = errno;
        LOGE("forkpty failed: %s", strerror(errno));
    } else if (pid == 0) {
        execve(command_chars, argv, envp);
        // After fork in Android's multithreaded runtime, call only
        // async-signal-safe functions before exec/_exit. In particular the
        // logging allocator can deadlock here if execve fails.
        _exit(127);
    } else {
        values[0] = master_fd;
        values[1] = pid;
    }

cleanup:
    if (argv != NULL && arg_refs != NULL) {
        for (jsize i = 0; i < argc; ++i) {
            if (argv[i] != NULL && arg_refs[i] != NULL) {
                (*env)->ReleaseStringUTFChars(env, arg_refs[i], argv[i]);
            }
            if (arg_refs[i] != NULL) (*env)->DeleteLocalRef(env, arg_refs[i]);
        }
    }
    if (envp != NULL && env_refs != NULL) {
        for (jsize i = 0; i < envc; ++i) {
            if (envp[i] != NULL && env_refs[i] != NULL) {
                (*env)->ReleaseStringUTFChars(env, env_refs[i], envp[i]);
            }
            if (env_refs[i] != NULL) (*env)->DeleteLocalRef(env, env_refs[i]);
        }
    }
    free(argv);
    free(envp);
    free(arg_refs);
    free(env_refs);
    (*env)->ReleaseStringUTFChars(env, command, command_chars);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_sh_haven_core_mosh_MoshPtyBridge_nativeSetSize(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jint rows,
    jint cols
) {
    (void)env;
    (void)clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short)rows;
    size.ws_col = (unsigned short)cols;
    return ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT jint JNICALL
Java_sh_haven_core_mosh_MoshPtyBridge_nativeWaitPid(
    JNIEnv *env,
    jclass clazz,
    jint pid
) {
    (void)env;
    (void)clazz;
    int status = 0;
    if (waitpid((pid_t)pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
