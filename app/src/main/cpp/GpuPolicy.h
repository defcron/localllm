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
inline int offloaded_count(const char* text) {
    const auto offset=std::string(text).find("offloaded ");
    if(offset==std::string::npos) return -1;
    int n=-1,total=-1;
    return std::sscanf(text+offset,"offloaded %d/%d layers to GPU",&n,&total)==2?n:-1;
}
}
