package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.ui.chipRow
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/** Submit a new question for the crowd-sourced rounds (R2/R3); gated on QUESTION_SUBMIT. */
object ContributeScreen {

    private var roundType = Round.crowdSourcedRounds.first()

    // Field values survive round switches and revisits.
    private var prompt = ""
    private var answer = ""
    private var choicesText = ""
    private var chapterText = ""
    private var referencesText = ""
    private var message: String? = null
    private var isError = false

    private lateinit var root: HTMLElement

    fun render(container: HTMLElement) {
        root = container
        root.child("h1", "page-title", "Contribute a question")
        root.child(
            "p", "text-muted",
            "Fact Finder and Identification are crowd-sourced. Find the Verse, Quotations, and Headings are " +
                "generated from the ESV text, so they aren't submitted here.",
        )
        root.chipRow(Round.crowdSourcedRounds.map { it.displayName to it }, roundType) {
            roundType = it
            root.clear()
            render(root)
        }

        val form = root.child("form", "mt-2")

        form.child("div", "mb-3") {
            child("label", "form-label", "Question / prompt")
            val ta = child("textarea", "form-control") as HTMLTextAreaElement
            ta.rows = 2
            ta.value = prompt
            ta.addEventListener("input", { prompt = ta.value; refreshSubmit() })
        }
        form.child("div", "mb-3") {
            child("label", "form-label", answerLabel(roundType))
            val input = child("input", "form-control") as HTMLInputElement
            input.value = answer
            input.addEventListener("input", { answer = input.value; refreshSubmit() })
        }
        if (roundType.multipleChoice) {
            form.child("div", "mb-3") {
                child("label", "form-label", "Choices (one per line, include the correct answer)")
                val ta = child("textarea", "form-control") as HTMLTextAreaElement
                ta.rows = 4
                ta.value = choicesText
                ta.addEventListener("input", { choicesText = ta.value })
            }
        }
        form.child("div", "row g-2 mb-3") {
            child("div", "col-4") {
                child("label", "form-label", "Chapter")
                val input = child("input", "form-control") as HTMLInputElement
                input.setAttribute("inputmode", "numeric")
                input.value = chapterText
                input.addEventListener("input", {
                    input.value = input.value.filter(Char::isDigit)
                    chapterText = input.value
                })
            }
            child("div", "col-8") {
                child("label", "form-label", "Verse refs (e.g. ${Session.season.eventScripture} 2:38)")
                val input = child("input", "form-control") as HTMLInputElement
                input.value = referencesText
                input.addEventListener("input", { referencesText = input.value })
            }
        }

        submitButton = form.child("button", "btn btn-primary w-100", "Submit for review") {
            setAttribute("type", "submit")
        } as HTMLButtonElement
        messageSlot = form.child("div")
        refreshSubmit()
        renderMessage()

        form.addEventListener("submit", { event ->
            event.preventDefault()
            submit()
        })
    }

    private var submitButton: HTMLButtonElement? = null
    private var messageSlot: HTMLElement? = null

    private fun refreshSubmit() {
        submitButton?.disabled = prompt.isBlank() || answer.isBlank()
    }

    private fun renderMessage() {
        val slot = messageSlot ?: return
        slot.clear()
        message?.let { slot.child("p", (if (isError) "text-danger" else "tbb-gold fw-semibold") + " mt-3 mb-0", it) }
    }

    private fun submit() {
        submitButton?.disabled = true
        message = null
        renderMessage()
        Shell.scope.launch {
            try {
                Session.api.submitQuestion(
                    SubmitQuestionRequest(
                        roundType = roundType,
                        prompt = prompt.trim(),
                        answer = answer.trim(),
                        references = referencesText.split(";", ",").map { it.trim() }.filter { it.isNotEmpty() },
                        choices = choicesText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        chapter = chapterText.toIntOrNull(),
                    )
                )
                isError = false
                message = "Submitted! Your question is pending review."
                prompt = ""; answer = ""; referencesText = ""; chapterText = ""; choicesText = ""
                root.clear()
                render(root) // re-render clears the form fields; the message re-appears below
            } catch (e: Throwable) {
                isError = true
                message = "Error: ${e.message}"
                refreshSubmit()
                renderMessage()
            }
        }
    }

    private fun answerLabel(roundType: Round): String = when (roundType) {
        Round.FIND_THE_VERSE -> "Answer (chapter:verse, e.g. 2:38)"
        Round.QUOTES, Round.EVENTS -> "Answer (chapter number)"
        else -> "Answer"
    }
}
