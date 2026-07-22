package net.markdrew.biblebowl.web

import kotlinx.browser.document
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

/** The shared inline error line (red text, same convention as the Compose screens). */
fun Element.errorLine(message: String) {
    child("p", "text-danger mb-2", message)
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
