package dev.glide.app.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/** A completed send returns to its file list, never to the QR scanner underneath it. */
internal fun returnToFileSelectionAfterSend(
    backStack: NavBackStack<NavKey>,
    transferRoute: GlideRoute.Transfer,
    sourceUris: List<String> = emptyList()
): Boolean {
    if (backStack.lastOrNull() != transferRoute) return false

    val selectionIndex = backStack.indexOfLast { it is GlideRoute.Selection }
    if (selectionIndex >= 0) {
        while (backStack.lastIndex > selectionIndex) backStack.removeAt(backStack.lastIndex)
    } else {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(GlideRoute.Selection(sourceUris))
    }
    return true
}
