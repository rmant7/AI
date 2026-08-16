package ai.localstudio.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.R
import ai.localstudio.app.applySystemBarInsets
import ai.localstudio.app.databinding.ActivityCameraBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live object detection through the back camera — the one feature VirtualClone's
 * `feature/mediapipe-integration` branch actually finished, ported onto plain
 * Views (this app has no Compose dependency) and an on-demand model download
 * instead of a bundled asset.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var store: ObjectDetectorStore
    private val helper by lazy { ObjectDetectorHelper(this) }
    private var analysisExecutor: ExecutorService? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPipeline() else showStatus(getString(R.string.camera_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = ObjectDetectorStore(this)
        binding.downloadButton.setOnClickListener { downloadModel() }

        if (!store.isInstalled()) {
            promptDownload()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        helper.close()
        analysisExecutor?.shutdown()
    }

    private fun promptDownload() {
        binding.downloadButton.visibility = View.VISIBLE
        showStatus(getString(R.string.camera_model_missing))
    }

    private fun downloadModel() {
        binding.downloadButton.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                store.download { downloaded, total ->
                    if (total > 0) binding.progress.progress = (downloaded * 100 / total).toInt()
                }
            }
            binding.progress.visibility = View.GONE
            result
                .onSuccess {
                    if (ContextCompat.checkSelfPermission(this@CameraActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startPipeline()
                    } else {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
                .onFailure { error ->
                    binding.downloadButton.visibility = View.VISIBLE
                    showStatus("Ошибка загрузки: ${error.message}")
                }
        }
    }

    private fun startPipeline() {
        showStatus("")
        val setup = helper.setup(store.modelFile) { result, width, height ->
            runOnUiThread { binding.overlay.update(result, width, height) }
        }
        setup.onFailure { error -> showStatus("Не удалось запустить детектор: ${error.message}") }
        if (setup.isFailure) return

        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy -> helper.detectLivestream(imageProxy, isFrontCamera = false) }

                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { error -> showStatus("Не удалось запустить камеру: ${error.message}") }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun showStatus(text: String) {
        binding.statusText.text = text
        binding.statusText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }
}
