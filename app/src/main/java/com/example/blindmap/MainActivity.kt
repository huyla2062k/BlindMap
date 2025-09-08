package com.example.blindmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Html
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.blindmap.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.ImageProxy
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class MainActivity : AppCompatActivity(), OnMapReadyCallback, TextToSpeech.OnInitListener {
    val TAG = this::class.java.simpleName
    private lateinit var map: GoogleMap
    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val client = OkHttpClient()
    private lateinit var cameraExecutor: ExecutorService
    private var isCameraStarted = false
    private var isMoving = false
    private lateinit var locationCallback: LocationCallback
    private var lastAnalysisTime: Long = 0L
    private var pendingAddress: String? = null  // Lưu địa chỉ chờ xác nhận
    private var isConfirming: Boolean = false    // Trạng thái đang xác nhận
    private var currentLatLng: LatLng? = null
    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()?.lowercase() ?: ""

            if (isConfirming) {
                // Đang xác nhận
                if (spokenText.contains("có")) {
                    tts.speak("Đã xác nhận. Bắt đầu tìm đường đến ${pendingAddress}.", TextToSpeech.QUEUE_FLUSH, null, null)
                    isConfirming = false
                    pendingAddress?.let { getCoordinatesFromAddress(it) }  // Tiếp tục tìm tọa độ và đường đi
                } else if (spokenText.contains("không")) {
                    tts.speak("Vui lòng nói lại địa chỉ.", TextToSpeech.QUEUE_FLUSH, null, null)
                    isConfirming = false
                    pendingAddress = null
                    startSpeechRecognition()  // Nói lại địa chỉ
                } else {
                    tts.speak("Không nhận diện được. Nói 'có' hoặc 'không'.", TextToSpeech.QUEUE_FLUSH, null, null)
                    startConfirmationRecognition()  // Khởi động lại để xác nhận
                }
            } else {
                // Lấy địa chỉ mới
                if (spokenText.isNotEmpty()) {
                    pendingAddress = spokenText
                    tts.speak("Bạn muốn đến $spokenText phải không? Nói 'có' để tiếp tục hoặc 'không' để nói lại.", TextToSpeech.QUEUE_FLUSH, null, null)
                    isConfirming = true
                    startConfirmationRecognition()  // Khởi động speech để xác nhận
                } else {
                    tts.speak("Không nhận diện được giọng nói. Vui lòng nói lại.", TextToSpeech.QUEUE_FLUSH, null, null)
                    startSpeechRecognition()
                }
            }
        }
    }
    private fun startConfirmationRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói 'có' hoặc 'không'")
        }
        speechRecognizerLauncher.launch(intent)
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (isMoving) startCamera()
        } else {
            tts.speak("Quyền truy cập camera bị từ chối. Không thể phát hiện vật cản.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.startNavigationButton.setOnClickListener {
            requestLocationPermission()
        }

        binding.speechButton.setOnClickListener {
            requestSpeechPermission()
        }

        setupLocationUpdates()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            getCurrentLocation()
            startLocationUpdates()
        }
    }

    private fun requestSpeechPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        } else {
            startSpeechRecognition()
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
                startLocationUpdates()
            }
            3 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeechRecognition()
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    currentLatLng = LatLng(it.latitude, it.longitude)  // Lưu vị trí hiện tại
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng!!, 15f))
                    map.addMarker(MarkerOptions().position(currentLatLng!!).title("Vị trí hiện tại"))

                    tts.speak("Vị trí hiện tại: ${it.latitude}, ${it.longitude}", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                val speed = lastLocation.speed // Tốc độ tính bằng m/s
                // Xác định di chuyển nếu tốc độ > 0.5 m/s (~1.8 km/h, tốc độ đi bộ chậm)
                isMoving = speed > 0.5f
                if (isMoving && !isCameraStarted) {
                    requestCameraPermission()
                } else if (!isMoving && isCameraStarted) {
                    stopCamera()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.create().apply {
                interval = 5000 // Cập nhật mỗi 5 giây
                fastestInterval = 2000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
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
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                isCameraStarted = true
                tts.speak("Bắt đầu phát hiện vật cản.", TextToSpeech.QUEUE_FLUSH, null, null)
            } catch (e: Exception) {
                Log.e("CameraX", "Lỗi camera: ${e.message}")
                tts.speak("Lỗi khởi động camera.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            isCameraStarted = false
            tts.speak("Đã dừng phát hiện vật cản.", TextToSpeech.QUEUE_FLUSH, null, null)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

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

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Hãy nói địa chỉ bạn muốn đến")
        }
        speechRecognizerLauncher.launch(intent)
    }

    private fun getCoordinatesFromAddress(address: String) {
        val encodedAddress = address.replace(" ", "+")
        Log.d(TAG, "getCoordinatesFromAddress: encodedAddress")
        val url = "https://maps.track-asia.com/api/v2/geocode/json?address=$encodedAddress&key=public_key"
        Log.d(TAG, "getCoordinatesFromAddress: $url")

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {

                    tts.speak("Không thể tìm vị trí. Vui lòng thử lại.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    Log.d(TAG, "onResponse: $json")
                    if (json.getString("status") == "OK") {
                        val location = json.getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("geometry")
                            .getJSONObject("location")
                        val lat = location.getDouble("lat")
                        val lng = location.getDouble("lng")
                        Log.d(TAG, "onResponse: $lat      $lng")
                        runOnUiThread {
                            val destLatLng = LatLng(lat, lng)
                            map.clear()  // Xóa các đường cũ
                            Log.d(TAG, "onResponse: $currentLatLng  $destLatLng")
                            map.addMarker(MarkerOptions().position(currentLatLng!!).title("Vị trí hiện tại"))
                            map.addMarker(MarkerOptions().position(destLatLng).title("Đích đến: $address"))
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(destLatLng, 15f))

                            tts.speak("Đã tìm thấy $address tại tọa độ $lat, $lng. Bắt đầu tìm đường.", TextToSpeech.QUEUE_FLUSH, null, null)

                            // Gọi tìm đường
                            getDirections(currentLatLng!!, destLatLng)
                        }
                    } else {
                        runOnUiThread {

                            tts.speak("Không tìm thấy địa chỉ. Vui lòng thử lại.", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                }
            }
        })
    }

    private fun detectObjects(bitmap: Bitmap) {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        val objectDetector = ObjectDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        val obstacleLabels = listOf(
            "car", "truck", "bus", "bicycle", "person", "tree", "pole", "fence", "wall", "barrier"
        )

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
                        val warning = if (isClose) {
                            "Cảnh báo: $label ở gần phía trước. Hãy cẩn thận!"
                        } else {
                            "Phát hiện $label phía trước."
                        }
                        tts.speak(warning, TextToSpeech.QUEUE_ADD, null, null)
                    }
                }
                if (!hasObstacle) {
                    tts.speak("Không phát hiện vật cản phía trước.", TextToSpeech.QUEUE_ADD, null, null)
                }
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Lỗi phát hiện đối tượng: ${e.message}")
                tts.speak("Lỗi: Không thể phát hiện vật thể. Vui lòng thử lại.", TextToSpeech.QUEUE_ADD, null, null)
            }
    }

    fun getDirections(origin: LatLng, destination: LatLng) {
        val url = "https://maps.track-asia.com/route/v2/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=walking" +  // Chế độ đi bộ
                "&language=vi" +   // Ngôn ngữ tiếng Việt để text phù hợp
                "&key=public_key"
        Log.d(TAG, "getDirections: $url")

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tts.speak("Lỗi tìm đường. Vui lòng thử lại.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    if (json.getString("status") == "OK") {
                        val routes = json.getJSONArray("routes")
                        val overviewPolyline = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                        val points = decodePolyline(overviewPolyline)

                        // Parse steps để tạo hướng dẫn chi tiết
                        val legs = routes.getJSONObject(0).getJSONArray("legs")
                        val steps = legs.getJSONObject(0).getJSONArray("steps")
                        val instructionsBuilder = StringBuilder("Hướng dẫn chi tiết: ")
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)

                            // Parse html_instructions (loại bỏ HTML)
                            val htmlInstructions = step.getString("html_instructions")
                            val cleanInstructions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                Html.fromHtml(htmlInstructions, Html.FROM_HTML_MODE_COMPACT).toString()
                            } else {
                                Html.fromHtml(htmlInstructions).toString()
                            }

                            // Lấy distance và duration
                            val distanceText = step.getJSONObject("distance").getString("text")
                            val durationText = step.getJSONObject("duration").getString("text")

                            // Lấy maneuver nếu có (ví dụ: turn-left → Rẽ trái)
                            val maneuver = if (step.has("maneuver")) step.getString("maneuver") else ""
                            val maneuverText = when (maneuver) {
                                "turn-left" -> "Rẽ trái"
                                "turn-right" -> "Rẽ phải"
                                "keep-left" -> "Giữ bên trái"
                                "keep-right" -> "Giữ bên phải"
                                "straight" -> "Đi thẳng"
                                else -> ""  // Nếu không có, dùng cleanInstructions
                            }

                            // Tạo hướng dẫn cho step
                            val stepInstruction = "Bước ${i + 1}: $maneuverText $cleanInstructions sau $distanceText, đi trong $durationText. "
                            instructionsBuilder.append(stepInstruction)
                        }

                        val fullInstructions = instructionsBuilder.toString()

                        runOnUiThread {
                            val polylineOptions = PolylineOptions()
                                .addAll(points)
                                .width(10f)
                                .color(Color.BLUE)
                            map.addPolyline(polylineOptions)

                            // Đọc hướng dẫn qua TTS
                            tts.speak("Đã vẽ đường đi đến đích. $fullInstructions", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    } else {
                        runOnUiThread {
                            tts.speak("Không tìm thấy đường đi.", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
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
                tts.speak("Ứng dụng sẵn sàng. Nói địa chỉ để tìm đường.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
        cameraExecutor.shutdown()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        stopCamera()
        super.onDestroy()
    }
}