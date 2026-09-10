package musicunlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 登录方式。短信仅由网易云提供，其余入口由两个平台共享同一套界面。 */
internal enum class LoginMethod { QR, SMS, BROWSER }

/** 登录卡片外壳：统一标题、说明、方式切换和内容宽度。 */
@Composable
internal fun LoginCardFrame(
    title: String,
    subtitle: String,
    tabs: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = cleanTokens()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                fontSize = 12.5.sp,
                color = t.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surfaceSoft)
                    .padding(3.dp),
                content = tabs,
            )
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

/** 登录方式切换项。 */
@Composable
internal fun LoginMethodTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) t.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) t.onPrimary else t.textSecondary,
        )
    }
}

/** 二维码登录区：QQ 与网易云共用尺寸、按钮层级和状态节奏。 */
@Composable
internal fun QrLoginPanel(
    qrImage: ImageBitmap?,
    status: String,
    statusError: Boolean,
    defaultStatus: String,
    footer: String,
    onRefresh: () -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(t.surfaceSoft)
            .border(1.dp, t.border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (qrImage != null) {
            Image(
                bitmap = qrImage,
                contentDescription = "登录二维码",
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.size(224.dp),
            )
        } else {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = t.textMuted,
                modifier = Modifier.size(48.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onRefresh,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = t.primarySoft,
            contentColor = t.primary,
        ),
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (qrImage == null) "获取二维码" else "刷新二维码",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        status.ifBlank { defaultStatus },
        fontSize = 12.5.sp,
        color = if (statusError) t.error else t.textSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        footer,
        fontSize = 12.sp,
        color = t.textMuted,
        textAlign = TextAlign.Center,
    )
}

/** 浏览器登录区；Cookie 作为浏览器不可用时的备用入口。 */
@Composable
internal fun BrowserLoginPanel(
    browserStatus: String,
    browserBusy: Boolean,
    browserError: Boolean,
    browserDefaultStatus: String,
    onBrowserLogin: () -> Unit,
    cookieText: String,
    onCookieTextChange: (String) -> Unit,
    cookieStatus: String,
    cookieBusy: Boolean,
    cookieError: Boolean,
    cookiePlaceholder: String,
    cookieDefaultStatus: String,
    onCookieLogin: () -> Unit,
) {
    val t = cleanTokens()
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = t.surfaceSoft,
        unfocusedContainerColor = t.surfaceSoft,
        focusedIndicatorColor = t.primary,
        unfocusedIndicatorColor = t.border,
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        cursorColor = t.primary,
    )

    Button(
        onClick = onBrowserLogin,
        enabled = !browserBusy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = t.primary,
            contentColor = t.onPrimary,
            disabledContainerColor = t.surfaceSoft,
            disabledContentColor = t.textMuted,
        ),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        if (browserBusy) {
            Text("请稍候…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("打开浏览器自动登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        browserStatus.ifBlank { browserDefaultStatus },
        fontSize = 12.5.sp,
        color = if (browserError) t.error else t.textSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = t.rowDivider)
    Spacer(Modifier.height(14.dp))
    Text(
        "浏览器不可用时，可手动粘贴 Cookie：",
        fontSize = 12.5.sp,
        color = t.textSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = cookieText,
        onValueChange = onCookieTextChange,
        placeholder = { Text(cookiePlaceholder, fontSize = 12.sp, color = t.textMuted) },
        colors = fieldColors,
        shape = RoundedCornerShape(12.dp),
        minLines = 3,
        maxLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCookieLogin,
        enabled = !cookieBusy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = t.primarySoft,
            contentColor = t.primary,
            disabledContainerColor = t.surfaceSoft,
            disabledContentColor = t.textMuted,
        ),
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        Text(
            if (cookieBusy) "请稍候…" else "Cookie 登录",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        cookieStatus.ifBlank { cookieDefaultStatus },
        fontSize = 12.5.sp,
        color = if (cookieError) t.error else t.textSecondary,
        textAlign = TextAlign.Center,
    )
}
