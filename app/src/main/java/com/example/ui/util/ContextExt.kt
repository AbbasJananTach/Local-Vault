package com.example.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Safely unwraps any ContextWrapper (such as Compose's LocalContext.current tint/theme wrappers)
 * to obtain the host Activity without ever throwing a ClassCastException.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
