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
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import com.google.android.gms.maps.model.PolylineOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = this::class.java.simpleName
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(application)
    private var tts: TextToSpeech = TextToSpeech(application, this)
    private var speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
    private val client = OkHttpClient()
    private lateinit var cameraExecutor: ExecutorService
    private var isCameraStarted = false
    private var isNavigatingInternal = false
    private var lastDetectedObject: String? = null
    private lateinit var locationCallback: LocationCallback
    private var destination: LatLng? = null
    private var pendingAddress: String? = null
    var navigationSteps: JSONArray? = null
    private var currentStepIndex = 0 // Theo dõi bước hiện tại
    private val _ttsMessage = MutableLiveData<String>("")
    private val _speechResult = MutableLiveData<String>("")
    private val _mapUpdate = MutableLiveData<MapUpdate>()
    private val _navigationButtonText = MutableLiveData<String>("Bắt đầu dẫn đường")
    private val _isNavigating = MutableLiveData<Boolean>(false)
    private val _isActivelyNavigating = MutableLiveData<Boolean>(false)
    private var speechState: SpeechState = SpeechState.WAITING_FOR_ADDRESS
    private val traveledPath = mutableListOf<LatLng>() // Lưu đường đã đi qua
    private var lastTtsTime = 0L // Thời gian phát TTS lần cuối
    private val ttsMinInterval = 1000L
    private var isTtsBusy = false // Trạng thái TTS đang phát
    private var lastAnnouncedStep = -1 // Bước cuối cùng đã thông báo

    enum class SpeechState {
        WAITING_FOR_ADDRESS,
        WAITING_FOR_CONFIRMATION
    }

    val ttsMessage: LiveData<String> get() = _ttsMessage
    val speechResult: LiveData<String> get() = _speechResult
    val mapUpdate: LiveData<MapUpdate> get() = _mapUpdate
    val navigationButtonText: LiveData<String> get() = _navigationButtonText
    val isNavigating: LiveData<Boolean> get() = _isNavigating
    val isActivelyNavigating: LiveData<Boolean> get() = _isActivelyNavigating

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupLocationCallback()
        setupTtsListener()
    }

    private fun setupTtsListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isTtsBusy = true
                Log.d(TAG, "TTS started at 09:00 PM +07, 02/10/2025")
            }

            override fun onDone(utteranceId: String?) {
                isTtsBusy = false
                lastTtsTime = System.currentTimeMillis()
                Log.d(TAG, "TTS completed at 09:00 PM +07, 02/10/2025")
            }

            override fun onError(utteranceId: String?) {
                isTtsBusy = false
                Log.e(TAG, "TTS error at 09:00 PM +07, 02/10/2025")
            }
        })
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    traveledPath.add(currentLatLng)
                    _mapUpdate.postValue(MapUpdate(latLng = currentLatLng))
                    checkCurrentStep(location)
                    updateRemainingRoute(currentLatLng)
                    checkDestinationReached(location)
                }
            }
        }
    }

    fun checkLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun checkSpeechPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun checkCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun startSpeechRecognition(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (speechState == SpeechState.WAITING_FOR_ADDRESS) "Nói địa chỉ..." else "Nói 'có' hoặc 'không'")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
        return intent
    }

    fun handleSpeechResult(data: Intent?) {
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!results.isNullOrEmpty()) {
            val spokenText = results[0].lowercase(Locale("vi_VN"))
            Log.d(TAG, "handleSpeechResult: $spokenText, state: $speechState at 09:00 PM +07, 02/10/2025")
            when (speechState) {
                SpeechState.WAITING_FOR_ADDRESS -> {
                    pendingAddress = spokenText
                    speechState = SpeechState.WAITING_FOR_CONFIRMATION
                    _speechResult.postValue("start_confirmation")
                    getCoordinatesFromAddress(spokenText)
                }
                SpeechState.WAITING_FOR_CONFIRMATION -> {
                    if (spokenText == "có" && destination != null) {
                        speechState = SpeechState.WAITING_FOR_ADDRESS
                        _speechResult.postValue("confirmed")
                        _ttsMessage.postValue("Đã xác nhận đích đến. Nhấn bắt đầu dẫn đường để tiếp tục.")
                    } else if (spokenText == "không") {
                        speechState = SpeechState.WAITING_FOR_ADDRESS
                        _speechResult.postValue("start_recognition")
                        _ttsMessage.postValue("Vui lòng nói lại địa chỉ.")
                        destination = null
                        pendingAddress = null
                    } else {
                        _speechResult.postValue("start_confirmation")
                        _ttsMessage.postValue("Vui lòng nói 'có' hoặc 'không'.")
                    }
                }
            }
        } else {
            _speechResult.postValue("start_recognition")
            _ttsMessage.postValue("Vui lòng nói lại.")
        }
    }

    fun getCoordinatesFromAddress(address: String) {
        val encodedAddress = address.replace(" ", "+")
        Log.d(TAG, "getCoordinatesFromAddress: $encodedAddress at 09:00 PM +07, 02/10/2025")
        val url = "https://maps.track-asia.com/api/v2/geocode/json?address=$encodedAddress&key=public_key"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to get coordinates: ${e.message} at 09:00 PM +07, 02/10/2025")
                _ttsMessage.postValue("Không tìm thấy địa chỉ.")
                speechState = SpeechState.WAITING_FOR_ADDRESS
                destination = null
                pendingAddress = null
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { json ->
                    val jsonObject = JSONObject(json)
                    if (jsonObject.getString("status") == "OK") {
                        val location = jsonObject.getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("geometry")
                            .getJSONObject("location")
                        destination = LatLng(location.getDouble("lat"), location.getDouble("lng"))
                        Log.d(TAG, "onResponse: lat/lng: (${destination?.latitude},${destination?.longitude}) at 09:00 PM +07, 02/10/2025")
                        _mapUpdate.postValue(MapUpdate(latLng = destination, markerTitle = pendingAddress))
                        _ttsMessage.postValue("Bạn muốn đến $pendingAddress? Nói 'có' hoặc 'không'.")
                    } else {
                        _ttsMessage.postValue("Không tìm thấy địa chỉ.")
                        speechState = SpeechState.WAITING_FOR_ADDRESS
                        destination = null
                        pendingAddress = null
                    }
                }
            }
        })
    }

    fun getDirections(origin: LatLng, destination: LatLng) {
        val url = "https://maps.track-asia.com/route/v2/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=walking" +  // Chế độ đi bộ
                "&language=vi" +   // Ngôn ngữ tiếng Việt để text phù hợp
                "&key=public_key"

        Log.d(TAG, "getDirections: $url at 09:00 PM +07, 02/10/2025")
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to get directions: ${e.message} at 09:00 PM +07, 02/10/2025")
                _ttsMessage.postValue("Lỗi tìm đường. Vui lòng thử lại.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    if (json.getString("status") == "OK") {
                        val routes = json.getJSONArray("routes")
                        val overviewPolyline = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                        val points = decodePolyline(overviewPolyline)
                        val legs = routes.getJSONObject(0).getJSONArray("legs")
                        navigationSteps = legs.getJSONObject(0).getJSONArray("steps")
                        currentStepIndex = 0
                        lastAnnouncedStep = -1
                        Log.d(TAG, "Navigation steps: $navigationSteps at 09:00 PM +07, 02/10/2025")
                        updateRemainingRoute(points[0])
                    } else {
                        _ttsMessage.postValue("Không tìm thấy đường đi.")
                    }
                }
            }
        })
    }

    private fun updateRemainingRoute(currentPosition: LatLng) {
        val timeTaken = measureTimeMillis {
            navigationSteps?.let { steps ->
                if (steps.length() > 0 && currentStepIndex < steps.length()) {
                    val remainingPoints = mutableListOf<LatLng>()
                    var startNewSegment = false
                    for (i in currentStepIndex until steps.length()) {
                        val step = steps.getJSONObject(i)
                        val startLocation = LatLng(
                            step.getJSONObject("start_location").getDouble("lat"),
                            step.getJSONObject("start_location").getDouble("lng")
                        )
                        val endLocation = LatLng(
                            step.getJSONObject("end_location").getDouble("lat"),
                            step.getJSONObject("end_location").getDouble("lng")
                        )
                        Log.d(TAG, "Step $i: Start $startLocation, End $endLocation at 09:00 PM +07, 02/10/2025")
                        if (!startNewSegment && isLocationClose(currentPosition, startLocation, 10.0)) {
                            startNewSegment = true
                            currentStepIndex = i
                            Log.d(TAG, "Starting new segment at step $currentStepIndex at 09:00 PM +07, 02/10/2025")
                        }
                        if (startNewSegment) {
                            remainingPoints.add(endLocation)
                        }
                    }
                    if (remainingPoints.isNotEmpty()) {
                        _mapUpdate.postValue(MapUpdate(
                            polylineOptions = PolylineOptions()
                                .add(currentPosition) // Bắt đầu từ vị trí hiện tại
                                .addAll(remainingPoints) // Thêm các điểm còn lại
                                .width(10f)
                                .color(Color.BLUE)
                        ))
                        Log.d(TAG, "Drawn route from $currentPosition to ${remainingPoints.last()} at 09:00 PM +07, 02/10/2025")
                    } else if (currentStepIndex >= steps.length() - 1) {
                        Log.d(TAG, "Reached or passed last step, clearing map at 09:00 PM +07, 02/10/2025")
                        _mapUpdate.postValue(MapUpdate(clearMap = true))
                    } else {
                        Log.w(TAG, "Warning: remainingPoints is empty but not at destination at 09:00 PM +07, 02/10/2025")
                    }
                } else {
                    Log.d(TAG, "No steps or index out of bounds, clearing map at 09:00 PM +07, 02/10/2025")
                    _mapUpdate.postValue(MapUpdate(clearMap = true))
                }
            }
        }
        Log.d(TAG, "updateRemainingRoute took ${timeTaken}ms at 09:00 PM +07, 02/10/2025")
    }

    private fun isLocationClose(location1: LatLng, location2: LatLng, threshold: Double): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(location1.latitude, location1.longitude, location2.latitude, location2.longitude, results)
        Log.d(TAG, "Distance between $location1 and $location2 is ${results[0]}m at 09:00 PM +07, 02/10/2025")
        return results[0] < threshold
    }

    private fun checkCurrentStep(location: Location) {
        navigationSteps?.let { steps ->
            if (currentStepIndex < steps.length()) {
                val currentStep = steps.getJSONObject(currentStepIndex)
                val startLocation = LatLng(
                    currentStep.getJSONObject("start_location").getDouble("lat"),
                    currentStep.getJSONObject("start_location").getDouble("lng")
                )
                val endLocation = LatLng(
                    currentStep.getJSONObject("end_location").getDouble("lat"),
                    currentStep.getJSONObject("end_location").getDouble("lng")
                )
                val currentLatLng = LatLng(location.latitude, location.longitude)
                val distanceToEnd = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, endLocation.latitude, endLocation.longitude, distanceToEnd)
                Log.d(TAG, "Current step $currentStepIndex: Distance to end $endLocation is ${distanceToEnd[0]}m at 09:00 PM +07, 02/10/2025")

                if (distanceToEnd[0] < 10 && currentStepIndex != lastAnnouncedStep) {
                    currentStepIndex++
                    lastAnnouncedStep = currentStepIndex - 1
                    Log.d(TAG, "Near turn, moved to step $currentStepIndex at 09:00 PM +07, 02/10/2025")
                    announceCurrentStep()
                } else if (distanceToStart(currentLatLng, startLocation) > 20 && currentStepIndex > 0) {
                    currentStepIndex--
                    if (currentStepIndex != lastAnnouncedStep) {
                        lastAnnouncedStep = currentStepIndex
                        Log.d(TAG, "Reverted to step $currentStepIndex at 09:00 PM +07, 02/10/2025")
                        announceCurrentStep()
                    }
                }
            } else {
                Log.d(TAG, "Current step index ($currentStepIndex) exceeds steps length (${steps.length()}) at 09:00 PM +07, 02/10/2025")
            }
        }
    }

    private fun distanceToStart(current: LatLng, start: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, start.latitude, start.longitude, results)
        Log.d(TAG, "Distance to start $start from $current is ${results[0]}m at 09:00 PM +07, 02/10/2025")
        return results[0]
    }

    private fun announceCurrentStep() {
        val currentTime = System.currentTimeMillis()
        if (isTtsBusy || (currentTime - lastTtsTime) < ttsMinInterval) return

        navigationSteps?.let { steps ->
            if (currentStepIndex < steps.length()) {
                val currentStep = steps.getJSONObject(currentStepIndex)
                val htmlInstructions = currentStep.getString("html_instructions")
                val cleanInstructions = android.text.Html.fromHtml(htmlInstructions).toString()
                val distanceText = currentStep.getJSONObject("distance").getString("text")
                val maneuver = if (currentStep.has("maneuver")) currentStep.getString("maneuver") else ""
                val maneuverText = when (maneuver) {
                    "turn-left" -> "Rẽ trái"
                    "turn-right" -> "Rẽ phải"
                    "keep-left" -> "Giữ bên trái"
                    "keep-right" -> "Giữ bên phải"
                    "straight" -> "Đi thẳng"
                    else -> ""
                }
                val message = "Bước ${currentStepIndex + 1}: $maneuverText $cleanInstructions, cách $distanceText."
                _ttsMessage.postValue(message)
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "navigation")
                Log.d(TAG, "Announced: $message at 09:00 PM +07, 02/10/2025")
            } else if (currentStepIndex == steps.length()) {
                val message = "Đã đến đích cuối cùng."
                _ttsMessage.postValue(message)
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "navigation")
                Log.d(TAG, "Announced: $message at 09:00 PM +07, 02/10/2025")
                _mapUpdate.postValue(MapUpdate(clearMap = true))
            }
        }
    }

    fun startActiveNavigation(steps: JSONArray) {
        navigationSteps = steps
        currentStepIndex = 0
        lastAnnouncedStep = -1
        _isActivelyNavigating.postValue(true)
        _navigationButtonText.postValue("Dừng dẫn đường")
        startLocationUpdates()
    }

    fun stopNavigation() {
        _mapUpdate.postValue(MapUpdate(clearMap = true))
        _ttsMessage.postValue("Đã dừng dẫn đường.")
        _isActivelyNavigating.postValue(false)
        _isNavigating.postValue(false)
        _navigationButtonText.postValue("Bắt đầu dẫn đường")
        stopCamera()
        destination = null
        pendingAddress = null
        navigationSteps = null
        currentStepIndex = 0
        lastAnnouncedStep = -1
        traveledPath.clear()
        speechState = SpeechState.WAITING_FOR_ADDRESS
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    fun startCamera(context: Context, activity: AppCompatActivity) {
        if (!isCameraStarted) {
            isCameraStarted = true
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(activity.findViewById<androidx.camera.view.PreviewView>(R.id.camera_preview).surfaceProvider)
                }
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImage(imageProxy)
                        }
                    }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                    Log.d(TAG, "Camera started at 09:00 PM +07, 02/10/2025")
                } catch (exc: Exception) {
                    Log.e(TAG, "Camera failed to start: ${exc.message} at 09:00 PM +07, 02/10/2025")
                    _ttsMessage.postValue("Lỗi khi khởi động camera")
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        imageProxy.toBitmap()?.let { bitmap ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build()
            val detector = ObjectDetection.getClient(options)
            detector.process(inputImage)
                .addOnSuccessListener { objects ->
                    handleObjects(objects)
                }
                .addOnFailureListener { e ->

                }
        }
        imageProxy.close()
    }

    private fun handleObjects(objects: List<com.google.mlkit.vision.objects.DetectedObject>) {
        if (isNavigatingInternal) {
            val currentTime = System.currentTimeMillis()
            if (isTtsBusy || (currentTime - lastTtsTime) < ttsMinInterval) return

            if (objects.isEmpty()) {
                if (lastDetectedObject != null) {

                }
            } else {
                val primaryObject = objects[0].labels.firstOrNull()?.text ?: "vật cản không xác định"
                if (primaryObject != lastDetectedObject) {
                    val message = "Cảnh báo: $primaryObject ở gần!"
                    _ttsMessage.postValue(message)
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "obstacle")
                    lastDetectedObject = primaryObject
                    Log.d(TAG, "Announced: $message at 09:00 PM +07, 02/10/2025")
                }
            }
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
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
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun startNavigation(context: Context, activity: AppCompatActivity) {
        isNavigatingInternal = true
        _isNavigating.postValue(true)
        _navigationButtonText.postValue("Dừng dẫn đường")
        startLocationUpdates()
        startCamera(context, activity)
    }

    fun startLocationUpdates() {
        if (checkLocationPermission(getApplication())) {
            val locationRequest = LocationRequest.create().apply {
                interval = 1000
                fastestInterval = 500
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "Location updates started at 09:00 PM +07, 02/10/2025")
        }
    }

    fun getCurrentLocation() {
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    _mapUpdate.postValue(MapUpdate(latLng = LatLng(it.latitude, it.longitude)))
                    Log.d(TAG, "Current location: ${it.latitude}, ${it.longitude} at 09:00 PM +07, 02/10/2025")
                }
            }
        }
    }

    private fun checkDestinationReached(location: Location) {
        destination?.let { dest ->
            navigationSteps?.let { steps ->
                if (steps.length() > 0) {
                    val lastStep = steps.getJSONObject(steps.length() - 1)
                    val destLat = lastStep.getJSONObject("end_location").getDouble("lat")
                    val destLng = lastStep.getJSONObject("end_location").getDouble("lng")
                    val distance = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, destLat, destLng, distance)
                    if (distance[0] < 10) {
                        val message = "Đã đến đích."
                        _ttsMessage.postValue(message)
                        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "navigation")
                        stopNavigation()
                        Log.d(TAG, "Announced: $message at 09:00 PM +07, 02/10/2025")
                    }
                }
            }
        }
    }

    fun startActiveNavigationWithDirections(currentLocation: LatLng) {
        destination?.let { dest ->
            getDirections(currentLocation, dest)
        } ?: run {
            _ttsMessage.postValue("Vui lòng chọn đích đến trước.")
        }
    }

    fun stopCamera() {
        if (isCameraStarted) {
            cameraExecutor.shutdown()
            isCameraStarted = false
            Log.d(TAG, "Camera stopped at 09:00 PM +07, 02/10/2025")
        }
    }

    fun ttTranslation(message: String) {
        if (!isTtsBusy && (System.currentTimeMillis() - lastTtsTime) >= ttsMinInterval) {
            _ttsMessage.postValue(message)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "translation")
            Log.d(TAG, "TTS speaking: $message at 09:00 PM +07, 02/10/2025")
        }
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
        Log.d(TAG, "Decoded polyline with ${poly.size} points at 09:00 PM +07, 02/10/2025")
        return poly
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("vi_VN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported at 09:00 PM +07, 02/10/2025")
                _ttsMessage.postValue("Ngôn ngữ không hỗ trợ.")
            } else {
                _ttsMessage.postValue("Ứng dụng sẵn sàng. Nói địa chỉ để tìm đường.")
            }
        } else {
            Log.e(TAG, "TTS initialization failed at 09:00 PM +07, 02/10/2025")
            _ttsMessage.postValue("Khởi tạo giọng nói thất bại.")
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
        Log.d(TAG, "ViewModel cleared at 09:00 PM +07, 02/10/2025")
        super.onCleared()
    }
}