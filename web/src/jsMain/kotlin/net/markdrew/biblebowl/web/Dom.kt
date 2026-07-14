package net.markdrew.biblebowl.web

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

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
