// Standalone check of ANR FG (Apex Neural Rendering, a DLSS-G NGX provider DLL) on this machine's Vulkan GPU, outside Minecraft.
//   anr_smoke.exe <path to nvngx_dlssg.dll> [width height iterations]
// Two synthetic frames: B is A with its content moved 16 px to the left, with matching motion vectors. A correct interpolation
// sits halfway, so the generated frame should match A shifted by 8 px. Prints the provider's limits, the measured shift and the
// GPU time of EvaluateFeature (timestamp queries). Uses the NGX interface headers (NVIDIA DLSS SDK, local only, not committed).
#define NOMINMAX
#include <windows.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_defs.h>
#include <nvsdk_ngx_params.h>
#include <nvsdk_ngx_defs_vk.h>
#include <nvsdk_ngx_defs_dlssg.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#define FN(name) static PFN_##name name
FN(vkGetInstanceProcAddr); FN(vkCreateInstance); FN(vkEnumeratePhysicalDevices); FN(vkGetPhysicalDeviceProperties);
FN(vkGetPhysicalDeviceQueueFamilyProperties); FN(vkGetPhysicalDeviceFeatures2); FN(vkEnumerateDeviceExtensionProperties);
FN(vkCreateDevice); FN(vkGetDeviceQueue); FN(vkGetPhysicalDeviceMemoryProperties); FN(vkCreateImage); FN(vkGetImageMemoryRequirements);
FN(vkAllocateMemory); FN(vkBindImageMemory); FN(vkCreateImageView); FN(vkCreateBuffer); FN(vkGetBufferMemoryRequirements);
FN(vkBindBufferMemory); FN(vkMapMemory); FN(vkCreateCommandPool); FN(vkAllocateCommandBuffers); FN(vkBeginCommandBuffer);
FN(vkEndCommandBuffer); FN(vkQueueSubmit); FN(vkQueueWaitIdle); FN(vkCmdPipelineBarrier); FN(vkCmdCopyBufferToImage);
FN(vkCmdCopyImageToBuffer); FN(vkCreateQueryPool); FN(vkCmdResetQueryPool); FN(vkCmdWriteTimestamp); FN(vkGetQueryPoolResults);
FN(vkResetCommandBuffer); FN(vkDeviceWaitIdle);

using PFN_Init = NVSDK_NGX_Result(NVSDK_CONV*)(unsigned long long, const wchar_t*, VkInstance, VkPhysicalDevice, VkDevice, NVSDK_NGX_Version, const NVSDK_NGX_Parameter*);
using PFN_Populate = NVSDK_NGX_Result(NVSDK_CONV*)(NVSDK_NGX_Parameter*);
using PFN_Create1 = NVSDK_NGX_Result(NVSDK_CONV*)(VkDevice, VkCommandBuffer, NVSDK_NGX_Feature, NVSDK_NGX_Parameter*, NVSDK_NGX_Handle**);
using PFN_Evaluate = NVSDK_NGX_Result(NVSDK_CONV*)(VkCommandBuffer, const NVSDK_NGX_Handle*, const NVSDK_NGX_Parameter*, PFN_NVSDK_NGX_ProgressCallback);
using PFN_Release = NVSDK_NGX_Result(NVSDK_CONV*)(NVSDK_NGX_Handle*);
using PFN_Requirements = NVSDK_NGX_Result(NVSDK_CONV*)(const VkInstance, const VkPhysicalDevice, const NVSDK_NGX_FeatureDiscoveryInfo*, NVSDK_NGX_FeatureRequirement*);

// The host's parameter map: a provider DLL reads and writes through these virtuals.
class Params final : public NVSDK_NGX_Parameter {
	struct V { bool ptr; double n; void* p; };
	std::unordered_map<std::string, V> m;
	template <class T> NVSDK_NGX_Result num(const char* k, T* o) const { auto i = m.find(k); if (!o || i == m.end() || i->second.ptr) return NVSDK_NGX_Result_Fail; *o = (T)i->second.n; return NVSDK_NGX_Result_Success; }
	template <class T> NVSDK_NGX_Result ptr(const char* k, T** o) const { auto i = m.find(k); if (!o || i == m.end() || !i->second.ptr) return NVSDK_NGX_Result_Fail; *o = (T*)i->second.p; return NVSDK_NGX_Result_Success; }
public:
	void Set(const char* k, unsigned long long v) override { m[k] = {false, (double)v, nullptr}; }
	void Set(const char* k, float v) override { m[k] = {false, v, nullptr}; }
	void Set(const char* k, double v) override { m[k] = {false, v, nullptr}; }
	void Set(const char* k, unsigned int v) override { m[k] = {false, (double)v, nullptr}; }
	void Set(const char* k, int v) override { m[k] = {false, (double)v, nullptr}; }
	void Set(const char* k, ID3D11Resource* v) override { m[k] = {true, 0, v}; }
	void Set(const char* k, ID3D12Resource* v) override { m[k] = {true, 0, v}; }
	void Set(const char* k, void* v) override { m[k] = {true, 0, v}; }
	NVSDK_NGX_Result Get(const char* k, unsigned long long* o) const override { return num(k, o); }
	NVSDK_NGX_Result Get(const char* k, float* o) const override { return num(k, o); }
	NVSDK_NGX_Result Get(const char* k, double* o) const override { return num(k, o); }
	NVSDK_NGX_Result Get(const char* k, unsigned int* o) const override { return num(k, o); }
	NVSDK_NGX_Result Get(const char* k, int* o) const override { return num(k, o); }
	NVSDK_NGX_Result Get(const char* k, ID3D11Resource** o) const override { return ptr(k, o); }
	NVSDK_NGX_Result Get(const char* k, ID3D12Resource** o) const override { return ptr(k, o); }
	NVSDK_NGX_Result Get(const char* k, void** o) const override { return ptr(k, o); }
	void Reset() override { m.clear(); }
	void dump() const { for (auto& [k, v] : m) if (!v.ptr) std::printf("  provider param %s = %g\n", k.c_str(), v.n); }
};

static VkDevice dev; static VkPhysicalDeviceMemoryProperties memProps;
static uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) { for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) if ((bits & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & want) == want) return i; return UINT32_MAX; }
#define CHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { std::printf("FAIL %s = %d (line %d)\n", #x, r_, __LINE__); return 1; } } while (0)

struct Img { VkImage img; VkImageView view; VkFormat fmt; NVSDK_NGX_Resource_VK res; };
static bool makeImage(Img& o, VkFormat fmt, uint32_t w, uint32_t h, bool storage) {
	VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ci.imageType = VK_IMAGE_TYPE_2D; ci.format = fmt; ci.extent = {w, h, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
	ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | (storage ? VK_IMAGE_USAGE_STORAGE_BIT : 0);
	if (vkCreateImage(dev, &ci, nullptr, &o.img) != VK_SUCCESS) return false;
	VkMemoryRequirements mr; vkGetImageMemoryRequirements(dev, o.img, &mr);
	VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = mr.size; ai.memoryTypeIndex = memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
	VkDeviceMemory mem; if (vkAllocateMemory(dev, &ai, nullptr, &mem) != VK_SUCCESS || vkBindImageMemory(dev, o.img, mem, 0) != VK_SUCCESS) return false;
	VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = o.img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt; vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
	if (vkCreateImageView(dev, &vi, nullptr, &o.view) != VK_SUCCESS) return false;
	o.fmt = fmt; std::memset(&o.res, 0, sizeof o.res);
	o.res.Resource.ImageViewInfo = {o.view, o.img, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, fmt, w, h};
	o.res.Type = NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW; o.res.ReadWrite = storage;
	return true;
}
static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to) {
	VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
	b.oldLayout = from; b.newLayout = to; b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
	vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}
static std::vector<uint8_t> g_src; static uint32_t g_w = 0, g_h = 0;   // optional real image (raw RGBA8), else synthetic noise
static uint8_t pattern(int x, int y) { if (!g_src.empty()) { x = x < 0 ? 0 : x >= (int)g_w ? (int)g_w - 1 : x; y = y < 0 ? 0 : y >= (int)g_h ? (int)g_h - 1 : y; return g_src[4 * ((size_t)y * g_w + x)]; } uint32_t h = (uint32_t)(x >> 2) * 73856093u ^ (uint32_t)(y >> 2) * 19349663u; h ^= h >> 13; h *= 0x5bd1e995u; return (uint8_t)(h >> 24); }
static float half2f(uint16_t h) { uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, f = h & 1023; float v = e == 0 ? std::ldexp((float)f, -24) : std::ldexp((float)(f | 1024), (int)e - 25); return s ? -v : v; }
static uint16_t f2half(float f) { uint32_t x; std::memcpy(&x, &f, 4); uint32_t s = (x >> 16) & 0x8000; int e = (int)((x >> 23) & 255) - 127 + 15; uint32_t m = (x >> 13) & 1023; if (f == 0) return (uint16_t)s; return (uint16_t)(s | (uint32_t)(e << 10) | m); }

int main(int argc, char** argv) {
	if (argc < 2) { std::printf("usage: anr_smoke <nvngx_dlssg.dll> [w h iters]\n"); return 2; }
	const uint32_t W = argc > 3 ? atoi(argv[2]) : 1920, H = argc > 3 ? atoi(argv[3]) : 1080; const int ITERS = argc > 4 ? atoi(argv[4]) : 50; const int SHIFT = 16;
	if (argc > 5) {
		FILE* f = std::fopen(argv[5], "rb"); if (!f) { std::printf("cannot open %s\n", argv[5]); return 1; }
		g_src.resize((size_t)W * H * 4); size_t got = std::fread(g_src.data(), 1, g_src.size(), f); std::fclose(f);
		if (got != g_src.size()) { std::printf("raw image must be %ux%u RGBA8\n", W, H); return 1; }
		g_w = W; g_h = H; std::printf("input: real image %s\n", argv[5]);
	}
	HMODULE vkLib = LoadLibraryA("vulkan-1.dll"); if (!vkLib) { std::printf("no vulkan-1.dll\n"); return 1; }
	vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)GetProcAddress(vkLib, "vkGetInstanceProcAddr");
	vkCreateInstance = (PFN_vkCreateInstance)vkGetInstanceProcAddr(nullptr, "vkCreateInstance");
	VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "anr_smoke"; app.apiVersion = VK_API_VERSION_1_3;
	VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app; VkInstance inst; CHECK(vkCreateInstance(&ici, nullptr, &inst));
#define IL(n) n = (PFN_##n)vkGetInstanceProcAddr(inst, #n)
	IL(vkEnumeratePhysicalDevices); IL(vkGetPhysicalDeviceProperties); IL(vkGetPhysicalDeviceQueueFamilyProperties); IL(vkGetPhysicalDeviceFeatures2);
	IL(vkEnumerateDeviceExtensionProperties); IL(vkCreateDevice); IL(vkGetPhysicalDeviceMemoryProperties);
	uint32_t n = 0; vkEnumeratePhysicalDevices(inst, &n, nullptr); std::vector<VkPhysicalDevice> pds(n); vkEnumeratePhysicalDevices(inst, &n, pds.data());
	VkPhysicalDevice pd = VK_NULL_HANDLE; VkPhysicalDeviceProperties props{};
	for (auto p : pds) { vkGetPhysicalDeviceProperties(p, &props); if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) { pd = p; break; } }
	if (!pd) { std::printf("no discrete GPU\n"); return 1; }
	std::printf("GPU: %s vendor=0x%04x device=0x%04x driver=0x%x\n", props.deviceName, props.vendorID, props.deviceID, props.driverVersion);
	vkGetPhysicalDeviceMemoryProperties(pd, &memProps);

	HMODULE anr = LoadLibraryExA(argv[1], nullptr, LOAD_WITH_ALTERED_SEARCH_PATH); if (!anr) { std::printf("cannot load %s (%lu)\n", argv[1], GetLastError()); return 1; }
	auto init = (PFN_Init)GetProcAddress(anr, "NVSDK_NGX_VULKAN_Init_Ext"); auto populate = (PFN_Populate)GetProcAddress(anr, "NVSDK_NGX_VULKAN_PopulateParameters_Impl");
	auto create1 = (PFN_Create1)GetProcAddress(anr, "NVSDK_NGX_VULKAN_CreateFeature1"); auto evaluate = (PFN_Evaluate)GetProcAddress(anr, "NVSDK_NGX_VULKAN_EvaluateFeature");
	auto release = (PFN_Release)GetProcAddress(anr, "NVSDK_NGX_VULKAN_ReleaseFeature"); auto reqs = (PFN_Requirements)GetProcAddress(anr, "NVSDK_NGX_VULKAN_GetFeatureRequirements");
	if (!init || !create1 || !evaluate || !release) { std::printf("missing exports\n"); return 1; }
	wchar_t temp[MAX_PATH]; GetTempPathW(MAX_PATH, temp);
	if (reqs) {
		NVSDK_NGX_FeatureDiscoveryInfo di{}; di.SDKVersion = NVSDK_NGX_Version_API; di.FeatureID = NVSDK_NGX_Feature_FrameGeneration;
		di.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id; di.Identifier.v.ApplicationId = 1; di.ApplicationDataPath = temp;
		NVSDK_NGX_FeatureRequirement fr{}; NVSDK_NGX_Result r = reqs(inst, pd, &di, &fr);
		std::printf("GetFeatureRequirements: result=0x%08x supported=%d minArch=%u minOS=%s\n", (unsigned)r, (int)fr.FeatureSupported, fr.MinHWArchitecture, fr.MinOSVersion);
	}

	// Device: every Vulkan 1.1/1.2/1.3 feature the GPU has, plus the extensions a neural pass may use when present.
	VkPhysicalDeviceCooperativeMatrixFeaturesKHR coop{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_MATRIX_FEATURES_KHR};
	VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
	VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}; VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
	VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2}; f2.pNext = &f11; f11.pNext = &f12; f12.pNext = &f13;
	uint32_t ne = 0; vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, nullptr); std::vector<VkExtensionProperties> exts(ne); vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, exts.data());
	std::vector<const char*> want; const char* wanted[] = {"VK_KHR_cooperative_matrix", "VK_KHR_shader_bfloat16", "VK_EXT_shader_float8", "VK_KHR_push_descriptor", "VK_KHR_shader_maximal_reconvergence", "VK_KHR_shader_subgroup_uniform_control_flow"};
	for (auto w : wanted) for (auto& e : exts) if (!std::strcmp(e.extensionName, w)) { want.push_back(w); if (!std::strcmp(w, "VK_KHR_cooperative_matrix")) f13.pNext = &coop; }
	vkGetPhysicalDeviceFeatures2(pd, &f2); f2.features.robustBufferAccess = VK_FALSE;
	std::printf("device extensions enabled for the test:"); for (auto w : want) std::printf(" %s", w); std::printf("  (coopmat feature=%d)\n", coop.cooperativeMatrix);
	uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr); std::vector<VkQueueFamilyProperties> qf(nq); vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf.data());
	uint32_t qfi = 0; for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { qfi = i; break; }
	float prio = 1; VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex = qfi; qci.queueCount = 1; qci.pQueuePriorities = &prio;
	VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.pNext = &f2; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci; dci.enabledExtensionCount = (uint32_t)want.size(); dci.ppEnabledExtensionNames = want.data();
	CHECK(vkCreateDevice(pd, &dci, nullptr, &dev));
#define DL(n) n = (PFN_##n)vkGetInstanceProcAddr(inst, #n)
	DL(vkGetDeviceQueue); DL(vkCreateImage); DL(vkGetImageMemoryRequirements); DL(vkAllocateMemory); DL(vkBindImageMemory); DL(vkCreateImageView); DL(vkCreateBuffer);
	DL(vkGetBufferMemoryRequirements); DL(vkBindBufferMemory); DL(vkMapMemory); DL(vkCreateCommandPool); DL(vkAllocateCommandBuffers); DL(vkBeginCommandBuffer); DL(vkEndCommandBuffer);
	DL(vkQueueSubmit); DL(vkQueueWaitIdle); DL(vkCmdPipelineBarrier); DL(vkCmdCopyBufferToImage); DL(vkCmdCopyImageToBuffer); DL(vkCreateQueryPool); DL(vkCmdResetQueryPool);
	DL(vkCmdWriteTimestamp); DL(vkGetQueryPoolResults); DL(vkResetCommandBuffer); DL(vkDeviceWaitIdle);
	VkQueue q; vkGetDeviceQueue(dev, qfi, 0, &q);
	VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; pci.queueFamilyIndex = qfi;
	VkCommandPool pool; CHECK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
	VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = pool; cai.commandBufferCount = 1; VkCommandBuffer cb; CHECK(vkAllocateCommandBuffers(dev, &cai, &cb));
	auto begin = [&] { vkResetCommandBuffer(cb, 0); VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; vkBeginCommandBuffer(cb, &bi); };
	auto submit = [&] { vkEndCommandBuffer(cb); VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cb; vkQueueSubmit(q, 1, &si, VK_NULL_HANDLE); vkQueueWaitIdle(q); };

	// Staging buffer, host visible, large enough for one RGBA8 frame.
	const VkDeviceSize sz = (VkDeviceSize)W * H * 4; VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bci.size = sz; bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
	VkBuffer buf; CHECK(vkCreateBuffer(dev, &bci, nullptr, &buf)); VkMemoryRequirements bmr; vkGetBufferMemoryRequirements(dev, buf, &bmr);
	VkMemoryAllocateInfo bai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; bai.allocationSize = bmr.size; bai.memoryTypeIndex = memType(bmr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
	VkDeviceMemory bmem; CHECK(vkAllocateMemory(dev, &bai, nullptr, &bmem)); CHECK(vkBindBufferMemory(dev, buf, bmem, 0)); uint8_t* map; CHECK(vkMapMemory(dev, bmem, 0, sz, 0, (void**)&map));

	Img frA, frB, depth, mv, out;
	if (!makeImage(frA, VK_FORMAT_R8G8B8A8_UNORM, W, H, false) || !makeImage(frB, VK_FORMAT_R8G8B8A8_UNORM, W, H, false) || !makeImage(depth, VK_FORMAT_R32_SFLOAT, W, H, false) ||
		!makeImage(mv, VK_FORMAT_R16G16_SFLOAT, W, H, false) || !makeImage(out, VK_FORMAT_R8G8B8A8_UNORM, W, H, true)) { std::printf("image creation failed\n"); return 1; }
	auto upload = [&](Img& im, auto fill) { fill(map); begin(); barrier(cb, im.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		VkBufferImageCopy r{}; r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; r.imageExtent = {W, H, 1}; vkCmdCopyBufferToImage(cb, buf, im.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
		barrier(cb, im.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL); submit(); };
	auto color = [&](int off) { return [&, off](uint8_t* p) { for (uint32_t y = 0; y < H; y++) for (uint32_t x = 0; x < W; x++) { uint8_t* o = p + 4 * ((size_t)y * W + x); if (!g_src.empty()) { int sx = std::min((int)W - 1, (int)x + off); const uint8_t* s = &g_src[4 * ((size_t)y * W + sx)]; o[0] = s[0]; o[1] = s[1]; o[2] = s[2]; o[3] = 255; } else { uint8_t v = pattern((int)x + off, (int)y); o[0] = v; o[1] = (uint8_t)(255 - v); o[2] = (uint8_t)(v / 2 + 64); o[3] = 255; } } }; };
	upload(frA, color(0)); upload(frB, color(SHIFT));   // B(x) = A(x + 16): content moved 16 px to the left
	upload(depth, [&](uint8_t* p) { float d = 0.5f; for (size_t i = 0; i < (size_t)W * H; i++) std::memcpy(p + 4 * i, &d, 4); });
	upload(mv, [&](uint8_t* p) { uint16_t mx = f2half((float)SHIFT), my = f2half(0); for (size_t i = 0; i < (size_t)W * H; i++) { std::memcpy(p + 4 * i, &mx, 2); std::memcpy(p + 4 * i + 2, &my, 2); } });  // current -> previous, in pixels
	begin(); barrier(cb, out.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); submit();

	NVSDK_NGX_Result r = init(1, temp, inst, pd, dev, NVSDK_NGX_Version_API, nullptr);
	std::printf("Init_Ext: 0x%08x\n", (unsigned)r); if (NVSDK_NGX_FAILED(r)) return 1;
	Params p; if (populate) { r = populate(&p); std::printf("PopulateParameters: 0x%08x\n", (unsigned)r); p.dump(); }
	unsigned maxCount = 0; p.Get(NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax, &maxCount); std::printf("MultiFrameCountMax = %u\n", maxCount);
	p.Set(NVSDK_NGX_Parameter_CreationNodeMask, 1u); p.Set(NVSDK_NGX_Parameter_VisibilityNodeMask, 1u);
	p.Set(NVSDK_NGX_Parameter_Width, W); p.Set(NVSDK_NGX_Parameter_Height, H); p.Set(NVSDK_NGX_DLSSG_Parameter_Width, W); p.Set(NVSDK_NGX_DLSSG_Parameter_Height, H);
	p.Set(NVSDK_NGX_DLSSG_Parameter_BackbufferFormat, (unsigned)VK_FORMAT_R8G8B8A8_UNORM); p.Set(NVSDK_NGX_DLSSG_Parameter_InternalWidth, W); p.Set(NVSDK_NGX_DLSSG_Parameter_InternalHeight, H);
	p.Set(NVSDK_NGX_DLSSG_Parameter_DynamicResolution, 0u);
	NVSDK_NGX_Handle* h = nullptr; begin(); auto t0 = std::chrono::steady_clock::now(); r = create1(dev, cb, NVSDK_NGX_Feature_FrameGeneration, &p, &h); submit();
	std::printf("CreateFeature1: 0x%08x handle=%p (%.1f ms CPU incl. submit)\n", (unsigned)r, (void*)h, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
	if (NVSDK_NGX_FAILED(r) || !h) return 1;

	float proj[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	unsigned long long frameId = 0;
	auto setFrame = [&](Img& cur, unsigned reset) {
		p.Set(NVSDK_NGX_DLSSG_Parameter_BackbufferFrameID, ++frameId);
		p.Set(NVSDK_NGX_DLSSG_Parameter_Backbuffer, (void*)&cur.res); p.Set(NVSDK_NGX_DLSSG_Parameter_HUDLess, (void*)&cur.res);
		p.Set(NVSDK_NGX_DLSSG_Parameter_Depth, (void*)&depth.res); p.Set(NVSDK_NGX_DLSSG_Parameter_MVecs, (void*)&mv.res);
		p.Set(NVSDK_NGX_DLSSG_Parameter_UI, (void*)nullptr); p.Set(NVSDK_NGX_DLSSG_Parameter_UIAlpha, (void*)nullptr);
		p.Set(NVSDK_NGX_DLSSG_Parameter_OutputInterpolated, (void*)&out.res); p.Set(NVSDK_NGX_DLSSG_Parameter_OutputReal, (void*)nullptr);
		p.Set(NVSDK_NGX_DLSSG_Parameter_MultiFrameCount, 1u); p.Set(NVSDK_NGX_DLSSG_Parameter_MultiFrameIndex, 1u); p.Set(NVSDK_NGX_DLSSG_Parameter_Reset, reset);
		{   // ANR_MV = norm (scale 1/size: normalised), px (scale 1: pixels), negpx (scale -1), negnorm
			const char* mvMode = std::getenv("ANR_MV"); std::string mm = mvMode ? mvMode : "norm";
			float sx = 1.0f / W, sy = 1.0f / H; if (mm == "px") sx = sy = 1.0f; else if (mm == "negpx") sx = sy = -1.0f; else if (mm == "negnorm") { sx = -sx; sy = -sy; }
			p.Set(NVSDK_NGX_DLSSG_Parameter_MvecScaleX, sx); p.Set(NVSDK_NGX_DLSSG_Parameter_MvecScaleY, sy);
		}
		p.Set(NVSDK_NGX_DLSSG_Parameter_JitterOffsetX, 0.0f); p.Set(NVSDK_NGX_DLSSG_Parameter_JitterOffsetY, 0.0f);
		p.Set(NVSDK_NGX_DLSSG_Parameter_CameraMotionIncluded, 1u); p.Set(NVSDK_NGX_DLSSG_Parameter_DepthInverted, 0u); p.Set(NVSDK_NGX_DLSSG_Parameter_ColorBuffersHDR, 0u);
		p.Set(NVSDK_NGX_DLSSG_Parameter_CameraViewToClip, (void*)proj); p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToCameraView, (void*)proj);
		p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToLensClip, (void*)ident); p.Set(NVSDK_NGX_DLSSG_Parameter_ClipToPrevClip, (void*)ident); p.Set(NVSDK_NGX_DLSSG_Parameter_PrevClipToClip, (void*)ident);
		p.Set(NVSDK_NGX_DLSSG_Parameter_CameraNear, 0.05f); p.Set(NVSDK_NGX_DLSSG_Parameter_CameraFar, 1000.0f); p.Set(NVSDK_NGX_DLSSG_Parameter_CameraFOV, 1.22f);
		p.Set(NVSDK_NGX_DLSSG_Parameter_CameraAspectRatio, (float)W / H);
	};
	VkQueryPoolCreateInfo qpi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; qpi.queryType = VK_QUERY_TYPE_TIMESTAMP; qpi.queryCount = 2; VkQueryPool qp; CHECK(vkCreateQueryPool(dev, &qpi, nullptr, &qp));
	auto evalOnce = [&](Img& cur, unsigned reset, double* gpuMs) -> NVSDK_NGX_Result {
		setFrame(cur, reset); begin(); vkCmdResetQueryPool(cb, qp, 0, 2); vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, 0);
		NVSDK_NGX_Result rr = evaluate(cb, h, &p, nullptr); vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, 1); submit();
		uint64_t ts[2] = {}; vkGetQueryPoolResults(dev, qp, 0, 2, sizeof ts, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
		if (gpuMs) *gpuMs = (ts[1] - ts[0]) * props.limits.timestampPeriod / 1e6; return rr; };
	r = evalOnce(frA, 1, nullptr); std::printf("Evaluate frame A (reset): 0x%08x\n", (unsigned)r); if (NVSDK_NGX_FAILED(r)) return 1;
	double ms = 0; r = evalOnce(frB, 0, &ms); std::printf("Evaluate frame B: 0x%08x  GPU %.3f ms\n", (unsigned)r, ms); if (NVSDK_NGX_FAILED(r)) return 1;

	// Read the generated frame back and find the horizontal shift that best matches frame A.
	begin(); barrier(cb, out.img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
	VkBufferImageCopy rc{}; rc.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; rc.imageExtent = {W, H, 1}; vkCmdCopyImageToBuffer(cb, out.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, 1, &rc);
	barrier(cb, out.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL); submit();
	int best = -99; double bestErr = 1e30, err0 = 0, meanOut = 0;
	for (int s = 0; s <= 2 * SHIFT; s++) { double e = 0; long cnt = 0;
		for (uint32_t y = H / 4; y < 3 * H / 4; y += 3) for (uint32_t x = W / 4; x < 3 * W / 4; x += 3) { e += std::abs((int)map[4 * ((size_t)y * W + x)] - (int)pattern((int)x + s, (int)y)); cnt++; }
		e /= cnt; if (s == 0) err0 = e; if (e < bestErr) { bestErr = e; best = s; } }
	{ FILE* fo = std::fopen("anr_out.rgba", "wb"); if (fo) { std::fwrite(map, 1, (size_t)W * H * 4, fo); std::fclose(fo); } }
	{ double eA = 0, eB = 0, eM = 0; long c = 0;   // against A, B and their 50/50 blend (what a plain cross-fade gives)
	  for (uint32_t y = H / 4; y < 3 * H / 4; y += 3) for (uint32_t x = W / 4; x < 3 * W / 4; x += 3) { int o = map[4 * ((size_t)y * W + x)], a = pattern((int)x, (int)y), b = pattern((int)x + SHIFT, (int)y);
	    eA += std::abs(o - a); eB += std::abs(o - b); eM += std::abs(o - (a + b) / 2.0); c++; }
	  std::printf("error vs A %.2f, vs B %.2f, vs 50/50 blend of A and B %.2f\n", eA / c, eB / c, eM / c); }
	for (size_t i = 0; i < (size_t)W * H; i += 97) meanOut += map[4 * i]; meanOut /= ((size_t)W * H / 97.0);
	std::printf("generated frame: best match = A shifted by %d px (expected %d), error %.2f/255 (error at 0 px %.2f), mean R %.1f\n", best, SHIFT / 2, bestErr, err0, meanOut);

	std::vector<double> t; for (int i = 0; i < ITERS; i++) { double m; evalOnce((i & 1) ? frA : frB, 0, &m); t.push_back(m); }
	std::sort(t.begin(), t.end()); if (!t.empty()) std::printf("EvaluateFeature GPU time over %d runs at %ux%u: median %.3f ms, p95 %.3f ms, max %.3f ms\n", ITERS, W, H, t[t.size() / 2], t[t.size() * 95 / 100], t.back());
	vkDeviceWaitIdle(dev); release(h); std::printf("released OK\n");
	return 0;
}
