package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.asImageBitmap
import com.localmind.ui.components.AvatarStore
import com.localmind.ui.components.rememberAvatarPicker
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.data.Persona
import com.localmind.data.VoiceCatalog
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.TextUtils

/** Emoji palette for avatars — covers people, animals, objects and moods. */
private val avatarEmojis = listOf(
    "🧠", "🙂", "😀", "😎", "🤓", "🥰", "😼", "🤖", "👻", "🐱",
    "🐶", "🦊", "🐼", "🐧", "🦉", "🐙", "🌿", "🌸", "🍵", "☕",
    "🔥", "⚡", "🌙", "⭐", "🎯", "🎨", "✍️", "📚", "💻", "⌨️",
    "🔧", "🧪", "🎧", "🍜", "🏔️", "🌊", "💡", "🧩", "🎭", "🛡️",
)

/**
 * Create / edit an AI persona.
 *
 * Everything the model needs to *be* someone lives here: name, avatar, prompt,
 * voice, sampling, and the experimental human-mind switch.
 */
@Composable
fun PersonaEditorSheet(
    viewModel: AppViewModel,
    persona: Persona,
    onDismiss: () -> Unit,
    /**
     * 覆盖「保存」行为。传了就不再写全局人设库。
     *
     * 对话详情用它把编辑结果写回**对话自己**的身份，而不是在库里新建一个
     * 同名同头像的重复人设 —— 那正是"每个对话一份身份"最容易被搞乱的地方。
     */
    onSave: ((Persona) -> Unit)? = null,
) {
    val ios = IosTheme.palette
    var draft by remember(persona.id) { mutableStateOf(persona) }
    var emojiPickerOpen by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ios.groupedBackground)
                .padding(top = 14.dp)
        ) {
            // header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (persona.builtIn) "编辑人设" else "编辑人设",
                    style = IosTheme.typography.title3,
                    color = ios.label,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    AppIcons.Close,
                    contentDescription = "关闭",
                    tint = ios.secondaryLabel,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onDismiss),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                // avatar + name
                FieldLabel("头像（可传照片）")
                AvatarEditor(
                    emoji = draft.avatarEmoji,
                    colorIndex = draft.avatarColor,
                    imagePath = draft.avatarImage,
                    emojis = avatarEmojis,
                    pickerPrefix = "persona",
                    onEmoji = { draft = draft.copy(avatarEmoji = it) },
                    onColor = { draft = draft.copy(avatarColor = it) },
                    onImage = { draft = draft.copy(avatarImage = it) },
                )
                Spacer(Modifier.height(14.dp))
                FieldLabel("昵称")
                InputField(
                    value = draft.name,
                    placeholder = "给它起个名字",
                    onChange = { draft = draft.copy(name = it) },
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("一句话人设")
                InputField(
                    value = draft.tagline,
                    placeholder = "例如：有脾气的朋友，会顶嘴",
                    onChange = { draft = draft.copy(tagline = it) },
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("系统提示词（决定它是谁）")
                InputField(
                    value = draft.systemPrompt,
                    placeholder = "留空则使用默认助手提示词。写清身份、语气、边界。",
                    singleLine = false,
                    onChange = { draft = draft.copy(systemPrompt = it) },
                )

                Spacer(Modifier.height(16.dp))
                // human mind
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("拟人思维", style = IosTheme.typography.body, color = ios.label)
                            Spacer(Modifier.width(6.dp))
                            IosPill("实验", ios.orange)
                        }
                        Text(
                            "让它把自己当人、有情绪和立场，而不是工具",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                    IosSwitch(
                        checked = draft.humanMind,
                        onCheckedChange = { on -> draft = draft.copy(humanMind = on) },
                    )
                }
                if (draft.humanMind) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("强度", style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(44.dp))
                        IosSlider(
                            value = draft.humanMindIntensity,
                            onValueChange = { v -> draft = draft.copy(humanMindIntensity = v) },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                            activeColor = ios.orange,
                        )
                        Text(
                            String.format(java.util.Locale.US, "%.2f", draft.humanMindIntensity),
                            style = IosTheme.typography.caption1,
                            color = ios.label,
                            modifier = Modifier.width(44.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("音色")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(VoiceCatalog.all.size) { i ->
                        val v = VoiceCatalog.all[i]
                        val sel = draft.voiceId == v.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (sel) ios.blue else ios.fillSecondary)
                                .clickable { draft = draft.copy(voiceId = v.id) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                v.label,
                                style = IosTheme.typography.footnote,
                                color = if (sel) ios.onAccent else ios.label,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("温度（留空跟随全局）")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosSlider(
                        value = draft.temperature ?: 0.7f,
                        onValueChange = { v -> draft = draft.copy(temperature = v) },
                        valueRange = 0f..1.5f,
                        modifier = Modifier.weight(1f),
                        activeColor = ios.purple,
                    )
                    Text(
                        draft.temperature?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "默认",
                        style = IosTheme.typography.caption1,
                        color = ios.label,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        "清除",
                        style = IosTheme.typography.caption1,
                        color = ios.blue,
                        modifier = Modifier.clickable { draft = draft.copy(temperature = null) },
                    )
                }

                Spacer(Modifier.height(18.dp))
            }

            // actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!persona.builtIn) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(ios.red.copy(alpha = 0.14f))
                            .clickable {
                                viewModel.deletePersona(persona.id)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Delete, contentDescription = null, tint = ios.red, modifier = Modifier.size(16.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(ios.blue)
                        .clickable {
                            val saved = if (draft.name.isBlank()) draft.copy(name = "未命名") else draft
                            // 默认写进全局人设库；对话详情会传自己的 onSave 拦截掉，
                            // 否则"改这个对话的 AI"会顺手在库里造一个重复人设。
                            if (onSave != null) {
                                onSave(saved)
                            } else {
                                viewModel.upsertPersona(saved)
                                viewModel.selectPersona(saved.id)
                            }
                            viewModel.toast("已保存：${saved.name}")
                            onDismiss()
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("保存并使用", style = IosTheme.typography.headline, color = ios.onAccent)
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val ios = IosTheme.palette
    Text(text, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun InputField(
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 0.dp else 84.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ios.fillSecondary)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = IosTheme.typography.footnote,
                color = ios.tertiaryLabel,
                maxLines = if (singleLine) 1 else 4,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = LocalTextStyle.current.merge(IosTheme.typography.footnote).copy(color = ios.label),
            cursorBrush = SolidColor(ios.blue),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 10,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Create a blank persona with a random-ish identity to edit. */
fun newPersona(): Persona = Persona(
    id = TextUtils.randomId("persona"),
    name = "",
    tagline = "",
    avatarEmoji = avatarEmojis.random(),
    avatarColor = (0..8).random(),
)

/**
 * Avatar bubble. Renders a user-picked photo when [imagePath] points at a real file,
 * otherwise the emoji. The emoji is never cleared, so removing a photo degrades to the
 * emoji instead of an empty circle.
 */
@Composable
fun PersonaAvatar(
    emoji: String,
    colorIndex: Int,
    size: Int = 44,
    imagePath: String = "",
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    val file = remember(imagePath) { imagePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) } }
    val bitmap = remember(file?.absolutePath) {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(ios.accents[colorIndex % ios.accents.size].copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(999.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(
                emoji,
                style = if (size >= 44) IosTheme.typography.title3 else IosTheme.typography.footnote.copy(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }
    }
}

/**
 * Avatar chooser: the current bubble (tap to pick a photo), a large emoji grid, a colour
 * row, and a "use emoji instead" escape hatch.
 */
@Composable
fun AvatarEditor(
    emoji: String,
    colorIndex: Int,
    imagePath: String,
    emojis: List<String>,
    pickerPrefix: String,
    onEmoji: (String) -> Unit,
    onColor: (Int) -> Unit,
    onImage: (String) -> Unit,
    size: Int = 72,
) {
    val ios = IosTheme.palette
    val storage = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberAvatarPicker(pickerPrefix, onImage)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                PersonaAvatar(emoji, colorIndex, size = size, imagePath = imagePath)
                // small camera badge so it is obvious the avatar is tappable
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Add,
                        contentDescription = "选择照片",
                        tint = ios.onAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(ios.blue.copy(alpha = 0.12f))
                        .clickable { pick() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("从相册选择照片", style = IosTheme.typography.footnote, color = ios.blue)
                }
                if (imagePath.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "改用 emoji",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                        modifier = Modifier.clickable {
                            AvatarStore.delete(imagePath)
                            onImage("")
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ios.secondaryGroupedBackground)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(emojis) { e ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (imagePath.isBlank() && emoji == e) ios.blue.copy(alpha = 0.20f)
                            else Color.Transparent
                        )
                        .clickable {
                            AvatarStore.delete(imagePath)
                            onImage("")
                            onEmoji(e)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = IosTheme.typography.title3) }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ios.accents.size) { i ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.accents[i])
                        .border(
                            width = if (colorIndex == i) 2.dp else 0.dp,
                            color = ios.label,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .clickable { onColor(i) }
                )
            }
        }
    }
}
