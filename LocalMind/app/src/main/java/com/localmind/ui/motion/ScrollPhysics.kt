package com.localmind.ui.motion

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable

/**
 * 全局滚动物理。
 *
 * ## 为什么不自己实现 FlingBehavior
 *
 * Compose 1.7 里：
 *  * `ScrollableDefaults.flingBehavior()` **不接受参数** —— 只能拿默认物理；
 *  * `DefaultFlingBehavior` 是 **internal** —— 拿不到；
 *  * 公开的 `FlingBehavior` 接口虽然能自己实现，但要正确复现
 *    "按速度积分 + 边界回弹 + 取消处理"这套逻辑，代码量不小，
 *    而且很容易写出微妙的滚动 bug（松手时机不对会跳一下）。
 *
 * 滚动手感是**最不该自己造**的地方：错了很难被发现，但用户天天摸。
 * 所以这里老老实实用官方实现，只把它**收口到一个入口**，
 * 以后要换物理时改一处而不是改二十四处。
 *
 * ## 真正提升流畅度的地方在别处
 *
 *  * 滚动惯性/减速：官方默认已经调过，不需要动；
 *  * **切页不跳回顶部**（见 `rememberScrollStatePreserved`）—— 这个体感差别最大；
 *  * 列表项错峰入场（见 `Motion.appearIn`）；
 *  * 页面转场（见 `Motion.gentle`）。
 */
object ScrollPhysics {

    /**
     * 全应用统一的 fling 行为。
     *
     * 用法：`LazyColumn(flingBehavior = ScrollPhysics.fling())`。
     *
     * 放在这里而不是各处直接用 `ScrollableDefaults.flingBehavior()`，
     * 是为了让"以后要换滚动物理"只需要改一个文件。
     */
    @Composable
    fun fling(): FlingBehavior = ScrollableDefaults.flingBehavior()
}
