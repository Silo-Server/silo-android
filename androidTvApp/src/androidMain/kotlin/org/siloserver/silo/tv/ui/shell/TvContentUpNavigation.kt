package org.siloserver.silo.tv.ui.shell

/**
 * A held Up may traverse content, but it must not escape from the first
 * content row into the top menu after focus movement has already failed.
 */
internal fun shouldRequestMenuAfterContentUp(
    movedWithinContent: Boolean,
    isRepeat: Boolean,
): Boolean = !movedWithinContent && !isRepeat
