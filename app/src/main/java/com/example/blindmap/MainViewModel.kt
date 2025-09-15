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
import androidx.appcompat.app.AppCompatActivity
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = this::class.java.simpleName
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(application)
    private var tts: TextToSpeech = TextToSpeech(application, this)
    private var speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
    private val client = OkHttpClient()
    private lateinit var cameraExecutor: ExecutorService
    private var isCameraStarted = false
    private var isMoving = false
    private lateinit var locationCallback: LocationCallback
    private val _ttsMessage = MutableLiveData<String>("")
    private val _speechResult = MutableLiveData<String>("")
    private val _mapUpdate = MutableLiveData<MapUpdate>()
    private val _navigationButtonText = MutableLiveData<String>("Bắt đầu dẫn đường")
    private val _isNavigating = MutableLiveData<Boolean>(false)
    private val _isActivelyNavigating = MutableLiveData<Boolean>(false)
    var navigationSteps: JSONArray? = null

    val ttsMessage: LiveData<String> get() = _ttsMessage
    val speechResult: LiveData<String> get() = _speechResult
    val mapUpdate: LiveData<MapUpdate> get() = _mapUpdate
    val navigationButtonText: LiveData<String> get() = _navigationButtonText
    val isNavigating: LiveData<Boolean> get() = _isNavigating
    val isActivelyNavigating: LiveData<Boolean> get() = _isActivelyNavigating

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupLocationCallback()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    _mapUpdate.postValue(MapUpdate(latLng = LatLng(location.latitude, location.longitude)))
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

    fun startSpeechRecognition(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói địa chỉ...")
        return intent
    }

    fun handleSpeechResult(data: Intent?) {
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!results.isNullOrEmpty()) {
            val spokenText = results[0]
            _speechResult.postValue("start_confirmation")
            getCoordinatesFromAddress(spokenText)
        } else {
            _speechResult.postValue("start_recognition")
            _ttsMessage.postValue("Vui lòng nói lại.")
        }
    }

    fun getCoordinatesFromAddress(address: String) {
        val url = "https://api.track-asia.com/geocode/v1/autocomplete?access_token=YOUR_API_KEY&text=$address"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to get coordinates: ${e.message} at 09:37 AM +07, 15/09/2025")
                _ttsMessage.postValue("Không tìm thấy địa chỉ.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { json ->
                    val jsonObject = JSONObject(json)
                    val features = jsonObject.getJSONArray("features")
                    if (features.length() > 0) {
                        val geometry = features.getJSONObject(0).getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")
                        val latLng = LatLng(coordinates.getDouble(1), coordinates.getDouble(0))
                        _mapUpdate.postValue(MapUpdate(latLng = latLng, markerTitle = address))
                        _ttsMessage.postValue("Bạn muốn đến...? Nói 'có' hoặc 'không'.")
                    } else {
                        _ttsMessage.postValue("Không tìm thấy địa chỉ.")
                    }
                }
            }
        })
    }

    fun startActiveNavigation(steps: JSONArray) {
        navigationSteps = steps
        val points = decodePolyline(steps.toString()) // Giả định decode từ steps
        _mapUpdate.postValue(MapUpdate(clearMap = true))
        _mapUpdate.postValue(MapUpdate(polylinePoints = points))
        _ttsMessage.postValue("Đã vẽ đường đi đến đích. Nhấn 'Bắt đầu dẫn đường' để tiếp tục.")
        _isActivelyNavigating.postValue(true)
    }

    fun stopNavigation() {
        _mapUpdate.postValue(MapUpdate(clearMap = true))
        _ttsMessage.postValue("Đã dừng dẫn đường.")
        _isActivelyNavigating.postValue(false)
    }

    fun startCamera(context: Context, activity: AppCompatActivity) {
        if (!isCameraStarted) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImage(imageProxy)
                        }
                    }
                cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalyzer)
                isCameraStarted = true
                Log.d(TAG, "Camera started at 09:42 AM +07, 15/09/2025")
            }, cameraExecutor)
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image != null) {
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray) // Đọc dữ liệu từ ByteBuffer vào ByteArray
            val yuvImage = YuvImage(
                byteArray,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )
            val stream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, stream)
            val bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
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
                    Log.e(TAG, "Object detection failed: ${e.message} at 09:42 AM +07, 15/09/2025")
                }
            imageProxy.close()
        }
    }

    private fun handleObjects(objects: List<com.google.mlkit.vision.objects.DetectedObject>) {
        if (isMoving) {
            val message = when {
                objects.isNotEmpty() -> "Cảnh báo: ${objects[0].labels.firstOrNull()?.text} ở gần!"
                else -> "Không phát hiện vật cản."
            }
            _ttsMessage.postValue(message)
            Log.d(TAG, "Object detection result: $message at 09:37 AM +07, 15/09/2025")
        }
    }

    fun onCameraPermissionDenied() {
        _ttsMessage.postValue("Quyền camera bị từ chối. Vui lòng cấp quyền.")
        Log.w(TAG, "Camera permission denied at 09:37 AM +07, 15/09/2025")
    }

    fun startLocationUpdates() {
        if (checkLocationPermission(getApplication())) {
            val locationRequest = LocationRequest.create().apply {
                interval = 5000
                fastestInterval = 2000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            Log.d(TAG, "Location updates started at 09:37 AM +07, 15/09/2025")
        }
    }

    fun getCurrentLocation() {
        if (checkLocationPermission(getApplication())) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    _mapUpdate.postValue(MapUpdate(latLng = LatLng(it.latitude, it.longitude)))
                    Log.d(TAG, "Current location fetched at 09:37 AM +07, 15/09/2025: ${it.latitude}, ${it.longitude}")
                }
            }
        }
    }

    private fun checkDestinationReached(location: Location) {
        navigationSteps?.let { steps ->
            // Logic kiểm tra đích (giả định đơn giản)
            if (steps.length() > 0) {
                val lastStep = steps.getJSONObject(steps.length() - 1)
                val destLat = lastStep.getJSONObject("end_location").getDouble("lat")
                val destLng = lastStep.getJSONObject("end_location").getDouble("lng")
                val distance = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, destLat, destLng, distance)
                if (distance[0] < 10) { // 10 mét
                    _ttsMessage.postValue("Đã đến đích.")
                    stopNavigation()
                }
            }
        }
    }

    fun stopCamera() {
        if (isCameraStarted) {
            cameraExecutor.shutdown()
            isCameraStarted = false
            Log.d(TAG, "Camera stopped at 09:37 AM +07, 15/09/2025")
        }
    }

    fun ttTranslation(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, null)
        Log.d(TAG, "TTS speaking: $message at 09:37 AM +07, 15/09/2025")
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
                Log.e(TAG, "Language not supported at 09:37 AM +07, 15/09/2025")
            } else {
                _ttsMessage.postValue("Ứng dụng sẵn sàng. Nói địa chỉ để tìm đường.")
            }
        } else {
            Log.e(TAG, "TTS initialization failed at 09:37 AM +07, 15/09/2025")
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
        Log.d(TAG, "ViewModel cleared at 09:37 AM +07, 15/09/2025")
        super.onCleared()
    }
}

