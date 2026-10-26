package com.localmind.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.localmind.data.ChatAction
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.LayoutIcons
import com.localmind.ui.theme.IosTheme

/**
 * 插件提供的聊天动作条。
 *
 * 只在有动作时出现、只在没在生成时出现。点一下：
 *  * send   —— 直接把提示词发出去（{{input}} 会用当前草稿替换）；
 *  * insert —— 把提示词填进输入框，用户继续改。
 */
@Composable
fun PluginActionBar(
    actions: List<ChatAction>,
    draft: String,
    onRun: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val ios = IosTheme.palette
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(actions, key = { it.id }) { action ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.fillSecondary)
                    .clickable(interactionSource = interaction, indication = null) { onRun(action) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = LayoutIcons.of(action.icon) ?: AppIcons.Bolt,
                    contentDescription = null,
                    tint = ios.blue,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = action.label,
                    style = IosTheme.typography.caption1,
                    color = ios.label,
                )
            }
        }
    }
}
