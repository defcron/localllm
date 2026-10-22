package dev.localllm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AppRegressionTest {
    @Test fun defaultsAreVulkanAndAllGpuLayers() {
        assertEquals(GpuBackend.VULKAN,GpuSettings().backend);assertEquals(DEFAULT_GPU_LAYERS,GpuSettings().gpuLayers)
        assertEquals(16_384,DEFAULT_CONTEXT_SIZE)
    }
    @Test fun acceptsAllAndExplicitCpu() {
        assertEquals(-1,GpuSettings.parseLayers("-1"));assertEquals(0,GpuSettings.parseLayers("0"))
    }
    @Test fun invalidLayersRejected() {
        for(value in listOf("","abc","-2","10000","999999999999")) assertNull(GpuSettings.parseLayers(value))
    }
    @Test fun storedOpenclRestored() { assertEquals(GpuSettings(GpuBackend.OPENCL,25),GpuSettings.fromStored("opencl",25)) }
    @Test fun invalidStoredSettingsRecover() { assertEquals(GpuSettings(),GpuSettings.fromStored("unknown",-3)) }
    @Test fun invalidStoredLayerCountUsesAllGpuLayers() { assertEquals(-1,GpuSettings.fromStored(null,-3).gpuLayers) }
    private fun tool(name:String,description:String="desc",schema:JsonObject=buildJsonObject{put("type","object")})=McpTool(name,description,schema)
    private fun functions(prompt:String)=prompt.substringAfter("<tools>\n").substringBefore("\n</tools>")
        .lines().map{Json.parseToJsonElement(it).jsonObject["function"]!!.jsonObject}
    @Test fun all150ToolsSurviveOldBudget() {
        val tools=(0 until 150).map{tool("tool_$it","description ".repeat(30))};val prompt=ToolPrompt.build(tools)
        assertTrue(prompt.length>6000);assertEquals(tools.map{it.name},functions(prompt).map{it["name"]!!.jsonPrimitive.content})
    }
    @Test fun oversizedFirstToolDoesNotHideNext() {
        val functions=functions(ToolPrompt.build(listOf(tool("huge","x".repeat(8000)),tool("last"))))
        assertEquals(2,functions.size);assertEquals("last",functions.last()["name"]!!.jsonPrimitive.content)
        assertEquals(8000,functions.first()["description"]!!.jsonPrimitive.content.length)
    }
    @Test fun completeSchemaPreserved() {
        val schema=Json.parseToJsonElement("""{"type":"object","additionalProperties":false,"required":["title"],"properties":{"title":{"type":"string","default":"x"},"examples":{"type":"array","items":{"type":"integer"}}}}""").jsonObject
        assertEquals(schema,functions(ToolPrompt.build(listOf(tool("schema",schema=schema)))).single()["parameters"])
    }
    @Test fun deterministicAndEmptyCatalog() {
        val tools=listOf(tool("one"));assertEquals(ToolPrompt.build(tools),ToolPrompt.build(tools));assertFalse(ToolPrompt.build(emptyList()).contains("<tools>"))
    }
    @Test fun uniqueNamesUnchanged() { assertEquals("search",ToolRegistry.build(listOf(ToolSource(0,"one",listOf(tool("search"))))).single().tool.name) }
    @Test fun serverDuplicatesHaveAliases() {
        val registry=ToolRegistry.build(listOf(ToolSource(0,"one",listOf(tool("search"))),ToolSource(1,"two",listOf(tool("search")))))
        assertEquals(listOf("mcp_s1__search","mcp_s2__search"),registry.map{it.tool.name});assertEquals(listOf("search","search"),registry.map{it.originalName})
    }
    @Test fun aliasesDoNotShadowRealNames() {
        val registry=ToolRegistry.build(listOf(ToolSource(0,"one",listOf(tool("search"),tool("mcp_s1__search"))),ToolSource(1,"two",listOf(tool("search")))))
        assertEquals(3,registry.map{it.tool.name}.toSet().size);assertEquals("mcp_s1__search_2",registry.first().tool.name)
    }
    private fun page(names:List<String>,cursor:String?=null)=buildJsonObject{
        put("tools",buildJsonArray{names.forEach{name->add(buildJsonObject{
            put("name",name);put("description","Description $name");put("inputSchema",buildJsonObject{put("type","object")})
        })}});if(cursor!=null) put("nextCursor",cursor)
    }
    private fun configure(server:MockWebServer,seen:MutableList<JsonObject>,sse:Boolean=false,result:(JsonObject)->JsonObject) {
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val rpc=Json.parseToJsonElement(request.body.readUtf8()).jsonObject;seen+=rpc
            val method=rpc["method"]!!.jsonPrimitive.content
            if(method=="notifications/initialized") return MockResponse().setResponseCode(202)
            val value=if(method=="initialize") buildJsonObject{
                put("protocolVersion",McpClient.PROTOCOL);put("capabilities",buildJsonObject{put("tools",buildJsonObject{})})
                put("serverInfo",buildJsonObject{put("name","test");put("version","1")})
            } else result(rpc)
            val body=buildJsonObject{put("jsonrpc","2.0");put("id",rpc["id"]!!);put("result",value)}
            return if(sse) MockResponse().setHeader("Content-Type","text/event-stream").setBody("event: message\ndata: $body\n\n")
                else MockResponse().setHeader("Content-Type","application/json").setBody(body.toString())
        }};server.start()
    }
    private fun pagination(sse:Boolean)=runBlocking{
        MockWebServer().use{server->
            val seen=CopyOnWriteArrayList<JsonObject>();configure(server,seen,sse){rpc->
                when(rpc["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content){
                    null->page((0 until 50).map{"tool_$it"},"");""->page((50 until 100).map{"tool_$it"},"opaque+/=cursor")
                    else->page((100 until 150).map{"tool_$it"})
                }
            }
            val client=McpClient(server.url("/mcp").toString(),null);client.connect()
            assertEquals((0 until 150).map{"tool_$it"},client.tools.map{it.name});assertEquals(3,seen.count{it["method"]!!.jsonPrimitive.content=="tools/list"})
        }
    }
    @Test fun allJsonPagesIncluded(){pagination(false)}
    @Test fun allSsePagesIncluded(){pagination(true)}
    @Test fun repeatedCursorFailsWithoutPartialCatalog()=runBlocking{
        MockWebServer().use{server->
            val seen=CopyOnWriteArrayList<JsonObject>();configure(server,seen){rpc->if(rpc["params"]==null)page(listOf("one"),"same") else page(listOf("two"),"same")}
            val client=McpClient(server.url("/mcp").toString(),null);var failure:Exception?=null
            try{client.connect()}catch(e:Exception){failure=e}
            assertTrue(failure?.message.orEmpty().contains("Repeated tools/list cursor"));assertTrue(client.tools.isEmpty())
        }
    }
    @Test fun callsRouteWithOriginalName()=runBlocking{
        MockWebServer().use{one->MockWebServer().use{two->
            val seenOne=CopyOnWriteArrayList<JsonObject>();val seenTwo=CopyOnWriteArrayList<JsonObject>()
            fun result(label:String,rpc:JsonObject):JsonObject=if(rpc["method"]!!.jsonPrimitive.content=="tools/list")page(listOf("search"))
                else buildJsonObject{put("content",buildJsonArray{add(buildJsonObject{put("type","text");put("text",label)})})}
            configure(one,seenOne){result("one",it)};configure(two,seenTwo){result("two",it)}
            val hub=McpHub();assertTrue(hub.connect(listOf(ServerCfg(one.url("/mcp").toString()),ServerCfg(two.url("/mcp").toString()))).isEmpty())
            val args=buildJsonObject{put("query","hello")}
            assertEquals("one",hub.call("mcp_s1__search",args));assertEquals("two",hub.call("mcp_s2__search",args))
            for(seen in listOf(seenOne,seenTwo)){
                val params=seen.single{it["method"]!!.jsonPrimitive.content=="tools/call"}["params"]!!.jsonObject
                assertEquals("search",params["name"]!!.jsonPrimitive.content);assertEquals(args,params["arguments"])
            }
            hub.connect(emptyList());assertTrue(hub.tools.isEmpty());assertTrue(hub.call("mcp_s1__search",args).startsWith("ERROR: unknown tool"))
        }}
    }
}
