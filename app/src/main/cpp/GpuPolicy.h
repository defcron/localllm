#pragma once
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <string>
namespace localllm {
inline bool valid_layers(int n) { return n==-1 || (n>=0 && n<=9999); }
inline std::string backend_key(std::string text) {
    std::transform(text.begin(),text.end(),text.begin(),[](unsigned char c){return std::tolower(c);});
    return text;
}
inline bool valid_backend(const std::string& key) { return key=="vulkan" || key=="opencl"; }
struct BatchConfig { int batch; int ubatch; };
inline BatchConfig batch_config(const std::string& backend, bool use_gpu) {
    if (!use_gpu) return {512, 512};
    // Vulkan keeps the conservative microbatch that avoids transient Adreno memory pressure.
    // OpenCL can use the full configured batch for more efficient prompt-prefill operations.
    return backend_key(backend) == "opencl" ? BatchConfig{128, 128} : BatchConfig{128, 32};
}
inline int offloaded_count(const char* text) {
    const auto offset=std::string(text).find("offloaded ");
    if(offset==std::string::npos) return -1;
    int n=-1,total=-1;
    return std::sscanf(text+offset,"offloaded %d/%d layers to GPU",&n,&total)==2?n:-1;
}
}
