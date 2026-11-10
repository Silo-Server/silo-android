package org.siloserver.silo.model.subtitles

import org.siloserver.silo.common.i18n.english

/** A result line as the screen shows it: its English text and whether it warns. */
fun SubtitleSyncResultLine.english(): Pair<String, Boolean> = text.english() to warning
