#!/usr/bin/env python3
from pathlib import Path
import shutil
root=Path(__file__).resolve().parents[1]/"app/src/main/cpp/vendor"
for name in ["opencl-include","vulkan-include"]:
    if (root/name).exists():shutil.rmtree(root/name)
shutil.copytree(root/"OpenCL-Headers/CL",root/"opencl-include/CL")
shutil.copytree(root/"OpenCL-CLHPP/include/CL",root/"opencl-include/CL",dirs_exist_ok=True)
shutil.copytree(root/"Vulkan-Headers/include",root/"vulkan-include")
shutil.copytree(root/"Vulkan-Hpp/vulkan",root/"vulkan-include/vulkan",dirs_exist_ok=True)
