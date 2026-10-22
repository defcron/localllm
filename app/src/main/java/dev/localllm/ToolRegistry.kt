package dev.localllm

internal data class ToolSource(val index: Int, val url: String, val tools: List<McpTool>)
internal data class ToolBinding(val tool: McpTool, val index: Int, val originalName: String)
internal object ToolRegistry {
    fun build(sources: List<ToolSource>): List<ToolBinding> {
        val counts = sources.flatMap { it.tools }.groupingBy { it.name }.eachCount()
        val used = counts.filterValues { it == 1 }.keys.toMutableSet()
        return sources.flatMap { source -> source.tools.map { original ->
            val name = if (counts.getValue(original.name) == 1) original.name else {
                val tail = original.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                val base = "mcp_s${source.index + 1}__$tail"
                var candidate = base; var serial = 2
                while (!used.add(candidate)) candidate = "${base}_${serial++}"
                candidate
            }
            ToolBinding(McpTool(name,
                "MCP server ${source.index + 1} (${source.url}), tool ${original.name}.\n" + original.description,
                original.schema), source.index, original.name)
        } }
    }
}
