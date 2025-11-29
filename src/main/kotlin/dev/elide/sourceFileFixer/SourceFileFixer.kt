@file:JvmName("SourceFileFixer")

package dev.elide.sourceFileFixer

import org.objectweb.asm.*
import java.io.InputStream
import java.nio.file.*
import java.util.jar.*

/**
 * Utility to fix missing or stripped SourceFile attributes in Java class files within a JAR.
 *
 * Usage: java -jar sourcefile-fixer.jar <jar-file>
 */
object SourceFileFixer {

    private const val STRIPPED_MARKER = "stripped"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            System.err.println("Usage: sourcefile-fixer <jar-file>")
            System.exit(1)
        }

        val jarPath = Path.of(args[0])
        if (!Files.exists(jarPath)) {
            System.err.println("Error: File not found: $jarPath")
            System.exit(1)
        }

        if (!jarPath.toString().endsWith(".jar")) {
            System.err.println("Error: File must be a JAR: $jarPath")
            System.exit(1)
        }

        val modifiedCount = processJar(jarPath)
        if (modifiedCount > 0) {
            println("Modified $modifiedCount class(es) in $jarPath")
        } else {
            println("No classes needed modification in $jarPath")
        }
    }

    private fun processJar(jarPath: Path): Int {
        val modifications = mutableMapOf<String, ByteArray>()

        // First pass: identify classes needing modification
        JarFile(jarPath.toFile()).use { jar ->
            for (entry in jar.entries()) {
                if (entry.name.endsWith(".class")) {
                    jar.getInputStream(entry).use { input ->
                        val originalBytes = input.readBytes()
                        val result = processClass(originalBytes)
                        if (result.modified) {
                            modifications[entry.name] = result.bytes
                            println("  Fixed: ${entry.name} -> ${result.sourceFile}")
                        }
                    }
                }
            }
        }

        // Only rewrite JAR if modifications were made
        if (modifications.isEmpty()) {
            return 0
        }

        // Create new JAR in temp directory
        val tempDir = Files.createTempDirectory("sourcefile-fixer-")
        val tempJar = tempDir.resolve(jarPath.fileName)

        try {
            JarFile(jarPath.toFile()).use { inputJar ->
                val manifest = inputJar.manifest
                val output = if (manifest != null) {
                    JarOutputStream(Files.newOutputStream(tempJar), manifest)
                } else {
                    JarOutputStream(Files.newOutputStream(tempJar))
                }

                output.use {
                    for (entry in inputJar.entries()) {
                        // Skip manifest (already written via constructor)
                        if (entry.name == JarFile.MANIFEST_NAME) {
                            continue
                        }

                        val newEntry = JarEntry(entry.name).apply {
                            // Preserve timestamps
                            entry.lastModifiedTime?.let { lastModifiedTime = it }
                            entry.lastAccessTime?.let { lastAccessTime = it }
                            entry.creationTime?.let { creationTime = it }
                        }

                        output.putNextEntry(newEntry)

                        val bytes = modifications[entry.name]
                            ?: inputJar.getInputStream(entry).use { it.readBytes() }

                        output.write(bytes)
                        output.closeEntry()
                    }
                }
            }

            // Replace original JAR with modified one
            Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING)

        } finally {
            // Clean up temp directory
            deleteRecursively(tempDir)
        }

        return modifications.size
    }

    private data class ClassResult(
        val bytes: ByteArray,
        val modified: Boolean,
        val sourceFile: String?
    )

    private fun processClass(originalBytes: ByteArray): ClassResult {
        // First, check if modification is needed
        var currentSourceFile: String? = null
        var className: String? = null

        val reader = ClassReader(originalBytes)
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                className = name
            }

            override fun visitSource(source: String?, debug: String?) {
                currentSourceFile = source
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        // Check if we need to modify
        val needsModification = currentSourceFile == null || currentSourceFile == STRIPPED_MARKER

        if (!needsModification) {
            return ClassResult(originalBytes, false, currentSourceFile)
        }

        // Calculate mock source file name from class name
        val mockSourceFile = calculateSourceFileName(className!!)

        // Rewrite the class with new SourceFile attribute
        val writer = ClassWriter(0)
        val visitor = SourceFileRewriter(writer, mockSourceFile)
        reader.accept(visitor, 0)

        return ClassResult(writer.toByteArray(), true, mockSourceFile)
    }

    /**
     * Calculate a mock source file name from the class name.
     * Handles inner classes, anonymous classes, and package structures.
     * For obfuscated short class names (< 5 chars), generates a deterministic
     * name based on a hash of the full class path.
     *
     * Examples:
     *   com/example/Foo -> Foo.java
     *   com/example/Foo$Bar -> Foo.java
     *   com/example/Foo$1 -> Foo.java
     *   com/example/Foo$Bar$Baz -> Foo.java
     *   com/oracle/svm/enterprise/truffle/a -> Obf_7a3f2b1c.java
     *   com/oracle/svm/enterprise/truffle/a$1 -> Obf_7a3f2b1c.java
     */
    private fun calculateSourceFileName(className: String): String {
        // Get simple class name (after last /)
        val simpleName = className.substringAfterLast('/')

        // Get the outermost class (before any $)
        val outerClass = simpleName.substringBefore('$')

        // If class name is short (likely obfuscated), generate deterministic name from hash
        return if (outerClass.length < 5) {
            // Hash the full path to the outer class (package + outer class name)
            // This ensures all inner classes of the same outer class get the same source file
            val lastSlash = className.lastIndexOf('/')
            val outerClassFullPath = if (lastSlash >= 0) {
                className.substring(0, lastSlash + 1) + outerClass
            } else {
                outerClass
            }
            
            val hash = deterministicHash(outerClassFullPath)
            "Obf_$hash.java"
        } else {
            "$outerClass.java"
        }
    }

    /**
     * Generate a deterministic short hash string from the input.
     * Uses a simple but stable algorithm to produce an 8-character hex string.
     */
    private fun deterministicHash(input: String): String {
        // Use a simple multiplicative hash that's stable across JVM versions
        var hash = 0L
        for (c in input) {
            hash = hash * 31 + c.code
        }
        // Take lower 32 bits and format as unsigned hex
        return (hash and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    /**
     * ASM ClassVisitor that rewrites the SourceFile attribute.
     */
    private class SourceFileRewriter(
        delegate: ClassVisitor,
        private val newSourceFile: String
    ) : ClassVisitor(Opcodes.ASM9, delegate) {

        private var sourceVisited = false

        override fun visitSource(source: String?, debug: String?) {
            sourceVisited = true
            // Always emit our new source file, preserve debug info if present
            super.visitSource(newSourceFile, debug)
        }

        override fun visitEnd() {
            // If no visitSource was called, we need to add one
            if (!sourceVisited) {
                super.visitSource(newSourceFile, null)
            }
            super.visitEnd()
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                stream.forEach { deleteRecursively(it) }
            }
        }
        Files.deleteIfExists(path)
    }
}
