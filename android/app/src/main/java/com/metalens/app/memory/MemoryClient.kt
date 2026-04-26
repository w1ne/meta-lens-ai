package com.metalens.app.memory

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client over OpenAI's Vector Store + Files APIs. Used as the long-term
 * memory backing for blackbox captures.
 *
 * Reference: https://platform.openai.com/docs/api-reference/vector-stores
 *
 * - [createVectorStore] one-time on first use; ID persisted in AppSettings.
 * - [uploadCaptionFile] called per blackbox capture (after vision analysis).
 *
 * Errors are returned as [Result] so callers can log + skip without crashing
 * the capture loop.
 */
class MemoryClient(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val BASE = "https://api.openai.com/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val TEXT = "text/plain; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Create a vector store. Returns its id (e.g. "vs_abc123").
     */
    fun createVectorStore(apiKey: String, name: String): Result<String> = runCatching {
        val payload = JSONObject().put("name", name).toString()
        val req = Request.Builder()
            .url("$BASE/vector_stores")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(300)}")
            JSONObject(body).getString("id")
        }
    }

    /**
     * Upload [content] as a small text file with [filename] and attach it to the given
     * vector store. The vector store auto-chunks and embeds. Returns the file id.
     */
    fun uploadCaptionFile(
        apiKey: String,
        vectorStoreId: String,
        filename: String,
        content: String,
    ): Result<String> = runCatching {
        // 1. POST /v1/files (purpose=user_data)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "user_data")
            .addFormDataPart("file", filename, content.toRequestBody(TEXT))
            .build()
        val uploadReq = Request.Builder()
            .url("$BASE/files")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()
        val fileId = client.newCall(uploadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("file upload HTTP ${resp.code}: ${body.take(300)}")
            JSONObject(body).getString("id")
        }

        // 2. POST /v1/vector_stores/{id}/files
        val attachPayload = JSONObject().put("file_id", fileId).toString()
        val attachReq = Request.Builder()
            .url("$BASE/vector_stores/$vectorStoreId/files")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(attachPayload.toRequestBody(JSON))
            .build()
        client.newCall(attachReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("attach HTTP ${resp.code}: ${body.take(300)}")
        }

        fileId
    }
}
