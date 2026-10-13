// A tiny PTY for the in-app terminal: forkpty, then `su -c <script>` in the child.
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

JNIEXPORT jintArray JNICALL
Java_dev_legendarymsr_lfsdroid_Pty_nativeSpawn(JNIEnv *env, jclass cls, jstring jscript, jint rows, jint cols) {
    const char *script = (*env)->GetStringUTFChars(env, jscript, NULL);
    char *copy = strdup(script);
    (*env)->ReleaseStringUTFChars(env, jscript, script);
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    int fd = -1;
    pid_t pid = forkpty(&fd, NULL, NULL, &ws);
    if (pid < 0) { free(copy); return NULL; }
    if (pid == 0) {
        for (int i = 3; i < 1024; i++) close(i);
        setenv("TERM", "xterm-256color", 1);
        execlp("su", "su", "-c", copy, (char *) NULL);
        _exit(127);
    }
    free(copy);
    jintArray out = (*env)->NewIntArray(env, 2);
    jint v[2] = { fd, pid };
    (*env)->SetIntArrayRegion(env, out, 0, 2, v);
    return out;
}

JNIEXPORT void JNICALL
Java_dev_legendarymsr_lfsdroid_Pty_nativeResize(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_legendarymsr_lfsdroid_Pty_nativeWait(JNIEnv *env, jclass cls, jint pid) {
    int st = 0;
    if (waitpid(pid, &st, 0) < 0) return -1;
    return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}

JNIEXPORT void JNICALL
Java_dev_legendarymsr_lfsdroid_Pty_nativeHangup(JNIEnv *env, jclass cls, jint fd, jint pid) {
    kill(pid, SIGHUP);
    close(fd);
}
