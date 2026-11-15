cmake_minimum_required(VERSION 3.20)
project(minecraft_fsr4_linux LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
find_package(Threads REQUIRED)

# Vulkan headers only (the loader is resolved with dlopen at runtime, exactly like Minecraft's LWJGL does).
if(WIN32)
  set(_default_vkh "$ENV{USERPROFILE}/.cache/fsr4-windows/vk-headers/include")
else()
  set(_default_vkh "$ENV{HOME}/.cache/fsr4-linux/vk-headers")
endif()
set(VULKAN_HEADERS_DIR "${_default_vkh}" CACHE PATH "directory containing vulkan/vulkan.h")

add_library(fsr4linux SHARED native/src/fsr4_capi.cpp native/src/bridge_client.cpp native/src/gpu_bridge.cpp native/src/prep_pass.cpp native/src/fgx3_pass.cpp)
target_include_directories(fsr4linux PRIVATE native/include ${VULKAN_HEADERS_DIR})
target_link_libraries(fsr4linux PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
if(WIN32)
  # Native Windows: no Wine/Proton. The helper (fsr4_bridge.exe) runs the official AMD DLLs directly and shares D3D12 textures by NT name.
  target_compile_definitions(fsr4linux PRIVATE VK_USE_PLATFORM_WIN32_KHR FSR4_NATIVE_WINDOWS NOMINMAX WIN32_LEAN_AND_MEAN)
endif()
set_target_properties(fsr4linux PROPERTIES OUTPUT_NAME "fsr4linux")

if(WIN32)
  # Windows smoke test (needs the helper + AMD DLLs next to it, see docs/WINDOWS_PORT.md).
  add_executable(win_smoke tests/win_smoke.cpp native/src/bridge_client.cpp native/src/gpu_bridge.cpp)
  target_include_directories(win_smoke PRIVATE native/include ${VULKAN_HEADERS_DIR})
  target_compile_definitions(win_smoke PRIVATE VK_USE_PLATFORM_WIN32_KHR FSR4_NATIVE_WINDOWS NOMINMAX)
  # Helper: needs the FidelityFX SDK headers (FFX_SDK_DIR = SDK root).
  if(DEFINED FFX_SDK_DIR)
    add_executable(fsr4_bridge native/backends/fsr4/bridge/fsr4_bridge.cpp)
    target_include_directories(fsr4_bridge PRIVATE native/include ${FFX_SDK_DIR}/Kits/FidelityFX/api/include ${FFX_SDK_DIR}/Kits/FidelityFX/upscalers/include ${FFX_SDK_DIR}/Kits/FidelityFX/framegeneration/include ${FFX_SDK_DIR}/Kits/FidelityFX/api/internal)
    target_compile_definitions(fsr4_bridge PRIVATE FSR4_NATIVE_WINDOWS NOMINMAX)
    target_link_libraries(fsr4_bridge PRIVATE d3d12 dxgi user32)
  endif()
  return()
endif()

# Test programs (need the loader at link time).
find_library(VULKAN_LIB NAMES vulkan libvulkan.so.1 PATHS /usr/lib64 /usr/lib/x86_64-linux-gnu)
if(VULKAN_LIB)
  foreach(t ipc_test gpu_ipc_test prep_test fgx3_test crc_stress fgx3_replay)
    add_executable(${t} tests/${t}.cpp native/src/bridge_client.cpp native/src/gpu_bridge.cpp native/src/prep_pass.cpp native/src/fgx3_pass.cpp)
    target_include_directories(${t} PRIVATE native/include ${VULKAN_HEADERS_DIR})
    target_link_libraries(${t} PRIVATE Threads::Threads ${VULKAN_LIB})
  endforeach()
endif()
