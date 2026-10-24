package com.auralis.music

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Isolates media/provider tests from background account and playback-service startup. */
class MotionArtworkTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, Application::class.java.name, context)
}
