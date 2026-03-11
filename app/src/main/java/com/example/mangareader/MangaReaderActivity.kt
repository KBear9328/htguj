package com.example.mangareader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.mangareader.databinding.ActivityMangaReaderBinding
import com.example.mangareader.model.Panel
import com.example.mangareader.util.MangaRegionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMangaReaderBinding
    private val viewModel: MangaViewModel by viewModels()
    private var gestureDetector: GestureDetector? = null
    private var zoomAnimator: AnimatorSet? = null
    private var ocrJob: Job? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isControlsVisible = true
    private var controlsHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupGestures()
        setupControls()
        setupObservers()
    }

    private fun setupObservers() {
        viewModel.currentPageIndex.observe(this)  { updatePageIndicator(); updatePageThumbnail() }
        viewModel.currentPanelIndex.observe(this) { panelIdx ->
            val page  = viewModel.getCurrentPage() ?: return@observe
            val panel = page.panels.getOrNull(panelIdx) ?: return@observe
            displayPanel(panel, page.panels.size, panelIdx)
        }
        viewModel.isAutoPlaying.observe(this)  { binding.btnPlayPause.text = if (it) "⏸" else "▶" }
        viewModel.currentOcrText.observe(this) { text ->
            binding.tvSubtitle.text       = text
            binding.tvSubtitle.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    // ── Panel display flow ─────────────────────────────────────────────────────
    // 1. Show full page with gold highlight on current panel
    // 2. Smooth zoom-in to panel
    // 3. Detect character + motion regions (ML Kit face + heuristics + MotionItemDetector)
    // 4. AnimatedPanelView starts per-region natural bounce + motion animations
    // 5. OCR → TTS reads text; auto-advance on completion if playing

    private fun displayPanel(panel: Panel, totalPanels: Int, panelIndex: Int) {
        ocrJob?.cancel()
        viewModel.ttsManager.stop()
        zoomAnimator?.cancel()
        binding.animatedPanelView.stopAnimations()
        showFullPageWithHighlight(panel)
        uiHandler.postDelayed({ zoomIntoPanelAnimated(panel, totalPanels, panelIndex) }, 380L)
        updatePanelIndicator(panelIndex, totalPanels)
    }

    private fun showFullPageWithHighlight(panel: Panel) {
        val page = viewModel.getCurrentPage() ?: return
        Glide.with(this).load(page.bitmap).into(binding.ivFullPage)
        binding.ivFullPage.alpha           = 1f
        binding.ivFullPage.visibility      = View.VISIBLE
        binding.panelHighlightView.setPanel(panel.rect, page.bitmap.width, page.bitmap.height)
        binding.panelHighlightView.alpha   = 1f
        binding.panelHighlightView.visibility = View.VISIBLE
        binding.animatedPanelView.alpha    = 0f
    }

    private fun zoomIntoPanelAnimated(panel: Panel, totalPanels: Int, panelIndex: Int) {
        binding.animatedPanelView.setPanel(panel.bitmap, emptyList())
        binding.animatedPanelView.scaleX = 0.75f
        binding.animatedPanelView.scaleY = 0.75f
        binding.animatedPanelView.alpha  = 0f

        zoomAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.ivFullPage,       "alpha",  1f,    0.15f).apply { duration = 320L; interpolator = AccelerateDecelerateInterpolator() },
                ObjectAnimator.ofFloat(binding.panelHighlightView,"alpha", 1f,    0f   ).apply { duration = 300L },
                ObjectAnimator.ofFloat(binding.animatedPanelView, "scaleX",0.75f, 1f   ).apply { duration = 420L; interpolator = OvershootInterpolator(1.1f) },
                ObjectAnimator.ofFloat(binding.animatedPanelView, "scaleY",0.75f, 1f   ).apply { duration = 420L; interpolator = OvershootInterpolator(1.1f) },
                ObjectAnimator.ofFloat(binding.animatedPanelView, "alpha", 0f,    1f   ).apply { duration = 320L }
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { detectAndAnimate(panel) }
            })
            start()
        }
    }

    private fun detectAndAnimate(panel: Panel) {
        ocrJob = lifecycleScope.launch {
            // Detect character regions (faces, hair, bodies) + motion items (speed lines, projectiles…)
            val regions = withContext(Dispatchers.Default) { MangaRegionDetector.detectRegions(panel.bitmap) }
            binding.animatedPanelView.setPanel(panel.bitmap, regions)
            binding.animatedPanelView.startAnimations()

            // OCR
            val text = withContext(Dispatchers.IO) {
                if (panel.ocrText.isNotEmpty()) panel.ocrText
                else viewModel.ttsManager.extractText(panel.bitmap).also { panel.ocrText = it }
            }
            viewModel.setOcrText(text)

            // TTS + auto-advance
            if (viewModel.isAutoPlaying.value == true) {
                viewModel.ttsManager.speak(text) {
                    uiHandler.post {
                        if (viewModel.isAutoPlaying.value == true) {
                            if (!viewModel.navigateToNextPanel()) { viewModel.setAutoPlaying(false); showEndMessage() }
                        }
                    }
                }
            }
        }
    }

    // ── Controls ───────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            val wasPlaying = viewModel.isAutoPlaying.value ?: false
            viewModel.setAutoPlaying(!wasPlaying)
            if (!wasPlaying) viewModel.getCurrentPanel()?.let { detectAndAnimate(it) }
            else viewModel.ttsManager.stop()
        }
        binding.btnNext.setOnClickListener { viewModel.ttsManager.stop(); viewModel.navigateToNextPanel() }
        binding.btnPrev.setOnClickListener { viewModel.ttsManager.stop(); viewModel.navigateToPrevPanel() }
        binding.seekBarPage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) viewModel.navigateToPage(progress) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.sliderSpeechRate.addOnChangeListener { _, value, _ -> viewModel.ttsManager.setSpeechRate(value) }
        binding.btnBack.setOnClickListener { finish() }
        binding.animatedPanelView.setOnClickListener { toggleControls() }
        binding.ivFullPage.setOnClickListener { toggleControls() }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean = when {
                vX < -400f -> { viewModel.ttsManager.stop(); viewModel.navigateToNextPanel(); true }
                vX >  400f -> { viewModel.ttsManager.stop(); viewModel.navigateToPrevPanel(); true }
                else -> false
            }
        })
        binding.root.setOnTouchListener { _, event -> gestureDetector?.onTouchEvent(event) ?: false }
    }

    private fun updatePageIndicator() {
        val pages = viewModel.getPages()
        val idx   = viewModel.currentPageIndex.value ?: 0
        binding.tvPageIndicator.text  = "${idx + 1} / ${pages.size}"
        binding.seekBarPage.max       = (pages.size - 1).coerceAtLeast(0)
        binding.seekBarPage.progress  = idx
    }

    private fun updatePageThumbnail() { Glide.with(this).load(viewModel.getCurrentPage()?.bitmap).into(binding.ivPageThumb) }

    private fun updatePanelIndicator(panelIndex: Int, total: Int) { binding.tvPanelIndicator.text = "Panel ${panelIndex + 1}/$total" }

    private fun toggleControls() {
        controlsHideRunnable?.let { uiHandler.removeCallbacks(it) }
        if (isControlsVisible) hideControls()
        else {
            showControls()
            controlsHideRunnable = Runnable { hideControls() }
            uiHandler.postDelayed(controlsHideRunnable!!, 3000L)
        }
    }

    private fun showControls() { isControlsVisible = true;  binding.layoutTopBar.animate().alpha(1f).translationY(0f).duration = 220; binding.layoutBottomBar.animate().alpha(1f).translationY(0f).duration = 220 }
    private fun hideControls() { isControlsVisible = false; binding.layoutTopBar.animate().alpha(0f).translationY(-80f).duration = 220; binding.layoutBottomBar.animate().alpha(0f).translationY(80f).duration = 220 }

    private fun showEndMessage() {
        binding.tvEndMessage.visibility = View.VISIBLE; binding.tvEndMessage.alpha = 0f
        binding.tvEndMessage.animate().alpha(1f).duration = 500
        uiHandler.postDelayed({ binding.tvEndMessage.animate().alpha(0f).duration = 500; uiHandler.postDelayed({ binding.tvEndMessage.visibility = View.GONE }, 500) }, 3000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.animatedPanelView.stopAnimations()
        zoomAnimator?.cancel(); ocrJob?.cancel()
        controlsHideRunnable?.let { uiHandler.removeCallbacks(it) }
    }
}
