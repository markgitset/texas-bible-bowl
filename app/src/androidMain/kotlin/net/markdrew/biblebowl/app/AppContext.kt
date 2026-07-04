package net.markdrew.biblebowl.app

import android.content.Context

/**
 * Holds the application [Context] so platform code with no Android dependency in its signature (e.g. the
 * common `savePdf` expect/actual) can reach a [Context] for MediaStore. Set once from [MainActivity.onCreate]
 * before any UI runs.
 */
object AppContext {
    lateinit var app: Context
}
