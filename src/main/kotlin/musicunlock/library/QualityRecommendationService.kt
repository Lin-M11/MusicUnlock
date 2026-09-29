package musicunlock.library

import java.io.File

data class QualityRecommendation(
    val path: String,
    val title: String,
    val score: Int,
    val action: String,
    val reason: String,
)

/** 把音频质量检测结果转换为用户可执行的保留、替换或升级建议。 */
object QualityRecommendationService {
    fun recommend(file: File): QualityRecommendation? {
        if (!file.isFile) return null
        val report = AudioQualityInspector.inspect(file)
        val lossless = file.extension.lowercase() in setOf("flac", "ape", "wav", "wave", "alac")
        val bitrate = report.bitRateKbps ?: report.effectiveBitRateKbps ?: 0
        val fakeLossless = lossless && report.issues.any { it.contains("伪无损") || it.contains("有损音源") }
        val action = when {
            fakeLossless -> "重新获取真无损"
            bitrate in 1..191 -> "搜索更高码率"
            bitrate in 192..319 && !lossless -> "优先升级到 320k 或无损"
            report.score < 70 -> "检查并替换音源"
            else -> "保持当前版本"
        }
        val reason = when {
            fakeLossless -> report.issues.firstOrNull { it.contains("伪无损") || it.contains("有损音源") }
                ?: "检测到疑似伪无损或由有损音源转码"
            bitrate in 1..191 -> "当前有效码率约 ${bitrate}k，存在明显音质提升空间"
            bitrate in 192..319 && !lossless -> "当前为有损格式，升级到 320k 或无损害处有限"
            report.score < 70 -> report.issues.joinToString("；").ifBlank { "质量评分偏低" }
            else -> "格式、码率和完整度处于合理范围"
        }
        return QualityRecommendation(
            path = file.absolutePath,
            title = file.nameWithoutExtension,
            score = report.score,
            action = action,
            reason = reason,
        )
    }
}
