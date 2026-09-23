package dev.glide.core

/**
 * Marker interface for core module capabilities.
 * Core is independent of Android platform APIs (Context, Uri, Room, GMS, etc.).
 */
interface CoreMarker {
    val version: String
}
