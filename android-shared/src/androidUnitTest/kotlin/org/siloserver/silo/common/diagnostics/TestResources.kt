package org.siloserver.silo.common.diagnostics

import java.io.File

/**
 * Loads the vendored client-diagnostics contract fixtures from
 * `src/androidUnitTest/resources/diagnostics/`. Fixtures are the wire
 * contract — vendored verbatim from silo-server, never hand-edited (see the
 * README next to them).
 */
internal object TestResources {

    private val classLoader: ClassLoader
        get() = checkNotNull(TestResources::class.java.classLoader) { "no test class loader" }

    fun bytes(path: String): ByteArray =
        checkNotNull(classLoader.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.use { it.readBytes() }

    fun text(path: String): String = bytes(path).decodeToString()

    /**
     * Lists files in a resource directory. Local unit-test resources are plain
     * files on disk, so the directory URL resolves to a listable [File].
     */
    fun listFiles(dirPath: String, extension: String): List<File> {
        val url = checkNotNull(classLoader.getResource(dirPath)) {
            "missing test resource directory: $dirPath"
        }
        val dir = File(url.toURI())
        check(dir.isDirectory) { "not a directory: $dir" }
        return dir.listFiles { f -> f.isFile && f.name.endsWith(extension) }
            .orEmpty()
            .sortedBy { it.name }
    }
}
