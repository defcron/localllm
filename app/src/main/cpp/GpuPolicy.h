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
    // Keep Vulkan conservative for mobile Adreno stability. OpenCL uses larger host batches
    // split into bounded microbatches: this reduces per-call overhead without a 512-token GPU
    // working set. Explicit CPU mode retains the upstream-friendly 512/512 configuration.
    return backend_key(backend) == "opencl" ? BatchConfig{512, 128} : BatchConfig{128, 32};
}
inline int offloaded_count(const char* text) {
    const auto offset=std::string(text).find("offloaded ");
    if(offset==std::string::npos) return -1;
    int n=-1,total=-1;
    return std::sscanf(text+offset,"offloaded %d/%d layers to GPU",&n,&total)==2?n:-1;
}
}
