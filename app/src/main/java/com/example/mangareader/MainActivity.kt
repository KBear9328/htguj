package com.example.mangareader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.mangareader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MangaViewModel by viewModels()

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.loadMangaZip(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnLoadManga.setOnClickListener {
            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        setupObservers()
    }

    private fun setupObservers() {
        viewModel.loadState.observe(this) { state ->
            when (state) {
                is MangaViewModel.LoadState.Idle    -> showIdle()
                is MangaViewModel.LoadState.Loading -> showLoading(state.message, state.progress, state.total)
                is MangaViewModel.LoadState.Success -> {
                    startActivity(Intent(this, MangaReaderActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
                is MangaViewModel.LoadState.Error   -> showError(state.message)
            }
        }
    }

    private fun showIdle() {
        binding.layoutIdle.visibility    = View.VISIBLE
        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility   = View.GONE
    }

    private fun showLoading(message: String, progress: Int, total: Int) {
        binding.layoutIdle.visibility    = View.GONE
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutError.visibility   = View.GONE
        binding.tvLoadingMessage.text    = message
        if (total > 0) { binding.progressBar.max = total; binding.progressBar.progress = progress; binding.progressBar.isIndeterminate = false }
        else binding.progressBar.isIndeterminate = true
    }

    private fun showError(message: String) {
        binding.layoutIdle.visibility  = View.VISIBLE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvError.text           = message
    }
}
