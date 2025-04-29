

// MainActivity.kt (multi-model detection with labeled angle values)
package com.example.dronedetectionapp
import android.content.Intent
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.tensorflow.lite.Interpreter
import androidx.preference.PreferenceManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var sharedPreferences: SharedPreferences
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var interpreter: Interpreter
    private val inputSize = 640

    private var prevCenter: Pair<Float, Float>? = null
    private var prevTime: Long = System.currentTimeMillis()
    private var missedFrames = 0
    private val maxMissedFrames = 3

    private var detectSpeed = false
    private var detectDrone = false
    private var detectAngle = false
    private var isDetecting = false
    private var detectDistance = false
    private var detectLED = false
    // For sticky bounding box logic
    private var lastDetections: List<DetectionOverlayView.DetectionResult> = emptyList()
    private var missedFrameCount = 0
    private val maxStickyFrames = 5  // Or rename to avoid confusion

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        findViewById<Button>(R.id.btnSpeedDetection).setOnClickListener {
            detectSpeed = true
            detectDrone = true
            detectAngle = false
            detectDistance = false
            detectLED = false
            startDetection("Drone_Detect_YOLOv8_float16.tflite")
        }

        findViewById<Button>(R.id.btnAngleDetection).setOnClickListener {
            detectSpeed = false
            detectDrone = false
            detectAngle = true
            detectDistance = false
            detectLED = false
            startDetection("angle_float16 (1).tflite")
        }

        findViewById<Button>(R.id.btnDroneDetection).setOnClickListener {
            detectSpeed = false
            detectDrone = true
            detectAngle = false
            detectDistance = false
            detectLED = false
            startDetection("Drone_Detect_YOLOv8_float16.tflite")
        }

        findViewById<Button>(R.id.btnDistanceDetection).setOnClickListener {
            detectDistance = true
            detectSpeed = false
            detectDrone = false
            detectAngle = false
            detectLED = false
            startDetection("Drone_Detection_YOLOv81_float16.tflite")
        }

        findViewById<Button>(R.id.btnLedIdDetection).setOnClickListener {
            detectLED = true
            detectSpeed = false
            detectDrone = false
            detectAngle = false
            detectDistance = false
            startDetection("Drone_ID_model_float16.tflite")
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }




        textureView.surfaceTextureListener = surfaceTextureListener
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(surfaceTexture)

        val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            applyManualShutterSettings(this)
        }

        cameraDevice?.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cameraSession = session
                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun closeCamera() {
        cameraSession?.close()
        cameraSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }

    private fun startDetection(modelName: String) {
        if (isDetecting) return
        isDetecting = true

        Thread {
            try {
                val model = loadModel(modelName)
                interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
            } catch (e: Exception) {
                Log.e("ModelError", "Model load failed: $modelName", e)
                runOnUiThread {
                    Toast.makeText(this, "Model load failed: $modelName", Toast.LENGTH_LONG).show()
                }
                isDetecting = false
                return@Thread
            }

            while (isDetecting) {
                val bitmap = textureView.bitmap ?: continue
                val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
                val input = convertBitmapToByteBuffer(resized)

                val outputShape = interpreter.getOutputTensor(0).shape()
                val output = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                interpreter.run(input, output)
                val detections = parseDetections(output[0])

                val first = detections.firstOrNull()
                runOnUiThread {
                    overlayView.setDetections(detections)
                    if (detectSpeed) updateSpeedFromBox(first?.boundingBox)
                    else findViewById<TextView>(R.id.tvSpeed)?.text = "Speed: N/A"
                }
                Thread.sleep(200)
            }
        }.start()
    }

    private fun updateSpeedFromBox(box: List<Float>?) {
        var speedCategory = "Unknown"
        val currTime = System.currentTimeMillis()

        val currCenter = box?.let {
            val x = (it[0] + it[2]) / 2
            val y = (it[1] + it[3]) / 2
            Pair(x, y)
        } ?: run {
            missedFrames++
            if (missedFrames <= maxMissedFrames) prevCenter else null
        }

        if (currCenter != null && prevCenter != null) {
            val deltaT = (currTime - prevTime) / 1000.0f
            val dx = currCenter.first - prevCenter!!.first
            val dy = currCenter.second - prevCenter!!.second
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            Log.d("DEBUG_SPEED", "Distance moved: $distance in $deltaT seconds")

            speedCategory = when {
                distance < 5 -> "Static"
                distance < 20 -> "Slow"
                distance < 50 -> "Medium"
                else -> "Fast"
            }
            missedFrames = 0
        }

        prevCenter = currCenter
        prevTime = currTime

        runOnUiThread {
            findViewById<TextView>(R.id.tvSpeed)?.text = "Speed: $speedCategory"
        }
    }

    private fun loadModel(fileName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        return byteBuffer
    }

    private fun parseDetections(output: Array<FloatArray>): List<DetectionOverlayView.DetectionResult> {
        val results = mutableListOf<DetectionOverlayView.DetectionResult>()
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

        val angleLabels = listOf("0°", "45°", "90°", "135°", "180°", "225°", "270°", "315°")
        val distanceLabels = listOf("1m", "2m", "3m", "4m", "5m")

        for (detection in output) {
            val xCenter = detection[0]
            val yCenter = detection[1]
            val width = detection[2]
            val height = detection[3]
            val score = detection[4]
            val labelIndex = detection.getOrNull(5)?.toInt() ?: 0

            if (score > 0.3f) {
                val left = maxOf(0f, (xCenter - width / 2) * viewWidth)
                val top = maxOf(0f, (yCenter - height / 2) * viewHeight)
                val right = minOf(viewWidth, (xCenter + width / 2) * viewWidth)
                val bottom = minOf(viewHeight, (yCenter + height / 2) * viewHeight)

                val label = when {
                    detectAngle -> angleLabels.getOrNull(labelIndex).orEmpty()
                    detectDistance -> distanceLabels.getOrNull(labelIndex).orEmpty()
                    detectLED -> "LED ID ${labelIndex + 1}"
                    else -> "Drone"
                }

                if (detectDistance) {
                    val distanceLabel = distanceLabels.getOrNull(labelIndex).orEmpty()
                    Log.d("DISTANCE_DEBUG", "Predicted Label Index: $labelIndex | Distance: $distanceLabel | Score: $score")
                }

                results.add(
                    DetectionOverlayView.DetectionResult(
                        boundingBox = listOf(left, top, right, bottom),
                        score = score,
                        label = label
                    )
                )
            }
        }

        // Sticky box logic: if current frame has no results, fallback to previous
        // Sticky box logic: if current frame has no results, fallback to previous
        if (results.isNotEmpty()) {
            lastDetections = results
            missedFrameCount = 0
            return nonMaxSuppression(results, 0.4f)
        } else if (missedFrameCount < maxStickyFrames) {
            missedFrameCount++
            return nonMaxSuppression(lastDetections, 0.4f)
        } else {
            lastDetections = emptyList()
            return emptyList()
        }





        return nonMaxSuppression(results, 0.4f)
    }

    private fun nonMaxSuppression(
        detections: List<DetectionOverlayView.DetectionResult>,
        iouThreshold: Float
    ): List<DetectionOverlayView.DetectionResult> {
        val output = mutableListOf<DetectionOverlayView.DetectionResult>()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            output.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(best.boundingBox, other.boundingBox) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return output
    }

    private fun iou(boxA: List<Float>, boxB: List<Float>): Float {
        val xA = maxOf(boxA[0], boxB[0])
        val yA = maxOf(boxA[1], boxB[1])
        val xB = minOf(boxA[2], boxB[2])
        val yB = minOf(boxA[3], boxB[3])

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val boxAArea = (boxA[2] - boxA[0]) * (boxA[3] - boxA[1])
        val boxBArea = (boxB[2] - boxB[0]) * (boxB[3] - boxB[1])

        return interArea / (boxAArea + boxBArea - interArea + 1e-5f)
    }
    private fun applyManualShutterSettings(requestBuilder: CaptureRequest.Builder) {
        val enabled = sharedPreferences.getBoolean("shutter_enabled", false)
        val hz = sharedPreferences.getString("shutter_speed", "30")?.toIntOrNull() ?: 30
        if (!enabled) return

        val shutterNs = 1_000_000_000L / hz
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (exposureRange != null && isoRange != null) {
            val safeExposure = shutterNs.coerceIn(exposureRange.lower, exposureRange.upper)
            val safeISO = isoRange.lower
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExposure)
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, safeISO)
        }
    }
}

