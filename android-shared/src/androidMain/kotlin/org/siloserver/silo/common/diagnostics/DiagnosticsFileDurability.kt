package org.siloserver.silo.common.diagnostics

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

internal fun syncDiagnosticsDirectory(directory: File) {
    var descriptor: FileDescriptor? = null
    try {
        descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        Os.fsync(checkNotNull(descriptor))
    } finally {
        descriptor?.let(Os::close)
    }
}

internal fun renameDiagnosticsFileAtomically(source: File, target: File) {
    // Both paths live in the same diagnostics state directory. Os.rename replaces an existing
    // target atomically; if it fails, propagate the failure and leave both the prior target and
    // the synced temporary file intact. Deleting the target as a fallback could lose the only
    // persisted hosted erasure authority at a crash boundary.
    Os.rename(source.absolutePath, target.absolutePath)
    check(!source.exists() && target.exists()) { "atomic publish did not complete for ${target.name}" }
}

internal fun deleteDiagnosticsEvidenceStrictly(
    target: File,
    deleteRecursively: (File) -> Boolean,
    directorySync: (File) -> Unit,
) {
    if (!target.exists()) return
    check(deleteRecursively(target)) { "unable to delete diagnostics evidence ${target.name}" }
    check(!target.exists()) { "diagnostics evidence still exists after deletion: ${target.name}" }
    target.parentFile?.let(directorySync)
    check(!target.exists()) { "diagnostics evidence deletion was not durable: ${target.name}" }
}
