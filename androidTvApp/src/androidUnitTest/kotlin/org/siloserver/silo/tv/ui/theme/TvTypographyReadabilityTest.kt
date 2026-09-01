package org.siloserver.silo.tv.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvTypographyReadabilityTest {
    private val tinyFontPattern = Regex(
        """fontSize\s*=\s*(?:[0-9]|1[0-3])(?:\.\d+)?\.sp""",
    )
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/theme/Type.kt",
    ).readText()
    private val tvOsMappedCompactTextLines = listOf(
        "org/siloserver/silo/tv/ui/screens/detail/TvDetailEpisodeRail.kt|fontSize = 11.5.sp,",
        "org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt|fontSize = 11.sp,",
        "org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt|fontSize = 11.5.sp,",
    )

    @Test
    fun sharedTvTypographyAvoidsTinyTenFootText() {
        // Readable floor: the shared theme keeps every ten-foot token at >=14sp.
        // Anything 9-13sp is a regression; 14sp (the metadata floor) and up are
        // allowed. Current Type.kt keeps its smallest styles at 16sp.
        assertFalse(tinyFontPattern.containsMatchIn(source))
        assertTrue(source.contains("titleSmall = TextStyle("))
        assertTrue(source.contains("fontSize = 18.sp"))
        assertTrue(source.contains("bodySmall = TextStyle("))
        assertTrue(source.contains("fontSize = 16.sp"))
        assertTrue(source.contains("labelSmall = TextStyle("))
        assertTrue(source.contains("sectionEyebrow = TextStyle("))
    }

    @Test
    fun sharedTvTypographyUsesNeutralLetterSpacing() {
        assertFalse(source.contains("letterSpacing = (-"))
    }

    @Test
    fun tinyFontPatternCatchesEveryValueBelowTheFourteenSpFloor() {
        listOf("0.5", "8", "9", "13", "13.5").forEach { size ->
            assertTrue(
                tinyFontPattern.containsMatchIn("fontSize = $size.sp"),
                "Expected $size.sp to be rejected",
            )
        }
        listOf("14", "14.5", "18").forEach { size ->
            assertFalse(
                tinyFontPattern.containsMatchIn("fontSize = $size.sp"),
                "Expected $size.sp to be allowed",
            )
        }
    }

    @Test
    fun tvScreensAvoidHardcodedTinyTextOutsideTheTheme() {
        // Normal TV copy honours a 14sp metadata floor. These three exact
        // assignments are approved half-scale mappings of compact tvOS chrome;
        // the matched-count assertion keeps this exception line-specific and
        // prevents it from becoming a file-wide escape hatch.
        val approvedMatches = mutableListOf<String>()
        val offenders = File("src/androidMain/kotlin/org/siloserver/silo/tv")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("/ui/theme/Type.kt") }
            .flatMap { file ->
                val relativePath = file.relativeTo(File("src/androidMain/kotlin")).invariantSeparatorsPath
                file.readLines().mapIndexedNotNull { index, line ->
                    if (tinyFontPattern.containsMatchIn(line)) {
                        val approvedKey = "$relativePath|${line.trim()}"
                        if (approvedKey in tvOsMappedCompactTextLines) {
                            approvedMatches += approvedKey
                            null
                        } else {
                            "$relativePath:${index + 1}: ${line.trim()}"
                        }
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertEquals(
            tvOsMappedCompactTextLines.sorted(),
            approvedMatches.sorted(),
            "The compact tvOS typography exception list must match exactly",
        )

        assertTrue(
            offenders.isEmpty(),
            "TV screens should use shared readable typography instead of hardcoded tiny font sizes:\n" +
                offenders.joinToString(separator = "\n"),
        )
    }
}
