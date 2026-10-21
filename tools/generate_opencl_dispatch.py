#!/usr/bin/env python3
"""Generate typed lazy vendor dispatch from official Khronos cl.h declarations."""
import argparse
import re
from pathlib import Path

def split_parameters(text):
    parts,depth,start=[],0,0
    for index,char in enumerate(text):
        if char in '([':depth+=1
        elif char in ')]':depth-=1
        elif char==',' and depth==0:parts.append(text[start:index].strip());start=index+1
    tail=text[start:].strip()
    if tail and tail!='void':parts.append(tail)
    return parts

def argument_name(parameter):
    callback=re.search(r'\*\s*(\w+)\s*\)',parameter)
    if callback:return callback.group(1)
    match=re.search(r'(\w+)\s*(?:\[[^\]]*\])?\s*$',parameter)
    if not match:raise ValueError('Unparseable OpenCL parameter: '+parameter)
    return match.group(1)

def declarations(header):
    text=re.sub(r'/\*.*?\*/|//[^\n]*','',header,flags=re.S)
    pattern=re.compile(r'extern\s+CL_API_ENTRY\s+(.+?)\s+CL_API_CALL\s+(cl\w+)\s*\(',re.S)
    result=[]
    for match in pattern.finditer(text):
        depth,index=1,match.end()
        while index<len(text) and depth:
            if text[index]=='(':depth+=1
            elif text[index]==')':depth-=1
            index+=1
        if depth:raise ValueError('Unclosed declaration')
        params=text[match.end():index-1].strip()
        names=[argument_name(p) for p in split_parameters(params)]
        result.append((match.group(1).strip(),match.group(2),params,names))
    if not result:raise ValueError('No OpenCL declarations found')
    if len({x[1] for x in result})!=len(result):raise ValueError('Duplicate declarations')
    return result

def generate(header):
    output=[r"""// Generated from Khronos cl.h. No proprietary GPU driver is bundled.
#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_0_APIS
#define CL_USE_DEPRECATED_OPENCL_1_1_APIS
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#define CL_USE_DEPRECATED_OPENCL_2_0_APIS
#define CL_USE_DEPRECATED_OPENCL_2_1_APIS
#define CL_USE_DEPRECATED_OPENCL_2_2_APIS
#include <CL/cl.h>
#include <CL/cl_ext.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <type_traits>
namespace {
std::once_flag driver_once;
void* driver=nullptr;
std::string driver_error;
void* driver_handle() {
    std::call_once(driver_once, [] {
        driver=dlopen("libOpenCL.so",RTLD_NOW | RTLD_LOCAL);
        if(!driver) { const char* error=dlerror();driver_error=error?error:"OpenCL unavailable"; }
    });
    return driver;
}
template<class R> R missing(const char* name) {
    if constexpr(std::is_void_v<R>) return;
    else if constexpr(std::is_pointer_v<R>) return nullptr;
    else {
        const std::string function(name);
        if(function=="clGetPlatformIDs") return static_cast<R>(CL_PLATFORM_NOT_FOUND_KHR);
        if(function=="clGetDeviceIDs") return static_cast<R>(CL_DEVICE_NOT_FOUND);
        return static_cast<R>(CL_INVALID_OPERATION);
    }
}
}
extern "C" bool localllm_opencl_driver_available() { return driver_handle()!=nullptr; }
extern "C" const char* localllm_opencl_driver_error() { driver_handle();return driver_error.c_str(); }
"""]
    for returns,name,params,names in declarations(header):
        args=', '.join(names)
        output.append(f'extern "C" CL_API_ENTRY {returns} CL_API_CALL {name}({params}) {{\n')
        output.append(f'    using Fn=decltype(&::{name});\n')
        output.append(f'    static Fn function=reinterpret_cast<Fn>(driver_handle()?dlsym(driver_handle(),"{name}"):nullptr);\n')
        output.append('    if(!function) {\n')
        if 'errcode_ret' in names:output.append('        if(errcode_ret) *errcode_ret=CL_INVALID_OPERATION;\n')
        if name=='clGetPlatformIDs' and 'num_platforms' in names:output.append('        if(num_platforms) *num_platforms=0;\n')
        if name=='clGetDeviceIDs' and 'num_devices' in names:output.append('        if(num_devices) *num_devices=0;\n')
        output.append(f'        return missing<decltype(function({args}))>("{name}");\n    }}\n')
        output.append(f'    return function({args});\n}}\n')
    return '\n'.join(output)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('header',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(generate(args.header.read_text()))
