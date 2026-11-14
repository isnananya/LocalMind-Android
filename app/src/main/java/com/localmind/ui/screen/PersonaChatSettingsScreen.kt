package com.localmind.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localmind.data.Conversation
import com.localmind.data.PersonaPresets
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.ConversationInsightSection
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.launch

/**
 * **人设聊天**的设置页 —— 也就是"人机恋"那一套。
 *
 * 这一页回答的问题和 AI 对话那一页完全不同：不是"用哪个模型"，而是
 * **"它是谁、我们到哪一步了、它记得我什么"**。所以这里的每一块都围绕"关系"：
 *
 *  1. 它是谁 —— 名字、头像、年龄、性别、人设（[PersonaEditorSheet]）；
 *  2. 我们怎么相处 —— 拟人强度、短话连发、多重思考；
 *  3. 它现在是什么状态 —— 亲密度/信任/熟悉度、情绪、记忆、经验、待问的疑问；
 *  4. 它能做什么 —— 语音、图片、表情包、**主动来找你**；
 *  5. 用哪个模型 —— 说话/看图/画图，还有跑在独立引擎上的决策模型；
 *  6. 怎么回答 —— 这个对话自己的追加要求与采样参数。
 *
 * AI 对话那一页里一个拟人项都没有，这一页里一个"机器参数"都不是主角 ——
 * 这就是用户说的"对话单独设置的界面应该不同"。
 */
@Composable
fun PersonaChatSettingsScreen(
    viewModel: AppViewModel,
    conversation: Conversation,
    onBack: () -> Unit,
) {
    val ios = IosTheme.palette
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val voiceOk = viewModel.voiceEngineReady
    val hasImageModel = models.any { it.isDownloaded && it.isImageModel }
    val hasVisionModel = models.any { it.isDownloaded && it.isVisionModel }

    var editingPersona by remember { mutableStateOf(false) }

    // 复用全局的人设编辑器，但编辑的是**这个对话自己的**身份：把对话字段装成一份
    // 临时 Persona 交给编辑器，编辑完再写回对话。这样没必要的第二套 UI 就不存在了。
    if (editingPersona) {
        val draft = conversation.persona(PersonaPresets.default)
        PersonaEditorSheet(
            viewModel = viewModel,
            persona = draft,
            onDismiss = { editingPersona = false },
            // 用 adoptIdentity 而不是 adoptPersona：后者会连「是不是当人」和
            // 能力开关一起重置 —— 改个名字就把这个对话变成 AI 对话、把用户
            // 关掉的表情包又打开，那是很糟的意外。
            onSave = { p -> viewModel.updateConversationIdentity(conversation.id) { it.adoptIdentity(p) } },
        )
    }

    SettingsPage(title = "人设聊天设置", onBack = onBack) {

        // ------------------------------------------------------------ 它是谁
        IosSection(
            header = "这个 AI 是谁",
            footer = "只影响这一个对话。同一部手机上，另一个对话里的 AI 可以是完全不同的人。",
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingPersona = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonaAvatar(
                    emoji = conversation.personaAvatarEmoji.ifBlank { "🙂" },
                    colorIndex = conversation.personaAvatarColor.coerceAtLeast(0),
                    size = 56,
                    imagePath = conversation.personaAvatarImage,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            conversation.personaName.ifBlank { "还没起名字" },
                            style = IosTheme.typography.title3,
                            color = ios.label,
                        )
                        Spacer(Modifier.width(6.dp))
                        IosPill("当人", ios.pink)
                    }
                    val sub = buildString {
                        append(conversation.personaTagline.ifBlank { "点这里改名字、头像、年龄、性别、人设" })
                        if (conversation.personaAge.isNotBlank()) {
                            append(" · ").append(conversation.personaAge).append("岁")
                        }
                        if (conversation.personaGender.isNotBlank()) {
                            append(" · ").append(conversation.personaGender)
                        }
                    }
                    Text(sub, style = IosTheme.typography.caption1, color = ios.secondaryLabel, maxLines = 2)
                }
                Icon(AppIcons.ChevronRight, null, tint = ios.tertiaryLabel, modifier = Modifier.size(18.dp))
            }
        }

        // ------------------------------------------------------------ 相处方式
        IosSection(
            header = "相处方式",
            footer = "类型在创建时定好，之后不能改。下面这几个是「这个对话」的节奏，" +
                "别的对话不受影响。",
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "拟人强度　${(conversation.companionIntensity * 100).toInt()}%",
                    style = IosTheme.typography.body,
                    color = ios.label,
                )
                Text(
                    "越高越有自己的脾气和主动性，越低越温和顺从",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
                Spacer(Modifier.height(6.dp))
                IosSlider(
                    value = conversation.companionIntensity,
                    onValueChange = { v ->
                        viewModel.updateConversationIdentity(conversation.id) { it.companionIntensity = v }
                    },
                    valueRange = 0f..1f,
                )
            }
            HorizontalDivider(color = ios.separator)
            ToggleLine(
                title = "短话连发",
                subtitle = "像发微信一样一条一条发过来，而不是一次吐一大段",
                checked = conversation.burstReplies ?: true,
            ) { on ->
                viewModel.updateConversationSettings(conversation.id) { it.copy(burstReplies = on) }
            }
            HorizontalDivider(color = ios.separator)
            ToggleLine(
                title = "多重思考",
                subtitle = "回答前先自己过一遍你的意思，再决定怎么接。更准，但明显更慢",
                checked = conversation.deepThinking ?: false,
            ) { on ->
                viewModel.updateConversationSettings(conversation.id) { it.copy(deepThinking = on) }
            }
        }

        // ------------------------------------------------------------ 状态与记忆
        ConversationInsightSection(viewModel, conversation.id)

        // ------------------------------------------------------------ 它在想什么
        val understanding by viewModel.understanding.collectAsState()
        understanding?.takeIf { it.reading.isNotBlank() || it.innerThought.isNotBlank() }?.let { u ->
            IosSection(
                header = "它刚才在想什么",
                footer = "这是它回答你之前自己过了一遍的念头，不会直接说出口。",
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (u.reading.isNotBlank()) ThinkLine("它以为你说的是", u.reading)
                    if (u.alternativeReading.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ThinkLine("它还想到另一种可能", u.alternativeReading)
                    }
                    if (u.confidence >= 0) {
                        Spacer(Modifier.height(8.dp))
                        ThinkLine("把握", "${u.confidence}%")
                    }
                    Spacer(Modifier.height(8.dp))
                    ThinkLine("它读到的你的情绪", u.userEmotion)
                    Spacer(Modifier.height(8.dp))
                    ThinkLine("它打算", u.intent)
                    if (u.innerThought.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("「${u.innerThought}」", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                    }
                }
            }
        }

        // ------------------------------------------------------------ 能力
        IosSection(
            header = "它可以做什么",
            footer = "默认只会文字回复。表情包和「主动来找你」是这个模式特有的 —— " +
                "它们让聊天不那么干巴，但也要你主动打开。",
        ) {
            AbilityRow("文字回复", "基础能力，始终开启", checked = true, locked = true) { }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "发语音",
                subtitle = if (voiceOk) "回复用 TTS 念出来" else "还缺语音引擎 —— 去「我的 → TTS 测试」下载（约 36 MB）",
                checked = conversation.abilities.voice,
                ready = voiceOk,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(voice = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "发图片",
                subtitle = if (hasImageModel) "它可以画图并发给你" else "还没有生图模型 —— 去「模型 → 生图」下载一个",
                checked = conversation.abilities.imageGeneration,
                ready = hasImageModel,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(imageGeneration = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "看懂你发的图",
                subtitle = visionSubtitle(hasVisionModel, viewModel.canSeeImages),
                checked = conversation.abilities.imageRecognition,
                // ⚠️ 光下模型**不够**：还要它的 mmproj 下全、并且当前加载的就是它。
                // 只判 hasVisionModel 的话，用户会看到这一行"可用"，打开、发图、
                // 得到一句没看见图的回复 —— 那是我们在骗他。
                ready = hasVisionModel && viewModel.canSeeImages,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(imageRecognition = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "发表情包",
                subtitle = "让文字聊天不那么干巴",
                checked = conversation.abilities.emoji,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(emoji = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "主动来找你说话",
                subtitle = if (engineState.ready) "不必等你先开口" else "需要先加载一个模型，它才有东西可想",
                checked = conversation.abilities.proactive,
                ready = engineState.ready,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(proactive = on) } }
            HorizontalDivider(color = ios.separator)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateConversationAbilities(conversation.id) { it.allOn() } }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部打开", style = IosTheme.typography.body, color = ios.blue)
            }
        }

        // ------------------------------------------------------------ 朗读
        ConversationVoiceSection(viewModel, conversation)

        // ------------------------------------------------------------ 设备感知
        //
        // 这一块是"人机恋"里最容易变成空话的地方：模型说"注意身体"很容易，
        // 说"你那边下雨了记得带伞"得有依据。所以给它三个**可选**的感知开关，
        // 而且**按需读**（见 DeviceSense.needs）—— 不是每句话都去读。
        var senseResult by remember { mutableStateOf<String?>(null) }
        var sensing by remember { mutableStateOf(false) }
        var permitted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            permitted = viewModel.hasLocationPermission()
            if (!permitted) viewModel.toast("没给定位权限，「知道你在哪 / 看天气」就用不了")
        }
        val scope = rememberCoroutineScope()
        // 系统定位开关查一次就够（每帧都去问 LocationManager 是没必要的系统调用）。
        // 权限变化会让 permitted 变，进而重算。
        val systemLocationOn = remember(permitted) { viewModel.locationReady() }
        val locationOk = permitted && systemLocationOn

        IosSection(
            header = "它能感知到什么",
            footer = "默认全关，一个对话一份设置。打开之后也「不是每句话都读」—— " +
                "只有你聊到相关内容时（下雨、好冷、在哪、没电了）它才读一次，" +
                "读完会缓存一会儿。地名会被写进提示词，经纬度不会；" +
                "天气那一步会把经纬度发给天气服务（Open-Meteo，不需要密钥）。",
        ) {
            AbilityRow(
                title = "知道你手机的电量",
                subtitle = "不需要任何权限。快没电的时候它会提一句",
                checked = conversation.abilities.battery,
            ) { on ->
                senseResult = null
                viewModel.updateConversationAbilities(conversation.id) { it.copy(battery = on) }
            }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "知道你在哪",
                subtitle = when {
                    !permitted -> "需要定位权限 —— 打开这个开关时会向你申请"
                    !systemLocationOn -> "系统定位没开 —— 去手机设置的定位里打开"
                    else -> "城市/区一级。只有聊到位置时才会读"
                },
                checked = conversation.abilities.location,
            ) { on ->
                senseResult = null
                // 打开时顺手申请权限：让"开开关"和"给权限"是同一个动作，
                // 而不是让人开了之后纳闷为什么一直读不到。
                if (on && !permitted) {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                }
                viewModel.updateConversationAbilities(conversation.id) { it.copy(location = on) }
            }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "看你那边的天气",
                subtitle = when {
                    !permitted -> "需要定位权限（先拿到你在哪，才查得到天气）"
                    !systemLocationOn -> "系统定位没开 —— 去手机设置的定位里打开"
                    else -> "用设备定位 + 天气服务，需要联网"
                },
                checked = conversation.abilities.weather,
            ) { on ->
                senseResult = null
                if (on && !permitted) {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                }
                viewModel.updateConversationAbilities(conversation.id) { it.copy(weather = on) }
            }
            HorizontalDivider(color = ios.separator)
            // 「现在读一次」：和聊天走同一套读取函数，所以它显示的就是模型会看到的东西。
            // 定位/天气这种"读不到"的情况太多（权限、开关、信号、网络），
            // 没有这个按钮，用户只能靠"它怎么没反应"来猜。
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !sensing) {
                        sensing = true
                        senseResult = null
                        scope.launch {
                            senseResult = runCatching { viewModel.senseSnapshot(conversation) }
                                .getOrElse { "读取失败：" + it.message }
                                .ifBlank { "这个对话还没打开任何感知开关" }
                            sensing = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    if (sensing) "正在读…" else "现在读一次看看",
                    style = IosTheme.typography.body,
                    color = if (sensing) ios.secondaryLabel else ios.blue,
                )
                senseResult?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Text(r, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                }
            }
        }

        // ------------------------------------------------------------ 模型
        IosSection(
            header = "用哪个模型",
            footer = "四个模型可以同时配合：说话的、看图的、画图的、做判断的。" +
                "决策模型跑在「独立的第二个引擎」上，不占说话模型的锁 —— 这就是它不拖慢聊天的原因。",
        ) {
            ConversationModelRow(
                viewModel = viewModel,
                label = "对话模型",
                hint = if (engineState.ready) "当前已加载：${engineState.modelName ?: "—"}" else "当前还没有加载模型",
                currentId = conversation.chatModelId,
                models = models,
                providers = providers,
                allowCustomApi = true,
                conversationId = conversation.id,
            ) { id -> viewModel.selectConversationChatModel(conversation.id, id) }
            HorizontalDivider(color = ios.separator)
            ConversationModelRow(
                viewModel = viewModel,
                label = "识图模型",
                hint = "你发的图片由它看（需要多模态模型）",
                currentId = conversation.visionModelId,
                models = models,
                providers = providers,
                visionOnly = true,
                defaultLabel = "不指定",
                defaultHint = "没人看图时就不加载，省内存",
            ) { id -> viewModel.updateConversationModels(conversation.id) { it.copy(visionModelId = id) } }
            HorizontalDivider(color = ios.separator)
            ConversationModelRow(
                viewModel = viewModel,
                label = "生图模型",
                hint = "它画图时用（需要扩散模型 + 后端插件）",
                currentId = conversation.imageModelId,
                models = models,
                providers = providers,
                imageOnly = true,
                defaultLabel = "不指定",
                defaultHint = "没人画图时就不加载，省内存",
            ) { id -> viewModel.updateConversationModels(conversation.id) { it.copy(imageModelId = id) } }
            HorizontalDivider(color = ios.separator)
            ConversationModelRow(
                viewModel = viewModel,
                label = "决策模型",
                hint = "判断「该记什么 / 你的情绪 / 要不要联网」。270M~0.8B 那种小模型亚秒级返回",
                currentId = conversation.decisionModelId,
                models = models,
                providers = providers,
                decisionOnly = true,
                defaultLabel = "内置规则（不用模型）",
                defaultHint = "纯本地正则判断，最快，不占任何模型",
            ) { id -> viewModel.selectConversationDecisionModel(conversation.id, id) }
        }

        // ------------------------------------------------------------ 交给 API
        //
        // 人设聊天是最吃本机算力的一种对话（人设 + 长期状态 + 记忆 + 时间感，
        // 每一轮提示词都很长），所以“换成 API”在这里的提速感比 AI 对话更强。
        // 注意：走 API 时记忆、情绪、经验**照常工作** —— 它们本来就是程序算的，
        // 和谁在说话无关。
        ApiAccessSection(
            viewModel = viewModel,
            conversation = conversation,
            localModelName = engineState.modelName,
        )

        // ------------------------------------------------------------ 怎么回答
        IosSection(
            header = "额外的要求",
            footer = "追加在人设之后，只对这个对话生效。比如「别叫我宝贝」「说话短一点」。",
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                PromptField(
                    value = conversation.systemPrompt,
                    placeholder = "例如：她不太会用感叹号，回复普遍很短。",
                ) { v -> viewModel.updateConversationSettings(conversation.id) { it.copy(systemPrompt = v) } }
            }
        }

        SamplingSection(viewModel, conversation)

        // ------------------------------------------------------------ 清理
        IosSection(footer = "三个动作是三件事：清空消息 = 聊天记录没了；清空记忆 = 它忘了你；删除对话 = 全没了。") {
            DangerRow(
                title = "清空这个对话的消息",
                subtitle = "保留它的人设、记忆和设置",
                icon = AppIcons.Refresh,
            ) { viewModel.clearConversationMessages(conversation.id) }
            HorizontalDivider(color = ios.separator)
            DangerRow(
                title = "删除这个对话",
                subtitle = "连同记忆一起删掉，不可恢复",
                icon = AppIcons.Delete,
            ) { viewModel.deleteConversation(conversation.id); onBack() }
        }
    }
}

/** 一行「标题 + 说明 + 开关」，用于相处方式里的两个开关。 */
@Composable
private fun ToggleLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IosTheme.typography.body, color = ios.label)
            Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
        IosSwitch(checked = checked, onCheckedChange = onChange)
    }
}
