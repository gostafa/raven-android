package com.gostafa.raven.core.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

@Singleton
class CompanionVisibility @Inject constructor() : Application.ActivityLifecycleCallbacks {
    val appVisible = MutableStateFlow(false)
    private var started = 0
    override fun onActivityStarted(activity: Activity) { started++; appVisible.value = started > 0 }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); appVisible.value = started > 0 }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
