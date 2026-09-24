package dev.archetype.definitions

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

data class SourceFile(val relativePath: String, val bytes: ByteArray)
data class PackSnapshot(val files: List<SourceFile>, val fingerprint: String)

class SnapshotException(message: String) : Exception(message)

/** ASVS 2.2.1, 15.3.5: bound file count and input size before YAML decoding. */
object PackCapture {
    private const val MAX_FILES = 512
    private const val MAX_FILE_BYTES = 1_048_576
    private const val MAX_TOTAL_BYTES = 16_777_216

    fun capture(root: Path): PackSnapshot {
        if (!Files.exists(root)) return PackSnapshot(emptyList(), digest(emptyList()))
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw SnapshotException("pack root is not a directory")
        val files = mutableListOf<SourceFile>()
        var total = 0
        Files.walk(root).use { stream ->
            stream.sorted().forEach { path ->
                if (path == root) return@forEach
                if (Files.isSymbolicLink(path)) throw SnapshotException("symbolic links are not allowed: ${root.relativize(path)}")
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val relative = root.relativize(path).toString().replace('\\', '/')
                val parts = relative.split('/')
                if ("assets" in parts || !(relative.endsWith(".yaml") || relative.endsWith(".yml"))) return@forEach
                if (files.size >= MAX_FILES) throw SnapshotException("too many manifest files")
                val size = Files.size(path)
                if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) throw SnapshotException("manifest input is too large: $relative")
                val bytes = Files.readAllBytes(path)
                if (bytes.size > MAX_FILE_BYTES || total + bytes.size > MAX_TOTAL_BYTES) throw SnapshotException("manifest input is too large: $relative")
                total += bytes.size
                files += SourceFile(relative, bytes)
            }
        }
        return PackSnapshot(files, digest(files))
    }

    private fun digest(files: List<SourceFile>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            hash.update(file.relativePath.toByteArray(Charsets.UTF_8))
            hash.update(0)
            hash.update(file.bytes)
            hash.update(0)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
