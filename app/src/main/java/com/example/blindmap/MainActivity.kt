package com.example.blindmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.example.blindmap.databinding.ActivityMainBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var map: GoogleMap
    private var currentLocation: LatLng? = null

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

        viewModel.ttsMessage.observe(this) { message ->
            viewModel.ttTranslation(message)
        }

        viewModel.speechResult.observe(this) { result ->
            when (result) {
                "start_recognition", "start_confirmation" -> speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
                "confirmed" -> {
                    // Đích đến đã xác nhận
                }
            }
        }

        viewModel.mapUpdate.observe(this) { update ->
            update.latLng?.let { latLng ->
                if (update.markerTitle != null) {
                    map.addMarker(MarkerOptions().position(latLng).title(update.markerTitle))
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                } else {
                    currentLocation = latLng
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            }
            if (update.clearMap) {
                map.clear()
            }
            update.polylineOptions?.let { polylineOptions ->
                map.addPolyline(polylineOptions)
            }
        }

        viewModel.navigationButtonText.observe(this) { text ->
            binding.startNavigationButton.text = text
            binding.startNavigationButton.contentDescription = text
        }

        binding.startNavigationButton.setOnClickListener {
            if (viewModel.isNavigating.value == true) {
                viewModel.stopNavigation()
            } else {
                if (!viewModel.checkLocationPermission(this)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                } else if (!viewModel.checkCameraPermission(this)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
                } else {
                    viewModel.startNavigation(this, this)
                    currentLocation?.let { viewModel.startActiveNavigationWithDirections(it) }
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
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        if (viewModel.checkLocationPermission(this)) {
            map.isMyLocationEnabled = true
            viewModel.getCurrentLocation()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    map.isMyLocationEnabled = true
                    viewModel.getCurrentLocation()
                }
            }
            2 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.startCamera(this, this)
            }
            3 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                speechRecognizerLauncher.launch(viewModel.startSpeechRecognition())
            }
        }
    }

    override fun onDestroy() {
        viewModel.stopCamera()
        super.onDestroy()
    }
}