import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('dispatch',ROOT/'tools/generate_opencl_dispatch.py')
dispatch=importlib.util.module_from_spec(spec);spec.loader.exec_module(dispatch)
HEADER=r"""
#pragma once
#include <stddef.h>
#define CL_API_ENTRY
#define CL_API_CALL
#define CL_CALLBACK
#define CL_INVALID_OPERATION -59
#define CL_DEVICE_NOT_FOUND -1
#define CL_PLATFORM_NOT_FOUND_KHR -1001
typedef int cl_int;
typedef unsigned cl_uint;
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef void* cl_context;
typedef long cl_context_properties;
extern "C" {
extern CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries,cl_platform_id* platforms,cl_uint* num_platforms);
extern CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(cl_platform_id platform,unsigned long type,cl_uint num_entries,cl_device_id* devices,cl_uint* num_devices);
extern CL_API_ENTRY cl_context CL_API_CALL clCreateContext(const cl_context_properties* properties,cl_uint num_devices,const cl_device_id* devices,void (CL_CALLBACK* pfn_notify)(const char*,const void*,size_t,void*),void* user_data,cl_int* errcode_ret);
extern CL_API_ENTRY cl_int CL_API_CALL clFinish(void* queue);
extern CL_API_ENTRY void* CL_API_CALL clGetExtensionFunctionAddress(const char* name);
}
"""
class HostTests(unittest.TestCase):
    def test_vulkan_headers_and_hpp_are_pinned_to_matching_release(self):
        setup=(ROOT/'setup.sh').read_text()
        self.assertIn('Vulkan-Headers" "$vendor/Vulkan-Headers" v1.4.365',setup)
        self.assertIn('Vulkan-Hpp" "$vendor/Vulkan-Hpp" v1.4.365',setup)
        self.assertNotIn('Vulkan-Headers" "$vendor/Vulkan-Headers" main',setup)
        self.assertNotIn('Vulkan-Hpp" "$vendor/Vulkan-Hpp" main',setup)
    def test_callback_parameter_names(self):
        context=next(x for x in dispatch.declarations(HEADER) if x[1]=='clCreateContext')
        self.assertEqual(context[3],['properties','num_devices','devices','pfn_notify','user_data','errcode_ret'])
    def test_balanced_parameter_split(self):
        self.assertEqual(len(dispatch.split_parameters('int x, void (*cb)(int, void*), void* data')),3)
    def test_invalid_header_fails(self):
        with self.assertRaises(ValueError):dispatch.declarations('not a header')
    def test_duplicate_header_fails(self):
        with self.assertRaises(ValueError):dispatch.declarations(HEADER+HEADER)
    def test_late_driver_and_error_output(self):
        output=dispatch.generate(HEADER)
        self.assertEqual(output.count('using Fn='),5)
        self.assertIn('dlopen("libOpenCL.so",RTLD_NOW | RTLD_LOCAL)',output)
        self.assertIn('*errcode_ret=CL_INVALID_OPERATION',output)
        self.assertIn('*num_platforms=0',output)
    @unittest.skipUnless(shutil.which('g++'),'g++ required')
    def test_policy_compiles_and_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'policy.cpp'
            source.write_text(r"""
#include "GpuPolicy.h"
#include <cassert>
int main() {
 assert(localllm::valid_layers(99));assert(localllm::valid_layers(-1));assert(localllm::valid_layers(0));
 auto opencl=localllm::batch_config("opencl",true);assert(opencl.batch==512 && opencl.ubatch==128);
 auto vulkan=localllm::batch_config("vulkan",true);assert(vulkan.batch==128 && vulkan.ubatch==32);
 auto cpu=localllm::batch_config("opencl",false);assert(cpu.batch==512 && cpu.ubatch==512);
 assert(!localllm::valid_layers(-2));assert(!localllm::valid_layers(10000));
 assert(localllm::backend_key("Vulkan")=="vulkan");assert(localllm::valid_backend("opencl"));
 assert(!localllm::valid_backend("cuda"));
 assert(localllm::offloaded_count("load_tensors: offloaded 33/33 layers to GPU\n")==33);
 assert(localllm::offloaded_count("offloaded 0/33 layers to GPU")==0);
 assert(localllm::offloaded_count("unrelated")==-1);
}
""")
            subprocess.run(['g++','-std=c++17','-I',str(ROOT/'app/src/main/cpp'),str(source),'-o',str(root/'test')],check=True)
            subprocess.run([str(root/'test')],check=True)
    @unittest.skipUnless(shutil.which('g++'),'g++ required')
    def test_dispatch_compiles_and_calls_mock_vendor(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'CL').mkdir();(root/'CL/cl.h').write_text(HEADER);(root/'CL/cl_ext.h').write_text('')
            (root/'dispatch.cpp').write_text(dispatch.generate(HEADER))
            (root/'vendor.cpp').write_text(r"""
#include <CL/cl.h>
extern "C" cl_int clGetPlatformIDs(cl_uint,cl_platform_id*,cl_uint* count){if(count)*count=7;return 0;}
extern "C" cl_context clCreateContext(const cl_context_properties*,cl_uint,const cl_device_id*,
 void (*callback)(const char*,const void*,size_t,void*),void* data,cl_int* error){
 if(callback)callback("ok",nullptr,0,data);if(error)*error=0;return reinterpret_cast<void*>(42);
}
""")
            (root/'main.cpp').write_text(r"""
#include <CL/cl.h>
#include <cassert>
extern "C" bool localllm_opencl_driver_available();
void callback(const char*,const void*,size_t,void* data){*static_cast<int*>(data)=1;}
int main(){
 assert(localllm_opencl_driver_available());cl_uint count=0;
 assert(clGetPlatformIDs(0,nullptr,&count)==0);assert(count==7);
 int called=0;cl_int error=-1;
 assert(clCreateContext(nullptr,0,nullptr,callback,&called,&error)==reinterpret_cast<void*>(42));
 assert(called==1 && error==0);assert(clFinish(nullptr)==CL_INVALID_OPERATION);
 assert(clGetExtensionFunctionAddress("missing")==nullptr);
}
""")
            subprocess.run(['g++','-shared','-fPIC','-I',str(root),str(root/'vendor.cpp'),'-o',str(root/'libOpenCL.so')],check=True)
            subprocess.run(['g++','-std=c++17','-I',str(root),str(root/'dispatch.cpp'),str(root/'main.cpp'),'-ldl','-pthread','-o',str(root/'test')],check=True)
            env=os.environ.copy();env['LD_LIBRARY_PATH']=str(root)
            subprocess.run([str(root/'test')],env=env,check=True)
    def test_missing_explicit_glslc_fails_without_download(self):
        env=os.environ.copy();env['GLSLC']='/definitely/missing/localllm/glslc'
        result=subprocess.run(['bash',str(ROOT/'ci/provision-glslc.sh')],env=env,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0);self.assertIn('Not an executable host compiler',result.stderr)
    def test_glslc_mock_preflight_exports_env_and_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);compiler=root/'host tools/glslc';compiler.parent.mkdir()
            compiler.write_text('#!'+sys.executable+'\nimport sys,struct\nfrom pathlib import Path\nif "--version" in sys.argv: print("mock glslc");sys.exit(0)\nPath(sys.argv[sys.argv.index("-o")+1]).write_bytes(struct.pack("<5I",0x07230203,0x10000,0,1,0))\n')
            compiler.chmod(0o755)
            env=os.environ.copy();env.update(GLSLC=str(compiler),GITHUB_ENV=str(root/'env'),GITHUB_PATH=str(root/'path'))
            result=subprocess.run(['bash',str(ROOT/'ci/provision-glslc.sh')],env=env,capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('GLSLC='+str(compiler.resolve()),(root/'env').read_text())
            self.assertIn('Vulkan_GLSLC_EXECUTABLE='+str(compiler.resolve()),(root/'env').read_text())
            self.assertIn(str(compiler.parent.resolve()),(root/'path').read_text())
    def test_glslc_invalid_spirv_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);compiler=root/'glslc'
            compiler.write_text('#!'+sys.executable+'\nimport sys\nfrom pathlib import Path\nif "--version" in sys.argv: sys.exit(0)\nPath(sys.argv[sys.argv.index("-o")+1]).write_bytes(b"bad")\n')
            compiler.chmod(0o755);env=os.environ.copy();env['GLSLC']=str(compiler)
            result=subprocess.run(['bash',str(ROOT/'ci/provision-glslc.sh')],env=env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertIn('Invalid SPIR-V',result.stderr)
    def test_cmake_resolver_uses_host_search_and_upstream_variable(self):
        text=(ROOT/'app/src/main/cpp/cmake/HostGlslc.cmake').read_text()
        self.assertIn('NO_CMAKE_FIND_ROOT_PATH',text);self.assertIn('Vulkan_GLSLC_EXECUTABLE',text)
        self.assertIn('CMAKE_TRY_COMPILE_PLATFORM_VARIABLES',text)
    def test_gpu_flags_and_no_prompt_cutoff(self):
        text=(ROOT/'app/src/main/cpp/CMakeLists.txt').read_text()
        self.assertIn('set(GGML_VULKAN ON',text);self.assertIn('set(GGML_OPENCL ON',text)
        native=(ROOT/'app/src/main/cpp/llama_jni.cpp').read_text()
        self.assertIn('batch_config(requested, use_gpu)',native)
        self.assertIn('decode %.1fs',native);self.assertIn('output %.1fs',native)
        self.assertIn('tokenize %.2fs',native)
        chat=(ROOT/'app/src/main/java/dev/localllm/ChatViewModel.kt').read_text()
        self.assertIn('toolCloseTail',chat);self.assertNotIn('sb.contains("</tool_call>")',chat)
        engine=(ROOT/'app/src/main/java/dev/localllm/LlamaEngine.kt').read_text()
        self.assertIn('ByteBuffer.allocate(512)',engine);self.assertIn('input.compact()',engine)
        prompt=(ROOT/'app/src/main/java/dev/localllm/ToolPrompt.kt').read_text()
        self.assertIn('put("parameters", tool.schema)',prompt);self.assertNotIn('6000',prompt)

if __name__=='__main__':unittest.main()
