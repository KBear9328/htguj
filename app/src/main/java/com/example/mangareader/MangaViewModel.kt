package com.example.mangareader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.mangareader.data.MangaRepository
import com.example.mangareader.model.MangaPage
import com.example.mangareader.model.Panel
import com.example.mangareader.tts.MangaTTSManager
import com.example.mangareader.util.PanelDetector
import com.example.mangareader.util.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    sealed class LoadState {
        object Idle : LoadState()
        data class Loading(val message: String, val progress: Int = 0, val total: Int = 0) : LoadState()
        data class Success(val pages: List<MangaPage>) : LoadState()
        data class Error(val message: String) : LoadState()
    }

    private val _loadState        = MutableLiveData<LoadState>(LoadState.Idle)
    private val _currentPageIndex = MutableLiveData(0)
    private val _currentPanelIndex= MutableLiveData(0)
    private val _isAutoPlaying    = MutableLiveData(false)
    private val _currentOcrText   = MutableLiveData("")

    val loadState:         LiveData<LoadState> = _loadState
    val currentPageIndex:  LiveData<Int>       = _currentPageIndex
    val currentPanelIndex: LiveData<Int>       = _currentPanelIndex
    val isAutoPlaying:     LiveData<Boolean>   = _isAutoPlaying
    val currentOcrText:    LiveData<String>    = _currentOcrText

    val ttsManager = MangaTTSManager(application)
    
    // Use repository as source of truth for pages
    private val allPages: List<MangaPage> get() = MangaRepository.loadedPages

    fun loadMangaZip(uri: Uri) {
        viewModelScope.launch {
            _loadState.value = LoadState.Loading("Opening ZIP file…")
            try {
                val images = ZipExtractor.extractImagesFromZip(
                    context = getApplication(), zipUri = uri,
                    onProgress = { done, total ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _loadState.value = LoadState.Loading("Extracting pages… ($done/$total)", done, total)
                        }
                    }
                )
                if (images.isEmpty()) { _loadState.value = LoadState.Error("No images found in ZIP"); return@launch }

                val mangaPages = mutableListOf<MangaPage>()
                images.forEachIndexed { index, (fileName, bitmap) ->
                    withContext(Dispatchers.Main) {
                        _loadState.value = LoadState.Loading("Detecting panels… (${index+1}/${images.size})", index+1, images.size)
                    }
                    val panels = PanelDetector.detectPanels(bitmap)
                    mangaPages.add(MangaPage(bitmap, panels, index, fileName))
                }
                
                // Store in repository to share across activities
                MangaRepository.loadedPages = mangaPages

                _currentPageIndex.value  = 0
                _currentPanelIndex.value = 0
                _loadState.value = LoadState.Success(mangaPages)
            } catch (e: Exception) {
                _loadState.value = LoadState.Error("Failed to load manga: ${e.message}")
            }
        }
    }

    fun getPages()       = allPages
    fun getCurrentPage() = allPages.getOrNull(_currentPageIndex.value ?: 0)
    fun getCurrentPanel(): Panel? = getCurrentPage()?.panels?.getOrNull(_currentPanelIndex.value ?: 0)

    fun navigateToPage(pageIndex: Int) {
        if (pageIndex in allPages.indices) { _currentPageIndex.value = pageIndex; _currentPanelIndex.value = 0 }
    }

    fun navigateToNextPanel(): Boolean {
        val page = getCurrentPage() ?: return false
        val pi   = _currentPanelIndex.value ?: 0
        return if (pi < page.panels.size - 1) { _currentPanelIndex.value = pi + 1; true }
        else {
            val pg = _currentPageIndex.value ?: 0
            if (pg < allPages.size - 1) { _currentPageIndex.value = pg + 1; _currentPanelIndex.value = 0; true }
            else false
        }
    }

    fun navigateToPrevPanel(): Boolean {
        val pi = _currentPanelIndex.value ?: 0
        return if (pi > 0) { _currentPanelIndex.value = pi - 1; true }
        else {
            val pg = _currentPageIndex.value ?: 0
            if (pg > 0) { _currentPageIndex.value = pg - 1; _currentPanelIndex.value = allPages[pg-1].panels.size - 1; true }
            else false
        }
    }

    fun setAutoPlaying(playing: Boolean) { _isAutoPlaying.value  = playing }
    fun setOcrText(text: String)          { _currentOcrText.value = text    }
    fun getPanelCount()                   = getCurrentPage()?.panels?.size ?: 0

    override fun onCleared() { super.onCleared(); ttsManager.release() }
}
