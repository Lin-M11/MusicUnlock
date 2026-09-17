package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.settings.AutomationRule
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.OutputFormat
import musicunlock.settings.extension

@Composable
internal fun AutomationRuleEditorOverlay(
    initial: AutomationRule,
    onSave: (AutomationRule) -> Unit,
    onClose: () -> Unit,
) {
    val t = cleanTokens()
    val scroll = rememberScrollState()
    var rule by remember(initial.id) { mutableStateOf(initial) }
    var extensionsText by remember(initial.id) { mutableStateOf(initial.extensions.joinToString(", ")) }
    var commandArgsText by remember(initial.id) { mutableStateOf(initial.postCommandArgs.joinToString("\n")) }
    var scheduleStartText by remember(initial.id) { mutableStateOf(initial.scheduleStartMinute.minuteText()) }
    var scheduleEndText by remember(initial.id) { mutableStateOf(initial.scheduleEndMinute.minuteText()) }
    var minMegabytes by remember(initial.id) { mutableStateOf((initial.minBytes / 1024.0 / 1024.0).takeIf { it > 0 }?.toString().orEmpty()) }
    var maxMegabytes by remember(initial.id) { mutableStateOf(initial.maxBytes?.let { it / 1024.0 / 1024.0 }?.toString().orEmpty()) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 780.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动化规则", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.text)
                Spacer(Modifier.weight(1f))
                AppTextAction("关闭", onClick = onClose, outlined = true)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                RuleField("名称", rule.name) { rule = rule.copy(name = it) }
                RulePathField("输入目录", rule.inputDir, { rule = rule.copy(inputDir = it) })
                RulePathField("输出目录", rule.outputDir, { rule = rule.copy(outputDir = it) })
                Text("输出格式", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutputFormat.entries.forEach { format ->
                        AppChoiceChip(format.editorName(), format == rule.outputFormat, { rule = rule.copy(outputFormat = format) })
                    }
                }
                RuleField("码率 kbps", rule.bitrateKbps.toString()) { rule = rule.copy(bitrateKbps = it.toIntOrNull()?.coerceIn(64, 512) ?: rule.bitrateKbps) }
                RuleField("命名模板", rule.outputTemplate) { rule = rule.copy(outputTemplate = it) }
                Text("同名文件", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DownloadExistingPolicy.entries.forEach { policy ->
                        AppChoiceChip(policy.editorName(), policy == rule.existingFilePolicy, { rule = rule.copy(existingFilePolicy = policy) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleField("最小 MB", minMegabytes, Modifier.weight(1f)) {
                        minMegabytes = it
                        rule = rule.copy(minBytes = ((it.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong().coerceAtLeast(0L))
                    }
                    RuleField("最大 MB（空不限）", maxMegabytes, Modifier.weight(1f)) {
                        maxMegabytes = it
                        rule = rule.copy(maxBytes = it.toDoubleOrNull()?.let { size -> (size * 1024 * 1024).toLong() })
                    }
                }
                RuleField("扩展名，逗号分隔", extensionsText) {
                    extensionsText = it
                    rule = rule.copy(extensions = it.split(',', '，').map(String::trim).filter(String::isNotEmpty))
                }
                RuleField("文件名模式，如 *live*", rule.fileNamePattern.orEmpty()) { rule = rule.copy(fileNamePattern = it.trim().takeIf(String::isNotEmpty)) }
                RuleToggle("按正则表达式", rule.regexPattern) { rule = rule.copy(regexPattern = it) }
                RuleField("优先级（越大越先匹配）", rule.priority.toString()) { rule = rule.copy(priority = it.toIntOrNull() ?: rule.priority) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RuleField("执行开始 HH:mm", scheduleStartText, Modifier.weight(1f)) { scheduleStartText = it; rule = rule.copy(scheduleStartMinute = it.toMinuteOrNull()) }
                    RuleField("执行结束 HH:mm", scheduleEndText, Modifier.weight(1f)) { scheduleEndText = it; rule = rule.copy(scheduleEndMinute = it.toMinuteOrNull()) }
                }
                Text("执行日：1=周一，7=周日", fontSize = 11.5.sp, color = t.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { day ->
                        AppChoiceChip(
                            text = day.shortDayName(),
                            selected = day in rule.daysOfWeek,
                            onClick = {
                                val days = if (day in rule.daysOfWeek) rule.daysOfWeek - day else rule.daysOfWeek + day
                                rule = rule.copy(daysOfWeek = days.sorted())
                            },
                        )
                    }
                }
                RuleToggle("成功后回收源文件", rule.trashSourceOnSuccess) { rule = rule.copy(trashSourceOnSuccess = it) }
                RuleField("Webhook URL", rule.webhookUrl.orEmpty()) { rule = rule.copy(webhookUrl = it.trim().takeIf(String::isNotEmpty)) }
                RuleField("后置命令", rule.postCommand.orEmpty()) { rule = rule.copy(postCommand = it.trim().takeIf(String::isNotEmpty)) }
                RuleField("命令参数，每行一个", commandArgsText, Modifier.fillMaxWidth()) {
                    commandArgsText = it
                    rule = rule.copy(postCommandArgs = it.lines().map(String::trim).filter(String::isNotEmpty))
                }
                Text("转换完成后执行；占位符：{input}、{inputDir}、{outputDir}、{output}、{name}、{extension}、{rule}", fontSize = 10.5.sp, color = t.textMuted)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppTextAction("取消", onClick = onClose, modifier = Modifier.height(UiMetrics.ControlHeight))
                Spacer(Modifier.width(8.dp))
                AppTextAction("保存规则", filled = true, modifier = Modifier.height(UiMetrics.ControlHeight), onClick = { onSave(rule) })
            }
        }
    }
}

@Composable
private fun RuleField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.5.sp) },
        singleLine = true,
        shape = RoundedCornerShape(UiMetrics.ControlRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.text),
        modifier = modifier,
    )
}

@Composable
private fun RulePathField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RuleField(label, value, Modifier.weight(1f), onChange)
        AppTextAction(
            text = "选择",
            outlined = true,
            onClick = { FileDialogs.pickFolder("选择${label}")?.let { onChange(it.absolutePath) } },
        )
    }
}

@Composable
private fun RuleToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.5.sp, color = cleanTokens().text)
        AppToggle(checked = checked, onCheckedChange = onChange)
    }
}

private fun OutputFormat.editorName(): String = when (this) {
    OutputFormat.ORIGINAL -> "原始"
    else -> extension?.uppercase() ?: name
}

private fun DownloadExistingPolicy.editorName(): String = when (this) {
    DownloadExistingPolicy.SKIP -> "跳过"
    DownloadExistingPolicy.OVERWRITE -> "覆盖"
    DownloadExistingPolicy.RENAME -> "另存"
    DownloadExistingPolicy.UPGRADE -> "升级"
}

private fun String.toMinuteOrNull(): Int? {
    val parts = trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun Int?.minuteText(): String = this?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()

private fun Int.shortDayName(): String = "一二三四五六日"[this - 1].toString()
