package org.siloserver.silo.common.diagnostics

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Atomic file write (tmp + fsync + rename) shared by the diagnostics stores —
 * the same crash-safety recipe as ScopedJsonFileStore, local to this package
 * so store code stays dependency-free.
 */
internal fun writeDiagnosticsFileAtomic(target: File, text: String, tag: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(tmp).use { stream ->
        stream.write(text.encodeToByteArray())
        stream.fd.sync()
    }
    if (!tmp.renameTo(target)) Log.w(tag, "atomic rename failed for ${target.path}")
}
