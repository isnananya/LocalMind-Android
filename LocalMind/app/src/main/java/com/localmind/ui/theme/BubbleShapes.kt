package com.localmind.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 聊天气泡的形状目录。
 *
 * ## 为什么不用 RoundedCornerShape + 一个颜色
 *
 * 上一版气泡只有 3 种（圆角 / 直角 / "尾巴"，而"尾巴"只是把一个角改小），
 * 用户的原话是"纯改颜色的一点也不好看"。QQ 那套气泡之所以一眼能认出，
 * 靠的是**轮廓**：尖尾、胶囊、切角、折纸、像素台阶、邮票齿……
 * 这些用 `RoundedCornerShape` 表达不出来，必须自己画 Path。
 *
 * ## 为什么全部是单个闭合路径
 *
 * 描边（`Modifier.border`）是沿着 outline 路径 stroke 的。如果用"圆角矩形 +
 * 三角形"两个子路径合并，描边会把三角形和矩形的接缝也画出来 —— 气泡中间
 * 凭空多一条线。所以每种形状都必须是**一条连续的路径**，没有内部接缝。
 *
 * ## 用户气泡 / AI 气泡
 *
 * 尾巴永远朝向头像那一侧：用户在右，AI 在左。同一份路径画两遍、AI 那份
 * 水平镜像即可 —— 镜像在坐标计算里做（[x] 函数），不用 Matrix 变换，
 * 避免不同 Compose 版本 Matrix 乘法顺序的差异。
 */
enum class BubbleKind(val id: String) {
    ROUNDED("rounded"),
    SQUARE("square"),
    TAIL("tail"),
    BUBBLE("bubble"),
    CAPSULE("capsule"),
    CUT("cut"),
    NOTE("note"),
    PIXEL("pixel"),
    BLOB("blob"),
    COMIC("comic");

    /** 形状本身占用了尾部空间，内容要额外留出这块 padding。 */
    val hasTail: Boolean get() = this == TAIL || this == BUBBLE || this == BLOB

    /**
     * 尾巴根部宽度 = bubbleTailSize * 这个系数。
     *
     * 正文的右内边距必须 ≥ 它，否则最后一行会钻到尾巴底下。之前这里写的是
     * 一个和形状无关的 0.85，碰到 `bubbleTailSize=20` 的主题（尾巴根部 44dp）
     * 就会压字 —— 改成从形状反推，两边永远不会脱钩。
     */
    val tailFactor: Float
        get() = when (this) {
            TAIL -> 1.35f
            BUBBLE -> 2.2f
            BLOB -> 1.0f
            else -> 0f
        }

    companion object {
        fun of(id: String): BubbleKind =
            entries.firstOrNull { it.id == id.lowercase() } ?: ROUNDED
    }
}

/**
 * 可直接交给 `Modifier.clip/shadow/border/background` 的气泡形状。
 *
 * @param kind 形状 id，见 [BubbleKind]
 * @param cornerDp 圆角（令牌 bubbleCorner）
 * @param tailDp 尾巴大小（令牌 bubbleTailSize）
 * @param isUser true = 尾巴在右（用户），false = 尾巴在左（AI）
 */
class BubbleShape(
    kind: String,
    private val cornerDp: Float,
    private val tailDp: Float,
    private val isUser: Boolean,
) : Shape {

    private val kind = BubbleKind.of(kind)

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return Outline.Rectangle(Rect(0f, 0f, w, h))

        val c = with(density) { cornerDp.dp.toPx() }.coerceIn(0f, min(w, h) / 2f)
        val tail = with(density) { tailDp.dp.toPx() }
            .coerceIn(0f, min(w / 2f, h / 2.6f))

        val path = Path()

        // AI 气泡 = 用户气泡的镜像。所有 x 坐标都过一遍 x()。
        fun x(v: Float): Float = if (isUser) v else w - v

        when (kind) {
            BubbleKind.ROUNDED -> roundedRect(path, w, h, c, c, c, c, ::x)
            BubbleKind.SQUARE -> {
                path.moveTo(x(0f), 0f)
                path.lineTo(x(w), 0f)
                path.lineTo(x(w), h)
                path.lineTo(x(0f), h)
                path.close()
            }
            BubbleKind.CAPSULE -> {
                val r = min(w, h) / 2f
                roundedRect(path, w, h, r, r, r, r, ::x)
            }
            // 尾巴类：宽度系数统一走 BubbleKind.tailFactor，正文 padding 也用同一个值
            BubbleKind.TAIL -> tailedRect(path, w, h, c, tail, kind.tailFactor, 2.6f, ::x)
            BubbleKind.BUBBLE -> tailedRect(path, w, h, c * 0.7f, tail, kind.tailFactor, 3.1f, ::x)
            BubbleKind.BLOB -> tailedRect(
                path, w, h,
                // 果冻必须"天生就圆"，但滑块也得有点用：下限 0.30h，上限 0.48h
                min(h * 0.48f, max(c * 1.4f, h * 0.30f)),
                tail, kind.tailFactor, 2.2f,
                ::x,
            )
            BubbleKind.CUT -> cutRect(
                path, w, h,
                ch = max(c * 0.85f, with(density) { 10.dp.toPx() }),
                r = c * 0.5f,
                x = ::x,
            )
            BubbleKind.NOTE -> noteRect(path, w, h, c, max(c, with(density) { 13.dp.toPx() }), ::x)
            BubbleKind.PIXEL -> pixelRect(
                path, w, h,
                max(c, with(density) { 6.dp.toPx() }),
                ::x,
            )
            BubbleKind.COMIC -> comicRect(
                path, w, h,
                depth = with(density) { 2.4.dp.toPx() },
                seg = with(density) { 6.dp.toPx() },
                x = ::x,
            )
        }
        return Outline.Generic(path)
    }
}

// ---------------------------------------------------------------- 各形状的路径

/**
 * 普通圆角矩形（四个角可不同）。
 *
 * 用二次贝塞尔近似 1/4 圆 —— 半径不大时肉眼看不出来，
 * 但少一堆 arcTo 的角度换算。
 */
private fun roundedRect(
    p: Path,
    w: Float,
    h: Float,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float,
    x: (Float) -> Float,
) {
    p.moveTo(x(tl), 0f)
    p.lineTo(x(w - tr), 0f)
    if (tr > 0f) p.quadraticBezierTo(x(w), 0f, x(w), tr) else p.lineTo(x(w), 0f)
    p.lineTo(x(w), h - br)
    if (br > 0f) p.quadraticBezierTo(x(w), h, x(w - br), h) else p.lineTo(x(w), h)
    p.lineTo(x(bl), h)
    if (bl > 0f) p.quadraticBezierTo(x(0f), h, x(0f), h - bl) else p.lineTo(x(0f), h)
    p.lineTo(x(0f), tl)
    if (tl > 0f) p.quadraticBezierTo(x(0f), 0f, x(tl), 0f) else p.lineTo(x(0f), 0f)
    p.close()
}

/**
 * 带尾巴的圆角矩形（一条连续路径）。
 *
 * ## 尾巴为什么长在**右边缘靠下**，而不是从底边伸出去
 *
 * 上一版是从底边甩出去一个三角形，结果那个三角形吃掉了气泡 1/3 的高度，
 * 看着像"右下角被削掉一块"。房间是有限的：形状不能超出布局边界（超了会被
 * clip 裁掉），所以尾巴只能长在身体内部。
 *
 * 现在身体宽度让出 `tw`，尾巴是从右边缘 `h - th` 处向外甩到 `(w, h - th*0.42)`
 * 的楔形，再内凹回身体下沿 —— 这就是 QQ / 微信那种"尖角朝头像"的读法，
 * 而且只占右下角一小块，不抢正文。
 *
 * @param twFactor 尾巴根部宽度 = tail * twFactor
 * @param thFactor 尾巴高度     = tail * thFactor
 */
private fun tailedRect(
    p: Path,
    w: Float,
    h: Float,
    c: Float,
    tail: Float,
    twFactor: Float,
    thFactor: Float,
    x: (Float) -> Float,
) {
    val tw = (tail * twFactor).coerceIn(0f, w * 0.42f)
    val th = (tail * thFactor).coerceIn(0f, h * 0.62f)
    if (tw <= 0.5f || th <= 0.5f) {
        roundedRect(p, w, h, c, c, c, c, x)
        return
    }
    val body = w - tw                      // 身体右边缘
    val cc = min(c, (h - th * 0.1f) / 2f)  // 圆角不能超过身体高度

    p.moveTo(x(cc), 0f)
    p.lineTo(x(body - cc), 0f)
    p.quadraticBezierTo(x(body), 0f, x(body), cc)
    p.lineTo(x(body), h - th)                          // 右边缘下到尾巴上沿
    p.lineTo(x(w), h - th * 0.42f)                     // 甩到尖
    p.quadraticBezierTo(                               // 下沿内凹回身体
        x(body + tw * 0.18f), h - th * 0.02f,
        x(body), h - th * 0.02f,
    )
    p.lineTo(x(body), h)                               // 落下底边（尖角，不圆）
    p.lineTo(x(cc), h)
    p.quadraticBezierTo(x(0f), h, x(0f), h - cc)
    p.lineTo(x(0f), cc)
    p.quadraticBezierTo(x(0f), 0f, x(cc), 0f)
    p.close()
}

/** 斜切：右上角一刀切掉，其余三角轻圆。 */
private fun cutRect(p: Path, w: Float, h: Float, ch: Float, r: Float, x: (Float) -> Float) {
    val chh = ch.coerceAtMost(min(w, h) * 0.6f)
    val rr = min(r, chh * 0.6f)
    p.moveTo(x(rr), 0f)
    p.lineTo(x(w - chh), 0f)
    p.lineTo(x(w), chh)
    p.lineTo(x(w), h - rr)
    p.quadraticBezierTo(x(w), h, x(w - rr), h)
    p.lineTo(x(rr), h)
    p.quadraticBezierTo(x(0f), h, x(0f), h - rr)
    p.lineTo(x(0f), rr)
    p.quadraticBezierTo(x(0f), 0f, x(rr), 0f)
    p.close()
}

/**
 * 便签：右下角挖掉一个方块，做出"折角被翻到背面"的读法。
 *
 * 上一版是斜切 + 一个 0.18 的小折返，在真机上跟圆角矩形没区别。
 * 现在改成实打实的阶梯缺口 —— 形状本身就把那个角让出来了，一眼能看出来。
 */
private fun noteRect(p: Path, w: Float, h: Float, c: Float, fold: Float, x: (Float) -> Float) {
    val f = fold.coerceIn(0f, min(w * 0.42f, h * 0.62f))
    val cc = min(c, (h - f) / 2f)
    p.moveTo(x(cc), 0f)
    p.lineTo(x(w - cc), 0f)
    p.quadraticBezierTo(x(w), 0f, x(w), cc)
    p.lineTo(x(w), h - f)
    p.lineTo(x(w - f), h - f)      // 往内折
    p.lineTo(x(w - f), h)          // 落到下边缘
    p.lineTo(x(cc), h)
    p.quadraticBezierTo(x(0f), h, x(0f), h - cc)
    p.lineTo(x(0f), cc)
    p.quadraticBezierTo(x(0f), 0f, x(cc), 0f)
    p.close()
}

/**
 * 像素台阶：四个角各 n 级楼梯。
 *
 * 楼梯必须"走一步、再走一步"，每级同时推进 dx 和 dy。上一版右下 / 左下 /
 * 左上三个角的循环把顺序写反了，会出现从底边直接斜切到半空的线段 ——
 * 在真机上是几根飞出气泡的尖刺。这里统一交给 [stair] 处理。
 */
private fun pixelRect(p: Path, w: Float, h: Float, sRaw: Float, x: (Float) -> Float) {
    val s = sRaw.coerceIn(0f, min(w, h) / 3f)
    if (s <= 1f) {
        roundedRect(p, w, h, 0f, 0f, 0f, 0f, x)
        return
    }
    val n = 3
    p.moveTo(x(0f), s)
    stair(p, 0f, s, s, 0f, n, x)                  // 左上
    p.lineTo(x(w - s), 0f)
    stair(p, w - s, 0f, w, s, n, x)               // 右上
    p.lineTo(x(w), h - s)
    stair(p, w, h - s, w - s, h, n, x)            // 右下
    p.lineTo(x(s), h)
    stair(p, s, h, 0f, h - s, n, x)               // 左下
    p.lineTo(x(0f), s)
    p.close()
}

/** 从 (ax, ay) 到 (bx, by) 走 n 级楼梯：每级先横后竖。 */
private fun stair(
    p: Path,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    n: Int,
    x: (Float) -> Float,
) {
    val sx = (bx - ax) / n
    val sy = (by - ay) / n
    for (i in 1..n) {
        p.lineTo(x(ax + sx * i), ay + sy * (i - 1))
        p.lineTo(x(ax + sx * i), ay + sy * i)
    }
}

/**
 * 邮票齿：四条边都向内咬出小圆弧。
 *
 * 齿**向内**而不是向外 —— 路径超出布局边界会被裁掉，向内才看得见，
 * 而且内容区不变，不需要额外 padding。
 *
 * ## 齿宽:齿深 ≈ 2.5:1
 *
 * 第一版齿宽 11px 齿深 2.6px（比例 4:1），在真机上是一条"波浪线"；
 * 第二版把齿距拉到 24dp 反而变成一排大尖刺。真正决定观感的是**宽深比**，
 * 不是密度：比例压到 2.5 左右才有"咬掉一口"的圆齿感。
 */
private fun comicRect(
    p: Path,
    w: Float,
    h: Float,
    depth: Float,
    /** 一个齿的宽度。跟 dp 走，否则高 DPI 上会碎成一排毛边。 */
    seg: Float,
    x: (Float) -> Float,
) {
    val nx = max(4, Math.round(w / seg.coerceAtLeast(1f)))
    val ny = max(3, Math.round(h / seg.coerceAtLeast(1f)))
    val dx = w / nx
    val dy = h / ny
    val dep = depth.coerceAtMost(min(dx, dy) * 0.45f)

    p.moveTo(x(0f), 0f)
    // 上边：向右，齿向下凹
    for (i in 1..nx) {
        p.quadraticBezierTo(x(dx * (i - 0.5f)), dep * 2f, x(dx * i), 0f)
    }
    // 右边：向下
    for (i in 1..ny) {
        p.quadraticBezierTo(x(w - dep * 2f), dy * (i - 0.5f), x(w), dy * i)
    }
    // 下边：向左
    for (i in 1..nx) {
        p.quadraticBezierTo(x(w - dx * (i - 0.5f)), h - dep * 2f, x(w - dx * i), h)
    }
    // 左边：向上
    for (i in 1..ny) {
        p.quadraticBezierTo(x(dep * 2f), h - dy * (i - 0.5f), x(0f), h - dy * i)
    }
    p.close()
}
