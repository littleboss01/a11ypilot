package com.mrgreenapps.a11ypilot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_ENTRIES = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun append(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        val current = _events.value
        val next = if (current.size >= MAX_ENTRIES) {
            current.drop(current.size - MAX_ENTRIES + 1) + stamped
        } else {
            current + stamped
        }
        _events.value = next
    }

    fun clear() {
        _events.value = emptyList()
    }
}
