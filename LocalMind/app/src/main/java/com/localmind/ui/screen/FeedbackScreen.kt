package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.design.AuroraCircleButton
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.launch

/**
 * 功能建议 / 意见反馈。
 *
 * 存在的理由：以前用户想反馈只能发邮件或去 GitHub 提 issue，绝大多数人不会。
 * 这里给一个 App 内的输入框，写完点一下就走 —— 后台（LocalMind-Data 的「数据」页）
 * 直接能看到，按留言多少决定下一个版本做什么。
 *
 * 提交走 ntfy 公共通道，App 里没有任何令牌；内容会带上机型/系统/运存/CPU，
 * 方便判断"是不是某台机器特有问题"。页面上把上报内容原样显示出来，不做暗箱。
 */
@Composable
fun FeedbackScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val ios = IosTheme.palette
    var text by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        IosNavigationBar(
            title = "功能建议",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                AuroraCircleButton(
                    icon = AppIcons.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    size = 36.dp,
                    tint = ios.label,
                )
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Text(
                "想要什么功能？哪里用着别扭？",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ios.label,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "直接写，我会照着改。写得越具体越好 —— 比如「希望模型下载能断点续传」。",
                fontSize = 13.sp,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(top = 6.dp),
            )

            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 1500) text = it },
                minLines = 6,
                placeholder = { Text("例如：希望聊天记录支持导出成 Markdown") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            Text(
                "${text.length} / 1500",
                fontSize = 11.sp,
                color = ios.tertiaryLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            OutlinedTextField(
                value = contact,
                onValueChange = { if (it.length <= 120) contact = it },
                singleLine = true,
                placeholder = { Text("联系方式（可选，想回访时用）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            Button(
                onClick = {
                    if (text.isBlank() || sending) return@Button
                    sending = true
                    scope.launch {
                        val ok = viewModel.sendFeedback(text.trim(), contact.trim())
                        sending = false
                        if (ok) {
                            done = true
                            text = ""
                            contact = ""
                            viewModel.toast("已收到，谢谢！")
                        } else {
                            viewModel.toast("发送失败，检查下网络再试")
                        }
                    }
                },
                enabled = text.isNotBlank() && !sending,
                colors = ButtonDefaults.buttonColors(containerColor = ios.pink),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .height(48.dp),
            ) {
                Text(if (sending) "发送中…" else if (done) "再写一条" else "提交", fontSize = 15.sp)
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "会上报什么",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ios.label,
            )
            Text(
                viewModel.localDeviceSummary() + "\n\n" +
                    "只有上面这些硬件信息 + 你写的这段话，用来判断问题是不是出在某台机器上。" +
                    "不含手机号、账号、位置、已安装应用列表，也不含任何能定位到个人的标识。",
                fontSize = 12.sp,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.padding(top = 14.dp, bottom = 40.dp),
            ) {
                Text(
                    "也可以直接在 GitHub 提 issue：isnananya/LocalMind-Android",
                    fontSize = 11.sp,
                    color = ios.tertiaryLabel,
                )
            }
        }
    }
}
