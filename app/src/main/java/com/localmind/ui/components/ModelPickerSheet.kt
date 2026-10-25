package com.localmind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/** One selectable entry in a [ModelPickerSheet]. */
data class PickerOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val enabled: Boolean = true,
)

/**
 * Generic single-choice picker used for "which model does this job": chat, vision,
 * image, voice, or remote provider. `null` id means "follow the global default".
 */
@Composable
fun ModelPickerSheet(
    title: String,
    subtitle: String,
    options: List<PickerOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    allowDefault: Boolean = true,
    defaultLabel: String = "跟随全局默认",
    /**
     * 列表最下面的**额外动作**行（例如「＋ 手动添加自定义 API 地址」）。
     *
     * 为什么放在选择器里而不是只放在能力页：用户想接一个自己的接口时，
     * 他**正在**这个对话框里找「用哪个模型」—— 让他为此关掉弹窗、跑去另一页、
     * 建完再回来找，是把一件事拆成四步。
     */
    extraOption: PickerOption? = null,
    onExtraOption: (() -> Unit)? = null,
) {
    val ios = IosTheme.palette
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ios.groupedBackground)
                .padding(vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = IosTheme.typography.title3, color = ios.label)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                    }
                }
                Icon(
                    AppIcons.Close,
                    contentDescription = "关闭",
                    tint = ios.secondaryLabel,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onDismiss),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                if (allowDefault) {
                    item {
                        OptionRow(
                            option = PickerOption("", defaultLabel, "使用「能力」页里设置的默认模型"),
                            selected = selectedId.isBlank(),
                            onClick = {
                                onSelect("")
                                onDismiss()
                            },
                        )
                    }
                }
                items(options, key = { it.id }) { option ->
                    if (option.enabled) {
                        OptionRow(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = {
                                onSelect(option.id)
                                onDismiss()
                            },
                        )
                    }
                }
                if (options.none { it.enabled }) {
                    item {
                        Text(
                            "还没有可选的模型，先去「模型」页下载一个（没下载的不列在这里）。",
                            style = IosTheme.typography.footnote,
                            color = ios.tertiaryLabel,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
                extraOption?.let { extra ->
                    item(key = "extra-option") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExtraOption?.invoke() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                extra.title,
                                style = IosTheme.typography.body,
                                color = ios.blue,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (extra.subtitle.isNotBlank()) {
                                Text(
                                    extra.subtitle,
                                    style = IosTheme.typography.caption2,
                                    color = ios.secondaryLabel,
                                    maxLines = 2,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) ios.fillSecondary else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.title,
                    style = IosTheme.typography.body,
                    color = ios.label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                option.badge?.let {
                    Spacer(Modifier.width(6.dp))
                    IosPill(it, ios.indigo)
                }
            }
            if (option.subtitle.isNotBlank()) {
                Text(
                    option.subtitle,
                    style = IosTheme.typography.caption2.copy(fontFamily = FontFamily.Monospace),
                    color = ios.secondaryLabel,
                    maxLines = 2,
                )
            }
        }
        if (selected) {
            Icon(AppIcons.Check, contentDescription = null, tint = ios.blue, modifier = Modifier.size(18.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(IosTheme.palette.separator))
}

// RouteRow 也一起删了：它唯一的消费端是那个已经撤掉的「模型分工」全局面板。
// 现在每个槽位的行为 ConversationModelRow（见 ui/screen/ConversationSettings.kt）。
