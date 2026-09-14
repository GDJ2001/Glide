package dev.glide.data

import dev.glide.core.CoreMarker

/**
 * Marker for data module persistence capabilities (Room, DataStore, private storage).
 */
class DataMarker(
    val coreMarker: CoreMarker? = null
)
