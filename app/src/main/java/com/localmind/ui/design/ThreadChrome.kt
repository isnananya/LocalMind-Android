package com.localmind.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.AppIconsAutoMirrored
import com.localmind.ui.theme.IosTheme

/**
 * 对话页顶栏。
 *
 * 只保留三个东西：返回、**你在和谁说话**、设置。没有大标题 —— 大标题在
 * 列表页已经用过一次，进来再显示一遍会让屏幕上半截全是字。
 */
@Composable
fun ThreadTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().background(ios.groupedBackground.copy(alpha = 0.58f))) {
        Spacer(Modifier.height(statusBarTopPadding()))
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuroraCircleButton(
                icon = AppIconsAutoMirrored.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 36.dp,
                tint = ios.label,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = IosTheme.typography.headline,
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = IosTheme.typography.caption2,
                        color = ios.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AuroraCircleButton(
                icon = AppIcons.Tune,
                contentDescription = "本对话设置",
                onClick = onSettings,
                size = 36.dp,
                tint = ios.label,
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
    }
}

/**
 * 输入条。
 *
 * 一个白胶囊装文字，左边加号发图，右边蓝色圆形发送。没有麦克风按钮 ——
 * 语音入口在系统键盘上，多放一个绿色按钮只会让底部变得吵。
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 没有可用模型时点输入条的行为。
     *
     * 原来这里只是把输入框 disabled 掉：用户点了没反应，底部写着"先加载模型"，
     * 却不知道去哪儿加载 —— 一次无效点击。所以把整条输入区做成"去选模型"的入口。
     */
    onBlocked: (() -> Unit)? = null,
) {
    val ios = IosTheme.palette
    val canSend = value.isNotBlank() && enabled && !streaming
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp, max = 140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(ios.secondaryGroupedBackground)
                .border(0.6.dp, ios.separator, RoundedCornerShape(22.dp))
                .then(
                    if (!enabled && onBlocked != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBlocked,
                        )
                    } else Modifier
                )
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        onClick = onPickImage,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Add,
                    contentDescription = "发图",
                    tint = if (enabled) ios.secondaryLabel else ios.tertiaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (enabled) "发消息…" else "点这里选模型",
                        style = IosTheme.typography.body,
                        color = ios.tertiaryLabel,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled && !streaming,
                    textStyle = IosTheme.typography.body.copy(color = ios.label),
                    cursorBrush = SolidColor(ios.blue),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    when {
                        streaming -> ios.red
                        canSend -> ios.blue
                        else -> ios.fill
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (streaming) onStop() else if (canSend) onSend() else onBlocked?.invoke()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (streaming) AppIcons.Stop else AppIconsAutoMirrored.Send,
                contentDescription = if (streaming) "停止" else "发送",
                tint = when {
                    streaming -> ios.onAccent
                    canSend -> ios.onAccent
                    else -> ios.secondaryLabel
                },
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
