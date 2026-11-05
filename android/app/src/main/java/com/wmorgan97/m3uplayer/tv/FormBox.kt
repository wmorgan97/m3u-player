package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.graphics.toArgb

// A [FormDialog] text box is a plain Android EditText, not a Compose BasicTextField: Fire TV's full-screen (extract-mode)
// keyboard's Next/Done never reached Compose's keyboard actions, so focus didn't move and the keyboard stayed up. An
// EditText gets every route the keyboard might use (performEditorAction with Next/Done/unspecified, or an Enter/Tab/
// Navigate-next key from the keyboard) through its own listeners, and it asks for the keyboard without the extract UI.

/** What a box does with the remote and the keyboard; [FormDialog] supplies these. */
internal class FormBoxActions(
    val onAction: () -> Unit,      // the keyboard's Next / Done (or Enter / Tab from it)
    val onUp: () -> Unit,          // D-pad Up / Down (the keyboard is closed first)
    val onDown: () -> Unit,
    val onFocus: (Boolean) -> Unit,
)

internal fun showKeyboard(v: View, tries: Int = 6) {
    // The keyboard only opens for a view whose window has focus: just after a move (or the dialog opening) it may not yet.
    v.post {
        val ok = v.hasFocus() && v.hasWindowFocus() &&
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        if (!ok && tries > 1) v.postDelayed({ showKeyboard(v, tries - 1) }, 80)
    }
}

internal fun hideKeyboard(v: View) {
    (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}

internal fun formBox(ctx: Context, f: FormField, last: Boolean, initial: String, onText: (String) -> Unit, a: FormBoxActions): EditText =
    EditText(ctx).apply {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        isSingleLine = true
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tv.body.value)
        setTextColor(Tv.text.toArgb())
        setHintTextColor(Tv.muted.toArgb())
        hint = f.hint
        highlightColor = Tv.accent.copy(alpha = 0.4f).toArgb()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or when {
            f.secret -> InputType.TYPE_TEXT_VARIATION_PASSWORD   // also masks the text (dots)
            f.url -> InputType.TYPE_TEXT_VARIATION_URI
            else -> 0
        }
        typeface = Typeface.DEFAULT   // a password inputType switches to monospace: keep the app's font
        imeOptions = (if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Tv.accent.toArgb()); setSize((2 * resources.displayMetrics.density).toInt(), 1)
        }
        setText(initial); setSelection(initial.length)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
        })
        // One keyboard press can arrive as both an editor action and a key: act once.
        var lastAction = 0L
        fun act() {
            val now = SystemClock.uptimeMillis()
            if (now - lastAction < 400) return
            lastAction = now
            a.onAction()
        }
        // Any editor action (Next, Done, Go, or "unspecified" from a keyboard that ignores imeOptions) moves on.
        setOnEditorActionListener { _, _, e -> if (e == null || e.action == KeyEvent.ACTION_DOWN) act(); true }
        setOnKeyListener { v, code, e ->
            // Keys typed on the on-screen keyboard carry the soft-keyboard flag / the virtual device; the remote's don't.
            val fromKeyboard = e.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 || e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
            when (code) {
                KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_TAB -> { if (e.action == KeyEvent.ACTION_DOWN) act(); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    // The keyboard's Enter is its action key. The remote's OK opens the keyboard on release, not press:
                    // opened on the press, the release went to the keyboard and typed its highlighted key.
                    if (fromKeyboard && code != KeyEvent.KEYCODE_DPAD_CENTER) { if (e.action == KeyEvent.ACTION_DOWN) act() }
                    else if (e.action == KeyEvent.ACTION_UP) showKeyboard(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> { if (e.action == KeyEvent.ACTION_DOWN) { hideKeyboard(v); a.onUp() }; true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (e.action == KeyEvent.ACTION_DOWN) { hideKeyboard(v); a.onDown() }; true }
                else -> false
            }
        }
        setOnFocusChangeListener { _, has -> a.onFocus(has) }
    }
