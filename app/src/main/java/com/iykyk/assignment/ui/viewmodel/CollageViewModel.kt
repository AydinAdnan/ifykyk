package com.iykyk.assignment.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.domain.model.PersonCluster
import com.iykyk.assignment.domain.model.PipelineProgress
import com.iykyk.assignment.domain.pipeline.CollageCanvasRenderer
import com.iykyk.assignment.domain.pipeline.VideoPipelineEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScreenState {
    object Home : ScreenState
    data class Processing(val progress: PipelineProgress) : ScreenState
    data class Results(val result: AnalysisResult) : ScreenState
    data class CollagePreview(val result: AnalysisResult) : ScreenState
    data class AppearanceBreakdown(val result: AnalysisResult) : ScreenState
    data class CollageSaved(val result: AnalysisResult, val savedUri: Uri?) : ScreenState
}

class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val pipelineEngine = VideoPipelineEngine(application)
    private val canvasRenderer = CollageCanvasRenderer(application)

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Home)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _progressState = MutableStateFlow(PipelineProgress())
    val progressState: StateFlow<PipelineProgress> = _progressState.asStateFlow()

    private var currentResult: AnalysisResult? = null

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _screenState.value = ScreenState.Processing(PipelineProgress())
            
            pipelineEngine.processVideo(uri).collect { progress ->
                _progressState.value = progress
                _screenState.value = ScreenState.Processing(progress)

                if (progress.finalResult != null) {
                    currentResult = progress.finalResult
                    _screenState.value = ScreenState.Results(progress.finalResult)
                }
            }
        }
    }

    fun navigateTo(screen: ScreenState) {
        _screenState.value = screen
    }

    fun saveAndShareCollage(bitmap: Bitmap, onSaved: (Uri?) -> Unit) {
        viewModelScope.launch {
            val uri = canvasRenderer.saveToGallery(bitmap)
            val res = currentResult
            if (res != null) {
                _screenState.value = ScreenState.CollageSaved(res, uri)
            }
            onSaved(uri)
        }
    }

    fun getShareIntent(bitmap: Bitmap): Intent {
        return canvasRenderer.createShareIntent(bitmap)
    }

    fun resetToHome() {
        _screenState.value = ScreenState.Home
        _progressState.value = PipelineProgress()
        currentResult = null
    }
}
