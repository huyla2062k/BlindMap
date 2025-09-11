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
        } else {
            viewModel.onCameraPermissionDenied()
        }
    }

    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.handleSpeechResult(result.data)
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
            Log.d("MainActivity", "Navigation button clicked, isActivelyNavigating: ${viewModel.isActivelyNavigating.value}, isNavigating: ${viewModel.isNavigating.value}")
            if (viewModel.isActivelyNavigating.value == true) {
                Log.d("MainActivity", "Stopping navigation")
                viewModel.stopNavigation()
            } else if (viewModel.isNavigating.value == true) {
                Log.d("MainActivity", "Starting active navigation")
                viewModel.startActiveNavigation(viewModel.navigationSteps ?: JSONArray())
            } else {
                Log.d("MainActivity", "Requesting location permission or starting location updates")
                if (!viewModel.checkLocationPermission(this)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                } else {
                    viewModel.getCurrentLocation()
                    viewModel.startLocationUpdates()
                }
            }
        }

        binding.speechButton.setOnClickListener {
            if (!viewModel.checkSpeechPermission(this)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            } else {
                speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
            }
        }

        viewModel.ttsMessage.observe(this) { message ->
        }

        viewModel.speechResult.observe(this) { action ->
            when (action) {
                "start_recognition" -> speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                "start_confirmation" -> speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                "request_camera_permission" -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        viewModel.mapUpdate.observe(this) { update ->
            update.latLng?.let { latLng ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                update.markerTitle?.let { title ->
                    map.addMarker(MarkerOptions().position(latLng).title(title))
                }
            }
            if (update.clearMap) {
                map.clear()
            }
            update.polylinePoints?.let { points ->
                val polylineOptions = PolylineOptions()
                    .addAll(points)
                    .width(10f)
                    .color(android.graphics.Color.BLUE)
                map.addPolyline(polylineOptions)
            }
        }

        viewModel.navigationButtonText.observe(this) { text ->
            binding.startNavigationButton.text = text
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        if (viewModel.checkLocationPermission(this)) {
            viewModel.getCurrentLocation()
            viewModel.startLocationUpdates()
            // Remove hardcoded address call to avoid automatic navigation
             viewModel.getCoordinatesFromAddress("50 mễ trì thượng")
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.getCurrentLocation()
                viewModel.startLocationUpdates()
            }
            3 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
            }
        }
    }
}