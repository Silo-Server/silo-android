package com.continuum.app.tv.ui.components

import kotlin.test.Test
import kotlin.test.assertTrue

class TvTextInputDialogSourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/components/TvTextInputDialog.kt",
    ).readText()

    @Test
    fun textEntryDialogUsesSiloAnsiKeyboardInsteadOfPlatformIme() {
        assertTrue(
            source.contains("TvAnsiKeyboard("),
            "TV text-entry dialogs should use Silo's own remote keyboard instead of launching the platform IME.",
        )
        assertTrue(
            source.contains("shouldOpenTvDialogKeyboard(event)"),
            "The display field should reopen the custom keyboard from center/enter.",
        )
        assertTrue(
            source.contains("event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp"),
            "Remote center presses can arrive on key down or key up depending on TV hardware.",
        )
        assertTrue(
            source.contains("keyboardType == KeyboardType.Number || keyboardType == KeyboardType.NumberPassword") &&
                source.contains("action.text.filter { it.isDigit() }"),
            "PIN dialogs should keep the shared keyboard but only accept numeric input.",
        )
        assertTrue(
            source.contains("primaryLabel = confirmLabel") &&
                source.contains("onPrimary = submit"),
            "The keyboard primary key should perform the dialog's confirm action.",
        )
        assertTrue(
            !source.contains("AndroidView(") && !source.contains("EditText(context)") && !source.contains("InputMethodManager"),
            "The platform IME path should stay out of TV dialogs.",
        )
        assertTrue(
            !source.contains("OutlinedTextField"),
            "TV text-entry dialogs should not fall back to Compose text fields.",
        )
        assertTrue(
            !source.contains("Card(\n                onClick = {}"),
            "The dialog panel must not use a clickable TV Card that can steal initial text focus.",
        )
    }
}
