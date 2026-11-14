# base.apk 逆向分析报告

> 分析对象：`D:\aichat\base.apk`（3,411,831 字节，SHA-256 `7FDD0AA55FE68F588072F31B9851B78CEEFE108206DC302D9D680AC39E7C1905`）
> 分析时间：本次会话；工具：aapt2 / apksigner（build-tools 35）、jadx 1.5.6、自写 DEX 解析脚本
> 产物目录：`_analysis/`（反编译树、字符串表、脚本）

---

## 一、结论速览

1. **它是 LSPosed/Xposed 模块「LiquidTab」的 1.0.0 发布版**：包名 `io.github.offlineglass`，versionCode **216**，versionName **1.0.0**，minSdk **35**（Android 15）、targetSdk **37**。作用是把 14 个国产 App 的底部导航栏替换成带实时折射/模糊/高光的悬浮「液态玻璃」底栏。
2. **文件夹里的 `lt/LiquidTab-main` 不是这个 APK 的反编译产物**，而是同一项目的 **2.0 源码快照**（versionCode 217、24 个应用、按应用拆 `adapters/`）。它比 APK 新一个大版本，详见第八节。除我本次生成的 `_analysis/` 之外，仓库里没有任何 jadx/smali 反编译输出。
3. APK 经 **R8 混淆并把几乎所有类重打包进默认包**（`Lpk;`、`Lub;` 这类描述符），只有 proguard keep 住的 `io.github.offlineglass.hook.**`、`config.**`、`MainActivity` 等保留原名；**hook 核心逻辑因此完整可读**，UI/渲染辅助类名被抹掉但逻辑仍可读。
4. **签名是 Android Debug 证书**（`CN=Android Debug, O=Android, C=US`），且只有 v3 签名方案 —— 这是本地自测构建，不是商店签名。
5. **无网络权限、无统计/广告 SDK、无 exec/root/任意文件读写**；风险面主要是「导出的 SettingsProvider / ConfigRequestReceiver 没有权限保护」，可被任意 App 读取模块配置和「最近活动的包名/时间」。
6. APK 里已经包含 **闲鱼、YouTube、菜鸟、美团外卖** 的适配代码，但它们不在 1.0.0 的注册表/作用域里（死代码）—— 这些正是 2.0 才启用的应用，说明 APK 是 2.0 重构前的一次快照构建。

---

## 二、身份与构建信息

| 项目 | 值 |
|---|---|
| 包名 | `io.github.offlineglass` |
| 应用名 | LiquidTab |
| versionCode / versionName | 216 / 1.0.0 |
| minSdk / targetSdk / compileSdk | 35 / 37 / 37（codename "17"） |
| AGP | 9.3.1（`META-INF/com/android/build/gradle/app-metadata.properties`） |
| 版本控制信息 | `NO_SUPPORTED_VCS_FOUND`（构建时目录没有 .git） |
| DEX | 单 `classes.dex`，3,097,968 B；**2960 个类**、12,890 字符串、15,284 方法、11,498 字段 |
| 类分布 | 2866 个在默认包（混淆/重打包）、68 个 `io.github.offlineglass.hook`、2 个 `config`、MainActivity、少量被 manifest/反射引用的 androidx 类 |
| 原生库 | 仅 `libandroidx.graphics.path.so`（arm64/armv7/x86/x86_64），无自研 .so，渲染全在 Java/Kotlin + AGSL |
| 权限 | 只有 `io.github.offlineglass.permission.CONFIG_SYNC`（signature）与 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；**没有 INTERNET** |
| 签名 | v3 only；证书 DN `C=US, O=Android, CN=Android Debug`；证书 SHA-256 `30243771a6f9f2e3574cfce8ef8eb4c72c10de45287b395c455548f292f3cf58`；RSA-2048 |
| allowBackup | true |
| 混淆 | R8（dex 的 SourceFile 被替换为 `r8-map-id-38873db0...`），类被重打包进默认包 |

### 依赖版本（取自 `META-INF/*.version`）

| 库 | 版本 |
|---|---|
| Compose（ui/foundation/runtime/animation） | 1.12.0-beta01 |
| material3 | 1.5.0-alpha24 |
| activity | 1.13.0 |
| lifecycle | 2.10.0 |
| navigation3 / navigationevent | 1.1.4 / 1.1.2 |
| graphics-shapes / graphics-path | 1.0.1 / 1.0.1 |
| core | 1.18.0 |
| coroutines | 1.9.0 |
| 动态取色 | materialkolor（`DynamicScheme`/`TonalPalette`） |

> 注意：1.0.0 **不含** Miuix、**不含** `hiddenapibypass`，这两者是 2.0 源码才引入的。
>
> 动态配色的实现：读 `Settings.Secure` 的 `theme_customization_overlay_packages`，取 `android.theme.customization.system_palette` 作为种子色（为空则退回 `android.R.color.system_accent1_500`）与 `theme_style`，交给 materialkolor 生成 M3 配色 —— 这正是 UI 文案「从系统壁纸提取颜色」的落地方式（MIUI/HyperOS 的系统色板本身来自壁纸）。

### 资源里的三套底栏图标

`assets/` 下除了 `xposed_init`、`xposed_scope`，还有 **weibo/**（20 张，亮/暗各一套）、**qq_icons/**（6 张）、**mi_market_icons/**（16 张）。这些是给「无法抓取原生底栏」的 App 做静态重绘用的。

---

## 三、模块机制与运行流程

### 3.1 入口

`assets/xposed_init` → `io.github.offlineglass.hook.HookEntry`；manifest 里 `xposedmodule=true`、`xposedminversion=82`；作用域由 `assets/xposed_scope` + `META-INF/xposed/scope.list` 提供（14 个包）。

```java
// HookEntry.handleLoadPackage（反编译）
Set set = ca.a;                                   // 静态归档集合（下面会讲）
gp1 gp1VarA = ca.a(str);                          // 包名 → TargetSpec
if (gp1VarA == null) return;
if (va0.i(loadPackageParam.processName, loadPackageParam.packageName)) {   // 仅主进程
    TargetHooks.INSTANCE.install(loadPackageParam, gp1VarA);               // 专项 hook
    XposedBridge.hookAllMethods(Activity.class, "onPostResume",  ...);     // → HookCoordinator.onActivityResumed
    XposedBridge.hookAllMethods(Activity.class, "onStop",       ...);
    XposedBridge.hookAllMethods(Activity.class, "onDestroy",    ...);
    XposedBridge.hookAllMethods(Activity.class, "finish",       ...);     // 微博/小红书返回拦截
    XposedBridge.hookAllMethods(Activity.class, "moveTaskToBack", ...);
}
```

### 3.2 底栏定位与注入

```
Activity.onPostResume
  └─ HookCoordinator.onActivityResumed(activity, spec)
       ├─ maybeHookPredictiveBackDispatcher()        // hook androidx OnBackPressedDispatcher.onBackPressed
       ├─ HookConfigReader.read()                     // 读配置
       └─ ScannerSession.scheduleScans()             // 0/180/650/1500/3000ms + 每次 global layout（300ms 节流）
            └─ NavigationFinder.find(decorView, spec) // 打分找底部导航 ViewGroup + 估算格数
                 └─ GlassInstaller.install(activity, navGroup, slots, config, spec)
                      → new GlassHostLayout(...) 加入 android.R.id.content
                      → 压制/隐藏原生底栏、接管 insets、触摸、返回
```

`NavigationFinder` 是纯启发式：按 `spec` 的 activityHints / idHints / classHints / textHints 加权，结合「位于屏幕底部」「水平排列可点击子 View 的个数」「TextView 文本命中标签」等打分（`horizontalSlotCount` 会按 26~32dp 的最小尺寸、去重圆心来估算 tab 数）。所以这类模块对宿主版本很敏感。

### 3.3 配置读取

`HookConfigReader.read(context, pkg)` 的顺序：

1. `io.github.offlineglass.settings` ContentProvider（`call("get", pkg)`），失败后 **5 秒退避**；
2. `XSharedPreferences("io.github.offlineglass", "offline_glass")` 兜底（LSPosed 旧式读取）；
3. 结果缓存 5 秒；QQ 按进程缓存；B 站国际版走「推送配置」；
4. 读到的配置里再叠加全局开关（liquid_glass / bottom_gradient_blur / solid_bar / outline）与 `app_<pkg>` 开关、`appcfg_<pkg>_override`。

---

## 四、1.0.0 的支持范围

注册表在 `defpackage/ca.java`，共 **23 个 TargetSpec**，其中：

```java
a = {mi_file_manager, mi_phone, mi_calendar, mi_gallery, mi_notes, netease, mi_messages, hellobike}  // 归档，不进 UI
b = {qq}                                                                                          // 隐藏（dex 中未见引用）
```

23 − 8 − 1 = **14 个启用项，与 xposed_scope 完全一致**。

| key | 包名 | 显示名 | tab 标签（textHints） | 格数 | hideOptions |
|---|---|---|---|---|---|
| wechat | com.tencent.mm | 微信 | 微信/通讯录/发现/我 | 4 | — |
| weibo | com.sina.weibo | 微博 | 首页/视频/发现/消息/我 | 5 | — |
| taobao | com.taobao.taobao | 淘宝 | 首页/视频/消息/购物车/我的淘宝 | 5 | — |
| jd | com.jingdong.app.mall | 京东 | 首页/逛/消息/购物车/我的 | 5 | — |
| pdd | com.xunmeng.pinduoduo | 拼多多 | 首页/直播/领券/消息/我的 | 5 | 隐藏直播(1)、隐藏领券(2) |
| bilibili | tv.danmaku.bili | 哔哩哔哩 | 首页/关注/会员购/我的 | 5 | — （有发布按钮） |
| bilibili_in | com.bilibili.app.in | 哔哩哔哩国际版 | 首页/关注/消息/我的 | 4 | — |
| xhs | com.xingin.xhs | 小红书 | 首页/市集/（空）/消息/我 | 5 | — （3 号位是发布按钮） |
| meituan_main | com.sankuai.meituan | 美团 | 推荐/视频/AI/购物车/我的 | 5 | — |
| mihome | com.xiaomi.smarthome | 米家 | 米家/智能/产品/商城/我的 | 4~5 | 隐藏产品(2)、隐藏商城(3) |
| mi_health | com.mi.health | 小米运动健康 | 健康/运动/设备/我的 | 4 | — |
| mi_market | com.xiaomi.market | 小米应用商店 | 首页/游戏/应用/榜单/我的 | 5 | — |
| mi_community | com.xiaomi.vipaccount | 小米社区 | 论坛/官方/活动/消息/我的 | 5 | — |
| tieba | com.baidu.tieba | 百度贴吧 | 首页/进吧/小卖部/消息/我的 | 5 | — |

**已注册但归档/隐藏**：qq（com.tencent.mobileqq，3 格）、netease（com.netease.cloudmusic）、hellobike（com.jingyao.easybike）、mi_file_manager（com.android.fileexplorer，隐藏云盘）、mi_phone（com.android.contacts）、mi_messages（com.android.mms）、mi_calendar（com.android.calendar）、mi_gallery（com.miui.gallery）、mi_notes（com.miui.notes）。

**代码里有、但没注册/不在作用域**（1.0.0 的死代码，2.0 才启用）：
`com.taobao.idlefish`（闲鱼）、`com.google.android.youtube`、`com.cainiao.wireless`（菜鸟）、`com.sankuai.meituan.takeoutnew`（美团外卖）。`GlassHostLayout` 里保留了 `suppressNativeXianyuBottomBar`、`suppressNativeYoutubeBottomBar`、`YouTubeDiag` 等整套逻辑。

---

## 五、逐应用 Hook 信号表（`TargetHooks.signalsFor`）

| key | hook 的宿主类 | hook 的方法 |
|---|---|---|
| qq | com.tencent.mobileqq.activity.home.MainFragment / …activity.SplashActivity | onViewCreated, onTabChanged, b9, onResume / openMainFragment, updateMain, onWindowFocusChanged |
| bilibili | com.bilibili.lib.homepage.widget.TabHost | onAttachedToWindow, setTabs, setCurrentItem |
| xhs | XHS_TAB_BAR_CLASS(=com.xingin.xhs.homepage.tabbar.TabBarView)、com.xingin.commercial.shop.malltabbar.TabBarView | onAttachedToWindow, onFinishInflate |
| pdd | com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView / …ui.activity.MainFrameActivity | onAttachedToWindow, onLayout, onSizeChanged, setTabs, setOnTabChangeListener / updateRootView, backToHome |
| taobao | com.taobao.tao.welcome.Welcome | onCreate, onResume, onWindowFocusChanged |
| meituan_main | com.meituan.android.pt.homepage.tab.TabBlockV2 / …homepage.activity.MainActivity | onAttachedToWindow, C, A, t / T5 |
| meituan（外卖，未注册） | com.sankuai.waimai.business.page.homepage.view.TitleIndicator | onAttachedToWindow, setActivity, setCurrentTab, k |
| mihome | com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew | onAttachedToWindow, setViewPager, setCurrentItem |
| mi_health | com.xiaomi.fitness.main.MainActivity | bindView, onResume, onConfigurationChanged |
| mi_market | com.xiaomi.market.widget.BottomTabLayout / …business_ui.main.MarketTabActivity | addFragmentTab, selectTab, show / onCreate, onResume, onConfigurationChanged |
| mi_community | com.xiaomi.vipaccount.ui.widget.tab.BottomNavView / …ui.home.page.HomeFrameActivity | onAttachedToWindow, setSelectedItemPos, updateSelectedItemPos, bindData / onCreate, onResume, onConfigurationChanged |
| netease（归档） | com.netease.cloudmusic.theme.ui.NavigationTabLayout / …activity.MainActivity | onAttachedToWindow, t / onDrawerOpened, onDrawerClosed |
| mi_gallery / mi_phone（mi_notes、mi_messages 同类） | miuix 信号：miuix.navigator.bottomnavigation.BottomNavigationView、miuix.navigator.navigation.NavigationBarView | onAttachedToWindow, setSelectedItemId / inflateMenu, show, hide |
| mi_calendar | com.android.calendar.homepage.BottomNavigatorView | onAttachedToWindow, setVisibility, g |
| mi_file_manager | miuix 信号 + com.android.fileexplorer.fragment.PhoneMainFragment | onVisibilityChanged |
| wechat / weibo / tieba / jd / bilibili_in | 无类信号 | 靠 Activity 生命周期 + 视图扫描 + 下面的专项 hook |

### 专项 hook（1.0.0 独有 hack）

| 应用 | 做了什么 |
|---|---|
| 微信 | `installWeChatNativeFrostSuppression`：hook `com.tencent.mm.ui.FrostedContentView.a(...)` 和 `dispatchDraw`，把实例的 `h`/`m`/`i` 三个 int 字段清零、`getFrostedEnabled()` 关掉，并把第 3 个参数置 `100000f`（从上下文看是冻结/模糊的触发阈值）；hook `LauncherUIBottomTabView.onLayout` 把自身与子树 `alpha=0`、清 background/tint，从而「抹掉」原生底栏，只留模块自己画的。判断"启动页"是向上 20 层找 `WECHAT_VIEW_PAGER_CLASS`。 |
| 贴吧 | `installTiebaNavigationBarBgSuppression`：三种 `FragmentTabWidget.setNavigationBarBg` 直接 `setResult(null)`，防止原生深色导航条盖住玻璃。 |
| 小米运动健康 | ① `installMiHealthReactSafeAreaHook`：hook RN 的 `x7n.c()`，反射找出 insets 对象里「与 systemBars bottom 相差 ≤6dp」的 float 字段并置 0；② `installMiHealthDeviceSettingsReturnRepair`：从设备设置返回后重建页面。 |
| 小米应用商店 | `installMiMarketNavigationHooks`：hook `DoubleTabProxyActivityWrapper` 的 `setTabContainer`/`onBottomTabBlurSwitch`/`onResume`，反射取 `bottomTabLayout` 或 `mActivity` 后注入。 |
| 微博 / 小红书 | `Activity.finish()` 与 `moveTaskToBack()` 前置拦截：微博当前 tab 为 1 或 3、小红书在消息 tab 时，改为回到首页而不是退出。 |
| 通用 | `Activity.onPostResume` 里对贴吧侧边栏 Activity（`com.baidu.tieba.sidebar.SideBarAssistActivity`）做可见性切换。 |

---

## 六、液态玻璃渲染实现（1.0.0 技术核心）

`GlassHostLayout` 是整个模块的核心：**18,026 行反编译 Java、635 个字段、424 个方法**（R8 合并 + 单文件巨类，2.0 源码里也是 369 KB 的 .kt）。

### 6.1 图层（RenderNode）

`OfflineGlassOuter`、`OfflineGlassTabsBackdrop`、`OfflineGlassIndicator`、`IndicatorCombined`、`IndicatorShadow`、`IndicatorCaustics`、`OfflineGlassLiveBackdrop`、`OfflineGlassNavigationContent`、`JdBottomVeil` —— 全部 `RenderNode` + `RenderEffect` 硬件合成，不用自定义 GL。

### 6.2 背景捕获

`captureBackdrop(view)`：

```java
int w = max(1, (getWidth()  + pad*2) / 2);   // CAPTURE_DOWNSCALE = 2，半分辨率
int h = max(1, (getHeight() + pad*2) / 2);
Bitmap backdrop = Bitmap.createBitmap(w, h, ARGB_8888);
setAlpha(0f);                                 // 把自己藏起来，避免递归
canvas.scale(0.5f, 0.5f); canvas.translate(...); view.draw(canvas);   // 抓宿主内容
setAlpha(alpha);
```

参数：`CAPTURE_IDLE_INTERVAL_MS=96`（96ms 空闲才抓）、`BACKDROP_STABLE_FRAMES=2`、`CONFIG_POLL_INTERVAL_MS=1000`。SurfaceView/TextureView（淘宝闪购、拼多多百亿补贴）额外走 `PixelCopy` 到 Bitmap，再作为 hybrid backdrop。

### 6.3 AGSL 折射 + 色散（`ROUNDED_RECT_DISPERSION_SHADER`）

外层面板是一个 RuntimeShader：对圆角矩形做 SDF（`sdRoundedRect` + 梯度），在边缘 `refractionHeight` 范围内按 `circleMap` 把采样坐标向内偏移，并做 **7 次采样色散**（红/橙/黄/绿/青/蓝/紫），模拟玻璃边缘的彩虹。

```glsl
float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
                        + depthEffect * normalize(centeredCoord));
float2 refractedCoord = coord + d * grad;
float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
// 红/橙/黄/绿/青/蓝/紫 7 次 content.eval(...) 加权累加
```

uniform：`size/offset/cornerRadii(32dp)/refractionHeight(默认 24dp)/refractionAmount(-24dp)/depthEffect/chromaticAberration`。
另有 `TabsBackdropLens`：只对 56dp 高的 tab 区做透镜（4dp 内缩、圆角 h/2）。

### 6.4 模糊链

```java
// 微信渐变模糊：6 段 alpha 遮罩 shader + 每段 blur，再 SRC_OVER 叠加
RenderEffect e = createRuntimeShaderEffect(passthrough, "content");
for (WeChatBlurBand band : WECHAT_BLUR_BANDS) {
    shader.setFloatUniform("zoneTop", top); shader.setFloatUniform("zoneBottom", bottom);
    shader.setFloatUniform("fadeT", band.fadeT); shader.setFloatUniform("solidT", band.solidT);
    RenderEffect blur = createBlurEffect(band.radiusDp * density, ..., Shader.TileMode.MIRROR);
    e = createBlendModeEffect(e, createChainEffect(createRuntimeShaderEffect(shader, "content"), blur), SRC_OVER);
}
view.setRenderEffect(e);
```

还有 `BottomGradientBlurVeil`（页面底部渐变模糊，6 段 alpha stops）、`JD 底部 veil`、`贴吧底部模糊 overlay`、`B 站底部 veil` —— 都是「遮罩 shader + createBlurEffect」的同一套路。

### 6.5 高光 / 交互

- **描边**：非液态模式画 1dp、白 81/黑 61 的圆角描边；液态模式画 `drawBloomStroke`（SDF 描边 + 光照方向 + 内外发光）。
- **传感器倾斜**：`BloomTiltTracker` 监听 `TYPE_ROTATION_VECTOR`（15，退回 11），平滑系数 0.15，实时更新光照方向 —— 手机倾斜时高光会移动。
- **按压高亮**：`drawInteractiveHighlight` 用 `BlendMode.PLUS` 叠 6% 白 + 指针位置 12% 的径向渐变。
- **指示器**：`positionSpring`/`velocitySpring`（`SpringFloat`）弹簧动画，按压缩放，拖拽时 `panelDragOffset` 带 4dp 阻尼位移。

### 6.6 原生底栏「消失」与重绘

`suppressNative*` 系列（bilibili_in、cainiao、hellobike、jd、meituan、meituan_takeout、mi_community、mi_home、mi_market、pdd、qq、taobao、tieba、wechat、weibo、xhs、xianyu、youtube）把宿主底栏透明化/移出可视区；然后用 `drawNavigationSource` 重画：

1. 有静态资产的：QQ/微博/小米商店直接画 Bitmap 或矢量 path（bilibili 用 1024 视口的 path 数据 `parseBilibiliIconPath`）；
2. 其余：把原生导航 View 录进 `NavigationContent` RenderNode，按面板几何「重放」（`sourceTransform` 做缩放/平移归一化）；
3. 淘宝/PDD 的 SurfaceView 场景走 PixelCopy 快照。

配合 `adjust*` 系列（约 15 个）：微信折叠聊天栏、贴吧底部控件、JD 结算栏/闪购、淘宝购物车/领券条、PDD 浮动按钮、XHS 底部浮层、米家底部动作、微博悬赏浮层、美团外卖滚动安全区…… 这就是 1.0.0 能「以假乱真」的原因，也是它和宿主版本强耦合的原因。

---

## 七、配置与 IPC

### 7.1 存储

- SharedPreferences 文件：**`offline_glass`**（`_migrated_from_de` 标记从设备保护存储迁移过；`formal_defaults_v1` 一次性写入正式默认值）。
- 全局键：`liquid_glass_enabled=true`、`bottom_gradient_blur_enabled=true`、`solid_bar_enabled=false`、`outline_enabled=true`、`dynamic_color`。
- 每应用：`app_<pkg>`（开关）、`appcfg_<pkg>_override`、以及 17 个 `appcfg_<pkg>_<field>`（字段名见下表左列）。

### 7.2 每应用配置字段（`z30`）与默认值

| 字段 | 键 | 1.0.0 默认 | 2.0 默认 |
|---|---|---|---|
| enabled | — | true | true |
| theme_mode | theme_mode | 0（跟随系统） | 0 |
| tab_width | tab_width | 76dp | 76dp |
| bottom_padding | bottom_padding | 12dp | 12dp |
| blur_radius | blur_radius | **4dp** | **2dp** |
| light_alpha / dark_alpha | … | 0.4 / 0.4 | 0.4 / 0.4（固定） |
| icon_scale | icon_scale | 1.0 | 1.0 |
| text_size | text_size | 11sp | 11sp |
| icon_only | icon_only | false | false |
| hidden_mask | hidden_mask | 0 | 0 |
| show_post | show_post | true | true |
| custom_accent_enabled/color | … | false / 0xFFFF8A00 | 同 |
| show_channel | show_channel | false | false |
| backdrop_capture | backdrop_capture | true | true |
| native_blur | native_blur | false | false |
| selected_accent | selected_accent | false | false |
| liquid_glass_enabled | liquid_glass_enabled | true | true |
| bottom_gradient_blur_enabled | … | **true** | **强制 false** |
| solid_bar_enabled | … | false | false |
| outline_enabled | … | true | true |
| bar_height / corner / highlight_strength / classic_navigation / systemDark | — | 不存在 | 2.0 新增 |

### 7.3 IPC 组件

| 组件 | 配置 | 行为 |
|---|---|---|
| `config.SettingsProvider`（authorities=`io.github.offlineglass.settings`） | **exported=true，无 android:permission**，directBootAware | `call("get", pkg)` 返回配置 Bundle；`call("snapshot")` 返回 `last_active_package/last_active_time`；`call("report_active", pkg)` 写入最近活动；`query()` 返回 5 列（package/enabled/blur/light_alpha/dark_alpha） |
| `config.ConfigRequestReceiver` | **exported=true，无权限** | 收到 `io.github.offlineglass.action.CONFIG_REQUEST` 且 `request_package` 在注册表内，就主动给该包推配置（给 provider 被包可见性策略挡住的目标 App 用） |
| `io.github.offlineglass.permission.CONFIG_SYNC` | protectionLevel=signature | 已声明、已申请，但组件上**没有使用** |

---

## 八、与文件夹里 `lt/LiquidTab-main` 的关系

### 8.1 判断：它是同一项目的 2.0 源码，不是本 APK 的反编译

| 证据 | 说明 |
|---|---|
| 版本 | `app/build.gradle.kts`：versionCode **217**、versionName **2.0**；APK 是 216 / 1.0.0 |
| 代码形态 | Kotlin 源码 + `WORKSPACE.md`/`docs/`/`design/`/`licenses/` + GPLv3 + NOTICE + `.bak_20260917`/`.bak_20260918_douyinfix` 备份，典型人工开发仓库；反编译产物不会长这样（没有 smali、没有 jadx 注释、类名完整） |
| 架构 | 2.0 已把每个应用拆到 `hook/adapters/<key>/`，有 `TargetAdapterRegistry`；APK 1.0.0 是单个 `ca` 注册表 + 巨型 `GlassHostLayout` |
| 功能 | 2.0 有 `AppMasterSwitchReceiver`、`hiddenapibypass`、Miuix 双 UI、别名包/UI 进程后缀；这些在 APK 里完全不存在 |
| 反向 | APK 里有 2.0 没启用的死代码（闲鱼/YouTube/菜鸟/美团外卖），但 2.0 把这些变成了正式适配 —— 说明 APK 早于源码，而不是源码来自 APK |
| 文档 | `AI_HANDOFF.md` 明确写「保持 applicationId 为 io.github.offlineglass 以兼容既有安装」「1.0.0 基线另存归档」，即作者知道自己有 1.0.0 |

> 所以：如果你在别处看到"逆向源码"的说法，实际对应的是这份 2.0 上游源码；本 APK 的**真正反编译结果**是我这次生成的 `_analysis/jadx-out`。

### 8.2 1.0.0 → 2.0 主要差异

| 维度 | 1.0.0（本 APK） | 2.0（文件夹源码） |
|---|---|---|
| 作用域 | 14 个包 | 24 个包（+amap、meituan_takeout、netease、qqmusic、douyin、cainiao、xianyu、youtube、xjtu、zhihu） |
| QQ | 注册但隐藏（代码完整） | 完全移除，`qq_icons` 资产也没了 |
| 架构 | `HookEntry` + `ca` 注册表 + `GlassHostLayout` 巨类 | `HookEntry` → `TargetAdapterRegistry` → `adapters/<app>`；应用级逻辑下沉，`GlassHostLayout` 仍保留渲染内核 |
| 进程支持 | 仅主进程 | `uiProcessSuffixes` + `aliasPackages`（如主题商店别名包） |
| UI | Compose + Material3 + Navigation3 | 增加 Miuix 版 UI（`MiuixUi.kt`）、管理器玻璃底栏、入门/赞赏页 |
| 配置 | 见 7.2 | 新增 barHeight、cornerRadiusPercent、cornerSmoothing、darkBarHighlightStrength、classicNavigation、systemDark；底部渐变模糊默认关 |
| 依赖 | 无 Miuix、无 hiddenapibypass | Miuix 0.9.3、hiddenapibypass 6.1、KILL_BACKGROUND_PROCESSES |
| 资产 | weibo / qq_icons / mi_market_icons | weibo / tiktok / mi_market_icons |
| 构建 | AGP 9.3.1，debug 签名，R8 重打包 | 同 compileSdk/minSdk，release 需自签，proguard 只 keep 少数类 |

**给后续工作的建议**：若要研究/移植 1.0.0 的行为，优先读 2.0 源码（可读性高一个量级），再对照本报告第五、六节补 1.0.0 的差异（微信 FrostedContentView 字段、贴吧 setNavigationBarBg、MiHealth RN insets、微博/小红书返回拦截、`BottomGradientBlurVeil` 的 6 段遮罩等）。

---

## 九、安全与隐私评估

| 风险 | 证据 | 影响 | 建议 |
|---|---|---|---|
| Debug 签名 | apksigner：`CN=Android Debug`，仅 v3 | 不能上架；任何人可用自己密钥重签分发；与正式版签名不同则无法覆盖安装 | 发布必须换正式 keystore；对来源不明的同包名 APK 校验证书哈希 |
| 导出 Provider 无权限 | manifest `exported=true` 且无 `android:permission`；代码未校验 callingUid | 任意 App 可 `call("get"/"snapshot")` 读模块配置与「最近活动包名/时间」，可 `report_active` 伪造活动记录 | 加 signature 级 permission 或用 `checkCallingPermission`，2.0 也仍建议补 |
| 导出 Receiver 无权限 | `ConfigRequestReceiver` exported，只校验包名在注册表内 | 可被任意 App 触发配置推送广播（无数据外泄，但可骚扰/探测模块是否安装） | 同上 |
| allowBackup=true | manifest | adb backup / 云备份可带走配置与本地上次活动记录 | 视需要设 false |
| 反射宿主私有实现 | `FrostedContentView.h/m/i`、`DoubleTabProxyActivityWrapper.bottomTabLayout`、RN insets 字段名、`TabBlockV2` 混淆方法 `C/A/t/T5/b9` | 宿主升级即失效或崩溃（大多被 `try/catch` 吞掉，表现为「功能悄悄没了」） | 每次适配新版本要重新抓包/试错 |
| 无网络 / 无统计 / 无 shell | 字符串扫描：无 http(s) 业务 URL、无 OkHttp/Retrofit、无 exec/ProcessBuilder、无 Cipher；权限表无 INTERNET | 无外传行为 | — |
| 读模块自身资产 | `createPackageContext("io.github.offlineglass", CONTEXT_IGNORE_SECURITY).getAssets()` | 宿主进程读模块图标，正常设计 | — |
| Xposed 本身 | 需 LSPosed；14 个 App 进程内注入 | 模块即"特权代码"，信任前提是开源可审计（GPLv3） | 用官方 LSPosed，限定作用域 |

补充：APK 的 `AndroidManifest` 里 provider 是 `directBootAware`，会在设备直接启动（未解锁）阶段初始化；因为只涉及本地 SharedPreferences，风险低。

许可证提示：`lt/LiquidTab-main` 是 **GPLv3**，而 `LocalMind/` 是 **MIT**。只参考布局/交互思路不涉及许可冲突；但若把 `lt` 里的代码（尤其渲染内核、per-app hack）复制进 MIT 项目，会与 GPLv3 冲突。kyant0 的 AndroidLiquidGlass 是 Apache-2.0，可以直接用。

---

## 十、复现与观察方法

```powershell
# 基础信息
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging D:\aichat\base.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump xmltree --file AndroidManifest.xml D:\aichat\base.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs --verbose D:\aichat\base.apk

# 反编译（需要 JDK 11+；我用的 jadx 1.5.6）
_analysis\jadx\bin\jadx.bat -d _analysis\jadx-out --no-res --no-debug-info base.apk
_analysis\jadx\bin\jadx.bat -d _analysis\jadx-badcode --no-res --show-bad-code base.apk   # 看 signalsFor 等失败方法

# 运行期日志（设备上）
adb logcat -s OfflineGlass OfflineGlassGeom TbGlassDiag LiquidTabUI LiquidTabInject
```

**已知错误**：jadx 报 82 处反编译错误，集中在被 R8 横向合并的大方法（如 `TargetHooks.signalsFor`），用 `--show-bad-code` 可得到 switch 分支；`GlassHostLayout` 里若干 `if` 结构也被合并，阅读时以方法名和日志字符串为锚点。

---

## 十一、本次产物清单

| 路径 | 内容 |
|---|---|
| `_analysis/jadx-out/sources/` | jadx 反编译（2960 类；`io/github/offlineglass/hook/` 为可读核心） |
| `_analysis/jadx-badcode/sources/` | 带指令 dump 的兜底反编译（`TargetHooks.signalsFor` 等） |
| `_analysis/dex_strings.txt` | DEX 全部 12,890 条字符串 |
| `_analysis/cjk_strings.txt` | 146 条中文 UI 文案（能反推模块界面结构：适配应用 / 底栏入口设置 / 离线与隐私 / 动态配色…） |
| `_analysis/resources.txt` | aapt2 资源表 |
| `_analysis/*.py` | DEX 头/类名/字符串/签名提取脚本 |
| `_analysis/calls.txt` | 23 个 TargetSpec 构造参数原文 |
