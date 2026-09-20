package com.auralis.music.ui.components

import com.auralis.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppPillData(
    val message: String,
    val iconRes: Int? = R.drawable.ic_notification_auralis,
    val durationMs: Long = 2000L
)

object AppPillManager {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var dismissJob: Job? = null

    private val _pillState = MutableStateFlow<AppPillData?>(null)
    val pillState: StateFlow<AppPillData?> = _pillState.asStateFlow()

    fun showPill(
        message: String,
        iconRes: Int? = R.drawable.ic_notification_auralis,
        durationMs: Long = 2000L
    ) {
        dismissJob?.cancel()
        _pillState.value = AppPillData(message, iconRes, durationMs)
        dismissJob = scope.launch {
            delay(durationMs)
            _pillState.value = null
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        _pillState.value = null
    }
}
