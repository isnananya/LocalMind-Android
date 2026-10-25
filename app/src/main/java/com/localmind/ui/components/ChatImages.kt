package com.localmind.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localmind.data.ChatImageStore
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.theme.IosTheme
import java.io.File

/**
 * 聊天里两类图片的渲染 + 全屏查看 + 保存/分享。
 *
 * ## 为什么要单独一个文件，而不是写在 ChatScreen 里
 *
 * 用户提过一句很关键的话："把 ai 发图和我发图的逻辑和界面、气泡 ui 完善"。
 * 这里面的难点**不在画图**，而在三件事：
 *
 *  1. **区分是谁的图**。AI 画的和你发的是两种东西：前者是"它想给你看的"，
 *     后者是"你给它看的"。混在一起之后，过几轮对话人就分不清哪张是自己发的，
 *     而这两种图该有的操作也完全不同（保存 vs 撤回）。
 *  2. **图片属于消息，不属于界面**。所以路径存在 `ChatMessage` 里
 *     （`imagePath` / `generatedImagePath`），落盘在
 *     [com.localmind.data.ChatImageStore]。界面只负责画。
 *  3. **列表里不能解码原图**。几十张 1600px 的图一起解码会直接 OOM，
 *     所以统一走 [ChatImageStore.decodeScaled] 按需采样。
 */

// ---------------------------------------------------------------- 加载

/**
 * 从消息里的路径加载一张位图，**按显示宽度采样**。
 *
 * @param maxWidth 这张图最多会画多宽（px）。用它算采样率，避免解码整张原图。
 */
@Composable
private fun rememberMessageBitmap(path: String, maxWidth: Dp): Bitmap? {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = remember(maxWidth, density) { (maxWidth.value * density.density).toInt() }
    return remember(path, px) {
        ChatImageStore.decodeScaled(path, maxWidth = px.coerceAtLeast(64))
    }
}

// ---------------------------------------------------------------- 用户发的图

/**
 * 用户附在消息里的图片。
 *
 * 视觉上刻意和 AI 的图不一样：
 *  * 贴在自己的气泡上方，圆角**和自己的气泡一致**（同一"坨"内容）；
 *  * 不加"它画的"那类标签 —— 它在右边，位置本身就说明了是谁发的；
 *  * 点击放大（用户常常想看清自己发了什么）。
 */
@Composable
fun UserMessageImage(
    path: String,
    maxWidth: Dp = 220.dp,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    ImageCard(
        path = path,
        maxWidth = maxWidth,
        corner = IosTheme.metrics.bubbleCorner,
        // 用户气泡没有单独的描边令牌，用气泡底色压暗一点就是它的边界 ——
        // 比写死一个灰色好：底色换了，描边跟着换。
        borderColor = ios.bubbleUser.copy(alpha = 0.35f),
        onClick = onClick,
    )
}

// ---------------------------------------------------------------- AI 画的图

/**
 * AI 自己画出来的图。
 *
 * 和用户那张的区别必须**一眼可见**，否则几轮之后人就分不清了。所以：
 *  * 左上角一行醒目的出处标记（画笔 + AI 的名字/"它画的"）；
 *  * 右侧一个"保存"按钮 —— 这才是 AI 图画完之后最自然的下一步；
 *  * 图下面跟一句提示词，让人知道它是照着什么画的。
 */
@Composable
fun AiMessageImage(
    path: String,
    maxWidth: Dp = 260.dp,
    label: String = "它画的",
    prompt: String? = null,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.widthIn(max = maxWidth)) {
        // 出处那一行：图在上、标记在下会让人以为标记属于下一段文字，
        // 所以放在图**上方**，和图片是一个视觉整体。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ios.pink.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Brush,
                    contentDescription = null,
                    tint = ios.pink,
                    modifier = Modifier.size(11.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = IosTheme.typography.caption1,
                color = ios.pink,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onSave)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Download,
                    contentDescription = null,
                    tint = ios.blue,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("保存", style = IosTheme.typography.caption2, color = ios.blue)
            }
        }

        ImageCard(
            path = path,
            maxWidth = maxWidth,
            corner = IosTheme.metrics.cardCorner,
            borderColor = ios.separator,
            onClick = onClick,
        )

        prompt?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(5.dp))
            Text(
                it,
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
                maxLines = 2,
            )
        }
    }
}

/** 两种图片共用的画法：圆角卡片 + 按比例高度 + 点击放大。 */
@Composable
private fun ImageCard(
    path: String,
    maxWidth: Dp,
    corner: Dp,
    borderColor: Color,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    val bitmap = rememberMessageBitmap(path, maxWidth)
    val shape = RoundedCornerShape(corner)

    if (bitmap == null) {
        // 图片文件没了（用户清了数据、或删除时被清掉）。**不要留一块空白**，
        // 那看起来像界面坏了；明说一句，用户才知道是图丢了。
        Box(
            modifier = Modifier
                .size(width = maxWidth, height = 90.dp)
                .clip(shape)
                .background(ios.fill)
                .border(0.5.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AppIcons.Image,
                    contentDescription = null,
                    tint = ios.tertiaryLabel,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text("图片已不在本机", style = IosTheme.typography.caption2, color = ios.tertiaryLabel)
            }
        }
        return
    }

    // 按原始比例算高度，避免方形裁剪切掉内容（用户发的是截图/文档照片时尤其明显）。
    val ratio = if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width else 0.75f
    val clamped = ratio.coerceIn(0.5f, 1.6f)
    val width = maxWidth
    val height = width * clamped

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .border(0.5.dp, borderColor, shape)
            .clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------- 全屏查看

/**
 * 全屏看图。点任意处关闭，上排一个"保存"、一个"分享"。
 *
 * ## 为什么不做双指缩放
 *
 * 双指缩放要自己接变换手势 + 边界回弹，写不好会出现"图飞到屏幕外拉不回来"
 * 这种比不能缩放更糟的状态。而这里的需求其实很简单：**看清一张图**。
 * 所以用 `ContentScale.Fit` 把整张图铺满可用区域，配合深色背景，
 * 已经在手机上提供了足够的可读性；真要细看可以保存下来用系统相册看。
 */
@Composable
fun FullscreenImage(
    path: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var barVisible by remember { mutableStateOf(true) }
    val bitmap = remember(path) {
        // 全屏才解码到屏宽，列表里不这么干。
        ChatImageStore.decodeScaled(path, maxWidth = 1440)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { barVisible = !barVisible },
                    onDoubleTap = { onDismiss() },
                )
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 72.dp),
            )
        } else {
            Text(
                "图片已不在本机",
                style = IosTheme.typography.body,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = barVisible,
            enter = fadeIn(Motion.smooth(160)),
            exit = fadeOut(Motion.smooth(140)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = com.localmind.ui.components.statusBarTopPadding() + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ViewerButton(
                    icon = AppIcons.Close,
                    label = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                ViewerButton(
                    icon = AppIcons.Download,
                    label = "保存",
                    onClick = {
                        val ok = saveImageToGallery(context, File(path))
                        Toast.makeText(
                            context,
                            if (ok) "已保存到相册" else "保存失败",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                )
                ViewerButton(
                    icon = AppIcons.Share,
                    label = "分享",
                    onClick = { shareImage(context, File(path)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ViewerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
            .background(Color(0x59FFFFFF))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = IosTheme.typography.caption2, color = Color.White)
    }
}

// ---------------------------------------------------------------- 保存 / 分享

/**
 * 保存到系统相册。
 *
 * API 29+ 用 MediaStore 的 `RELATIVE_PATH`（不需要任何存储权限）；
 * 更老的版本没有这个字段，只能写公共目录，那时需要权限 —— 所以老版本
 * 直接返回 false 并让界面说"保存失败"，而不是崩在权限上。
 *
 * 用 `IS_PENDING` 标记：写完之前别让相册扫到半张图。
 */
fun saveImageToGallery(context: Context, file: File): Boolean {
    if (!file.isFile) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return shareImage(context, file)
    }
    return runCatching {
        val name = "LocalMind_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalMind")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        true
    }.getOrElse {
        Log.w("ChatImages", "保存到相册失败：${it.message}")
        false
    }
}

/**
 * 分享一张图。
 *
 * 走 `FileProvider`：直接把 `file://` 传给别的应用在 API 24+ 会抛
 * `FileUriExposedException`，必须有 content:// 授权。
 */
fun shareImage(context: Context, file: File): Boolean = runCatching {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享图片"))
    true
}.getOrElse {
    Log.w("ChatImages", "分享失败：${it.message}")
    false
}
