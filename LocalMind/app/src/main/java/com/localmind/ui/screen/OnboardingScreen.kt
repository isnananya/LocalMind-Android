package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.ui.AppViewModel
import com.localmind.ui.theme.IosTheme

/**
 * First-run wizard —— **只问"你是谁"这一件事**。
 *
 * ## 为什么这里不再配置 AI 对话
 *
 * 原来它是三步：你是谁 → 你想怎么用它（当 AI 用 / 当一个人）→ 它是谁（名字、头像、
 * 年龄、人设）。那三步是"全局只有一个 AI 身份"那个时代的产物。
 *
 * 现在两件事都变了：
 *  * **对话只有两种，而且是建对话时选的**（AI 对话 / 人设聊天），类型建好不能改；
 *  * **人设、记忆、模型、能力全部是按对话设置的**，在对话自己的设置页里。
 *
 * 在一个还没有任何对话的时刻问"你想怎么用它"，答案是悬空的：用户选完"当一个人"，
 * 得到的只是一份**默认人设**，而真正的选择发生在他新建对话的时候。既然真正要做
 * 决定的地方在界面里，就不该在这儿再问一遍 —— 那是让人白填一次，还得回去改。
 *
 * 所以第一步只有昵称 / 头像 / 性别，然后直接进去。模型、人设、能力都在要用到它们的
 * 地方设置。
 */
@Composable
fun OnboardingScreen(
    viewModel: AppViewModel,
    /** Hoisted so the host can wire the system back button to "previous step". */
    step: Int,
    onStepChange: (Int) -> Unit,
) {
    val ios = IosTheme.palette

    var nickname by remember { mutableStateOf("") }
    var myEmoji by remember { mutableStateOf("😀") }
    var myColor by remember { mutableIntStateOf(0) }
    var myGender by remember { mutableStateOf("") }
    var myImage by remember { mutableStateOf("") }

    val emojis = listOf(
        "😀", "😎", "🙂", "🤓", "🥰", "😼", "🐱", "🐶",
        "🦊", "🐼", "🐧", "🌿", "🌸", "🔥", "⚡", "🌙",
    )
    val genders = listOf("女", "男", "不便透露")

    /**
     * 只落"你是谁"。
     *
     * **不写人设**：内置的那份默认人设（PersonaPresets.default）继续当模板，
     * 用户新建人设聊天时会以它为起点，然后在那个对话自己的设置里改成他想要的样子。
     * 在这里提前造一份"默认人设"，只会让用户以为他刚才的选择决定了什么 —— 其实没有。
     */
    fun finish() {
        viewModel.updateUserProfile {
            it.copy(
                nickname = nickname.ifBlank { "我" },
                avatarEmoji = myEmoji,
                avatarColor = myColor,
                avatarImage = myImage,
                gender = myGender,
                onboarded = true,
            )
        }
        viewModel.finishOnboarding()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 26.dp)
    ) {
        // 只剩一步，就不画"1/1"的进度条了：单独一条短蓝线飘在标题上方，
        // 看起来像没加载完的进度条，而不是"这是第几步"。
        Spacer(Modifier.height(if (step == 0) 76.dp else 56.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when (step) {
                0 -> {
                    Heading("先认识一下你", "这些只存在这台手机上，用来显示你的头像和称呼。")
                    Spacer(Modifier.height(26.dp))

                    Label("头像")
                    AvatarEditor(
                        emoji = myEmoji,
                        colorIndex = myColor,
                        imagePath = myImage,
                        emojis = emojis,
                        pickerPrefix = "user",
                        onEmoji = { myEmoji = it },
                        onColor = { myColor = it },
                        onImage = { myImage = it },
                        size = 72,
                    )
                    Spacer(Modifier.height(18.dp))
                    Label("昵称")
                    PlainInput(nickname, "你希望它怎么叫你？") { nickname = it }
                    Spacer(Modifier.height(18.dp))

                    Label("性别（可跳过）")
                    ChipRow(genders, myGender) { myGender = it }
                }

                // 这里原来是"你想怎么用它 / 它是谁"两步。
                // 现在对话类型是**新建对话时选的**、人设是**按对话设置的**，
                // 在一个还没有任何对话的时刻问这两件事没有任何着落点，所以整块删掉。
                // 见文件顶部的说明。


            }
            Spacer(Modifier.height(30.dp))
        }

        // actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 只有一步了，"返回/继续"这套两段式按钮就没有意义：
            // 一个"开始"到底，中间不需要任何分叉。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(ios.blue)
                    .clickable { finish() }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "开始",
                    style = IosTheme.typography.headline,
                    color = ios.onAccent,
                )
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    val ios = IosTheme.palette
    Text(title, style = IosTheme.typography.largeTitle, color = ios.label)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
}

@Composable
private fun Label(text: String) {
    val ios = IosTheme.palette
    Text(text, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PlainInput(
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 0.dp else 76.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(ios.secondaryGroupedBackground)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = IosTheme.typography.footnote,
                color = ios.tertiaryLabel,
                maxLines = if (singleLine) 1 else 3,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = LocalTextStyle.current.merge(IosTheme.typography.body).copy(color = ios.label),
            cursorBrush = SolidColor(ios.blue),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 6,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmojiGrid(
    emojis: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val ios = IosTheme.palette
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
                    .background(if (selected == e) ios.blue.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable { onSelect(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, style = IosTheme.typography.title3) }
        }
    }
}

@Composable
private fun ColorRow(colors: List<Color>, selected: Int, onSelect: (Int) -> Unit) {
    val ios = IosTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c)
                    .border(
                        width = if (selected == i) 2.dp else 0.dp,
                        color = ios.label,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val ios = IosTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = selected == o
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (sel) ios.blue else ios.secondaryGroupedBackground)
                    .clickable { onSelect(if (sel) "" else o) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    o,
                    style = IosTheme.typography.footnote,
                    color = if (sel) ios.onAccent else ios.label,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
