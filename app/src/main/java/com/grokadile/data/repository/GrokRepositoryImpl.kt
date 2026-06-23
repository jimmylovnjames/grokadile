package com.grokadile.data.repository

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.core.common.DispatcherProvider
import com.grokadile.data.remote.api.GrokApi
import com.grokadile.data.remote.mapper.toDomain
import com.grokadile.data.remote.mapper.toDto
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatResponse
import com.grokadile.domain.repository.GrokRepository
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GrokRepositoryImpl @Inject constructor(
    private val api: GrokApi,
    private val dispatchers: DispatcherProvider,
) : GrokRepository {

    override suspend fun chat(request: ChatRequest): AppResult<ChatResponse> =
        withContext(dispatchers.io) {
            try {
                val dto = api.chatCompletions(request.toDto())
                val choice = dto.choices.firstOrNull()
                    ?: return@withContext AppResult.Failure(
                        AppError.Unknown("Grok returned no choices"),
                    )
                AppResult.Success(
                    ChatResponse(
                        content = choice.message.content,
                        model = dto.model.ifBlank { request.model },
                        finishReason = choice.finishReason,
                        usage = dto.usage?.toDomain(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                AppResult.Failure(AppError.Http(e.code(), e.message(), e))
            } catch (e: IOException) {
                AppResult.Failure(AppError.Network(e.message ?: "Network error", e))
            } catch (e: SerializationException) {
                AppResult.Failure(AppError.Serialization(e.message ?: "Malformed response", e))
            } catch (e: Exception) {
                AppResult.Failure(AppError.Unknown(e.message ?: "Unexpected error", e))
            }
        }
}
