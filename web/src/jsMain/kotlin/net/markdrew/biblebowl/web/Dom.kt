package net.markdrew.biblebowl.web

import kotlinx.browser.document
import net.markdrew.biblebowl.client.ApiException
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * Tiny DOM builder: creates a [tag] element, applies [classes] and [text], runs [init] on it
 * (nest further [child] calls there), and appends it to the receiver.
 */
fun Element.child(
    tag: String,
    classes: String = "",
    text: String? = null,
    init: HTMLElement.() -> Unit = {},
): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (classes.isNotEmpty()) e.className = classes
    if (text != null) e.textContent = text
    e.init()
    appendChild(e)
    return e
}

fun Element.clear() {
    textContent = ""
}

fun HTMLElement.onClick(handler: () -> Unit) {
    addEventListener("click", { it.preventDefault(); handler() })
}

/** A centered Bootstrap spinner, the shared loading state. */
fun Element.spinner() {
    child("div", "text-center py-5") {
        child("div", "spinner-border tbb-spinner") {
            setAttribute("role", "status")
            setAttribute("aria-label", "Loading")
        }
    }
}

private const val UNREACHABLE = "Couldn't reach the server — check your connection and try again."

/**
 * A message safe to show a coach or parent. [ApiException] carries the server's human-readable
 * reason, so it passes through; anything else means the request never got an answer (offline,
 * cold start, DNS) and would otherwise surface a raw Ktor/JS exception string.
 *
 * A few server messages are deliberately operator-facing (they name env vars, for `curl` and the
 * logs). Those are translated here by [ApiException.errorCode] rather than reworded server-side,
 * so the wire keeps the precise diagnostic and only the UI gets the plain-English version.
 */
fun friendlyError(e: Throwable): String {
    val api = e as? ApiException ?: return UNREACHABLE
    return when (api.errorCode) {
        "esv_unconfigured" -> "Bible text isn't available right now — please try again later."
        else -> api.message ?: UNREACHABLE
    }
}

/** The shared inline error line (red text, same convention as the Compose screens). */
fun Element.errorLine(message: String) {
    child("p", "text-danger mb-2", message)
}

/** [errorLine] with the throwable run through [friendlyError] — the usual `catch` body. */
fun Element.errorLine(e: Throwable) {
    errorLine(friendlyError(e))
}

/**
 * Instant feedback for an in-flight save: disables the control so it can't fire twice, and swaps a
 * button's label for a small spinner. No restore needed — the screen re-renders when the call lands.
 */
fun HTMLElement.showBusy() {
    when (this) {
        is HTMLButtonElement -> {
            disabled = true
            clear()
            child("span", "spinner-border spinner-border-sm") {
                setAttribute("role", "status")
                setAttribute("aria-label", "Saving")
            }
        }
        is HTMLInputElement -> disabled = true
        is HTMLSelectElement -> disabled = true
    }
}
