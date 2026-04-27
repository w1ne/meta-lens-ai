package com.metalens.app.settings

import android.content.Context
import com.meta.wearable.dat.camera.types.VideoQuality
import com.metalens.app.BuildConfig
import com.metalens.app.R
import com.metalens.app.conversation.OpenAIRealtimeClient

object AppSettings {
    private const val PREFS_NAME = "meta_lens_ai_settings"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_OPENAI_MODEL = "openai_model"
    private const val KEY_CAMERA_VIDEO_QUALITY = "camera_video_quality"
    private const val KEY_PICTURE_ANALYSIS_SYSTEM_INSTRUCTIONS = "picture_analysis_system_instructions_override"
    private const val KEY_CONVERSATION_SYSTEM_INSTRUCTIONS = "conversation_system_instructions_override"
    private const val KEY_INTERVAL_CAPTURE_ENABLED = "interval_capture_enabled"
    private const val KEY_INTERVAL_CAPTURE_SECONDS = "interval_capture_seconds"
    private const val KEY_INTERVAL_AUTO_ANALYZE_ENABLED = "interval_auto_analyze_enabled"
    private const val KEY_MEMORY_ENABLED = "memory_enabled"
    private const val KEY_MEMORY_VECTOR_STORE_ID = "memory_vector_store_id"
    const val INTERVAL_CAPTURE_DEFAULT_SECONDS = 300
    const val INTERVAL_CAPTURE_MIN_SECONDS = 60

    fun getOpenAiApiKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fromPrefs = prefs.getString(KEY_OPENAI_API_KEY, null)
        if (!fromPrefs.isNullOrBlank()) return fromPrefs
        return BuildConfig.OPENAI_API_KEY
    }

    fun setOpenAiApiKey(context: Context, apiKey: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OPENAI_API_KEY, apiKey.trim()).apply()
    }

    fun getOpenAiModel(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fromPrefs = prefs.getString(KEY_OPENAI_MODEL, null)
        if (!fromPrefs.isNullOrBlank()) return fromPrefs
        val fromBuild = BuildConfig.OPENAI_MODEL
        if (fromBuild.isNotBlank()) return fromBuild
        return OpenAIRealtimeClient.DEFAULT_MODEL
    }

    fun setOpenAiModel(context: Context, model: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OPENAI_MODEL, model.trim()).apply()
    }

    fun getCameraVideoQuality(context: Context): VideoQuality {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CAMERA_VIDEO_QUALITY, null)?.trim()?.uppercase()
        return when (raw) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            "MEDIUM", null, "" -> VideoQuality.MEDIUM
            else -> VideoQuality.MEDIUM
        }
    }

    fun setCameraVideoQuality(context: Context, quality: VideoQuality) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAMERA_VIDEO_QUALITY, quality.name).apply()
    }

    fun getPictureAnalysisSystemInstructions(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fromPrefs = prefs.getString(KEY_PICTURE_ANALYSIS_SYSTEM_INSTRUCTIONS, null)?.trim()
        if (!fromPrefs.isNullOrBlank()) return fromPrefs
        return context.getString(R.string.picture_analysis_system_instructions).trim()
    }

    fun setPictureAnalysisSystemInstructions(context: Context, instructions: String) {
        val normalized = instructions.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (normalized.isBlank()) {
            prefs.edit().remove(KEY_PICTURE_ANALYSIS_SYSTEM_INSTRUCTIONS).apply()
        } else {
            prefs.edit().putString(KEY_PICTURE_ANALYSIS_SYSTEM_INSTRUCTIONS, normalized).apply()
        }
    }

    fun resetPictureAnalysisSystemInstructions(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PICTURE_ANALYSIS_SYSTEM_INSTRUCTIONS).apply()
    }

    fun getConversationSystemInstructions(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fromPrefs = prefs.getString(KEY_CONVERSATION_SYSTEM_INSTRUCTIONS, null)?.trim()
        if (!fromPrefs.isNullOrBlank()) return fromPrefs
        return context.getString(R.string.conversation_system_instructions).trim()
    }

    fun setConversationSystemInstructions(context: Context, instructions: String) {
        val normalized = instructions.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (normalized.isBlank()) {
            prefs.edit().remove(KEY_CONVERSATION_SYSTEM_INSTRUCTIONS).apply()
        } else {
            prefs.edit().putString(KEY_CONVERSATION_SYSTEM_INSTRUCTIONS, normalized).apply()
        }
    }

    fun resetConversationSystemInstructions(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CONVERSATION_SYSTEM_INSTRUCTIONS).apply()
    }

    fun getIntervalCaptureEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_INTERVAL_CAPTURE_ENABLED, false)
    }

    fun setIntervalCaptureEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_INTERVAL_CAPTURE_ENABLED, enabled).apply()
    }

    fun getIntervalCaptureSeconds(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_INTERVAL_CAPTURE_SECONDS, INTERVAL_CAPTURE_DEFAULT_SECONDS)
            .coerceAtLeast(INTERVAL_CAPTURE_MIN_SECONDS)
    }

    fun setIntervalCaptureSeconds(context: Context, seconds: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_INTERVAL_CAPTURE_SECONDS, seconds.coerceAtLeast(INTERVAL_CAPTURE_MIN_SECONDS))
            .apply()
    }

    fun getIntervalAutoAnalyzeEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_INTERVAL_AUTO_ANALYZE_ENABLED, false)
    }

    fun setIntervalAutoAnalyzeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_INTERVAL_AUTO_ANALYZE_ENABLED, enabled).apply()
    }

    fun getMemoryEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MEMORY_ENABLED, true)
    }

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
    }

    fun getMemoryVectorStoreId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MEMORY_VECTOR_STORE_ID, null).orEmpty()
    }

    fun setMemoryVectorStoreId(context: Context, id: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MEMORY_VECTOR_STORE_ID, id.trim()).apply()
    }
}

