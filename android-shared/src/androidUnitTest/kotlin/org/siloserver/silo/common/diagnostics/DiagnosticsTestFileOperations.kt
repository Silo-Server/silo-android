package org.siloserver.silo.common.diagnostics

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun testAtomicRename(source: File, target: File) {
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}
