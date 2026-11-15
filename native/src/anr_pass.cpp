#include "anr_pass.hpp"
#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <cstdlib>
#include <cstring>
#include <unordered_map>
#include <nvsdk_ngx_defs.h>
#include <nvsdk_ngx_params.h>
#include <nvsdk_ngx_defs_vk.h>
#include <nvsdk_ngx_defs_dlssg.h>

namespace fsr4 {

struct AnrPass::Fns {
#define F(n) PFN_##n n = nullptr;
    F(vkCreateImage) F(vkDestroyImage) F(vkGetImageMemoryRequirements) F(vkBindImageMemory) F(vkCreateImageView) F(vkDestroyImageView)
    F(vkAllocateMemory) F(vkFreeMemory) F(vkCmdPipelineBarrier) F(vkCmdCopyImage)
    F(vkCreateCommandPool) F(vkDestroyCommandPool) F(vkAllocateCommandBuffers) F(vkBeginCommandBuffer) F(vkEndCommandBuffer) F(vkQueueSubmit) F(vkQueueWaitIdle) F(vkFreeCommandBuffers)
    F(vkCreateQueryPool) F(vkDestroyQueryPool) F(vkCmdResetQueryPool) F(vkCmdWriteTimestamp) F(vkGetQueryPoolResults) F(vkGetPhysicalDeviceProperties) F(vkGetPhysicalDeviceMemoryProperties)
#undef F
};

// The host side of the NGX parameter interface: values by name; numbers readable as any number type, pointers as void*.
class AnrParams final : public NVSDK_NGX_Parameter {
    struct V { bool ptr; double n; void* p; };
    std::unordered_map<std::string, V> m_;
    template <class T> NVSDK_NGX_Result num(const char* k, T* o) const { auto i = m_.find(k); if (!o || i == m_.end() || i->second.ptr) return NVSDK_NGX_Result_Fail; *o = (T)i->second.n; return NVSDK_NGX_Result_Success; }
    template <class T> NVSDK_NGX_Result ptr(const char* k, T** o) const { auto i = m_.find(k); if (!o || i == m_.end() || !i->second.ptr) return NVSDK_NGX_Result_Fail; *o = (T*)i->second.p; return NVSDK_NGX_Result_Success; }
public:
    void Set(const char* k, unsigned long long v) override { m_[k] = {false, (double)v, nullptr}; }
    void Set(const char* k, float v) override { m_[k] = {false, v, nullptr}; }
    void Set(const char* k, double v) override { m_[k] = {false, v, nullptr}; }
    void Set(const char* k, unsigned int v) override { m_[k] = {false, (double)v, nullptr}; }
    void Set(const char* k, int v) override { m_[k] = {false, (double)v, nullptr}; }
    void Set(const char* k, ID3D11Resource* v) override { m_[k] = {true, 0, v}; }
    void Set(const char* k, ID3D12Resource* v) override { m_[k] = {true, 0, v}; }
    void Set(const char* k, void* v) override { m_[k] = {true, 0, v}; }
    NVSDK_NGX_Result Get(const char* k, unsigned long long* o) const override { return num(k, o); }
    NVSDK_NGX_Result Get(const char* k, float* o) const override { return num(k, o); }
    NVSDK_NGX_Result Get(const char* k, double* o) const override { return num(k, o); }
    NVSDK_NGX_Result Get(const char* k, unsigned int* o) const override { return num(k, o); }
    NVSDK_NGX_Result Get(const char* k, int* o) const override { return num(k, o); }
    NVSDK_NGX_Result Get(const char* k, ID3D11Resource** o) const override { return ptr(k, o); }
    NVSDK_NGX_Result Get(const char* k, ID3D12Resource** o) const override { return ptr(k, o); }
    NVSDK_NGX_Result Get(const char* k, void** o) const override { return ptr(k, o); }
    void Reset() override { m_.clear(); }
};

struct AnrPass::Ngx {
    using PFN_Init = NVSDK_NGX_Result(NVSDK_CONV*)(unsigned long long, const wchar_t*, VkInstance, VkPhysicalDevice, VkDevice, NVSDK_NGX_Version, const NVSDK_NGX_Parameter*);
    using PFN_Populate = NVSDK_NGX_Result(NVSDK_CONV*)(NVSDK_NGX_Parameter*);
    using PFN_Create1 = NVSDK_NGX_Result(NVSDK_CONV*)(VkDevice, VkCommandBuffer, NVSDK_NGX_Feature, NVSDK_NGX_Parameter*, NVSDK_NGX_Handle**);
    using PFN_Evaluate = NVSDK_NGX_Result(NVSDK_CONV*)(VkCommandBuffer, const NVSDK_NGX_Handle*, const NVSDK_NGX_Parameter*, PFN_NVSDK_NGX_ProgressCallback);
    using PFN_Release = NVSDK_NGX_Result(NVSDK_CONV*)(NVSDK_NGX_Handle*);
    using PFN_Shutdown1 = NVSDK_NGX_Result(NVSDK_CONV*)(VkDevice);
    HMODULE mod = nullptr; PFN_Init init = nullptr; PFN_Populate populate = nullptr; PFN_Create1 create1 = nullptr; PFN_Evaluate evaluate = nullptr; PFN_Release release = nullptr; PFN_Shutdown1 shutdown1 = nullptr;
    AnrParams p; NVSDK_NGX_Handle* h = nullptr;
    NVSDK_NGX_Resource_VK res[5] = {};   // 0 HUD-less, 1 backbuffer, 2 output, 3 depth, 4 motion vectors: must outlive EvaluateFeature's recording
    float proj[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
};

static std::string hex(NVSDK_NGX_Result r) { char b[16]; snprintf(b, sizeof b, "0x%08x", (unsigned)r); return b; }
static float envF(const char* n, float d) { const char* s = std::getenv(n); return s ? (float)atof(s) : d; }

bool AnrPass::init(const VkCtx& c, const std::string& dllPath, std::string* err) {
    if (init_ && dll_ == dllPath && ctx_.device == c.device) return true;
    destroy(); ctx_ = c; f_ = new Fns(); n_ = new Ngx(); dll_ = dllPath;
    auto gd = (PFN_vkGetDeviceProcAddr)c.gipa(c.instance, "vkGetDeviceProcAddr");
#define LD(n) f_->n = (PFN_##n)gd(c.device, #n); if (!f_->n) { if (err) *err = "missing Vulkan entry point " #n; return false; }
    LD(vkCreateImage) LD(vkDestroyImage) LD(vkGetImageMemoryRequirements) LD(vkBindImageMemory) LD(vkCreateImageView) LD(vkDestroyImageView)
    LD(vkAllocateMemory) LD(vkFreeMemory) LD(vkCmdPipelineBarrier) LD(vkCmdCopyImage)
    LD(vkCreateCommandPool) LD(vkDestroyCommandPool) LD(vkAllocateCommandBuffers) LD(vkBeginCommandBuffer) LD(vkEndCommandBuffer) LD(vkQueueSubmit) LD(vkQueueWaitIdle) LD(vkFreeCommandBuffers)
#undef LD
    f_->vkGetPhysicalDeviceMemoryProperties = (PFN_vkGetPhysicalDeviceMemoryProperties)c.gipa(c.instance, "vkGetPhysicalDeviceMemoryProperties");
    f_->vkGetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)c.gipa(c.instance, "vkGetPhysicalDeviceProperties");
    tsOn_ = std::getenv("FSR4_GPU_TS") != nullptr;
    if (tsOn_) {
        f_->vkCreateQueryPool = (PFN_vkCreateQueryPool)gd(c.device, "vkCreateQueryPool"); f_->vkDestroyQueryPool = (PFN_vkDestroyQueryPool)gd(c.device, "vkDestroyQueryPool");
        f_->vkCmdResetQueryPool = (PFN_vkCmdResetQueryPool)gd(c.device, "vkCmdResetQueryPool"); f_->vkCmdWriteTimestamp = (PFN_vkCmdWriteTimestamp)gd(c.device, "vkCmdWriteTimestamp");
        f_->vkGetQueryPoolResults = (PFN_vkGetQueryPoolResults)gd(c.device, "vkGetQueryPoolResults");
        VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; qi.queryType = VK_QUERY_TYPE_TIMESTAMP; qi.queryCount = 16;
        VkPhysicalDeviceProperties pr; f_->vkGetPhysicalDeviceProperties(c.phys, &pr); tsPeriod_ = pr.limits.timestampPeriod;
        if (!f_->vkCreateQueryPool || f_->vkCreateQueryPool(c.device, &qi, nullptr, &qp_) != VK_SUCCESS) tsOn_ = false;
    }
    VkCommandPoolCreateInfo cpo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpo.queueFamilyIndex = c.queueFamily; cpo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    if (f_->vkCreateCommandPool(c.device, &cpo, nullptr, &pool_)) { if (err) *err = "ANR command pool failed"; return false; }

    // The DLL loads its programs/, kernels/ and nr_fg.ini from its own folder.
    int wl = MultiByteToWideChar(CP_UTF8, 0, dllPath.c_str(), -1, nullptr, 0); std::wstring w(wl > 0 ? wl - 1 : 0, L'\0'); if (wl > 1) MultiByteToWideChar(CP_UTF8, 0, dllPath.c_str(), -1, w.data(), wl);
    n_->mod = LoadLibraryExW(w.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!n_->mod) { if (err) *err = "cannot load ANR provider " + dllPath + " (error " + std::to_string(GetLastError()) + ")"; return false; }
    auto get = [&](const char* s) { return (void*)GetProcAddress(n_->mod, s); };
    n_->init = (Ngx::PFN_Init)get("NVSDK_NGX_VULKAN_Init_Ext"); n_->populate = (Ngx::PFN_Populate)get("NVSDK_NGX_VULKAN_PopulateParameters_Impl");
    n_->create1 = (Ngx::PFN_Create1)get("NVSDK_NGX_VULKAN_CreateFeature1"); n_->evaluate = (Ngx::PFN_Evaluate)get("NVSDK_NGX_VULKAN_EvaluateFeature");
    n_->release = (Ngx::PFN_Release)get("NVSDK_NGX_VULKAN_ReleaseFeature"); n_->shutdown1 = (Ngx::PFN_Shutdown1)get("NVSDK_NGX_VULKAN_Shutdown1");
    if (!n_->init || !n_->create1 || !n_->evaluate || !n_->release) { if (err) *err = dllPath + " lacks the NGX Vulkan exports (Init_Ext, CreateFeature1, EvaluateFeature, ReleaseFeature)"; return false; }
    wchar_t temp[MAX_PATH] = {}; GetTempPathW(MAX_PATH, temp);
    NVSDK_NGX_Result r = n_->init(1, temp, c.instance, c.phys, c.device, NVSDK_NGX_Version_API, nullptr);
    if (NVSDK_NGX_FAILED(r)) { if (err) *err = "ANR NGX Init_Ext: " + hex(r); return false; }
    if (n_->populate) n_->populate(&n_->p);
    int available = 0; n_->p.Get(NVSDK_NGX_Parameter_FrameGeneration_Available, &available); n_->p.Get(NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax, &maxCount_);
    if (!available) { if (err) *err = "ANR reports frame generation not available on this device"; return false; }
    // Minecraft's prep pass writes motion vectors in render-resolution pixels (prev - cur); ANR reads MvecScale 1 as pixels (measured in tests/anr_smoke.cpp).
    mvScaleX_ = envF("FSR4_ANR_MVX", 1.f); mvScaleY_ = envF("FSR4_ANR_MVY", 1.f);
    hudless_ = envF("FSR4_ANR_HUDLESS", 1.f) != 0.f;   // 0: give ANR the final colour as HUD-less too (diagnostic)
    init_ = true; return true;
}

static int memType(AnrPass::Fns* f, VkPhysicalDevice phys, uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp; f->vkGetPhysicalDeviceMemoryProperties(phys, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    return -1;
}

static void setRes(NVSDK_NGX_Resource_VK& r, VkImageView v, VkImage i, VkFormat f, uint32_t w, uint32_t h, bool rw) {
    std::memset(&r, 0, sizeof r); r.Resource.ImageViewInfo = {v, i, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, f, w, h}; r.Type = NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW; r.ReadWrite = rw;
}

bool AnrPass::alloc(uint32_t dw, uint32_t dh, uint32_t rw, uint32_t rh, VkImage depth, VkImage mv, std::string* err) {
    release(); if (!init_) { if (err) *err = "ANR not initialised"; return false; }
    dw_ = dw; dh_ = dh; rw_ = rw; rh_ = rh; depthIn_ = depth; mvIn_ = mv; VkDevice d = ctx_.device;
    auto fail = [&](const std::string& m) { if (err) *err = m; release(); return false; };
    const VkImageUsageFlags use[3] = {VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT};
    for (int i = 0; i < 3; ++i) {
        VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ic.imageType = VK_IMAGE_TYPE_2D; ic.format = VK_FORMAT_R8G8B8A8_UNORM; ic.extent = {dw, dh, 1}; ic.mipLevels = 1; ic.arrayLayers = 1;
        ic.samples = VK_SAMPLE_COUNT_1_BIT; ic.tiling = VK_IMAGE_TILING_OPTIMAL; ic.usage = use[i]; ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (f_->vkCreateImage(d, &ic, nullptr, &img_[i])) return fail("ANR image creation failed");
        VkMemoryRequirements mr; f_->vkGetImageMemoryRequirements(d, img_[i], &mr); int mt = memType(f_, ctx_.phys, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize = mr.size; mai.memoryTypeIndex = mt < 0 ? 0 : (uint32_t)mt;
        if (mt < 0 || f_->vkAllocateMemory(d, &mai, nullptr, &mem_[i]) || f_->vkBindImageMemory(d, img_[i], mem_[i], 0)) return fail("ANR image memory failed");
        bytes_ += mr.size;
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = img_[i]; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = VK_FORMAT_R8G8B8A8_UNORM; vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (f_->vkCreateImageView(d, &vi, nullptr, &view_[i])) return fail("ANR image view failed");
    }
    VkImageViewCreateInfo vd{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vd.image = depth; vd.viewType = VK_IMAGE_VIEW_TYPE_2D; vd.format = VK_FORMAT_R32_SFLOAT; vd.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (f_->vkCreateImageView(d, &vd, nullptr, &depthView_)) return fail("ANR depth view failed");
    VkImageViewCreateInfo vm = vd; vm.image = mv; vm.format = VK_FORMAT_R16G16_SFLOAT; if (f_->vkCreateImageView(d, &vm, nullptr, &mvView_)) return fail("ANR mv view failed");
    setRes(n_->res[0], view_[0], img_[0], VK_FORMAT_R8G8B8A8_UNORM, dw, dh, false);
    setRes(n_->res[1], view_[1], img_[1], VK_FORMAT_R8G8B8A8_UNORM, dw, dh, false);
    setRes(n_->res[2], view_[2], img_[2], VK_FORMAT_R8G8B8A8_UNORM, dw, dh, true);
    setRes(n_->res[3], depthView_, depth, VK_FORMAT_R32_SFLOAT, rw, rh, false);
    setRes(n_->res[4], mvView_, mv, VK_FORMAT_R16G16_SFLOAT, rw, rh, false);

    // Private images to GENERAL, then the NGX feature (its creation records into a one-off command buffer).
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = pool_; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1; VkCommandBuffer cb;
    if (f_->vkAllocateCommandBuffers(d, &cai, &cb)) return fail("ANR command buffer failed");
    VkCommandBufferBeginInfo bg{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bg.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT; f_->vkBeginCommandBuffer(cb, &bg);
    VkImageMemoryBarrier ib[3] = {}; for (int i = 0; i < 3; ++i) { ib[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER; ib[i].newLayout = VK_IMAGE_LAYOUT_GENERAL; ib[i].srcQueueFamilyIndex = ib[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib[i].image = img_[i]; ib[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}; ib[i].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT; }
    f_->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 3, ib);
    AnrParams& p = n_->p;
    p.Set(NVSDK_NGX_Parameter_CreationNodeMask, 1u); p.Set(NVSDK_NGX_Parameter_VisibilityNodeMask, 1u);
    p.Set(NVSDK_NGX_Parameter_Width, dw); p.Set(NVSDK_NGX_Parameter_Height, dh); p.Set(NVSDK_NGX_DLSSG_Parameter_Width, dw); p.Set(NVSDK_NGX_DLSSG_Parameter_Height, dh);
    p.Set(NVSDK_NGX_DLSSG_Parameter_BackbufferFormat, (unsigned)VK_FORMAT_R8G8B8A8_UNORM); p.Set(NVSDK_NGX_DLSSG_Parameter_InternalWidth, rw); p.Set(NVSDK_NGX_DLSSG_Parameter_InternalHeight, rh);
    p.Set(NVSDK_NGX_DLSSG_Parameter_DynamicResolution, 0u);
    NVSDK_NGX_Result r = n_->create1(d, cb, NVSDK_NGX_Feature_FrameGeneration, &p, &n_->h);
    f_->vkEndCommandBuffer(cb);
    VkSubmitInfo su{VK_STRUCTURE_TYPE_SUBMIT_INFO}; su.commandBufferCount = 1; su.pCommandBuffers = &cb; VkResult vr = f_->vkQueueSubmit(ctx_.queue, 1, &su, nullptr); if (vr == VK_SUCCESS) f_->vkQueueWaitIdle(ctx_.queue);
    f_->vkFreeCommandBuffers(d, pool_, 1, &cb);
    if (NVSDK_NGX_FAILED(r) || !n_->h) return fail("ANR CreateFeature1 (frame generation): " + hex(r));
    if (vr != VK_SUCCESS) return fail("ANR setup submit failed");
    havePrev_ = false; reset_ = true; return true;
}

void AnrPass::release() {
    if (!f_) return; VkDevice d = ctx_.device;
    if (n_ && n_->h) { n_->release(n_->h); n_->h = nullptr; }
    for (auto& v : view_) if (v) f_->vkDestroyImageView(d, v, nullptr), v = nullptr;
    if (depthView_) f_->vkDestroyImageView(d, depthView_, nullptr); depthView_ = nullptr; if (mvView_) f_->vkDestroyImageView(d, mvView_, nullptr); mvView_ = nullptr;
    for (auto& i : img_) if (i) f_->vkDestroyImage(d, i, nullptr), i = nullptr;
    for (auto& m : mem_) if (m) f_->vkFreeMemory(d, m, nullptr), m = nullptr;
    bytes_ = 0; havePrev_ = false; depthIn_ = mvIn_ = nullptr;
}

void AnrPass::destroy() {
    if (!f_) { delete n_; n_ = nullptr; return; }
    release(); VkDevice d = ctx_.device;
    if (n_ && n_->shutdown1 && init_) n_->shutdown1(d);
    // The provider is never unloaded: it keeps worker threads (logging, crash handler) that outlive Shutdown1; unloading it made them fault on
    // exit (access violations at an unknown module, measured 2026-11-14). It stays mapped until the process ends.
    delete n_; n_ = nullptr;
    if (qp_) f_->vkDestroyQueryPool(d, qp_, nullptr); qp_ = nullptr;
    if (pool_) f_->vkDestroyCommandPool(d, pool_, nullptr); pool_ = nullptr;
    delete f_; f_ = nullptr; ctx_ = {}; init_ = false; maxCount_ = 0; tsOn_ = tsValid_ = false;
}

static void globalBarrier(AnrPass::Fns* f, VkCommandBuffer cb) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT; mb.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    f->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
}
static void copyImg(AnrPass::Fns* f, VkCommandBuffer cb, VkImage src, VkImage dst, uint32_t w, uint32_t h) {
    VkImageCopy c{}; c.srcSubresource = c.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; c.extent = {w, h, 1};
    f->vkCmdCopyImage(cb, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &c);
}

void AnrPass::recordHudless(VkCommandBuffer cb, VkImage mcColor) {
    if (!img_[0]) return;
    globalBarrier(f_, cb); copyImg(f_, cb, mcColor, img_[0], dw_, dh_); globalBarrier(f_, cb);
}

void AnrPass::pollTs() {
    uint32_t s = (tsSlot_ + 8 - 4) % 8; if (tsSlotFrame_[s] == 0) return; uint64_t t[2];
    if (f_->vkGetQueryPoolResults(ctx_.device, qp_, s * 2, 2, sizeof t, t, 8, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return;
    tsMs_ = (float)((t[1] - t[0]) * tsPeriod_ * 1e-6); tsValid_ = true;
}

bool AnrPass::recordGenerateN(VkCommandBuffer cb, VkImage mcFinal, int idx, int n, VkImage dst) {
    if (!havePrev_ || !n_ || !n_->h) return false;
    if (n - 1 > (int)maxCount_ && maxCount_) return false;
    if (idx == 1) {
        copyImg(f_, cb, mcFinal, img_[1], dw_, dh_);
        if (tsOn_) { tsSlot_ = (tsSlot_ + 1) % 8; f_->vkCmdResetQueryPool(cb, qp_, tsSlot_ * 2, 2); tsSlotFrame_[tsSlot_] = ++tsFrame_; }
    }
    globalBarrier(f_, cb);
    if (tsOn_ && idx == 1) f_->vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp_, tsSlot_ * 2);
    AnrParams& p = n_->p;
    p.Set(NVSDK_NGX_DLSSG_Parameter_Backbuffer, (void*)&n_->res[1]); p.Set(NVSDK_NGX_DLSSG_Parameter_HUDLess, (void*)&n_->res[hudless_ ? 0 : 1]);
    p.Set(NVSDK_NGX_DLSSG_Parameter_UI, (void*)nullptr); p.Set(NVSDK_NGX_DLSSG_Parameter_UIAlpha, (void*)nullptr);
    p.Set(NVSDK_NGX_DLSSG_Parameter_Depth, (void*)&n_->res[3]); p.Set(NVSDK_NGX_DLSSG_Parameter_MVecs, (void*)&n_->res[4]);
    p.Set(NVSDK_NGX_DLSSG_Parameter_OutputInterpolated, (void*)&n_->res[2]); p.Set(NVSDK_NGX_DLSSG_Parameter_OutputReal, (void*)nullptr);
    p.Set(NVSDK_NGX_DLSSG_Parameter_MultiFrameCount, (unsigned)(n - 1)); p.Set(NVSDK_NGX_DLSSG_Parameter_MultiFrameIndex, (unsigned)idx);
    p.Set(NVSDK_NGX_DLSSG_Parameter_Reset, (reset_ && idx == 1) ? 1u : 0u); p.Set(NVSDK_NGX_DLSSG_Parameter_BackbufferFrameID, (unsigned long long)frameId_);
    p.Set(NVSDK_NGX_DLSSG_Parameter_MvecScaleX, mvScaleX_); p.Set(NVSDK_NGX_DLSSG_Parameter_MvecScaleY, mvScaleY_);
    p.Set(NVSDK_NGX_DLSSG_Parameter_JitterOffsetX, 0.f); p.Set(NVSDK_NGX_DLSSG_Parameter_JitterOffsetY, 0.f);   // the prep pass removes the jitter from its vectors
    p.Set(NVSDK_NGX_DLSSG_Parameter_CameraMotionIncluded, 1u); p.Set(NVSDK_NGX_DLSSG_Parameter_DepthInverted, 1u); p.Set(NVSDK_NGX_DLSSG_Parameter_ColorBuffersHDR, 0u);
    p.Set(NVSDK_NGX_DLSSG_Parameter_CameraViewToClip, (void*)n_->proj); p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToCameraView, (void*)n_->proj);
    p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToLensClip, (void*)n_->ident); p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToPrevClip, (void*)n_->ident); p.Set(NVSDK_NGX_DLSSG_Parameter_PrevClipToClip, (void*)n_->ident);
    p.Set(NVSDK_NGX_DLSSG_Parameter_CameraNear, 0.05f); p.Set(NVSDK_NGX_DLSSG_Parameter_CameraFar, 1024.f); p.Set(NVSDK_NGX_DLSSG_Parameter_CameraFOV, 1.22f);
    p.Set(NVSDK_NGX_DLSSG_Parameter_CameraAspectRatio, (float)dw_ / (float)dh_);
    NVSDK_NGX_Result r = n_->evaluate(cb, n_->h, &p, nullptr);
    if (NVSDK_NGX_FAILED(r)) return false;
    globalBarrier(f_, cb);
    if (tsOn_ && idx == n - 1) { f_->vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp_, tsSlot_ * 2 + 1); pollTs(); }
    copyImg(f_, cb, img_[2], dst, dw_, dh_); globalBarrier(f_, cb);
    if (idx == n - 1) reset_ = false;
    return true;
}

void AnrPass::recordEndFrame(VkCommandBuffer) { frameId_++; havePrev_ = true; }

}  // namespace fsr4
