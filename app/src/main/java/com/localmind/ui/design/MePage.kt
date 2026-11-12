package com.localmind.ui.design

import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.BuildConfig
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.pressScale
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.screen.AboutScreen
import com.localmind.ui.screen.ApiScreen
import com.localmind.ui.screen.PersonaAvatar
import com.localmind.ui.screen.PipelineScreen
import com.localmind.ui.screen.ThemeScreen
import com.localmind.ui.theme.IosTheme

private enum class MeRoute { THEME, API, PIPELINE, ABOUT }

/**
 * 「我的」—— 只放普通用户会点的东西。
 *
 * 排序理由：先个人身份，再个性化，最后才是高级。高级里的每一项都是
 * "知道自己在干嘛才点得下去"的功能，所以放在最下面且不写解释。
 */
@Composable
fun MePage(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val profile by viewModel.userProfile.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    var route by remember { mutableStateOf<MeRoute?>(null) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(route) { viewModel.setMeFullPage(route != null) }
    BackHandler(enabled = route != null) { route = null }

    if (route != null) {
        Box(Modifier.fillMaxSize()) {
            when (route) {
                MeRoute.THEME -> ThemeScreen(viewModel, onBack = { route = null })
                MeRoute.API -> ApiScreen(viewModel, onBack = { route = null })
                MeRoute.PIPELINE -> PipelineScreen(viewModel, onBack = { route = null })
                MeRoute.ABOUT -> AboutScreen(
                    onBack = { route = null },
                    onOpenDebug = { viewModel.openDebug(true) },
                    viewModel = viewModel,
                )
                null -> Unit
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(statusBarTopPadding() + 8.dp))
        AuroraTitle(text = "我的")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 108.dp),
        ) {
            item {
                ProfileCard(
                    photo = profile.avatarImage,
                    emoji = profile.avatarEmoji,
                    colorIndex = profile.avatarColor,
                    name = profile.nickname.ifBlank { "我" },
                    subtitle = "",
                    onClick = { editing = true },
                )
            }

            item {
                AuroraSectionLabel("个性化")
                AuroraGroup {
                    AuroraRow(
                        title = "外观",
                        leading = { AuroraIcon(AppIcons.Brush, ios.purple) },
                        onClick = { route = MeRoute.THEME },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "语音工作室",
                        subtitle = "写脚本 · 选音色 · 合成导出",
                        leading = { AuroraIcon(AppIcons.Mic, ios.green) },
                        onClick = { viewModel.openVoiceScreen(true) },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "声音克隆",
                        subtitle = "录一段，复刻成你自己的音色",
                        leading = { AuroraIcon(AppIcons.RecordVoiceOver, ios.pink) },
                        onClick = { viewModel.openVoiceCloneScreen(true) },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "插件",
                        subtitle = plugins.count { it.value.isActive }.toString() + " 个已启用",
                        leading = { AuroraIcon(AppIcons.Extension, ios.orange) },
                        onClick = { viewModel.openPlugins(true) },
                    )
                }
            }

            item {
                AuroraSectionLabel("高级")
                AuroraGroup {
                    AuroraRow(
                        title = "设备状态",
                        subtitle = "CPU / 内存 / 电池 / 模型实时速度",
                        leading = { AuroraIcon(AppIcons.GraphicEq, ios.teal) },
                        onClick = { viewModel.openStatus(true) },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "本机 API",
                        leading = { AuroraIcon(AppIcons.Api, ios.teal) },
                        onClick = { route = MeRoute.API },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "生图",
                        leading = { AuroraIcon(AppIcons.Image, ios.pink) },
                        onClick = { viewModel.openImageStudio(true) },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "分步合成",
                        leading = { AuroraIcon(AppIcons.Bolt, ios.yellow) },
                        onClick = { route = MeRoute.PIPELINE },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "生成参数",
                        leading = { AuroraIcon(AppIcons.Tune, ios.gray) },
                        onClick = { viewModel.openSettings(true) },
                    )
                }
            }

            item {
                AuroraGroup(modifier = Modifier.padding(top = 24.dp)) {
                    AuroraRow(
                        title = "关于 LocalMind",
                        leading = { AuroraIcon(AppIcons.Info, ios.blue) },
                        onClick = { route = MeRoute.ABOUT },
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "LocalMind " + BuildConfig.VERSION_NAME + " · 全部在本机运行",
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (editing) {
        ProfileEditSheet(
            nickname = profile.nickname,
            emoji = profile.avatarEmoji,
            colorIndex = profile.avatarColor,
            photo = profile.avatarImage,
            onDismiss = { editing = false },
            onSave = { name, emoji, color, photoPath ->
                val oldPhoto = profile.avatarImage
                viewModel.updateUserProfile {
                    it.copy(nickname = name, avatarEmoji = emoji, avatarColor = color, avatarImage = photoPath)
                }
                // 换了照片就把旧文件删掉：不删的话每换一次头像就在 files/avatars
                // 里留一张 512px 的 JPEG，一年下来能积出几十 MB 没人认领的图。
                if (oldPhoto.isNotBlank() && oldPhoto != photoPath) {
                    com.localmind.ui.components.AvatarStore.delete(oldPhoto)
                }
                editing = false
            },
        )
    }
}

@Composable
private fun ProfileCard(
    photo: String = "",
    emoji: String,
    colorIndex: Int,
    name: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding, vertical = 12.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Aurora.CardCorner))
            .background(ios.secondaryGroupedBackground)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo.isNotBlank()) {
            val bmp = remember(photo) { android.graphics.BitmapFactory.decodeFile(photo) }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                )
            } else {
                PersonaAvatar(emoji = emoji, colorIndex = colorIndex, size = 56)
            }
        } else {
            PersonaAvatar(emoji = emoji, colorIndex = colorIndex, size = 56)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = IosTheme.typography.title3, color = ios.label)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(
    nickname: String,
    emoji: String,
    colorIndex: Int,
    photo: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String) -> Unit,
) {
    val ios = IosTheme.palette
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(nickname) }
    var pickedEmoji by remember { mutableStateOf(emoji.ifBlank { "😀" }) }
    var pickedColor by remember { mutableStateOf(colorIndex.coerceAtLeast(0)) }
    var pickedPhoto by remember { mutableStateOf(photo) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // 走 AvatarStore：居中裁成方形 + 缩到 512px + JPEG。
            // 直接 copyTo 会把一张 10 MB 的原图留在 filesDir，而且列表里每个
            // 头像都要 decode 一次原图 —— 这是上一版真实存在的内存/磁盘浪费。
            com.localmind.ui.components.AvatarStore.save(context, uri, "user")?.let {
                pickedPhoto = it
            }
        }
    }
    val emojis = listOf("😀", "🙂", "😎", "🐱", "🐶", "🦊", "🐰", "🐼", "🌸", "⭐️", "🍀", "🌙")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ios.secondaryGroupedBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("编辑资料", style = IosTheme.typography.title2, color = ios.label)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pickedPhoto.isNotBlank()) {
                    val bmp = remember(pickedPhoto) { android.graphics.BitmapFactory.decodeFile(pickedPhoto) }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(62.dp).clip(CircleShape),
                        )
                    } else {
                        PersonaAvatar(emoji = pickedEmoji, colorIndex = pickedColor, size = 62)
                    }
                } else {
                    PersonaAvatar(emoji = pickedEmoji, colorIndex = pickedColor, size = 62)
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.fillSecondary)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    if (name.isEmpty()) {
                        Text("你的名字", style = IosTheme.typography.body, color = ios.tertiaryLabel)
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(20) },
                        textStyle = IosTheme.typography.body.copy(color = ios.label),
                        cursorBrush = SolidColor(ios.blue),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "从相册选择照片",
                    style = IosTheme.typography.footnote,
                    color = ios.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(ios.blue.copy(alpha = 0.10f))
                        .clickable { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                if (pickedPhoto.isNotBlank()) {
                    Text(
                        "清除照片",
                        style = IosTheme.typography.footnote,
                        color = ios.red,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { pickedPhoto = "" }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("头像", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emojis.take(6).forEach { e ->
                    EmojiChip(e, e == pickedEmoji) { pickedEmoji = e }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emojis.drop(6).forEach { e ->
                    EmojiChip(e, e == pickedEmoji) { pickedEmoji = e }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("颜色", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ios.accents.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = if (index == pickedColor) 1f else 0.25f))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { pickedColor = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == pickedColor) {
                            Icon(
                                AppIcons.Check,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            AuroraButton(
                text = "保存",
                onClick = { onSave(name.trim(), pickedEmoji, pickedColor, pickedPhoto) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmojiChip(value: String, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ios.blue.copy(alpha = 0.14f) else ios.fillSecondary)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(value, style = IosTheme.typography.title3)
    }
}
