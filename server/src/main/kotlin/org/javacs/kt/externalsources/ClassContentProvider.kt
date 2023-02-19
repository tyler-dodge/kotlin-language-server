package org.javacs.kt.externalsources

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.LOG
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.j2k.convertJavaToKotlin
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import kotlin.io.path.copyTo

/**
 * Provides the source code for classes located inside
 * compiled or source archives, such as JARs or ZIPs.
 */
class ClassContentProvider(
    private val config: ExternalSourcesConfiguration,
    private val cp: CompilerClassPath,
    private val tempDir: TemporaryDirectory,
    private val sourceArchiveProvider: SourceArchiveProvider,
    private val decompiler: Decompiler = FernflowerDecompiler()
) {
    /** Maps recently used (source-)KLS-URIs to their source contents (e.g. decompiled code) and the file extension. */
    private val cachedContents = object : LinkedHashMap<String, Pair<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 5
    }

    /**
     * Fetches the contents of a compiled class/source file in an archive
     * and another URI which can be used to refer to these extracted
     * contents.
     * If the file is inside a source archive, the source code is returned as is.
     */
    fun contentOf(uri: KlsURI): Pair<KlsURI, String> {
        val resolvedUri = if (uri.archiveType == KlsURI.ArchiveType.LOCAL)
            uri else (sourceArchiveProvider.fetchSourceArchive(uri.archivePath)?.let(uri.withSource(true)::withArchivePath) ?: uri)

        val key = resolvedUri.toString()
        val (contents, extension) = cachedContents[key] ?: run {
                LOG.info("Finding contents of {}", describeURI(resolvedUri.fileUri))
                tryReadContentOf(resolvedUri)
                    ?: tryReadContentOf(resolvedUri.withFileExtension("class"))
                    ?: tryReadContentOf(resolvedUri.withFileExtension("java"))
                    ?: tryReadContentOf(resolvedUri.withFileExtension("kt"))
                    ?: throw KotlinLSException("Could not find $uri")
            }.also { cachedContents[key] = it }
        val sourceUri = resolvedUri.withFileExtension(extension)
        return Pair(sourceUri, contents)
    }

    private fun convertToKotlinIfNeeded(javaCode: String): String = if (config.autoConvertToKotlin) {
        convertJavaToKotlin(javaCode, cp.compiler)
    } else {
        javaCode
    }

    private fun tryReadContentOf(uri: KlsURI): Pair<String, String>? = try {
        when (uri.fileExtension) {
            "class" ->
                if (uri.archiveType == KlsURI.ArchiveType.LOCAL) {
                    val destination = tempDir.createTempFile(uri.fileName, ".class")
                    val file: Path = Paths.get(URI.create(uri.fileUri.schemeSpecificPart).schemeSpecificPart)
                    file.copyTo(destination, overwrite = true)
                    Pair(decompiler.decompileClass(destination)
                        .let { Files.newInputStream(it) }
                        .bufferedReader()
                        .use(BufferedReader::readText)
                        .let(this::convertToKotlinIfNeeded),
                        if (config.autoConvertToKotlin) "kt" else "java")
                } else
                    Pair(uri.extractToTemporaryFile(tempDir)
                             .let(decompiler::decompileClass)
                             .let { Files.newInputStream(it) }
                             .bufferedReader()
                             .use(BufferedReader::readText)
                             .let(this::convertToKotlinIfNeeded), if (config.autoConvertToKotlin) "kt" else "java")
            "java" -> if (uri.source) Pair(uri.readContents(), "java") else Pair(convertToKotlinIfNeeded(uri.readContents()), "kt")
            else -> Pair(uri.readContents(), "kt") // e.g. for Kotlin source files
        }
    } catch (e: FileNotFoundException) { null }
}
