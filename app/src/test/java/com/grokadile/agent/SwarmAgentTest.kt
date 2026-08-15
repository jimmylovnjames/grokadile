package com.grokadile.agent

import com.grokadile.agent.builtin.SwarmAgent
import com.grokadile.core.device.DeviceInfoProvider
import com.grokadile.data.remote.api.CloudflareApi
import com.grokadile.data.remote.dto.AgentReportDto
import com.grokadile.data.remote.dto.DeviceHeartbeatRequest
import com.grokadile.data.remote.dto.DeviceInfoDto
import com.grokadile.data.remote.dto.DeviceListDto
import com.grokadile.data.remote.dto.EnqueueTaskRequest
import com.grokadile.data.remote.dto.HealthDto
import com.grokadile.data.remote.dto.HeartbeatResponseDto
import com.grokadile.data.remote.dto.RemoteTaskDto
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.testutil.FakeAgentContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SwarmAgentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val identity = object : DeviceInfoProvider {
        override val deviceId = "android-test-device"
        override val label = "Test Phone"
        override fun meta() = mapOf("sdk" to "34")
    }

    private fun agent(api: CloudflareApi = FakeCloudflareApi()) =
        SwarmAgent(api, identity, json)

    @Test
    fun `unknown mode fails`() = runTest {
        val task = Task(agentId = SwarmAgent.ID, title = "x", payload = """{"mode":"nope"}""")
        assertTrue(agent().execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }

    @Test
    fun `whoami returns device id`() = runTest {
        val task = Task(agentId = SwarmAgent.ID, title = "who", payload = """{"mode":"whoami"}""")
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("android-test-device"))
    }

    @Test
    fun `broadcast requires targetAgentId`() = runTest {
        val task = Task(agentId = SwarmAgent.ID, title = "b", payload = """{"mode":"broadcast"}""")
        assertTrue(agent().execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }

    @Test
    fun `dispatch requires targetDeviceId`() = runTest {
        val task = Task(
            agentId = SwarmAgent.ID,
            title = "d",
            payload = """{"mode":"dispatch","targetAgentId":"echo"}""",
        )
        assertTrue(agent().execute(task, FakeAgentContext(task)) is AgentResult.Failure)
    }

    @Test
    fun `list returns peer lines`() = runTest {
        val task = Task(agentId = SwarmAgent.ID, title = "l", payload = """{"mode":"list"}""")
        val result = agent().execute(task, FakeAgentContext(task))
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).output!!.contains("android-peer-1"))
    }

    @Test
    fun `broadcast succeeds when api ok`() = runTest {
        val task = Task(
            agentId = SwarmAgent.ID,
            title = "b",
            payload = """{"mode":"broadcast","targetAgentId":"echo","title":"farm ping"}""",
        )
        assertTrue(agent().execute(task, FakeAgentContext(task)) is AgentResult.Success)
    }
}

private class FakeCloudflareApi : CloudflareApi {
    override suspend fun health() = HealthDto("ok")
    override suspend fun pullTasks(agentId: String, deviceId: String?) = emptyList<RemoteTaskDto>()
    override suspend fun enqueueTask(agentId: String, body: EnqueueTaskRequest): Response<okhttp3.ResponseBody> {
        val json = """{"mode":"broadcast","delivered":2,"tasks":[]}"""
        return Response.success(json.toResponseBody("application/json".toMediaType()))
    }
    override suspend fun report(agentId: String, body: AgentReportDto) = Response.success(Unit)
    override suspend fun deviceHeartbeat(body: DeviceHeartbeatRequest) =
        HeartbeatResponseDto(deviceId = body.deviceId, onlinePeers = 1)
    override suspend fun listDevices(all: String?, windowMs: Long?) =
        DeviceListDto(
            count = 1,
            devices = listOf(
                DeviceInfoDto(
                    deviceId = "android-peer-1",
                    label = "Peer Phone",
                    agents = listOf("echo"),
                    lastSeenAt = System.currentTimeMillis(),
                    online = true,
                ),
            ),
        )
}
