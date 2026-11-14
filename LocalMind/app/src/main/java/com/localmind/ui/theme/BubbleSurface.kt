package com.localmind.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * 气泡的"材质"层。
 *
 * 形状（[BubbleShape]）决定轮廓，这里决定**表面**。用户要的不是"再换几个颜色"，
 * 而是 QQ / iOS 那种一眼能看出材质差别的气泡：
 *
 *  * `solid`    纯色（默认，最省）
 *  * `gradient` 双色渐变
 *  * `frost`    磨砂玻璃：半透明 + 顶部高光 + 底部暗边。底下是极光/壁纸时
 *               能透出颜色，看起来像磨砂膜，而不是一块白饼干
 *  * `liquid`   液体折射玻璃：强镜面高光（椭圆）+ 双侧反光 + 亮边。
 *               高光位置固定在左上，模拟光源，所以无论气泡多宽都像同一块玻璃
 *  * `neon`     发光：深色底 + 亮描边 + 彩色投影
 *  * `paper`    纸感：轻微上亮下暗
 *
 * 为什么不用真正的背景模糊（RenderEffect）：`Modifier.blur` 模糊的是**自己**，
 * 把文字一起糊掉；Compose 至今没有跨平台的后景模糊。用"半透明 + 高光"
 * 模拟玻璃，在所有 Android 版本上表现一致，也不会因为 GPU 差异变成一坨灰。
 */
@Composable
fun BubbleSurface(
    shape: Shape,
    material: String,
    base: Color,
    alt: Color?,
    borderColor: Color?,
    borderWidth: Float,
    shadow: Float,
    gloss: Float,
    modifier: Modifier = Modifier,
    padding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    val isFrost = material == "frost"
    val isLiquid = material == "liquid"
    val isNeon = material == "neon"

    val fill: Brush = when {
        isFrost -> Brush.verticalGradient(
            listOf(base.copy(alpha = 0.58f), base.copy(alpha = 0.30f)),
        )
        isLiquid -> Brush.linearGradient(
            listOf(
                base.copy(alpha = 0.62f),
                base.copy(alpha = 0.24f),
                base.copy(alpha = 0.44f),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
        material == "gradient" && alt != null -> Brush.linearGradient(listOf(base, alt))
        else -> SolidColor(base)
    }

    val shine = gloss.coerceIn(0f, 1f)

    Box(
        modifier
            .then(
                if (shadow > 0f) {
                    Modifier.shadow(
                        elevation = shadow.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = if (isNeon && borderColor != null) borderColor.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.4f),
                        spotColor = if (isNeon && borderColor != null) borderColor.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.5f),
                    )
                } else Modifier
            )
            .clip(shape)
            .drawBehind {
                drawRect(fill)

                if (isFrost) {
                    // 顶部高光带：磨砂膜的"迎光面"
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.26f * shine),
                            0.55f to Color.White.copy(alpha = 0.05f * shine),
                            1f to Color.Transparent,
                        ),
                    )
                    // 底部边缘有一点暗，制造厚度
                    drawRect(
                        Brush.verticalGradient(
                            0.7f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.10f),
                        ),
                    )
                }

                if (isLiquid) {
                    // 镜面高光：椭圆，压在左上角，超出边界的那部分被 clip 裁掉
                    val r = size.minDimension * 1.15f
                    val c = Offset(size.width * 0.18f, -r * 0.28f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f * shine),
                                Color.White.copy(alpha = 0.16f * shine),
                                Color.Transparent,
                            ),
                            center = c,
                            radius = r,
                        ),
                        radius = r,
                        center = c,
                    )
                    // 右下角的二次反光（玻璃边缘的厚度感）
                    val r2 = size.minDimension * 0.9f
                    val c2 = Offset(size.width * 1.02f, size.height * 1.05f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f * shine),
                                Color.Transparent,
                            ),
                            center = c2,
                            radius = r2,
                        ),
                        radius = r2,
                        center = c2,
                    )
                    // 横向的细微色散：左冷右暖，玻璃折射的常见观感
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0x334FC3FF),
                                Color.Transparent,
                                Color(0x22FF9BD2),
                            ),
                        ),
                    )
                }
            }
            .then(
                if (borderWidth > 0f && borderColor != null) {
                    val bc = when {
                        isLiquid -> borderColor.copy(alpha = 0.75f)
                        isFrost -> borderColor.copy(alpha = 0.65f)
                        else -> borderColor
                    }
                    Modifier.border(borderWidth.dp, bc, shape)
                } else Modifier
            )
            .padding(padding),
        content = content,
    )
}

/**
 * 从令牌表里解出一份"气泡外观"，聊天页和主题商店的预览共用。
 *
 * 之所以抽出来：预览如果自己写一套取色逻辑，两边一定会走样 ——
 * 商店里是亮面、聊天页是磨砂，用户会觉得被耍了。
 */
data class BubbleLook(
    val kind: String,
    val material: String,
    val corner: Float,
    val tail: Float,
    val borderWidth: Float,
    val shadow: Float,
    val gloss: Float,
    val base: Color,
    val alt: Color?,
    val border: Color?,
    val text: Color,
)

@Composable
fun bubbleLookForUser(tokens: Map<String, String>, fallback: Color, fallbackText: Color): BubbleLook =
    bubbleLook(tokens, user = true, fallback, fallbackText)

@Composable
fun bubbleLookForAi(tokens: Map<String, String>, fallback: Color, fallbackText: Color): BubbleLook =
    bubbleLook(tokens, user = false, fallback, fallbackText)

@Composable
private fun bubbleLook(
    tokens: Map<String, String>,
    user: Boolean,
    fallback: Color,
    fallbackText: Color,
): BubbleLook {
    fun hex(key: String): Color? = tokens[key]
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { parseHexColor(it) }.getOrNull() }
    val base = hex(if (user) "bubbleUser" else "bubbleAi") ?: fallback
    val alt = hex(if (user) "bubbleUserAlt" else "bubbleAiAlt")
    val border = hex(if (user) "bubbleUserBorder" else "bubbleAiBorder")
    val text = hex(if (user) "bubbleUserText" else "bubbleAiText") ?: fallbackText
    return BubbleLook(
        kind = tokens["bubbleStyle"] ?: "rounded",
        material = tokens["bubbleMaterial"] ?: "solid",
        corner = tokens["bubbleCorner"]?.toFloatOrNull()?.coerceIn(0f, 32f) ?: 18f,
        tail = tokens["bubbleTailSize"]?.toFloatOrNull()?.coerceIn(4f, 20f) ?: 9f,
        borderWidth = tokens["bubbleBorderWidth"]?.toFloatOrNull()?.coerceIn(0f, 4f) ?: 0.5f,
        shadow = tokens["bubbleShadow"]?.toFloatOrNull()?.coerceIn(0f, 12f) ?: 0f,
        gloss = tokens["bubbleGloss"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.6f,
        base = base,
        alt = alt,
        border = border,
        text = text,
    )
}

/** 圆角形状（主题卡、输入框这类小件用）。 */
fun roundedShape(dp: Float): Shape = RoundedCornerShape(dp.dp)
