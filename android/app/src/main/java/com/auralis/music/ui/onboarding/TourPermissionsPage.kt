package com.auralis.music.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Only permissions used by Auralis; storage is scoped on Android 10 and newer. */
internal enum class TourPermission(val title: String, val explanation: String) {
    NOTIFICATIONS("Stay in the loop", "Download progress and Auralis alerts in your notification shade."),
    MICROPHONE("Search with your voice", "For voice search and identifying songs. Not needed to play music."),
    STORAGE("Save your downloads", "Allow saving songs and playlists to Downloads on this Android version.");

    fun runtimePermission(api: Int): String? = when (this) {
        NOTIFICATIONS -> if (api >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        MICROPHONE -> Manifest.permission.RECORD_AUDIO
        STORAGE -> if (api <= 28) Manifest.permission.WRITE_EXTERNAL_STORAGE else null
    }

    companion object {
        fun visibleOn(api: Int): List<TourPermission> = entries.filter { it != STORAGE || api <= 28 }
    }
}

@Composable
internal fun TourPermissionsPage(accent: Color, ink: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.permissionActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val api = Build.VERSION.SDK_INT
    val permissions = remember(api) { TourPermission.visibleOn(api) }
    var revision by remember { mutableIntStateOf(0) }
    var requested by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings(intent: Intent) = runCatching { context.startActivity(intent) }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val cards = permissions.map { permission ->
        val granted = remember(revision, permission) {
            if (permission == TourPermission.NOTIFICATIONS) NotificationManagerCompat.from(context).areNotificationsEnabled()
            else permission.runtimePermission(api)?.let {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } ?: true
        }
        val runtime = permission.runtimePermission(api)
        val needsSettings = granted || runtime == null || (runtime in requested && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, runtime))
        TourCard(
            icon = when (permission) {
                TourPermission.NOTIFICATIONS -> Icons.Rounded.NotificationsActive
                TourPermission.MICROPHONE -> Icons.Rounded.Mic
                TourPermission.STORAGE -> Icons.Rounded.Download
            },
            tile = when (permission) {
                TourPermission.NOTIFICATIONS -> TourTiles.Pink
                TourPermission.MICROPHONE -> TourTiles.Lavender
                TourPermission.STORAGE -> TourTiles.Green
            },
            title = permission.title, subtitle = permission.explanation,
            trailing = TourCardTrailing.Toggle(granted),
        ) {
            // Granted permissions can only be withdrawn from system settings.
            if (needsSettings) openSettings(
                if (permission == TourPermission.NOTIFICATIONS && api >= 26) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else appDetails)
            else if (runtime != null) {
                requested = ArrayList(requested + runtime)
                launcher.launch(runtime)
            }
        }
    }
    val installCard = if (api >= 26) {
        val canInstall = remember(revision) { context.packageManager.canRequestPackageInstalls() }
        TourCard(Icons.Rounded.Update, TourTiles.Orange, "Install updates",
            "Lets the built-in updater install new versions of Auralis.",
            TourCardTrailing.Toggle(canInstall)) {
            openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
    } else null
    TourCardList(cards + listOfNotNull(installCard), ink, modifier,
        footer = "Everything here is optional. Streaming and background playback already work without any extra permission.")
}

private tailrec fun Context.permissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.permissionActivity()
    else -> null
}
