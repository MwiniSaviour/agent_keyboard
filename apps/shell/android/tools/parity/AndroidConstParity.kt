// Compile-time parity of AndroidConst against the platform SDK. Compiled with android.jar on the
// classpath; kotlinc inlines both constants, so running it never touches the (stub) android classes.
import com.agentkeyboard.core.editor.AndroidConst

private var failures = 0

private fun check(name: String, ours: Int, platform: Int) {
    if (ours != platform) { failures++; println("✗ $name: ours=0x${ours.toString(16)} platform=0x${platform.toString(16)}") }
}

fun main() {
    check("TYPE_MASK_CLASS", AndroidConst.TYPE_MASK_CLASS, android.text.InputType.TYPE_MASK_CLASS)
    check("TYPE_MASK_VARIATION", AndroidConst.TYPE_MASK_VARIATION, android.text.InputType.TYPE_MASK_VARIATION)
    check("TYPE_CLASS_TEXT", AndroidConst.TYPE_CLASS_TEXT, android.text.InputType.TYPE_CLASS_TEXT)
    check("TYPE_CLASS_NUMBER", AndroidConst.TYPE_CLASS_NUMBER, android.text.InputType.TYPE_CLASS_NUMBER)
    check("TYPE_CLASS_PHONE", AndroidConst.TYPE_CLASS_PHONE, android.text.InputType.TYPE_CLASS_PHONE)
    check("TYPE_CLASS_DATETIME", AndroidConst.TYPE_CLASS_DATETIME, android.text.InputType.TYPE_CLASS_DATETIME)
    check("TYPE_TEXT_FLAG_CAP_CHARACTERS", AndroidConst.TYPE_TEXT_FLAG_CAP_CHARACTERS, android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
    check("TYPE_TEXT_FLAG_CAP_WORDS", AndroidConst.TYPE_TEXT_FLAG_CAP_WORDS, android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    check("TYPE_TEXT_FLAG_CAP_SENTENCES", AndroidConst.TYPE_TEXT_FLAG_CAP_SENTENCES, android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    check("TYPE_TEXT_FLAG_MULTI_LINE", AndroidConst.TYPE_TEXT_FLAG_MULTI_LINE, android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    check("TYPE_TEXT_FLAG_IME_MULTI_LINE", AndroidConst.TYPE_TEXT_FLAG_IME_MULTI_LINE, android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)
    check("TYPE_TEXT_FLAG_NO_SUGGESTIONS", AndroidConst.TYPE_TEXT_FLAG_NO_SUGGESTIONS, android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    check("TYPE_TEXT_VARIATION_URI", AndroidConst.TYPE_TEXT_VARIATION_URI, android.text.InputType.TYPE_TEXT_VARIATION_URI)
    check("TYPE_TEXT_VARIATION_EMAIL_ADDRESS", AndroidConst.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    check("TYPE_TEXT_VARIATION_SHORT_MESSAGE", AndroidConst.TYPE_TEXT_VARIATION_SHORT_MESSAGE, android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)
    check("TYPE_TEXT_VARIATION_LONG_MESSAGE", AndroidConst.TYPE_TEXT_VARIATION_LONG_MESSAGE, android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
    check("TYPE_TEXT_VARIATION_PERSON_NAME", AndroidConst.TYPE_TEXT_VARIATION_PERSON_NAME, android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
    check("TYPE_TEXT_VARIATION_PASSWORD", AndroidConst.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
    check("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD", AndroidConst.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    check("TYPE_TEXT_VARIATION_FILTER", AndroidConst.TYPE_TEXT_VARIATION_FILTER, android.text.InputType.TYPE_TEXT_VARIATION_FILTER)
    check("TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS", AndroidConst.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    check("TYPE_TEXT_VARIATION_WEB_PASSWORD", AndroidConst.TYPE_TEXT_VARIATION_WEB_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    check("TYPE_NUMBER_VARIATION_PASSWORD", AndroidConst.TYPE_NUMBER_VARIATION_PASSWORD, android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    check("IME_MASK_ACTION", AndroidConst.IME_MASK_ACTION, android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
    check("IME_ACTION_UNSPECIFIED", AndroidConst.IME_ACTION_UNSPECIFIED, android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED)
    check("IME_ACTION_NONE", AndroidConst.IME_ACTION_NONE, android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
    check("IME_ACTION_GO", AndroidConst.IME_ACTION_GO, android.view.inputmethod.EditorInfo.IME_ACTION_GO)
    check("IME_ACTION_SEARCH", AndroidConst.IME_ACTION_SEARCH, android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
    check("IME_ACTION_SEND", AndroidConst.IME_ACTION_SEND, android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
    check("IME_ACTION_NEXT", AndroidConst.IME_ACTION_NEXT, android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
    check("IME_ACTION_DONE", AndroidConst.IME_ACTION_DONE, android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
    check("IME_ACTION_PREVIOUS", AndroidConst.IME_ACTION_PREVIOUS, android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS)
    check("IME_FLAG_NO_PERSONALIZED_LEARNING", AndroidConst.IME_FLAG_NO_PERSONALIZED_LEARNING, android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    check("IME_FLAG_NO_ENTER_ACTION", AndroidConst.IME_FLAG_NO_ENTER_ACTION, android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION)
    println(if (failures == 0) "  AndroidConst parity: 34 constants match android.jar" else "AndroidConst parity: $failures mismatches")
    if (failures != 0) kotlin.system.exitProcess(1)
}
