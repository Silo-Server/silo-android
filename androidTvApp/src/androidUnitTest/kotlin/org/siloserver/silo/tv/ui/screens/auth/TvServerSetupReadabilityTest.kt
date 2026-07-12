package org.siloserver.silo.tv.ui.screens.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvServerSetupReadabilityTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvServerSetupScreen.kt",
    ).readText()

    @Test
    fun setupUsesPlatformImeTextFieldForFireTvInputSupport() {
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("KeyboardType.Uri"))
        assertTrue(source.contains("ImeAction.Go"))
        assertTrue(source.contains("LocalSoftwareKeyboardController"))
        assertFalse(source.contains("ServerUrlDisplayField("))
        assertFalse(source.contains("SiloServerUrlKeyboard("))
    }

    @Test
    fun setupTextUsesTenFootReadableSizes() {
        assertTrue(source.contains("TvServerSetupTextStyles.FieldText"))
        assertTrue(source.contains("fontSize = 16.sp"))
    }

    @Test
    fun phoneSetupCardUsesCompactLayoutThatDoesNotClipCopy() {
        assertTrue(source.contains("PHONE_SETUP_BEACON_SIZE"))
        assertTrue(source.contains("maxLines = 2"))
        assertTrue(source.contains("PhoneSetupBody("))
        assertTrue(source.contains("contentAlignment = Alignment.Center"))
        assertTrue(source.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(source.contains("textAlign = TextAlign.Center"))
    }

    @Test
    fun phoneSetupPillIsCenteredAndUsesSetupLabel() {
        assertTrue(source.contains("text = \"RECOMMENDED · USE PHONE\""))
        assertTrue(source.contains(".align(Alignment.CenterHorizontally)"))
    }

    @Test
    fun serverSetupKeepsUrlProtocolShortcutsBesideStockKeyboard() {
        assertTrue(source.contains("\"https://\""))
        assertTrue(source.contains("\"http://\""))
        assertTrue(source.contains("\".com\""))
    }

    @Test
    fun serverSetupKeepsManualEntryScrollableAbovePlatformIme() {
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(source.contains(".align(Alignment.TopCenter)"))
        assertTrue(source.contains("height(SERVER_SETUP_CHOOSER_HEIGHT)"))
        assertFalse(source.contains(".weight(1f)\n                        .padding(top = Spacing.lg)"))
    }

    @Test
    fun manualEntryCardScrollsInsteadOfClippingErrorTextInsideChooser() {
        val manualEntryBlock = source
            .substringAfter("private fun ManualEntryCard(")
            .substringBefore("@Composable\nprivate fun UrlShortcutRow")

        assertTrue(manualEntryBlock.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(manualEntryBlock.contains("Spacer(modifier = Modifier.weight(1f))"))
    }

}
