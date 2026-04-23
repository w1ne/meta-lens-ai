package com.metalens.app.wakeword

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.metalens.app.accessibility.ChatGPTAccessibilityService

/**
 * Android's permission model splits into:
 *   - Runtime: mic / notifications — app can prompt via ActivityCompat.requestPermissions.
 *   - Special: overlay / accessibility — OS forces a trip to Settings; we can only deep-link.
 *
 * Helpers below check each and deep-link straight to the right page.
 */
object WakeWordPermissions {

    fun hasMic(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasAccessibility(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val ourName = ChatGPTAccessibilityService::class.java.name
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC,
        ).any { it.id.endsWith("/$ourName") || it.resolveInfo.serviceInfo.name == ourName }
    }

    fun openOverlaySettings(ctx: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        )
        ctx.startActivity(intent)
    }

    fun openAccessibilitySettings(ctx: Activity) {
        // Android doesn't support deep-linking into a specific accessibility
        // service's toggle on all OEMs. Opening the overall Accessibility list
        // is the most reliable behavior.
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Missing permissions in priority order. First element is what to request next. */
    fun missing(ctx: Context): List<Missing> = buildList {
        if (!hasMic(ctx)) add(Missing.Mic)
        if (!hasOverlay(ctx)) add(Missing.Overlay)
        if (!hasAccessibility(ctx)) add(Missing.Accessibility)
    }

    enum class Missing { Mic, Overlay, Accessibility }
}
