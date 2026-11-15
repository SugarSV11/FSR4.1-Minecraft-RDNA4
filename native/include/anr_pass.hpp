// ANR FG (Apex Neural Rendering, closed-source beta by Apex Compute) as a frame generator inside Minecraft's Vulkan device.
// ANR ships as a DLSS-G NGX "provider" DLL (nvngx_dlssg.dll) with Vulkan exports; this class is its host: it implements the
// NGX parameter map, loads the DLL at run time and records EvaluateFeature into Minecraft's command buffers. Same contract as
// FgX3Pass (fsr4_x3_* entry points route here when ANR is selected), so the Java x3 presentation path drives it unchanged.
// Inputs: Minecraft's planes (depth R32F reversed-Z and motion vectors RG16F in render-resolution pixels, prev - cur, both GENERAL)
// and the colour images copied from Minecraft (world-only colour = HUD-less, final colour incl. HUD = backbuffer).
// Requires the NGX interface headers (NVIDIA RTX SDK licence, local only: native/third_party/ngx, never committed).
#pragma once
#include <string>
#include "gpu_bridge.hpp"

namespace fsr4 {

class AnrPass {
public:
    ~AnrPass() { destroy(); }
    // Loads the provider DLL and initialises it on the device (NGX Init_Ext + PopulateParameters). Idempotent for the same path.
    bool init(const VkCtx& ctx, const std::string& dllPath, std::string* err);
    bool initialised() const { return init_; }
    unsigned maxGenerated() const { return maxCount_; }   // DLSSG.MultiFrameCountMax reported by the provider
    bool alloc(uint32_t dispW, uint32_t dispH, uint32_t rendW, uint32_t rendH, VkImage depth, VkImage mv, std::string* err);
    void release();   // per-size resources and the NGX feature (GPU must be idle)
    void destroy();   // also unloads the provider (VkDevice still alive)
    void recordHudless(VkCommandBuffer cb, VkImage mcColor);
    // Generated frame idx of n (t = idx/n): idx==1 copies mcFinal first; result copied into dst (GENERAL). False until a previous frame exists.
    bool recordGenerateN(VkCommandBuffer cb, VkImage mcFinal, int idx, int n, VkImage dst);
    void recordEndFrame(VkCommandBuffer cb);
    void invalidate() { havePrev_ = false; reset_ = true; }
    bool ready() const { return havePrev_; }
    bool gpuMs(float* ms) const { if (!tsOn_ || !tsValid_) return false; *ms = tsMs_; return true; }
    uint64_t deviceBytes() const { return bytes_; }
    const std::string& dllPath() const { return dll_; }
    struct Fns; struct Ngx;
private:
    VkCtx ctx_{}; Fns* f_ = nullptr; Ngx* n_ = nullptr; std::string dll_; bool init_ = false; unsigned maxCount_ = 0;
    uint32_t dw_ = 0, dh_ = 0, rw_ = 0, rh_ = 0; bool havePrev_ = false, reset_ = true; uint64_t frameId_ = 0, bytes_ = 0;
    VkCommandPool pool_ = nullptr;
    VkImage img_[3] = {}; VkDeviceMemory mem_[3] = {}; VkImageView view_[3] = {};   // 0 HUD-less colour, 1 final colour (backbuffer), 2 output
    VkImageView depthView_ = nullptr, mvView_ = nullptr; VkImage depthIn_ = nullptr, mvIn_ = nullptr;
    float mvScaleX_ = 1.f, mvScaleY_ = 1.f; bool hudless_ = true;
    VkQueryPool qp_ = nullptr; bool tsOn_ = false, tsValid_ = false; double tsPeriod_ = 1.0; float tsMs_ = 0; uint32_t tsSlot_ = 0; uint64_t tsSlotFrame_[8] = {}, tsFrame_ = 0;
    void pollTs();
};

}  // namespace fsr4
