package com.gostafa.raven.features.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.model.ReviewItem
import com.gostafa.raven.core.model.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val mode: String = "due",
) {
    val current: ReviewItem? get() = items.getOrNull(index)
    val complete: Boolean get() = !loading && current == null
}

@HiltViewModel
class ReviewViewModel @Inject constructor(private val repository: RavenRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()

    fun begin(mode: String, target: String?) {
        if (!_state.value.loading && _state.value.mode == mode) return
        _state.value = ReviewUiState(loading = true, mode = mode)
        viewModelScope.launch {
            val items = when (mode) {
                "card" -> if (target == null) emptyList() else repository.queueCard(target)
                "folder" -> if (target == null) emptyList() else repository.queueFolder(target)
                else -> repository.queueDue()
            }
            _state.value = ReviewUiState(items = items, loading = false, mode = mode)
        }
    }

    fun reveal() { _state.value = _state.value.copy(revealed = true) }

    fun rate(rating: Rating) {
        val current = _state.value.current ?: return
        if (_state.value.submitting) return
        _state.value = _state.value.copy(submitting = true)
        viewModelScope.launch {
            repository.rate(current.prompt.id, rating, practice = _state.value.mode != "due")
            _state.value = _state.value.copy(index = _state.value.index + 1, revealed = false, submitting = false)
        }
    }
}
