package com.grokadile.data.remote.api

import com.grokadile.data.remote.dto.AgentReportDto
import com.grokadile.data.remote.dto.DeviceHeartbeatRequest
import com.grokadile.data.remote.dto.DeviceListDto
import com.grokadile.data.remote.dto.EnqueueTaskRequest
import com.grokadile.data.remote.dto.HealthDto
import com.grokadile.data.remote.dto.HeartbeatResponseDto
import com.grokadile.data.remote.dto.RemoteTaskDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CloudflareApi {

    @GET("health")
    suspend fun health(): HealthDto

    @GET("agents/{agentId}/tasks")
    suspend fun pullTasks(
        @Path("agentId") agentId: String,
        @Query("device_id") deviceId: String? = null,
    ): List<RemoteTaskDto>

    @POST("agents/{agentId}/tasks")
    suspend fun enqueueTask(
        @Path("agentId") agentId: String,
        @Body body: EnqueueTaskRequest,
    ): Response<okhttp3.ResponseBody>

    @POST("agents/{agentId}/report")
    suspend fun report(
        @Path("agentId") agentId: String,
        @Body body: AgentReportDto,
    ): Response<Unit>

    @POST("devices/heartbeat")
    suspend fun deviceHeartbeat(@Body body: DeviceHeartbeatRequest): HeartbeatResponseDto

    @GET("devices")
    suspend fun listDevices(
        @Query("all") all: String? = null,
        @Query("window_ms") windowMs: Long? = null,
    ): DeviceListDto
}
