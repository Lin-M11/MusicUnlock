package musicunlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Shared geometry keeps unrelated screens on the same optical grid. */
internal object UiMetrics {
    val CardRadius = 16.dp
    val ControlRadius = 10.dp
    val PrimaryRadius = 12.dp
    val IconButtonSize = 32.dp
    val CompactIconButtonSize = 30.dp
    val ControlHeight = 40.dp
    val PrimaryHeight = 44.dp
    val RowIconSize = 16.dp
    val HeaderIconSize = 18.dp
    val Hairline = 1.dp
}

/** Icon-only control with a consistent hit target, hover state, focus ring, and tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
    size: Dp = UiMetrics.IconButtonSize,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        !enabled -> Color.Transparent
        danger && hovered -> t.errorSoft
        hovered -> t.surfaceSoft
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> t.textMuted.copy(alpha = 0.42f)
        danger && hovered -> t.error
        hovered -> t.text
        else -> t.textSecondary
    }

    TooltipArea(
        tooltip = {
            Surface(
                color = t.text,
                contentColor = t.surface,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 4.dp,
            ) {
                Text(
                    contentDescription,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    fontSize = 11.5.sp,
                )
            }
        },
        delayMillis = 450,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(UiMetrics.ControlRadius))
                .background(background)
                .then(
                    if (focused) {
                        Modifier.border(width = 1.dp, color = t.primary.copy(alpha = 0.72f), shape = RoundedCornerShape(UiMetrics.ControlRadius))
                    } else {
                        Modifier
                    },
                )
                .hoverable(interaction, enabled)
                .focusable(enabled, interaction)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(UiMetrics.RowIconSize),
            )
        }
    }
}

/** Empty-state mark sized and optically centered consistently across list pages. */
@Composable
internal fun AppEmptyIcon(icon: ImageVector, size: Dp = 52.dp) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(t.surfaceSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = t.textMuted,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

@Composable
internal fun rememberAppScrollbarStyle(): ScrollbarStyle {
    val t = cleanTokens()
    return ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 120,
        unhoverColor = t.textMuted.copy(alpha = 0.24f),
        hoverColor = t.primary.copy(alpha = 0.64f),
    )
}


/** Compact binary switch used by settings panels. */
@Composable
internal fun AppToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    !enabled -> t.surfaceSoft.copy(alpha = 0.58f)
                    checked -> t.primary
                    else -> t.surfaceSoft
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    focused -> t.primary
                    hovered && !checked -> t.dropBorder
                    checked -> Color.Transparent
                    else -> t.border
                },
                shape = RoundedCornerShape(11.dp),
            )
            .hoverable(interaction, enabled)
            .focusable(enabled, interaction)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .shadow(if (checked) 1.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(t.surface),
        )
    }
}

@Composable
internal fun AppVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = rememberAppScrollbarStyle(),
    )
}

@Composable
internal fun AppVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = rememberAppScrollbarStyle(),
    )
}

/** Text action with a stable hit target and consistent hover/focus treatment. */
@Composable
internal fun AppTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    filled: Boolean = false,
    outlined: Boolean = false,
    danger: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val contentColor = when {
        !enabled -> t.textMuted
        filled -> t.onPrimary
        danger -> t.error
        primary -> t.primary
        else -> t.textSecondary
    }
    val background = when {
        !enabled -> if (filled) t.surfaceSoft else Color.Transparent
        filled -> if (hovered || focused) t.primary.copy(alpha = 0.90f) else t.primary
        danger && hovered -> t.errorSoft
        primary && hovered -> t.primarySoft
        hovered -> t.surfaceSoft
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (outlined) Modifier.border(width = 1.dp, color = t.border, shape = RoundedCornerShape(8.dp)) else Modifier,
            )
            .then(
                if (focused) Modifier.border(width = 1.dp, color = t.primary.copy(alpha = 0.72f), shape = RoundedCornerShape(8.dp)) else Modifier,
            )
            .hoverable(interaction, enabled)
            .focusable(enabled, interaction)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIcon?.let {
                Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
            }
            Text(text, fontSize = 12.sp, color = contentColor, maxLines = 1)
        }
    }
}

/** Compact selectable chip for settings, filters, and segmented choices. */
@Composable
internal fun AppChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        !enabled -> t.surfaceSoft.copy(alpha = 0.55f)
        selected -> t.primarySoft
        hovered -> t.surfaceSoft
        else -> t.surface
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = when {
                    focused -> t.primary
                    selected -> t.primary.copy(alpha = 0.48f)
                    else -> t.border
                },
                shape = RoundedCornerShape(9.dp),
            )
            .hoverable(interaction, enabled)
            .focusable(enabled, interaction)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                !enabled -> t.textMuted
                selected -> t.primary
                else -> t.textSecondary
            },
            maxLines = 1,
        )
    }
}
