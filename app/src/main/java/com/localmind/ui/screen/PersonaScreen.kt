package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.localmind.data.ChatMode
import com.localmind.data.Persona
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.TextUtils

/**
 * 人设管理页。
 *
 * ## 为什么必须有这一页
 *
 * 人设是「这个 AI 是谁」：名字、头像、语气、系统提示词、以及它算 AI 还是算人。
 * 但**根页面（我的）上一直没有入口** —— 人设编辑器只出现在两处：
 *
 *  1. 「人设聊天」对话设置里，编辑的是**那一个对话自己的身份**；
 *  2. `ProfileScreen.kt`（根页面换成 `MePage` 之后就没人渲染它了）。
 *
 * 于是用户能改某一句话里的 AI，却**不能新建、不能切换、不能删除人设** ——
 * 新建对话时用的永远是内置的那几个。「人设不能设置」就是这件事。
 *
 * ## 这一页做什么
 *
 *  * 列出所有人设，标出哪一种模式（AI 聊天 / 当人）、当前在用哪个；
 *  * 点一行 → 编辑（[PersonaEditorSheet]）；
 *  * 右侧按钮 → 切换成当前使用的人设（新建对话时以它为起点）；
 *  * 底部两个「新建」：一个 AI 聊天版、一个当人版 —— 模式在创建时定死，
 *    因为它决定默认能力（流式、表情、语音）和要不要人设记忆层。
 */
@Composable
fun PersonaScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val personas by viewModel.personas.collectAsState()
    val activeId by viewModel.activePersonaId.collectAsState()

    // null = 没开编辑器；否则是"正在编辑的草稿"。新建时用一份空壳占位。
    var editing by remember { mutableStateOf<Persona?>(null) }

    editing?.let { p ->
        PersonaEditorSheet(
            viewModel = viewModel,
            persona = p,
            onDismiss = { editing = null },
        )
    }

    SettingsPage(title = "人设", onBack = onBack) {
        IosSection(
            header = "正在使用",
            footer = "新建「人设聊天」时，这个对话会以它为起点；改它的名字/头像/提示词不会影响别的对话。",
        ) {
            val active = personas.firstOrNull { it.id == activeId }
            PersonaRow(
                persona = active ?: com.localmind.data.PersonaPresets.default,
                selected = true,
                onEdit = { editing = active ?: com.localmind.data.PersonaPresets.default },
                onUse = null,
            )
        }

        IosSection(
            header = "全部人设",
            footer = "「AI 聊天」是一问一答的助手；「当人」会有情绪、记忆和更口语的语气。模式在新建时选定，之后不可改。",
        ) {
            personas.forEachIndexed { index, p ->
                if (index > 0) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 76.dp)
                            .height(0.5.dp)
                            .background(ios.separator),
                    )
                }
                PersonaRow(
                    persona = p,
                    selected = p.id == activeId,
                    onEdit = { editing = p },
                    onUse = if (p.id == activeId) null else {
                        { viewModel.selectPersona(p.id) }
                    },
                )
            }
        }

        IosSection(
            header = "新建",
            footer = "新建之后会自动切过去，下一次开对话就是它。",
        ) {
            NewPersonaRow(
                label = "新建 AI 聊天人设",
                hint = "助手型：不需要年龄性别，回答简短直接",
                color = ios.blue,
                onClick = {
                    editing = Persona(
                        id = TextUtils.randomId("persona"),
                        name = "",
                        mode = ChatMode.AI,
                    )
                },
            )
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp)
                    .height(0.5.dp)
                    .background(ios.separator),
            )
            NewPersonaRow(
                label = "新建「当人」人设",
                hint = "有年龄性别、情绪与记忆，默认流式与表情",
                color = ios.pink,
                onClick = {
                    editing = Persona(
                        id = TextUtils.randomId("persona"),
                        name = "",
                        mode = ChatMode.COMPANION,
                        humanMind = true,
                    )
                },
            )
        }
    }
}

@Composable
private fun PersonaRow(
    persona: Persona,
    selected: Boolean,
    onEdit: () -> Unit,
    /** null = 已经是当前人设，不再显示「使用」按钮。 */
    onUse: (() -> Unit)?,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonaAvatar(
            emoji = persona.avatarEmoji.ifBlank { "🙂" },
            colorIndex = persona.avatarColor.coerceAtLeast(0),
            size = 44,
            imagePath = persona.avatarImage,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    persona.name.ifBlank { "未命名" },
                    style = IosTheme.typography.body,
                    color = ios.label,
                )
                Spacer(Modifier.width(6.dp))
                IosPill(
                    if (persona.mode.isCompanion) "当人" else "AI",
                    if (persona.mode.isCompanion) ios.pink else ios.blue,
                )
                if (persona.builtIn) {
                    Spacer(Modifier.width(4.dp))
                    IosPill("内置", ios.gray)
                }
            }
            if (persona.tagline.isNotBlank()) {
                Text(
                    persona.tagline,
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    maxLines = 1,
                )
            }
        }
        if (onUse != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "使用",
                style = IosTheme.typography.footnote,
                color = ios.blue,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(ios.blue.copy(alpha = 0.12f))
                    .clickable(onClick = onUse)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.Check,
                contentDescription = "正在使用",
                tint = ios.blue,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NewPersonaRow(
    label: String,
    hint: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(AppIcons.Add, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = IosTheme.typography.body, color = ios.label)
            Text(hint, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
    }
}
