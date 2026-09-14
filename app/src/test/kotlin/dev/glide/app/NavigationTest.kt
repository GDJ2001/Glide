package dev.glide.app

import androidx.navigation3.runtime.NavBackStack
import dev.glide.app.navigation.GlideRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(GlideRoute::class) {
                subclass(GlideRoute.Home::class)
                subclass(GlideRoute.Selection::class)
                subclass(GlideRoute.Discovery::class)
                subclass(GlideRoute.ReceiveReadiness::class)
                subclass(GlideRoute.Transfer::class)
                subclass(GlideRoute.History::class)
                subclass(GlideRoute.Settings::class)
            }
        }
    }

    @Test
    fun homeRoute_serializationRoundTrip() {
        val serialized = json.encodeToString(GlideRoute.serializer(), GlideRoute.Home)
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(GlideRoute.Home, deserialized)
    }

    @Test
    fun selectionRoute_serializationRoundTrip() {
        val serialized = json.encodeToString(GlideRoute.serializer(), GlideRoute.Selection())
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(GlideRoute.Selection(), deserialized)
    }

    @Test
    fun selectionRoute_withInitialUris_serializationRoundTrip() {
        val original = GlideRoute.Selection(listOf("content://media/external/images/media/1", "content://media/external/images/media/2"))
        val serialized = json.encodeToString(GlideRoute.serializer(), original)
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(original, deserialized)
        assertEquals(2, (deserialized as GlideRoute.Selection).initialUris.size)
    }

    @Test
    fun discoveryRoute_serializationRoundTrip() {
        val original = GlideRoute.Discovery(batchId = "batch-1", sourceHandles = listOf("handle-1", "handle-2"))
        val serialized = json.encodeToString(GlideRoute.serializer(), original)
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun receiveReadinessRoute_serializationRoundTrip() {
        val original = GlideRoute.ReceiveReadiness
        val serialized = json.encodeToString(GlideRoute.serializer(), original)
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun transferRoute_serializationRoundTrip() {
        val original = GlideRoute.Transfer(sessionId = "test-session-123")
        val serialized = json.encodeToString(GlideRoute.serializer(), original)
        val deserialized = json.decodeFromString(GlideRoute.serializer(), serialized)
        assertEquals(original, deserialized)
        assertEquals("test-session-123", (deserialized as GlideRoute.Transfer).sessionId)
    }

    @Test
    fun backStack_pushAndPopTransitions() {
        val backStack = NavBackStack<GlideRoute>(GlideRoute.Home)
        assertEquals(1, backStack.size)
        assertEquals(GlideRoute.Home, backStack.last())

        // Navigate to Transfer
        val transferRoute = GlideRoute.Transfer("tx-456")
        backStack.add(transferRoute)
        assertEquals(2, backStack.size)
        assertEquals(transferRoute, backStack.last())

        // Pop back to Home
        val popped = backStack.removeAt(backStack.lastIndex)
        assertEquals(transferRoute, popped)
        assertEquals(1, backStack.size)
        assertEquals(GlideRoute.Home, backStack.last())
    }
}
