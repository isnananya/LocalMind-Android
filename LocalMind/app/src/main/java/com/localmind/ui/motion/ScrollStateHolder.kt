package com.localmind.ui.motion

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.remember

/**
 * 跨页面切换保留滚动位置。
 *
 * ## 问题
 *
 * 根层用 `AnimatedContent` 做页面转场。`AnimatedContent` 会**销毁**离开的
 * 页面内容（这正是转场动画能跑的前提），所以页面里 `rememberLazyListState()`
 * 拿到的状态也跟着没了 —— 切到「模型」再切回「对话」，列表跳回顶部。
 *
 * 这个体感很差：用户明明记得自己滑到了哪，回来却要重新滑一遍。
 *
 * ## 做法
 *
 * 把 `LazyListState` **提到页面之外**保存（按页面 key 索引），
 * 切回来时把同一个 state 对象交给列表。
 *
 * 为什么能工作：`LazyListState` 本身是普通对象，持有滚动位置。
 * 只要不销毁它，位置就还在。列表重新组合时只要 `state` 是同一个对象，
 * 就接着上次的滚动位置画。
 *
 * ## 实现要点
 *
 * `rememberLazyListState()` 只能在**组合期**直接调用，而 `remember(key) { ... }`
 * 的计算块不是组合期（它是普通 lambda），所以不能在 `getOrPut` 里就地创建。
 *
 * 正确做法是用 `composeKey(key) { ... }` 给每个 key 划出**独立的组合槽位**：
 * 在这个槽位里 `rememberLazyListState()` 拿到的就是该 key 专属的状态实例，
 * 不同 key 之间不会串。
 *
 * 顺带的好处：`rememberLazyListState` 内部用的是 `rememberSaveable`，
 * 所以配置变化（旋转屏幕）时位置也能恢复。
 *
 * ## 注意
 *
 * 状态对象会持有整个列表的可见项信息，所以**不要**无限增长。
 * 这里用 `remember` 挂在根层，根层不销毁就一直有效；页面数量有限，
 * 几个 key 完全够用。
 */
@Composable
fun rememberScrollStateHolder(): ScrollStateHolder {
    return remember { ScrollStateHolder() }
}

/** 滚动状态仓库。按 key 存 [LazyListState]。 */
class ScrollStateHolder {

    private val states = HashMap<String, LazyListState>()

    /**
     * 拿到（或首次创建）某个 key 的滚动状态。
     *
     * @param key 页面标识，比如 "chat-list" / "models" / "me"。
     */
    @Composable
    fun state(key: String): LazyListState {
        // composeKey 给每个 key 独立的组合槽位：
        // 槽位内 rememberLazyListState() 拿到的实例只属于这个 key。
        return composeKey(key) {
            val created = rememberLazyListState()
            remember(key) {
                states.getOrPut(key) { created }
            }
        }
    }

    /** 清掉某个 key 的状态（数据变了、列表重建时用）。 */
    fun reset(key: String) {
        states.remove(key)
    }
}
