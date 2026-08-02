package com.gozar.app.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-app log of what the tunnel actually did.
 *
 * Android Studio and `adb logcat` are not available to most people running this
 * app, and "it says connected but nothing loads" is impossible to act on. Every
 * connect attempt records why it succeeded or failed here so the reason can be
 * read — and copied — from the Settings screen.
 */
object Diagnostics {

    private const val CAPACITY = 200

    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>(CAPACITY)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun log(message: String) {
        Log.i("GozarDiag", message)
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast("${timestamp.format(Date())}  $message")
        _lines.value = entries.toList()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _lines.value = emptyList()
    }

    fun asText(): String = _lines.value.joinToString("\n")
}
