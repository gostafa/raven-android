package com.gostafa.raven.features.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.model.ReviewItem
import com.gostafa.raven.core.model.Rating
import com.gostafa.raven.core.reminders.ActiveReview
import com.gostafa.raven.core.reminders.ReminderInvalidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val mode: String = "due",
    val target: String? = null,
) {
    val current: ReviewItem? get() = items.getOrNull(index)
    val complete: Boolean get() = !loading && !loadFailed && current == null
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: RavenRepository,
    private val activeReview: ActiveReview = ActiveReview(),
    private val reminders: ReminderInvalidator = ReminderInvalidator {},
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()
    private var sessionJob: Job? = null
    private var started = false

    fun begin(mode: String, target: String?, retry: Boolean = false) {
        if (started && !retry && _state.value.mode == mode && _state.value.target == target) return
        started = true
        sessionJob?.cancel()
        _state.value = ReviewUiState(loading = true, mode = mode, target = target)
        sessionJob = viewModelScope.launch {
            try {
                val items = when (mode) {
                    "card" -> target?.let { repository.queueCard(it) }.orEmpty()
                    "folder" -> target?.let { repository.queueFolder(it) }.orEmpty()
                    "due-card" -> target?.let { repository.queueDueCard(it) }.orEmpty()
                    else -> repository.queueDue()
                }
                _state.value = ReviewUiState(items = items, loading = false, mode = mode, target = target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun retry() = begin(_state.value.mode, _state.value.target, retry = true)

    fun visibleCard(cardId: String?) {
        activeReview.show(cardId)
        reminders.changed()
    }

    fun rate(rating: Rating) {
        val snapshot = _state.value
        val current = snapshot.current ?: return
        if (snapshot.submitting) return
        _state.value = snapshot.copy(submitting = true, saveFailed = false)
        sessionJob = viewModelScope.launch {
            try {
                repository.rate(current.prompt.id, rating, practice = snapshot.mode in setOf("card", "folder"))
                _state.value = _state.value.copy(index = snapshot.index + 1, submitting = false, saveFailed = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(submitting = false, saveFailed = true)
            }
        }
    }
}
