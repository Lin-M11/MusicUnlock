package musicunlock.service

import musicunlock.core.Formats
import musicunlock.core.MusicDecoder
import musicunlock.core.MusicResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 统一转换服务:根据扩展名选择解码器,解密后写入输出目录。
 * 对 NCM 额外写回元数据(歌名/歌手/专辑/封面)。
 */
object MusicConverter {

    /**
     * 转换单个加密音乐文件,成功返回 null,失败返回错误信息。
     */
    fun convertWithError(inputPath: String, outputDir: String): String? {
        return try {
            val input = File(inputPath)
            if (!input.isFile) return "不是有效的文件: $inputPath"

            val decoder = Formats.get(input.name) ?: run {
                println("不支持的格式,已跳过: $inputPath")
                return "不支持的格式: $inputPath"
            }

            val data = Files.readAllBytes(input.toPath())
            val result = decoder.decode(data, input.name)

            val outName = baseName(input.name) + "." + result.ext
            val output = File(outputDir, outName)
            Files.write(output.toPath(), result.data)

            if (result.musicName != null || result.artist != null || result.album != null || result.cover != null) {
                TagWriter.embed(output, result)
            }

            println("转换成功文件: ${output.absolutePath}")
            null
        } catch (e: Exception) {
            println("转换失败文件: $inputPath")
            e.printStackTrace()
            e.message ?: e.toString()
        }
    }

    /**
     * 计算解密后音频的 SHA-256(用于内容去重)。
     * 必须对解密后的音频而非加密文件做哈希,否则同名歌曲因元数据不同会被误判为不同。
     */
    fun audioSha256(inputPath: String): String? {
        val input = File(inputPath)
        val decoder = Formats.get(input.name) ?: return null
        val data = Files.readAllBytes(input.toPath())
        val result = decoder.decode(data, input.name)
        val digest = MessageDigest.getInstance("SHA-256").digest(result.data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 递归收集 path 下的所有受支持加密音乐文件。 */
    fun listAllFiles(files: MutableList<File>, file: File) {
        if (!file.isDirectory) {
            if (Formats.isSupported(file.name)) files.add(file)
            return
        }
        file.listFiles()?.forEach { listAllFiles(files, it) }
    }

    private fun baseName(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx > 0) fileName.substring(0, idx) else fileName
    }
}
