package com.example.blindmap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.example.blindmap.databinding.ActivityMainBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import androidx.core.app.ActivityCompat
import org.json.JSONArray

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var map: GoogleMap

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            viewModel.startCamera(this, this)
            Log.d("MainActivity", "Camera permission granted at 09:37 AM +07, 15/09/2025")
        } else {
            viewModel.onCameraPermissionDenied()
            Log.w("MainActivity", "Camera permission denied at 09:37 AM +07, 15/09/2025")
        }
    }

    private val speechPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
            Log.d("MainActivity", "Speech permission granted at 09:37 AM +07, 15/09/2025")
        } else {
            Log.w("MainActivity", "Speech permission denied at 09:37 AM +07, 15/09/2025")
        }
    }

    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.handleSpeechResult(result.data)
            Log.d("MainActivity", "Speech recognition result processed at 09:37 AM +07, 15/09/2025")
        } else {
            Log.e("MainActivity", "Speech recognition failed at 09:37 AM +07, 15/09/2025")
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            viewModel.getCurrentLocation()
            viewModel.startLocationUpdates()
            Log.d("MainActivity", "Location permission granted at 09:37 AM +07, 15/09/2025")
        } else {
            Log.w("MainActivity", "Location permission denied at 09:37 AM +07, 15/09/2025")
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

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.startNavigationButton.setOnClickListener {
            Log.d("MainActivity", "Navigation button clicked at 09:37 AM +07, 15/09/2025, isActivelyNavigating: ${viewModel.isActivelyNavigating.value}, isNavigating: ${viewModel.isNavigating.value}")
            when {
                viewModel.isActivelyNavigating.value == true -> viewModel.stopNavigation()
                viewModel.isNavigating.value == true -> viewModel.startActiveNavigation(viewModel.navigationSteps ?: JSONArray())
                else -> {
                    if (!viewModel.checkLocationPermission(this)) {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        viewModel.getCurrentLocation()
                        viewModel.startLocationUpdates()
                    }
                }
            }
        }

        binding.speechButton.setOnClickListener {
            if (!viewModel.checkSpeechPermission(this)) {
                speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
            }
        }

        viewModel.ttsMessage.observe(this) { message ->
            if (message.isNotEmpty()) {
                viewModel.ttTranslation(message)
                Log.d("MainActivity", "TTS message: $message at 09:37 AM +07, 15/09/2025")
            }
        }

        viewModel.speechResult.observe(this) { action ->
            when (action) {
                "start_recognition" -> speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                "start_confirmation" -> speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                "request_camera_permission" -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        viewModel.mapUpdate.observe(this) { update ->
            Log.d("MainActivity", "Map update received: $update at 09:37 AM +07, 15/09/2025")
            if (update.clearMap) {
                map.clear()
                Log.d("MainActivity", "Map cleared at 09:37 AM +07, 15/09/2025")
            }
            update.latLng?.let { latLng ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                update.markerTitle?.let { title ->
                    map.addMarker(MarkerOptions().position(latLng).title(title))
                    Log.d("MainActivity", "Marker added at $latLng with title: $title at 09:37 AM +07, 15/09/2025")
                }
            }
            update.polylinePoints?.let { points ->
                val polylineOptions = PolylineOptions()
                    .addAll(points)
                    .width(10f)
                    .color(android.graphics.Color.BLUE)
                map.addPolyline(polylineOptions)
                Log.d("MainActivity", "Polyline added with ${points.size} points at 09:37 AM +07, 15/09/2025")
            }
        }

        viewModel.navigationButtonText.observe(this) { text ->
            binding.startNavigationButton.text = text
            Log.d("MainActivity", "Navigation button text updated to: $text at 09:37 AM +07, 15/09/2025")
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        Log.d("MainActivity", "Map ready at 09:37 AM +07, 15/09/2025")
        if (viewModel.checkLocationPermission(this)) {
            viewModel.getCurrentLocation()
            viewModel.startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.getCurrentLocation()
                viewModel.startLocationUpdates()
                Log.d("MainActivity", "Location permission granted in onRequest at 09:37 AM +07, 15/09/2025")
            }
            3 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                Log.d("MainActivity", "Speech permission granted in onRequest at 09:37 AM +07, 15/09/2025")
            }
        }
    }
}