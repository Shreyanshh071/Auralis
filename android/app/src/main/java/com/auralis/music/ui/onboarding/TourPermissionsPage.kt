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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.VerifiedUser, null, tint = accent, modifier = Modifier.size(42.dp))
                Text("A few small permissions.\nYou make the call.", color = ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        items(permissions, key = { it.name }) { permission ->
            val granted = remember(revision, permission) {
                if (permission == TourPermission.NOTIFICATIONS) NotificationManagerCompat.from(context).areNotificationsEnabled()
                else permission.runtimePermission(api)?.let {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                } ?: true
            }
            val runtime = permission.runtimePermission(api)
            val needsSettings = runtime == null || (runtime in requested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, runtime))
            val shape = RoundedCornerShape(22.dp)
            Column(Modifier.fillMaxWidth().background(ink.copy(alpha = .045f), shape)
                .border(1.dp, ink.copy(alpha = .12f), shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(when (permission) {
                        TourPermission.NOTIFICATIONS -> Icons.Rounded.NotificationsActive
                        TourPermission.MICROPHONE -> Icons.Rounded.Mic
                        TourPermission.STORAGE -> Icons.Rounded.Download
                    }, null, tint = accent, modifier = Modifier.size(24.dp))
                    Text(permission.title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Text(permission.explanation, color = ink.copy(alpha = .65f), fontSize = 13.sp, lineHeight = 19.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (granted) "Ready to go" else "Your choice", color = ink.copy(alpha = .5f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TourTouchButton(if (granted) "Allowed ✓" else if (needsSettings) "Settings" else "Allow", granted, accent, ink,
                        Modifier.width(98.dp).height(44.dp)) {
                        if (!granted) {
                            if (needsSettings) {
                                val intent = if (permission == TourPermission.NOTIFICATIONS && api >= 26) {
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            } else if (runtime != null) {
                                requested = ArrayList(requested + runtime)
                                launcher.launch(runtime)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Streaming and background playback are already supported — no extra permission switch. " +
                "If an app update needs installation access, Auralis will ask when you install it.",
                color = ink.copy(alpha = .5f), fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
    }
}

private tailrec fun Context.permissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.permissionActivity()
    else -> null
}
