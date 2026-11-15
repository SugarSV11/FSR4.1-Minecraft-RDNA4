// win_smoke.cpp - native Windows 11 smoke test of the bridge (no Wine): starts fsr4_bridge.exe, creates a Vulkan device on the AMD GPU,
// imports the helper's named D3D12 shared textures into Vulkan, runs the CRC self-test and creates the shared timeline fence.
// usage: win_smoke.exe <fsr4_bridge.exe> <log file> [provider]
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "bridge_client.hpp"
#include "gpu_bridge.hpp"

using namespace fsr4;
using namespace fsr4ipc;

static float halton(int i, int b) { float f = 1, r = 0; while (i > 0) { f /= (float)b; r += f * (float)(i % b); i /= b; } return r; }
#define CHECK(x, msg) do { if (!(x)) { printf("FAIL: %s\n", msg); return 2; } } while (0)

int main(int argc, char** argv) {
    if (argc < 3) { printf("usage: win_smoke <fsr4_bridge.exe> <log> [provider]\n"); return 64; }
    HMODULE vk = LoadLibraryA("vulkan-1.dll"); CHECK(vk, "vulkan-1.dll");
    auto gipa = (PFN_vkGetInstanceProcAddr)GetProcAddress(vk, "vkGetInstanceProcAddr"); CHECK(gipa, "vkGetInstanceProcAddr");
    auto createInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO}; ai.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &ai;
    VkInstance inst; CHECK(createInstance(&ici, nullptr, &inst) == VK_SUCCESS, "vkCreateInstance");
    auto enumPD = (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
    auto getProps = (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    auto getQF = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)gipa(inst, "vkGetPhysicalDeviceQueueFamilyProperties");
    auto createDevice = (PFN_vkCreateDevice)gipa(inst, "vkCreateDevice");
    uint32_t n = 0; enumPD(inst, &n, nullptr); std::vector<VkPhysicalDevice> pds(n); enumPD(inst, &n, pds.data());
    VkPhysicalDevice phys = nullptr; VkPhysicalDeviceProperties pp{};
    for (auto d : pds) { getProps(d, &pp); printf("GPU: %s (vendor 0x%x)\n", pp.deviceName, pp.vendorID); if (pp.vendorID == 0x1002 && !phys) phys = d; }
    CHECK(phys, "no AMD GPU");
    getProps(phys, &pp); printf("using %s\n", pp.deviceName);
    uint32_t qn = 0; getQF(phys, &qn, nullptr); std::vector<VkQueueFamilyProperties> qf(qn); getQF(phys, &qn, qf.data());
    uint32_t fam = ~0u; for (uint32_t i = 0; i < qn; ++i) if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) { fam = i; break; }
    CHECK(fam != ~0u, "no graphics+compute queue");
    float prio = 1.f; VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex = fam; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    const char* exts[] = {"VK_KHR_external_memory", "VK_KHR_external_memory_win32", "VK_KHR_external_semaphore", "VK_KHR_external_semaphore_win32", "VK_KHR_dedicated_allocation", "VK_KHR_timeline_semaphore"};
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}; f12.timelineSemaphore = VK_TRUE;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &f12}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci; dci.enabledExtensionCount = 6; dci.ppEnabledExtensionNames = exts;
    VkDevice dev; VkResult dr = createDevice(phys, &dci, nullptr, &dev); if (dr != VK_SUCCESS) { printf("FAIL: vkCreateDevice = %d\n", (int)dr); return 2; }
    auto gdpa = (PFN_vkGetDeviceProcAddr)gipa(inst, "vkGetDeviceProcAddr"); auto getQueue = (PFN_vkGetDeviceQueue)gdpa(dev, "vkGetDeviceQueue");
    VkQueue q; getQueue(dev, fam, 0, &q);

    BridgeConfig cfg; cfg.helperExe = argv[1]; cfg.logFile = argv[2]; cfg.provider = argc > 3 ? argv[3] : "4.1.1";
    cfg.maxRenderW = 1280; cfg.maxRenderH = 720; cfg.maxOutW = 2560; cfg.maxOutH = 1440; cfg.transferMode = 2; cfg.startTimeoutMs = 60000;
    BridgeClient bc;
    printf("starting helper...\n");
    if (!bc.start(cfg)) { printf("FAIL: helper start: %s\n", bc.error().c_str()); return 3; }
    printf("helper ready. offered providers: [%s] active: [%s]\n", bc.offeredProviders().c_str(), bc.activeProvider().c_str());

    VkCtx ctx; ctx.instance = inst; ctx.phys = phys; ctx.device = dev; ctx.queue = q; ctx.queueFamily = fam; ctx.gipa = gipa;
    GpuBridge gb; std::string err;
    if (!gb.init(ctx, &bc, &err)) { printf("FAIL: GpuBridge::init: %s\n", err.c_str()); return 4; }
    if (!gb.allocate(1280, 720, 2560, 1440, Fmt::RGBA16F, Fmt::RGBA16F, &err)) { printf("FAIL: allocate/import/selftest: %s\n", err.c_str()); bc.stop(); return 5; }
    printf("PASS: 4 shared D3D12 textures imported into Vulkan and verified by CRC (usage %s)\n", gb.usageUsed());
    if (!gb.initSharedFence(&err)) { printf("NOTE: shared fence not available: %s\n", err.c_str()); }
    else printf("PASS: shared D3D12 fence imported as a Vulkan timeline semaphore\n");
    // Real FSR dispatch through the official AMD runtime: 1280x720 -> 2560x1440, 120 frames (planes hold the self-test pattern, so only timing/status matter).
    {
        double sum = 0, mn = 1e9, mx = 0; int ok = 0; Response last{};
        for (int f = 0; f < 120; ++f) {
            FrameParams p; p.cmd = kCmdFrame; p.frameId = (uint64_t)f + 1; p.renderW = 1280; p.renderH = 720; p.outW = 2560; p.outH = 1440;
            p.flags = (f == 0 ? kReset : 0) | kAutoExposure | kNonLinearSrgb; p.quality = 1; p.jitterX = halton((f % 8) + 1, 2) - 0.5f; p.jitterY = halton((f % 8) + 1, 3) - 0.5f; p.frameTimeMs = 16.6f;
            p.provider = argc > 3 ? argv[3] : "4.1.2"; Response r{};
            BridgeResult br = bc.submit(p, 5000, &r);
            if (br != BridgeResult::Ok) { printf("FRAME %d failed: result=%d status=%u ffx=%u msg=%s\n", f, (int)br, r.status, r.ffxReturnCode, r.message); break; }
            ++ok; if (f >= 10) { sum += r.gpuDispatchMs; mn = r.gpuDispatchMs < mn ? r.gpuDispatchMs : mn; mx = r.gpuDispatchMs > mx ? r.gpuDispatchMs : mx; }
        }
        if (ok == 120) printf("PASS: %d FSR dispatches OK, active provider [%s], GPU dispatch ms avg %.3f min %.3f max %.3f (frames 10..119)\n", ok, bc.activeProvider().c_str(), sum / 110.0, mn, mx);
        else printf("FAIL: only %d/120 dispatches succeeded\n", ok);
    }
    gb.release(); bc.stop();
    printf("done\n");
    return 0;
}
