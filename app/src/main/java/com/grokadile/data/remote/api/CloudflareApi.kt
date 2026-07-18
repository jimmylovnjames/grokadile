package com.grokadile.data.remote.api

import com.grokadile.data.remote.dto.AgentReportDto
import com.grokadile.data.remote.dto.HealthDto
import com.grokadile.data.remote.dto.RemoteTaskDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Control-plane surface exposed by the Grokadile Cloudflare Worker. Lets the
 * device pull remotely-scheduled tasks and report agent activity back. These
 * are wired and ready; flesh out the Worker side to match.
 */
interface CloudflareApi {
    @GET("health")
    suspend fun health(): HealthDto

    @GET("agents/{agentId}/tasks")
    suspend fun pullTasks(@Path("agentId") agentId: String): List<RemoteTaskDto>

    @POST("agents/{agentId}/report")
    suspend fun report(
        @Path("agentId") agentId: String,
        @Body report: AgentReportDto,
    ): Response<Unit>
}
