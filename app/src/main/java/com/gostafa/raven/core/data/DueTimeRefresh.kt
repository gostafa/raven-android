package com.gostafa.raven.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Restarts due-time queries immediately when the activity resumes. */
@Singleton
class DueTimeRefresh @Inject constructor() {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun refresh() { revision.value++ }
}
