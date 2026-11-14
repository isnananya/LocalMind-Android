package com.localmind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.localmind.data.CompanionState
import com.localmind.ui.AppViewModel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 「这个 AI 现在是什么状态」——**按对话显示**。
 *
 * 以前这些放在「我的」页，那是错的：情绪、性格、记忆、经验都是**这段关系**的属性，
 * 而关系是按对话建立的。和「小美」聊工作那段的心情，不该出现在「老陈」那段里。
 * 所以它属于对话详情，不属于全局设置。
 *
 * 记忆与经验分开显示，因为对用户的意义不同：前者是"它记得你"，后者是"它有自己"。
 */
@Composable
fun ConversationInsightSection(
    viewModel: AppViewModel,
    conversationId: String,
) {
    val ios = IosTheme.palette
    val states by viewModel.companionStates.collectAsState()
    val state: CompanionState = states[conversationId] ?: CompanionState(personaId = conversationId)

    IosSection(
        header = "它现在的状态",
        footer = "这些是它自己积累的，不是设置项。清空后它会把这段关系的过去全部忘掉。",
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            TraitBar("亲密度", state.traits.intimacy, ios.pink)
            TraitBar("信任", state.traits.trust, ios.blue)
            TraitBar("熟悉度", state.traits.familiarity, ios.teal)
            TraitBar("话多程度", state.traits.talkativeness, ios.purple)
            TraitBar("主动性", state.traits.initiative, ios.orange)
            TraitBar("情绪外露", state.traits.expressiveness, ios.indigo)
            Spacer(Modifier.height(6.dp))
            Text(
                "当前心情：" + moodText(state.emotion.mood) +
                    "　精力：" + energyText(state.emotion.energy) +
                    "　记忆 ${state.memories.size} 条　经验 ${state.experiences.size} 条　共 ${state.totalTurns} 轮",
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
        }
    }

    if (state.memories.isNotEmpty()) {
        IosSection(
            header = "它记得关于你的事",
            footer = "点一条可以删掉。这些是它从对话里学到的，和它自己的经历分开记。",
        ) {
            state.memories.takeLast(14).reversed().forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.deleteMemory(conversationId, m.id) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosPill(kindLabel(m.kind), ios.gray)
                    Spacer(Modifier.width(8.dp))
                    Text(m.text, style = IosTheme.typography.footnote, color = ios.label, maxLines = 3)
                }
                Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
            }
        }
    }

    if (state.experiences.isNotEmpty() || state.questions.isNotEmpty()) {
        IosSection(
            header = "它自己的积累",
            footer = "这是它作为「一个人」攒下来的东西，不是你告诉它的信息。" +
                "「想问你的」会在合适的时机自己问出来。",
        ) {
            state.experiences.takeLast(8).reversed().forEach { e ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IosPill("经验", ios.indigo)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.text, style = IosTheme.typography.footnote, color = ios.label, maxLines = 3)
                        if (e.takeaway.isNotBlank()) {
                            Text(
                                "看法：${e.takeaway}",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
            }
            if (state.experiences.isNotEmpty() && state.questions.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
            }
            state.questions.takeLast(8).reversed().forEach { q ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IosPill(if (q.timesAsked > 0) "问过" else "没问", ios.orange)
                    Spacer(Modifier.width(8.dp))
                    Text(q.text, style = IosTheme.typography.footnote, color = ios.label, maxLines = 3)
                }
            }
        }
    }

    IosSection(footer = "清空只影响这一个对话，别的对话不受影响。") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.forgetPersona(conversationId) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                AppIcons.Refresh,
                contentDescription = null,
                tint = ios.red,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("清空这段关系的记忆", style = IosTheme.typography.body, color = ios.red)
        }
    }
}

@Composable
private fun TraitBar(label: String, value: Float, color: Color) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.caption1, color = ios.secondaryLabel, modifier = Modifier.width(64.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ios.fillSecondary)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${(value.coerceIn(0f, 1f) * 100).toInt()}",
            style = IosTheme.typography.caption2,
            color = ios.tertiaryLabel,
            modifier = Modifier.width(24.dp),
        )
    }
}

private fun kindLabel(kind: CompanionState.Kind): String = when (kind) {
    CompanionState.Kind.FACT -> "事实"
    CompanionState.Kind.PREFERENCE -> "喜好"
    CompanionState.Kind.EVENT -> "事件"
    CompanionState.Kind.COMMITMENT -> "约定"
    CompanionState.Kind.FEELING -> "情绪"
}

private fun moodText(mood: Float): String = when {
    mood > 0.72f -> "很好"
    mood > 0.58f -> "还不错"
    mood > 0.42f -> "平静"
    mood > 0.28f -> "有点低落"
    else -> "很差"
}

private fun energyText(energy: Float): String = when {
    energy > 0.7f -> "充沛"
    energy > 0.45f -> "一般"
    energy > 0.25f -> "有点累"
    else -> "很疲惫"
}
