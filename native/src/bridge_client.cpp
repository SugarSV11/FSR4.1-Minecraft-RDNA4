#include "bridge_client.hpp"
#include <cerrno>
#include <fstream>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <thread>
#ifdef _WIN32
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <windows.h>
#  include <mmsystem.h>
#  pragma comment(lib, "winmm.lib")
#else
#  include <dirent.h>
#  include <fcntl.h>
#  include <sys/mman.h>
#  include <sys/stat.h>
#  include <sys/wait.h>
#  include <unistd.h>
#endif

using namespace fsr4ipc;
namespace fsr4 {

#ifndef _WIN32
// Detect if running inside Flatpak sandbox
static bool isFlatpak() {
    return access("/.flatpak-info", F_OK) == 0 || getenv("FLATPAK_ID") != nullptr;
}
#endif

static void sleepUs(int us) { std::this_thread::sleep_for(std::chrono::microseconds(us)); }
using Clock = std::chrono::steady_clock;

BridgeClient::~BridgeClient() { stop(); }

bool BridgeClient::childAlive() {
#ifdef _WIN32
    if (!childProc_) return false;
    if (WaitForSingleObject((HANDLE)childProc_, 0) == WAIT_TIMEOUT) return true;
    CloseHandle((HANDLE)childProc_); childProc_ = nullptr; child_ = 0; return false;
#else
    if (child_ <= 0) return false;
    int st; pid_t r = waitpid(child_, &st, WNOHANG);
    if (r == 0) return true;
    child_ = -1; return false;
#endif
}

bool BridgeClient::start(const BridgeConfig& cfg) {
    abortStart_ = false;
    std::lock_guard<std::mutex> lk(lifeMu_);
    return startLocked(cfg);
}

bool BridgeClient::startLocked(const BridgeConfig& cfg) {
    stopLocked();
    cfg_ = cfg; err_.clear(); seq_ = 0;
#ifdef _WIN32
    timeBeginPeriod(1);   // 1 ms timer: the short sleeps in the reply wait would otherwise round up to the ~15.6 ms scheduler tick (never undone: process lifetime)
    {
        char tmp[MAX_PATH]; DWORD n = GetTempPathA(MAX_PATH, tmp);
        if (!n || n >= MAX_PATH) { err_ = "GetTempPath failed"; return false; }
        path_ = std::string(tmp) + "fsr4-bridge-" + std::to_string((unsigned long)GetCurrentProcessId()) + ".shm";
        Header tmpH{}; size_ = computeLayout(tmpH, cfg.maxRenderW, cfg.maxRenderH, cfg.maxOutW, cfg.maxOutH);
        // The file is the shared medium: the helper opens the same path (see fsr4_bridge.cpp main) and maps it again.
        hFile_ = CreateFileA(path_.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                             CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
        if (hFile_ == INVALID_HANDLE_VALUE) { hFile_ = nullptr; err_ = "cannot create " + path_ + ": error " + std::to_string(GetLastError()); return false; }
        hMap_ = CreateFileMappingA(hFile_, nullptr, PAGE_READWRITE, (DWORD)((uint64_t)size_ >> 32), (DWORD)(size_ & 0xffffffffu), nullptr);
        if (!hMap_) { err_ = "CreateFileMapping failed: " + std::to_string(GetLastError()); return false; }
        base_ = (uint8_t*)MapViewOfFile(hMap_, FILE_MAP_ALL_ACCESS, 0, 0, size_);
        if (!base_) { err_ = "MapViewOfFile failed: " + std::to_string(GetLastError()); return false; }
    }
#else
    path_ = "/dev/shm/fsr4-bridge-" + std::to_string(getpid());
    fd_ = open(path_.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd_ < 0) { err_ = "cannot create " + path_ + ": " + strerror(errno); return false; }
    Header tmp{}; size_ = computeLayout(tmp, cfg.maxRenderW, cfg.maxRenderH, cfg.maxOutW, cfg.maxOutH);
    if (ftruncate(fd_, (off_t)size_) != 0) { err_ = "ftruncate failed"; return false; }
    base_ = (uint8_t*)mmap(nullptr, size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (base_ == MAP_FAILED) { base_ = nullptr; err_ = "mmap failed"; return false; }
#endif
    memset(base_, 0, sizeof(Layout));
    Header* h = hdr();
    computeLayout(*h, cfg.maxRenderW, cfg.maxRenderH, cfg.maxOutW, cfg.maxOutH);
    snprintf(h->requestedProvider, sizeof h->requestedProvider, "%s", cfg.provider.c_str());
    h->transferMode = (uint32_t)cfg.transferMode; h->leaseTimeoutMs = (uint32_t)cfg.leaseTimeoutMs; h->version = kVersion; h->helperState.store(kStarting);
    h->magic = kMagic;   // written last: helper refuses a half-initialised header

#ifdef _WIN32
    {
        // Native Windows: the helper is an ordinary child process (no Wine/Proton layer). Its stdout/stderr go to the helper log.
        std::string exeDir = cfg.helperExe.substr(0, cfg.helperExe.find_last_of("\\/"));
        std::string helperLog = cfg.logFile + ".helper.log";
        SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
        HANDLE hLog = CreateFileA(helperLog.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        STARTUPINFOA si{}; si.cb = sizeof si; si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = INVALID_HANDLE_VALUE; si.hStdOutput = hLog != INVALID_HANDLE_VALUE ? hLog : INVALID_HANDLE_VALUE; si.hStdError = si.hStdOutput;
        PROCESS_INFORMATION pi{};
        std::string cmd = "\"" + cfg.helperExe + "\" \"" + path_ + "\" \"" + cfg.logFile + "\"";
        std::vector<char> cl(cmd.begin(), cmd.end()); cl.push_back(0);
        BOOL ok = CreateProcessA(nullptr, cl.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP, nullptr, exeDir.c_str(), &si, &pi);
        if (hLog != INVALID_HANDLE_VALUE) CloseHandle(hLog);
        if (!ok) { err_ = "CreateProcess(helper) failed: error " + std::to_string(GetLastError()); return false; }
        CloseHandle(pi.hThread); childProc_ = pi.hProcess; child_ = pi.dwProcessId;
    }
#else
    child_ = fork();
    if (child_ < 0) { err_ = "fork failed"; return false; }
    if (child_ == 0) {
        setsid();   // own process group so kill() can take down python+wine together
        std::string exeDir = cfg.helperExe.substr(0, cfg.helperExe.rfind('/'));
        if (chdir(exeDir.c_str()) != 0) _exit(126);
        setenv("STEAM_COMPAT_DATA_PATH", cfg.prefixDir.c_str(), 1);
        setenv("STEAM_COMPAT_CLIENT_INSTALL_PATH", cfg.steamDir.c_str(), 1);
        // GE-Proton installs AMD's driver DLL into this private prefix when no local copy exists.
        // Its own upscaler installer verifies the download; the DLL is never bundled in our JAR.
        const std::string amdDriver = cfg.prefixDir + "/pfx/drive_c/windows/system32/amdxcffx64.dll";
        if (access(amdDriver.c_str(), R_OK) != 0) setenv("PROTON_FSR4_UPGRADE", "1", 0);
        setenv("WINEDEBUG", "-all", 1);
        if (!getenv("FSR4_KEEP_LAYERS")) for (const char* v : {"VK_INSTANCE_LAYERS", "VK_LAYER_PATH", "VK_ADD_LAYER_PATH", "VK_LAYER_ENABLES", "VK_LAYER_MESSAGE_ID_FILTER", "VK_LOADER_LAYERS_ENABLE"}) unsetenv(v);   // game-side debugging layers must not leak into AMD's Wine helper
        
        // Capture helper stdout/stderr to log file for debugging
        std::string helperLog = cfg.logFile + ".helper.log";
        int logFd = open(helperLog.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (logFd >= 0) {
            dup2(logFd, 1);  // stdout
            dup2(logFd, 2);  // stderr
        } else {
            int devnull = open("/dev/null", O_RDWR); dup2(devnull, 1); dup2(devnull, 2);
        }
        
        std::string proton = cfg.protonDir + "/proton";
        std::string winMap = "Z:" + path_, winLog = "Z:" + cfg.logFile;
        int devnull = open("/dev/null", O_RDWR); dup2(devnull, 0);
        execlp("python3", "python3", proton.c_str(), "run", cfg.helperExe.c_str(), winMap.c_str(), winLog.c_str(), (char*)nullptr);
        _exit(127);
    }
#endif
    leaseRun_ = true;
    lease_ = std::thread([this] { while (leaseRun_) { hdr()->clientBeat.fetch_add(1, std::memory_order_release); sleepUs(200000); } });
    auto t0 = Clock::now();
    for (;;) {
        uint32_t s = h->helperState.load(std::memory_order_acquire);
        if (s == kReady) return true;
        if (s == kFailed) { err_ = std::string("helper failed: ") + h->error; kill(); return false; }
        if (!childAlive()) { err_ = "helper process exited during start (see " + cfg.logFile + ")"; return false; }
        if (abortStart_) { err_ = "helper start aborted (shutdown)"; kill(); return false; }
        if (std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count() > cfg.startTimeoutMs) { err_ = "helper start timeout"; kill(); return false; }
        sleepUs(20000);
    }
}

bool BridgeClient::running() { return base_ && childAlive() && hdr()->helperState.load(std::memory_order_acquire) == kReady; }

BridgeResult BridgeClient::post(FrameParams p) {
    if (!base_ || !running()) return BridgeResult::NotRunning;
    if (outstanding_) return BridgeResult::Internal;
    if (!base_ || !running()) return BridgeResult::NotRunning;
    Request* r = req();
    if (!p.colorPitch) p.colorPitch = p.renderW * 8;
    if (!p.depthPitch) p.depthPitch = p.renderW * 4;
    if (!p.mvPitch) p.mvPitch = p.renderW * 4;
    memset(r->provider, 0, sizeof r->provider); snprintf(r->provider, sizeof r->provider, "%s", p.provider.c_str());
    r->cmd = p.cmd; r->frameId = p.frameId; r->renderW = p.renderW; r->renderH = p.renderH; r->outW = p.outW; r->outH = p.outH;
    r->flags = p.flags; r->quality = p.quality;
    r->colorFmt = p.colorFmt; r->depthFmt = p.depthFmt; r->mvFmt = p.mvFmt; r->outFmt = p.outFmt;
    r->colorPitch = p.colorPitch; r->depthPitch = p.depthPitch; r->mvPitch = p.mvPitch;
    r->jitterX = p.jitterX; r->jitterY = p.jitterY; r->mvScaleX = p.mvScaleX; r->mvScaleY = p.mvScaleY;
    r->frameTimeMs = p.frameTimeMs; r->preExposure = p.preExposure; r->sharpness = p.sharpness;
    r->cameraNear = p.cameraNear; r->cameraFar = p.cameraFar; r->fovY = p.fovY; r->enableSharpening = p.enableSharpening;
    memcpy(r->fgCam, p.fgCam, sizeof r->fgCam);
    r->fenceWait = p.fenceWait; r->fenceSignal = p.fenceSignal;
    r->colorCrc = r->depthCrc = r->mvCrc = 0;
    if (p.withCrc && p.renderW && p.renderH) {
        r->colorCrc = crc32(colorPlane(), (size_t)p.colorPitch * p.renderH) | 1u;   // |1 keeps it non-zero ("provided")
        r->depthCrc = crc32(depthPlane(), (size_t)p.depthPitch * p.renderH) | 1u;
        r->mvCrc = crc32(mvPlane(), (size_t)p.mvPitch * p.renderH) | 1u;
    }
    if (p.corruptCrc) r->colorCrc ^= 0x10u;
    // helper compares crc32(...) with these; make both sides apply the same |1
    uint64_t s = ++seq_;
    r->seq.store(s, std::memory_order_release);
    pendingFrameId_ = p.frameId;
    outstanding_ = true;
    return BridgeResult::Ok;
}

BridgeResult BridgeClient::collect(int timeoutMs, Response* outResp) {
    if (!outstanding_) return BridgeResult::Internal;
    uint64_t s = seq_;
    auto t0 = Clock::now(); uint64_t hb = hdr()->heartbeat.load(); auto hbT = t0; unsigned spin = 0; static const bool legacy = getenv("FSR4_LEGACY_WAIT") != nullptr; static const long spinUs = getenv("FSR4_CLIENT_SPIN_US") ? atol(getenv("FSR4_CLIENT_SPIN_US")) : 8000;
    for (;;) {
        if (resp()->seq.load(std::memory_order_acquire) == s) {
            outstanding_ = false;
            if (resp()->frameId != pendingFrameId_ || resp()->status > kInternal ||
                !memchr(resp()->message, 0, sizeof resp()->message)) {
                kill();
                return BridgeResult::Internal;
            }
            if (outResp) memcpy(outResp, (const void*)resp(), sizeof(Response));
            return resp()->status == kOk ? BridgeResult::Ok : BridgeResult::Rejected;
        }
        auto now = Clock::now();
        if (legacy) { if (++spin > 2000) sleepUs(50); } else if (++spin > 2000 && (spin & 63) == 0 && std::chrono::duration_cast<std::chrono::microseconds>(now - t0).count() > spinUs) sleepUs(50);
        if ((spin & 63) == 0) {
            if (!childAlive()) { outstanding_ = false; return BridgeResult::HelperDied; }
            uint64_t h2 = hdr()->heartbeat.load();
            if (h2 != hb) { hb = h2; hbT = now; }
        }
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - t0).count() > timeoutMs) {
            outstanding_ = false;
            kill(); // A late reply must never be mistaken for the next request.
            return BridgeResult::Timeout;
        }
    }
}

BridgeResult BridgeClient::submit(FrameParams p, int timeoutMs, Response* outResp) {
    BridgeResult pr = post(std::move(p)); if (pr != BridgeResult::Ok) return pr;
    return collect(timeoutMs, outResp);
}

std::string BridgeClient::activeProvider() const { return base_ ? std::string(hdr()->activeProvider) : ""; }
uint64_t BridgeClient::helperPid() const { return base_ ? hdr()->helperPid : 0; }

#ifdef _WIN32
std::vector<pid_t> BridgeClient::helperLinuxPids() const { return child_ ? std::vector<pid_t>{child_} : std::vector<pid_t>{}; }
void BridgeClient::killHelperProcesses() {}   // no Wine re-parenting on Windows: the helper is our direct child

void BridgeClient::kill() {
    if (childProc_) {
        TerminateProcess((HANDLE)childProc_, 1);
        WaitForSingleObject((HANDLE)childProc_, 5000);
        CloseHandle((HANDLE)childProc_); childProc_ = nullptr; child_ = 0;
    }
    if (base_) hdr()->helperState.store(kShutdown);
}
#else
static std::vector<pid_t> scanFor(const std::string& needle) {
    std::vector<pid_t> v; DIR* d = opendir("/proc"); if (!d) return v;
    while (dirent* e = readdir(d)) {
        pid_t pid = (pid_t)atoi(e->d_name); if (pid <= 1 || pid == getpid()) continue;
        std::ifstream f(std::string("/proc/") + e->d_name + "/cmdline", std::ios::binary);
        std::string c((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        if (c.find(needle) != std::string::npos) v.push_back(pid);
    }
    closedir(d); return v;
}
std::vector<pid_t> BridgeClient::helperLinuxPids() const { return path_.empty() ? std::vector<pid_t>{} : scanFor("Z:" + path_); }
void BridgeClient::killHelperProcesses() { for (pid_t p : helperLinuxPids()) ::kill(p, SIGKILL); }

void BridgeClient::kill() {
    killHelperProcesses();
    if (child_ > 0) {
        ::kill(-child_, SIGKILL); ::kill(child_, SIGKILL);
        int st; waitpid(child_, &st, 0); child_ = -1;
    }
    if (base_) hdr()->helperState.store(kShutdown);
}

#endif

void BridgeClient::stop() {
    abortStart_ = true;
    std::lock_guard<std::mutex> lk(lifeMu_);
    stopLocked();
}

void BridgeClient::stopLocked() {
    if (base_ && child_ > 0 && hdr()->helperState.load() == kReady) {
        req()->seq.store(UINT64_MAX, std::memory_order_release);   // clean shutdown request
        for (int i = 0; i < 300 && childAlive() && hdr()->helperState.load() != kShutdown; ++i) sleepUs(10000);
    }
    kill();
    leaseRun_ = false; if (lease_.joinable()) lease_.join();
#ifdef _WIN32
    if (base_) { UnmapViewOfFile(base_); base_ = nullptr; }
    if (hMap_) { CloseHandle((HANDLE)hMap_); hMap_ = nullptr; }
    if (hFile_) { CloseHandle((HANDLE)hFile_); hFile_ = nullptr; }
    if (!path_.empty()) { DeleteFileA(path_.c_str()); path_.clear(); }
#else
    if (base_) { munmap(base_, size_); base_ = nullptr; }
    if (fd_ >= 0) { close(fd_); fd_ = -1; }
    if (!path_.empty()) { unlink(path_.c_str()); path_.clear(); }
#endif
}

}  // namespace fsr4
