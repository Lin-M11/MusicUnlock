package musicunlock.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 文件/文件夹选择(AWT JFileChooser,跨平台)。
 */
object FileDialogs {

    fun pickFiles(title: String, extensions: List<String>): List<File> {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = true
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.fileFilter = FileNameExtensionFilter(
            "加密音乐 (${extensions.joinToString("/")})",
            *extensions.toTypedArray(),
        )
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.toList()
        } else {
            emptyList()
        }
    }

    fun saveFile(title: String, defaultName: String): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.selectedFile = File(defaultName)
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun pickFile(title: String, extensions: List<String>): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        if (extensions.isNotEmpty()) chooser.fileFilter = FileNameExtensionFilter("配置 (${extensions.joinToString("/")})", *extensions.toTypedArray())
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun pickFolder(title: String): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }
}
