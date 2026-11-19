#include "elf_analyzer.hpp"

#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <signal.h>
#include <sstream>
#include <string>
#include <ucontext.h>
#include <unistd.h>

#define LOG_TAG "ZaphREBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using VoidFn = void (*)();

char g_crashLogPath[512] = {0};
char g_currentStage[160] = "idle";
bool g_crashInstalled = false;
struct sigaction g_oldSig[32];

void* g_zaphkielHandle = nullptr;
VoidFn g_zaphkielInitFn = nullptr;
VoidFn g_zaphkielFullEntryFn = nullptr;
uint64_t g_zaphkielBase = 0;

struct ModuleQuery {
    const char* needle;
    uint64_t base;
    uint64_t span;
    std::string fullPath;
    bool found;
};

int phdrCallback(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* q = static_cast<ModuleQuery*>(data);
    if (!info || !info->dlpi_name || info->dlpi_name[0] == '\0') return 0;
    if (std::strstr(info->dlpi_name, q->needle) != nullptr) {
        q->base = static_cast<uint64_t>(info->dlpi_addr);
        q->fullPath = info->dlpi_name;
        uint64_t maxEnd = 0;
        for (int i = 0; i < info->dlpi_phnum; ++i) {
            if (info->dlpi_phdr[i].p_type == PT_LOAD) {
                uint64_t end = info->dlpi_phdr[i].p_vaddr + info->dlpi_phdr[i].p_memsz;
                if (end > maxEnd) maxEnd = end;
            }
        }
        q->span = maxEnd;
        q->found = true;
        return 1;
    }
    return 0;
}

// Async-signal-safe helpers for fatal signal diagnostics inside :mcre
void safeWriteStr(int fd, const char* s) {
    if (fd < 0 || !s) return;
    size_t len = 0;
    while (s[len]) ++len;
    if (len > 0) (void)::write(fd, s, len);
}

void safeWriteHex(int fd, uint64_t v) {
    char buf[20];
    buf[0] = '0';
    buf[1] = 'x';
    static const char kHex[] = "0123456789abcdef";
    for (int i = 0; i < 16; ++i) {
        buf[2 + i] = kHex[(v >> ((15 - i) * 4)) & 0xf];
    }
    (void)::write(fd, buf, 18);
}

void safeWriteAddrWithMod(int fd, const char* label, uint64_t addr) {
    safeWriteStr(fd, label);
    safeWriteHex(fd, addr);
    Dl_info dli {};
    if (addr != 0 && ::dladdr(reinterpret_cast<void*>(addr), &dli) != 0 && dli.dli_fname) {
        safeWriteStr(fd, " (");
        safeWriteStr(fd, dli.dli_fname);
        safeWriteStr(fd, "+");
        safeWriteHex(fd, addr - reinterpret_cast<uint64_t>(dli.dli_fbase));
        if (dli.dli_sname) {
            safeWriteStr(fd, " ");
            safeWriteStr(fd, dli.dli_sname);
        }
        safeWriteStr(fd, ")");
    }
    safeWriteStr(fd, "\n");
}

const char* sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGABRT: return "SIGABRT";
        case SIGFPE:  return "SIGFPE";
        default:      return "SIGNAL";
    }
}

void crashSignalHandler(int sig, siginfo_t* info, void* uctx) {
    if (g_crashLogPath[0] != '\0') {
        int fd = ::open(g_crashLogPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) {
            safeWriteStr(fd, "*** ZaphRE :mcre NATIVE CRASH ***\n");
            safeWriteStr(fd, "signal: ");
            safeWriteStr(fd, sigName(sig));
            safeWriteStr(fd, "\nstage:  ");
            safeWriteStr(fd, g_currentStage);
            safeWriteStr(fd, "\n");
            if (info) {
                safeWriteStr(fd, "fault:  ");
                safeWriteHex(fd, reinterpret_cast<uint64_t>(info->si_addr));
                safeWriteStr(fd, "\n");
            }
#if defined(__aarch64__)
            if (uctx) {
                auto* uc = static_cast<ucontext_t*>(uctx);
                safeWriteAddrWithMod(fd, "pc:     ", static_cast<uint64_t>(uc->uc_mcontext.pc));
                safeWriteAddrWithMod(fd, "lr:     ", static_cast<uint64_t>(uc->uc_mcontext.regs[30]));
            }
#endif
            ::fsync(fd);
            ::close(fd);
        }
    }
    // Chain previous handler or default exit
    if (sig > 0 && sig < 32) {
        ::sigaction(sig, &g_oldSig[sig], nullptr);
    }
    ::raise(sig);
}

std::string jstrToStd(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring stdToJstr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeInstallCrashHandler(
    JNIEnv* env, jclass, jstring crashLogPath) {
    std::string p = jstrToStd(env, crashLogPath);
    std::snprintf(g_crashLogPath, sizeof(g_crashLogPath), "%s", p.c_str());
    if (g_crashInstalled) return;

    struct sigaction sa {};
    sa.sa_sigaction = crashSignalHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    const int sigs[] = {SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE};
    for (int s : sigs) {
        ::sigaction(s, &sa, &g_oldSig[s]);
    }
    g_crashInstalled = true;
}

JNIEXPORT void JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeSetStage(
    JNIEnv* env, jclass, jstring stage) {
    std::string s = jstrToStd(env, stage);
    std::snprintf(g_currentStage, sizeof(g_currentStage), "%s", s.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeDlopen(
    JNIEnv* env, jclass, jstring path, jboolean global, jboolean lazy) {
    std::string p = jstrToStd(env, path);
    int flags = (lazy ? RTLD_LAZY : RTLD_NOW) | (global ? RTLD_GLOBAL : RTLD_LOCAL);
    ::dlerror();
    void* handle = ::dlopen(p.c_str(), flags);
    if (!handle) {
        const char* err = ::dlerror();
        std::string msg = err ? err : "unknown dlopen failure";
        LOGE("dlopen(%s) failed: %s", p.c_str(), msg.c_str());
        return stdToJstr(env, msg);
    }
    LOGI("dlopen(%s) OK -> %p", p.c_str(), handle);
    return stdToJstr(env, "");
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeLoadZaphkiel(
    JNIEnv* env, jclass, jstring path) {
    std::string p = jstrToStd(env, path);
    uint64_t initArrayVa = zaphre::findFirstInitArrayVa(p);

    // Clear previous error and load libZaphkiel.so (triggers INIT_ARRAY[0]: zaphkiel_init + sig_targets)
    ::dlerror();
    void* handle = ::dlopen(p.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* err = ::dlerror();
        std::string msg = err ? err : "unknown dlopen error";
        LOGE("dlopen Zaphkiel failed: %s", msg.c_str());
        return stdToJstr(env, std::string("ERROR: ") + msg);
    }
    g_zaphkielHandle = handle;
    g_zaphkielInitFn = reinterpret_cast<VoidFn>(::dlsym(handle, "zaphkiel_init"));

    ModuleQuery mq {"Zaphkiel", 0, 0, "", false};
    ::dl_iterate_phdr(phdrCallback, &mq);
    if (!mq.found) {
        ModuleQuery mq2 {p.c_str(), 0, 0, "", false};
        ::dl_iterate_phdr(phdrCallback, &mq2);
        mq = mq2;
    }
    g_zaphkielBase = mq.base;
    if (mq.found && initArrayVa != 0) {
        g_zaphkielFullEntryFn = reinterpret_cast<VoidFn>(mq.base + initArrayVa);
    }

    char hexBuf[32];
    std::snprintf(hexBuf, sizeof(hexBuf), "0x%llx", static_cast<unsigned long long>(g_zaphkielBase));
    std::ostringstream os;
    os << "OK base=" << hexBuf
       << " hotInit=" << (g_zaphkielInitFn ? "yes" : "no")
       << " hotSig=" << (g_zaphkielFullEntryFn ? "yes" : "no");
    LOGI("Zaphkiel loaded: %s", os.str().c_str());
    return stdToJstr(env, os.str());
}

JNIEXPORT jboolean JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeRetriggerZaphkiel(
    JNIEnv*, jclass, jboolean includeSigGen) {
    if (includeSigGen && g_zaphkielFullEntryFn != nullptr) {
        LOGI("Hot-retriggering Zaphkiel full entry (zaphkiel_init + siggen)");
        g_zaphkielFullEntryFn();
        return JNI_TRUE;
    }
    if (g_zaphkielInitFn != nullptr) {
        LOGI("Hot-retriggering zaphkiel_init()");
        g_zaphkielInitFn();
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeGetModuleInfo(
    JNIEnv* env, jclass, jstring libSubstr) {
    std::string sub = jstrToStd(env, libSubstr);
    ModuleQuery mq {sub.c_str(), 0, 0, "", false};
    ::dl_iterate_phdr(phdrCallback, &mq);
    if (!mq.found) {
        return stdToJstr(env, "");
    }
    char buf[64];
    std::snprintf(buf, sizeof(buf), "0x%llx|0x%llx|",
                  static_cast<unsigned long long>(mq.base),
                  static_cast<unsigned long long>(mq.span));
    return stdToJstr(env, std::string(buf) + mq.fullPath);
}

JNIEXPORT jint JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeGetRssMb(JNIEnv*, jclass) {
    FILE* f = std::fopen("/proc/self/statm", "r");
    if (!f) return 0;
    unsigned long totalPages = 0, rssPages = 0;
    int scanned = std::fscanf(f, "%lu %lu", &totalPages, &rssPages);
    std::fclose(f);
    if (scanned < 2) return 0;
    long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    uint64_t bytes = static_cast<uint64_t>(rssPages) * static_cast<uint64_t>(pageSize);
    return static_cast<jint>(bytes / (1024ULL * 1024ULL));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativePrepareZaphkielImage(
    JNIEnv* env, jclass, jstring srcPath, jstring dstPath, jstring oldPrefix, jstring newPrefix) {
    int replaced = 0;
    std::string err;
    bool ok = zaphre::prepareZaphkielImage(
        jstrToStd(env, srcPath),
        jstrToStd(env, dstPath),
        jstrToStd(env, oldPrefix),
        jstrToStd(env, newPrefix),
        replaced,
        err
    );
    if (!ok) {
        return stdToJstr(env, std::string("ERROR: ") + err);
    }
    return stdToJstr(env, "OK:" + std::to_string(replaced));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeInspectElf(
    JNIEnv* env, jclass, jstring path) {
    return stdToJstr(env, zaphre::inspectElfJson(jstrToStd(env, path)));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeSearchStrings(
    JNIEnv* env, jclass, jstring path, jstring query, jboolean caseSensitive, jint maxHits) {
    return stdToJstr(env, zaphre::searchElfStrings(
        jstrToStd(env, path), jstrToStd(env, query), caseSensitive == JNI_TRUE, maxHits));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeSearchRtti(
    JNIEnv* env, jclass, jstring path, jstring query, jint maxHits) {
    return stdToJstr(env, zaphre::searchElfRtti(
        jstrToStd(env, path), jstrToStd(env, query), maxHits));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeSearchDynsym(
    JNIEnv* env, jclass, jstring path, jstring query, jint maxHits) {
    return stdToJstr(env, zaphre::searchElfDynsym(
        jstrToStd(env, path), jstrToStd(env, query), maxHits));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeScanSignature(
    JNIEnv* env, jclass, jstring path, jstring pattern, jint maxHits) {
    return stdToJstr(env, zaphre::scanElfSignature(
        jstrToStd(env, path), jstrToStd(env, pattern), maxHits));
}

JNIEXPORT jstring JNICALL
Java_org_levimc_zaphre_bridge_NativeBridge_nativeReadVa(
    JNIEnv* env, jclass, jstring path, jlong va, jint byteCount) {
    return stdToJstr(env, zaphre::readElfVa(
        jstrToStd(env, path), static_cast<uint64_t>(va), static_cast<size_t>(byteCount)));
}

}  // extern "C"
