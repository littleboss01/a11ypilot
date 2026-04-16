package com.mrgreenapps.a11ypilot

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceState {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    fun refresh(context: Context) {
        _enabled.value = isAccessibilityServiceEnabled(context)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PilotAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component.flattenToString().equals(expected, ignoreCase = true)) return true
            if (component.packageName == context.packageName &&
                component.className == PilotAccessibilityService::class.java.name) return true
        }
        return false
    }
}
