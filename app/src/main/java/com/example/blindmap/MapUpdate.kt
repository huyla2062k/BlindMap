package com.example.blindmap

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions

data class MapUpdate(
    val latLng: LatLng? = null,
    val markerTitle: String? = null,
    val polylinePoints: List<LatLng>? = null,
    val clearMap: Boolean = false,
    val polylineOptions: PolylineOptions? = null
)