package com.example.blindmap

import com.google.android.gms.maps.model.LatLng

data class MapUpdate(
    val latLng: LatLng? = null,
    val markerTitle: String? = null,
    val polylinePoints: List<LatLng>? = null,
    val clearMap: Boolean = false
)