package com.example.blindmap

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.location.Location
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = this::class.java.simpleName
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(application)
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val client = OkHttpClient()
    private lateinit var cameraExecutor: ExecutorService
    private var isCameraStarted = false
    private var isMoving = false
    private lateinit var locationCallback: LocationCallback
    private var lastAnalysisTime: Long = 0L
    private var pendingAddress: String? = null
    private var isConfirming: Boolean = false
    private var currentLatLng: LatLng? = null
    private val _isNavigating = MutableLiveData<Boolean>(false)
    val isNavigating: LiveData<Boolean> get() = _isNavigating

    private val _speechResult = MutableLiveData<String>()
    val speechResult: LiveData<String> get() = _speechResult

    private val _ttsMessage = MutableLiveData<String>()
    val ttsMessage: LiveData<String> get() = _ttsMessage

    private val _mapUpdate = MutableLiveData<MapUpdate>()
    val mapUpdate: LiveData<MapUpdate> get() = _mapUpdate

    private val _navigationButtonText = MutableLiveData<String>("Bắt đầu dẫn đường")
    val navigationButtonText: LiveData<String> get() = _navigationButtonText

    data class MapUpdate(
        val latLng: LatLng? = null,
        val markerTitle: String? = null,
        val polylinePoints: List<LatLng>? = null,
        val clearMap: Boolean = false
    )

    init {
        tts = TextToSpeech(application, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupLocationUpdates()
    }

    fun handleSpeechResult(result: Intent?) {
        val results = result?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spokenText = results?.firstOrNull()?.lowercase() ?: ""
        if (isConfirming) {
            if (spokenText.contains("có")) {
                _ttsMessage.setValue("Đã xác nhận. Bắt đầu tìm đường đến ${pendingAddress}.")
                isConfirming = false
                _isNavigating.setValue(true)
                _navigationButtonText.setValue("Dừng dẫn đường")
                pendingAddress?.let { getCoordinatesFromAddress(it) }
            } else if (spokenText.contains("không")) {
                _ttsMessage.setValue("Vui lòng nói lại địa chỉ.")
                isConfirming = false
                pendingAddress = null
                _speechResult.setValue("start_recognition")
            } else {
                _ttsMessage.setValue("Không nhận diện được. Nói 'có' hoặc 'không'.")
                _speechResult.setValue("start_confirmation")
            }
        } else {
            if (spokenText.isNotEmpty()) {
                pendingAddress = spokenText
                _ttsMessage.setValue("Bạn muốn đến $spokenText phải không? Nói 'có' để tiếp tục hoặc 'không' để nói lại.")
                isConfirming = true
                _speechResult.setValue("start_confirmation")
            } else {
                _ttsMessage.setValue("Không nhận diện được giọng nói. Vui lòng nói lại.")
                _speechResult.setValue("start_recognition")
            }
        }
    }

    fun startSpeechRecognition(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (isConfirming) "Nói 'có' hoặc 'không'" else "Hãy nói địa chỉ bạn muốn đến")
        }
    }

    fun checkLocationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun checkSpeechPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun checkCameraPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun onCameraPermissionDenied() {
        _ttsMessage.setValue("Quyền truy cập camera bị từ chối. Không thể phát hiện vật cản.")
    }

    fun getCurrentLocation() {
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    currentLatLng = LatLng(it.latitude, it.longitude)
                    _mapUpdate.setValue(MapUpdate(latLng = currentLatLng, markerTitle = "Vị trí hiện tại"))
                    _ttsMessage.setValue("Vị trí hiện tại: ${it.latitude}, ${it.longitude}")
                }
            }
        }
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                isMoving = lastLocation.speed > 0.5f
                if (isMoving && !isCameraStarted) {
                    _speechResult.setValue("request_camera_permission")
                } else if (!isMoving && isCameraStarted) {
                    stopCamera()
                }
            }
        }
    }

    fun startLocationUpdates() {
        if (checkLocationPermission(getApplication())) {
            val locationRequest = LocationRequest.create().apply {
                interval = 5000
                fastestInterval = 2000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    fun stopNavigation() {
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (isCameraStarted) {
            stopCamera()
        }
        pendingAddress = null
        isConfirming = false
        _isNavigating.setValue(false)
        _navigationButtonText.setValue("Bắt đầu dẫn đường")
        _mapUpdate.setValue(MapUpdate(clearMap = true))
        _ttsMessage.setValue("Đã dừng dẫn đường.")
        // Re-add current location marker after stopping navigation
        currentLatLng?.let {
            _mapUpdate.setValue(MapUpdate(latLng = it, markerTitle = "Vị trí hiện tại"))
        }
    }

    fun startCamera(context: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        if (!checkCameraPermission(context)) {
            _speechResult.setValue("request_camera_permission")
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastAnalysisTime >= 1000) {
                            if (isMoving) {
                                val bitmap = imageProxy.toBitmap()
                                detectObjects(bitmap)
                            }
                            lastAnalysisTime = currentTime
                        }
                        imageProxy.close()
                    }
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                isCameraStarted = true
                _ttsMessage.setValue("Bắt đầu phát hiện vật cản.")
            } catch (e: Exception) {
                Log.e("CameraX", "Lỗi camera: ${e.message}")
                _ttsMessage.setValue("Lỗi khởi động camera.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(getApplication())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            isCameraStarted = false
            _ttsMessage.setValue("Đã dừng phát hiện vật cản.")
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun detectObjects(bitmap: Bitmap) {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        val objectDetector = ObjectDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        val obstacleLabels = listOf("car", "truck", "bus", "bicycle", "person", "tree", "pole", "fence", "wall", "barrier")
        objectDetector.process(image)
            .addOnSuccessListener { detectedObjects ->
                var hasObstacle = false
                for (obj in detectedObjects) {
                    val label = obj.labels.firstOrNull()?.text?.lowercase() ?: "Không xác định"
                    if (label in obstacleLabels) {
                        hasObstacle = true
                        val boundingBox = obj.boundingBox
                        val boxArea = boundingBox.width() * boundingBox.height()
                        val imageArea = bitmap.width * bitmap.height
                        val isClose = boxArea > 0.3 * imageArea
                        _ttsMessage.setValue(
                            if (isClose) {
                                "Cảnh báo: $label ở gần phía trước. Hãy cẩn thận!"
                            } else {
                                "Phát hiện $label phía trước."
                            }
                        )
                    }
                }
                if (!hasObstacle) {
                    _ttsMessage.setValue("Không phát hiện vật cản phía trước.")
                }
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Lỗi phát hiện đối tượng: ${e.message}")
                _ttsMessage.setValue("Lỗi: Không thể phát hiện vật thể. Vui lòng thử lại.")
            }
    }

    fun getCoordinatesFromAddress(address: String) {
        val encodedAddress = address.replace(" ", "+")
        val url = "https://maps.track-asia.com/api/v2/geocode/json?address=$encodedAddress&key=public_key"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                _ttsMessage.postValue("Không thể tìm vị trí. Vui lòng thử lại.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    if (json.getString("status") == "OK") {
                        val location = json.getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("geometry")
                            .getJSONObject("location")
                        val lat = location.getDouble("lat")
                        val lng = location.getDouble("lng")
                        val destLatLng = LatLng(lat, lng)
                        _mapUpdate.postValue(MapUpdate(clearMap = true))
                        _mapUpdate.postValue(MapUpdate(latLng = currentLatLng, markerTitle = "Vị trí hiện tại"))
                        _mapUpdate.postValue(MapUpdate(latLng = destLatLng, markerTitle = "Đích đến: $address"))
                        _ttsMessage.postValue("Đã tìm thấy $address tại tọa độ $lat, $lng. Bắt đầu tìm đường.")
                        getDirections(currentLatLng!!, destLatLng)
                    } else {
                        _ttsMessage.postValue("Không tìm thấy địa chỉ. Vui lòng thử lại.")
                    }
                }
            }
        })
    }

    fun getDirections(origin: LatLng, destination: LatLng) {
        val url = "https://maps.track-asia.com/route/v2/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=walking" +
                "&language=vi" +
                "&key=public_key"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                _ttsMessage.postValue("Lỗi tìm đường. Vui lòng thử lại.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    if (json.getString("status") == "OK") {
                        // Clear the map before adding new markers and polyline
                        _mapUpdate.postValue(MapUpdate(clearMap = true))
                        // Re-add current location and destination markers
                        _mapUpdate.postValue(MapUpdate(latLng = origin, markerTitle = "Vị trí hiện tại"))
                        _mapUpdate.postValue(MapUpdate(latLng = destination, markerTitle = "Đích đến"))
                        val routes = json.getJSONArray("routes")
                        val overviewPolyline = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                        val points = decodePolyline(overviewPolyline)
                        val legs = routes.getJSONObject(0).getJSONArray("legs")
                        val steps = legs.getJSONObject(0).getJSONArray("steps")
                        val instructionsBuilder = StringBuilder("Hướng dẫn chi tiết: ")
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            val htmlInstructions = step.getString("html_instructions")
                            val cleanInstructions = android.text.Html.fromHtml(htmlInstructions).toString()
                            val distanceText = step.getJSONObject("distance").getString("text")
                            val durationText = step.getJSONObject("duration").getString("text")
                            val maneuver = if (step.has("maneuver")) step.getString("maneuver") else ""
                            val maneuverText = when (maneuver) {
                                "turn-left" -> "Rẽ trái"
                                "turn-right" -> "Rẽ phải"
                                "keep-left" -> "Giữ bên trái"
                                "keep-right" -> "Giữ bên phải"
                                "straight" -> "Đi thẳng"
                                else -> ""
                            }
                            instructionsBuilder.append("Bước ${i + 1}: $maneuverText $cleanInstructions sau $distanceText, đi trong $durationText. ")
                        }
                        _mapUpdate.postValue(MapUpdate(polylinePoints = points))
                        _ttsMessage.postValue("Đã vẽ đường đi đến đích. ${instructionsBuilder.toString()}")
                    } else {
                        _ttsMessage.postValue("Không tìm thấy đường đi.")
                    }
                }
            }
        })
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            val latLng = LatLng((lat.toDouble() / 1E5), (lng.toDouble() / 1E5))
            poly.add(latLng)
        }
        return poly
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("vi_VN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Ngôn ngữ không hỗ trợ")
            } else {
                _ttsMessage.setValue("Ứng dụng sẵn sàng. Nói địa chỉ để tìm đường.")
            }
        }
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
        cameraExecutor.shutdown()
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        stopCamera()
        super.onCleared()
    }
}