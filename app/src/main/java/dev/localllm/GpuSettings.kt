package dev.localllm

const val DEFAULT_GPU_LAYERS = -1
const val DEFAULT_CONTEXT_SIZE = 16_384

enum class GpuBackend(val id: String, val label: String) {
    VULKAN("vulkan", "Vulkan"), OPENCL("opencl", "OpenCL (Adreno)");
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.id == value } ?: VULKAN }
}

data class GpuSettings(val backend: GpuBackend = GpuBackend.VULKAN, val gpuLayers: Int = DEFAULT_GPU_LAYERS) {
    init { require(gpuLayers == -1 || gpuLayers in 0..9999) }
    companion object {
        fun parseLayers(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it == -1 || it in 0..9999 }
        fun fromStored(backend: String?, layers: Int) = GpuSettings(GpuBackend.fromStored(backend), parseLayers(layers.toString()) ?: DEFAULT_GPU_LAYERS)
    }
}
