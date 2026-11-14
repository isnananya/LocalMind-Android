package com.localmind.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "DeviceSense"

/**
 * 设备感知：**电量 / 位置 / 你那边天气**。
 *
 * 这是人设聊天（人机恋）那一层的东西 —— 有了它，"关心"才有由头：
 * 「这么晚还在外面？」「你那边降温了，记得加衣服」「你手机快没电了，先充上」。
 * 没有它，模型能说的只有空泛的"注意身体"。
 *
 * ## 三条设计原则
 *
 * 1. **按需读，不是每回合读。** [needs] 用规则判断这句话是不是聊到了相关内容，
 *    只有聊到了才真的去读一次。规则判断不花任何模型调用，也永远不会"因为判断
 *    失败而拖慢聊天"。
 * 2. **位置只在设备里加工。** 给模型的是「浙江省杭州市西湖区」这种人话，
 *    **经纬度不给模型** —— 它不需要，也不该拿到。经纬度只用于查天气那一次网络请求。
 * 3. **读不到就不注入。** 定位关着、权限被拒、没有网络、Geocoder 后端缺失……
 *    任何一种情况都返回 null，调用方**什么都不加**。绝不塞一段"读取失败"进提示词。
 *
 * ## 多机型 / 多品牌的适配点（都是实测会遇到的）
 *
 * * 电量走 BatteryManager + ACTION_BATTERY_CHANGED 粘性广播，不需要权限，
 *   任何品牌都一样。温度、充电器类型这些字段**可能缺失**，全部按"没有就算了"处理。
 * * 定位只用 AOSP 的 LocationManager。不引 Google Play Services（国内机型大多没有），
 *   也不碰任何厂商私有 API。provider 按 网络 → GPS → PASSIVE 的顺序试：
 *   网络定位室内就有、够城市级；GPS 慢但准，作为兜底。
 * * getLastKnownLocation 先看一眼历史位置：15 分钟内的直接用，一次都不用等。
 *   这一步在真机上命中率很高，也是"读得快"的关键。
 * * 反向地理编码（经纬度 → 地名）用 Geocoder。**部分机型/部分区域没有后端**，
 *   会返回 null 或抛异常（国产 ROM 尤其常见），所以一定有回退：退回
 *   「北纬 30.2°、东经 120.1° 附近」，至少天气还能查。
 * * 定位总开关可能被用户关掉（Settings.Secure.LOCATION_MODE），
 *   这时**不要**去弹系统设置页，安静返回 null。
 */
object DeviceSense {

    // ---------------------------------------------------------------- 数据

    /** 电量。 */
    data class Battery(
        val percent: Int,
        val charging: Boolean,
        /** 充电器 / USB / 无线充电；未知为空串。 */
        val source: String,
        /** 电池温度（摄氏度）。很多机型不报这个值。 */
        val temperatureC: Float?,
        val powerSave: Boolean,
    )

    /** 位置。[lat]/[lon] **只在本机使用**，不写进提示词。 */
    data class Place(
        val lat: Double,
        val lon: Double,
        /** 人话地名，例如「浙江省杭州市西湖区」；拿不到就退回坐标描述。 */
        val label: String,
        /** 这条位置有多旧（毫秒）。 */
        val ageMs: Long,
        val accuracyM: Float?,
        /** 来自哪个 provider，仅用于诊断。 */
        val source: String,
    )

    /** 天气。 */
    data class Weather(
        val text: String,
        val tempC: Double,
        val feelsC: Double?,
        val humidity: Int?,
        val windKmh: Double?,
        val precipMm: Double?,
        val isDay: Boolean,
        /** 天气服务报的时区，例如 Asia/Shanghai。用来兜底地名，见 [regionFromTimezone]。 */
        val timezone: String? = null,
    )

    /** 这一句话需要读哪些东西。 */
    enum class Need { BATTERY, LOCATION, WEATHER }

    // ---------------------------------------------------------------- 缓存

    // 「按需读」还不够：同一段对话里连着问三次天气，不该打三次网络。
    // TTL 按各自的变化速度定 —— 电量秒级变，位置分钟级变，天气半小时级变。
    private const val BATTERY_TTL_MS = 45_000L
    private const val PLACE_TTL_MS = 10 * 60_000L
    private const val WEATHER_TTL_MS = 25 * 60_000L

    /**
     * 最近一次反向地理编码到底成没成。null = 还没试过。
     *
     * 为什么要记它：**Geocoder.isPresent() 会撒谎** —— 模拟器和不少国产 ROM 上它返回
     * true，真去解析却抛 TimeoutException（本机实测就是这样）。只有"上一次真的成没成"
     * 才是证据，诊断页显示的必须是这个，而不是一个乐观的"支持"。
     */
    @Volatile
    var lastGeocodeOk: Boolean? = null
        private set

    @Volatile private var batteryCache: Pair<Long, Battery>? = null
    @Volatile private var placeCache: Pair<Long, Place>? = null
    @Volatile private var weatherCache: Triple<Long, Double, Weather>? = null

    /** 测试/诊断用：清掉缓存，下一次一定真读。 */
    fun invalidate() {
        batteryCache = null
        placeCache = null
        weatherCache = null
    }

    // ---------------------------------------------------------------- 意图

    private val BATTERY_WORDS = listOf(
        "电量", "电池", "没电", "快没电", "要没电", "充电", "充满", "续航", "省电",
        "battery", "charge", "low power",
    )
    private val LOCATION_WORDS = listOf(
        "在哪", "哪里", "哪个城市", "什么地方", "定位", "位置", "坐标", "这边", "那边",
        "出差", "搬去", "搬到", "回老家", "where are you", "location", "which city",
    )
    private val WEATHER_WORDS = listOf(
        "天气", "下雨", "下雪", "下雹", "阴天", "晴天", "多云", "降温", "升温", "气温",
        "多少度", "几度", "冷不冷", "热不热", "好冷", "好热", "穿什么", "带伞", "台风",
        "雾霾", "空气", "湿度", "风大",
        "weather", "rain", "snow", "temperature", "cold", "hot outside", "forecast",
    )

    /**
     * 「这句话要不要读设备」——纯规则，**零成本**，也零风险。
     *
     * 为什么不用决策模型来判断：这一步跑在用户那句话**之前**，占了引擎就等于把
     * 首 token 往后推。规则能覆盖的都是很明确的词（"下雨""电量""在哪"），
     * 判错的代价也只是"少读一次"，模型下一句还能再问。
     *
     * @param enabled 这个对话开了哪几项；没开的直接不算，省得白读。
     */
    fun needs(text: String, enabled: Set<Need>): Set<Need> {
        if (text.isBlank() || enabled.isEmpty()) return emptySet()
        val t = text.lowercase(Locale.ROOT)
        val out = mutableSetOf<Need>()
        if (Need.BATTERY in enabled && BATTERY_WORDS.any { t.contains(it) }) out += Need.BATTERY
        // 问天气的人，通常也想知道"你那边" —— 天气必须靠定位才拿得到。
        if (Need.WEATHER in enabled && WEATHER_WORDS.any { t.contains(it) }) out += Need.WEATHER
        if (Need.LOCATION in enabled && LOCATION_WORDS.any { t.contains(it) }) out += Need.LOCATION
        return out
    }

    // ---------------------------------------------------------------- 电量

    /** 电量。不需要权限，也没有任何机型差异。 */
    fun battery(context: Context, force: Boolean = false): Battery? {
        val now = System.currentTimeMillis()
        batteryCache?.let { (at, v) -> if (!force && now - at < BATTERY_TTL_MS) return v }
        val value = runCatching { readBattery(context) }.getOrNull() ?: return null
        batteryCache = now to value
        return value
    }

    @Suppress("DEPRECATION")
    private fun readBattery(context: Context): Battery? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // 粘性广播：注册一个 null receiver 立刻拿到当前状态，不用等下一次变化。
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        var percent = -1
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) percent = level * 100 / scale
        if (percent < 0) percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        // 两个来源都拿不到就别编一个数字出来。
        if (percent < 0) return null

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "充电器"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> ""
        }
        // 温度单位是 0.1℃，而且不少机型压根不报 —— 缺失就留 null。
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (tenths != Int.MIN_VALUE && tenths in 1..2000) tenths / 10f else null
        // 省电模式在 PowerManager 上，不在 BatteryManager 上（这两者很容易记混）。
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saver = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        return Battery(percent.coerceIn(0, 100), charging, source, temp, saver)
    }

    // ---------------------------------------------------------------- 位置

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** 系统的定位总开关。关着的时候**不要**去弹设置页，安静返回 false。 */
    @Suppress("DEPRECATION")
    fun locationEnabled(context: Context): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
        }
    }.getOrDefault(false)

    /**
     * 读一次位置。
     *
     * @param budgetMs 最多等多久（拿不到就算了）。默认 7 秒：城市级定位一般
     *   1-3 秒就有结果，再久多半是室内/GPS 被挡，等下去也没意义。
     */
    suspend fun place(context: Context, budgetMs: Long = 7_000L, force: Boolean = false): Place? {
        val now = System.currentTimeMillis()
        placeCache?.let { (at, v) -> if (!force && now - at < PLACE_TTL_MS) return v }
        if (!hasLocationPermission(context)) return null
        if (!locationEnabled(context)) return null

        return withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext null
            // force = "现在读一次"：连历史位置都不信，一定要一个新的。
            // 普通聊天路径则先用历史位置 —— 15 分钟内的话城市没变、天气也没变，
            // 秒回，不用等 GPS。
            var best = if (force) null else lastKnown(lm)
            val stale = best == null || System.currentTimeMillis() - best.time > 15 * 60_000L
            if (stale) {
                val fix = firstFix(lm, providers(lm), budgetMs)
                if (fix != null && (best == null || fix.time > best.time)) best = fix
            }
            val loc = best ?: return@withContext null
            val place = Place(
                lat = loc.latitude,
                lon = loc.longitude,
                label = reverseGeocode(context, loc),
                ageMs = (System.currentTimeMillis() - loc.time).coerceAtLeast(0),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                source = loc.provider ?: "?",
            )
            placeCache = System.currentTimeMillis() to place
            Log.i(TAG, "定位：" + place.label + "（" + place.source + "，精度 " +
                (place.accuracyM?.toInt() ?: -1) + "m，旧 " + place.ageMs / 1000 + "s）")
            place
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        allProviders().mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }

    /** provider 优先级：快和省电优先，准其次 —— 城市级就够了。 */
    private fun providers(lm: LocationManager): List<String> =
        allProviders().filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

    private fun allProviders(): List<String> = buildList {
        // FUSED 是 API 31+ 的融合 provider：又准又省电，有就先用它。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }

    /**
     * 所有 provider **同时**挂上，谁先给出结果就用谁，总预算 [budgetMs]。
     *
     * ## 为什么不是"一个接一个试"
     *
     * 这是多机型上最容易踩的坑：FUSED provider 在 API 31+ 存在，但**没有 Play 服务的
     * 机器（国内机型、部分模拟器）上它可能永远不回调**。顺序等待的话，它会把整个
     * 预算吃光，后面的网络/GPS 一次机会都没有 —— 表现就是"这个牌子的手机读不到位置"。
     * 并行竞速之后，哪个 provider 能用哪个先回答，坏掉的那个只是没人理它。
     *
     * 用 requestSingleUpdate 而不是 getCurrentLocation：后者要 API 30+，
     * 两套代码路径只会让"某个版本上不能用"更难查。这个方法从 API 1 一直活到现在，
     * 弃用只是标记，行为没有变。
     */
    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun firstFix(lm: LocationManager, providers: List<String>, budgetMs: Long): Location? {
        if (providers.isEmpty()) return null
        return withTimeoutOrNull(budgetMs) {
            val arrived = CompletableDeferred<Location>()
            val listeners = providers.mapNotNull { provider ->
                runCatching { attachOnce(lm, provider) { arrived.complete(it) } }.getOrElse {
                    Log.w(TAG, "挂 " + provider + " 失败：" + it.message)
                    null
                }
            }
            try {
                arrived.await()
            } finally {
                // 超时取消时也要摘掉监听，否则会一直耗电。
                listeners.forEach { l -> runCatching { lm.removeUpdates(l) } }
            }
        }
    }

    /** 挂一个只用一次的监听：第一次回调就自动摘掉自己。 */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun attachOnce(
        lm: LocationManager,
        provider: String,
        onFix: (Location) -> Unit,
    ): LocationListener {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                onFix(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("API < 30 需要这个回调")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        return listener
    }

    /**
     * 经纬度 → 人话地名。
     *
     * 拿不到就退回坐标描述：天气照样能查，只是模型看到的是
     * 「北纬 30.2°、东经 120.1° 附近」而不是「杭州市西湖区」。
     * 这一步是阻塞网络调用，**必须在 IO 线程**（调用方已经在 IO 上）。
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(context: Context, loc: Location): String {
        val fallback = "北纬 %.1f°、东经 %.1f° 附近".format(loc.latitude, loc.longitude)
        if (!Geocoder.isPresent()) {
            lastGeocodeOk = false
            return fallback
        }
        return runCatching {
            val list = Geocoder(context, Locale.getDefault())
                .getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            if (a == null) {
                lastGeocodeOk = false
                return fallback
            }
            // 中国地址习惯「省 市 区」，但各 ROM 填的字段不一样（有的把市填进
            // subAdminArea，有的省市区都塞在 adminArea 里），所以先收集再去重。
            val parts = listOfNotNull(a.adminArea, a.locality, a.subAdminArea, a.subLocality)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            // "杭州市" 和 "杭州市西湖区" 这种包含关系也要去掉一个。
            val trimmed = parts.filterIndexed { i, s ->
                parts.take(i).none { it.contains(s) || s.contains(it) }
            }
            val label = if (trimmed.isEmpty()) {
                a.featureName?.trim().orEmpty().ifEmpty { fallback }
            } else {
                trimmed.take(3).joinToString("")
            }
            lastGeocodeOk = label != fallback
            label
        }.getOrElse {
            // 这一步失败的机型不少（尤其国产 ROM 没有 Google 的后端），
            // 所以只记一行日志，不打扰用户。
            Log.w(TAG, "反向地理编码失败（这台机型可能没有后端）：" + it.message)
            lastGeocodeOk = false
            fallback
        }
    }

    // ---------------------------------------------------------------- 天气

    /**
     * 天气服务端点。
     *
     * 选 Open-Meteo 的理由：**不需要 key**。这个应用的前提是"装完就能用"，
     * 任何要求注册、领 key、有额度的服务都会变成"装好了功能是灰的"。
     * 它按经纬度查、返回 JSON、全球可用。
     *
     * ⚠️ Android **没有**系统级天气接口（各厂商的天气 App 都是自己接的服务，
     * 没有公开 API），所以"通过设备查询天气"能做的部分是**用设备定位**，
     * 天气本身必须走网络 —— 这一点得说清楚，别让人以为是没接好。
     */
    private const val WEATHER_ENDPOINT = "https://api.open-meteo.com/v1/forecast"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun weather(place: Place, force: Boolean = false): Weather? {
        val now = System.currentTimeMillis()
        weatherCache?.let { (at, lat, v) ->
            // 位置变了就重查（换了城市当然不能拿旧天气）。
            if (!force && now - at < WEATHER_TTL_MS && kotlin.math.abs(lat - place.lat) < 0.2) return v
        }
        return withContext(Dispatchers.IO) {
            val url = WEATHER_ENDPOINT +
                "?latitude=%.4f&longitude=%.4f".format(place.lat, place.lon) +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "precipitation,weather_code,wind_speed_10m,is_day" +
                "&timezone=auto"
            val body = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string().orEmpty() else ""
                }
            }.getOrElse {
                Log.w(TAG, "天气查询失败：" + it.message)
                ""
            }
            if (body.isBlank()) return@withContext null
            val current = runCatching {
                json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
            }.getOrNull() ?: return@withContext null

            fun d(key: String): Double? =
                runCatching { current[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
            fun i(key: String): Int? =
                runCatching { current[key]?.jsonPrimitive?.intOrNull }.getOrNull()

            val temp = d("temperature_2m") ?: return@withContext null
            val w = Weather(
                text = describe(i("weather_code") ?: -1),
                tempC = temp,
                feelsC = d("apparent_temperature"),
                humidity = i("relative_humidity_2m"),
                windKmh = d("wind_speed_10m"),
                precipMm = d("precipitation"),
                isDay = i("is_day") != 0,
                timezone = runCatching {
                    json.parseToJsonElement(body).jsonObject["timezone"]?.jsonPrimitive?.content
                }.getOrNull(),
            )
            weatherCache = Triple(now, place.lat, w)
            Log.i(TAG, "天气：" + w.text + " " + w.tempC + "℃")
            w
        }
    }

    /** WMO 天气代码 → 中文。这是 Open-Meteo 用的标准码表。 */
    private fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "局部多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "天气不明"
    }

    /**
     * 没有地名时用**时区**兜一个粗略地区。
     *
     * 为什么需要它：反向地理编码要设备自带后端，**国产 ROM 很多没有**
     *（模拟器上实测就是 TimeoutException）。这时如果只给模型一串经纬度，
     * 它既不知道那是哪，也没法说"你那边"。
     * 而天气服务一定会回一个 IANA 时区（Asia/Shanghai），拿它推一个城市级别的
     * 提示足够了 —— 说"大约在 上海 一带"远好过"北纬 31.2°、东经 121.5° 附近"。
     *
     * 措辞上必须带"大约"：时区覆盖的是一整片区域（Asia/Shanghai 覆盖整个东八区），
     * 它**不是**精确定位，不能说得像知道具体城市。
     */
    fun regionFromTimezone(timezone: String?): String? {
        val tz = timezone?.trim().orEmpty()
        if (tz.isEmpty()) return null
        val city = tz.substringAfterLast('/').replace('_', ' ').trim()
        if (city.isEmpty()) return null
        // IANA 里中国相关的几个：只有这几个是"城市名还好认"的。
        val cn = when (city) {
            "Shanghai" -> "上海"
            "Chongqing" -> "重庆"
            "Urumqi" -> "乌鲁木齐"
            "Harbin" -> "哈尔滨"
            "Kashgar" -> "喀什"
            else -> city
        }
        return cn + "一带"
    }

    /**
     * 给模型/设置页看的地名。
     *
     * 优先用人话地名；只有坐标（说明反向地理编码没成功）时，用天气的时区兜底；
     * 两者都没有才显示坐标。
     */
    fun displayLabel(place: Place, weather: Weather?): String =
        if (place.label.startsWith("北纬")) {
            regionFromTimezone(weather?.timezone) ?: place.label
        } else {
            place.label
        }

    // ---------------------------------------------------------------- 提示词

    /**
     * 拼成给模型看的一小段。
     *
     * 三条措辞上的讲究：
     *  * 说清"这是**刚刚**读到的真实信息"，否则小模型会当成背景设定，不敢用；
     *  * 说清"**可以提，但别每次复述**" —— 否则它会变成每句话都汇报天气的播报机；
     *  * 不给经纬度，只给地名。
     */
    fun buildBlock(
        battery: Battery?,
        place: Place?,
        weather: Weather?,
        userNickname: String,
    ): String {
        if (battery == null && place == null && weather == null) return ""
        val sb = StringBuilder()
        sb.append("\n\n【你现在能感知到的】")
        sb.append("（这些是刚刚从设备上读到的真实信息，不是设定。可以自然地提起，")
        sb.append("但不要每次复述一遍，也不要逐条汇报。）")
        battery?.let { b ->
            sb.append("\n- ").append(userNickname).append("的手机电量：").append(b.percent).append("%")
            if (b.charging) {
                sb.append("，正在充电")
                if (b.source.isNotBlank()) sb.append("（").append(b.source).append("）")
            } else {
                sb.append("，没在充电")
                if (b.percent <= 20) sb.append("（很低了，可以提醒一句）")
            }
            if (b.powerSave) sb.append("，开了省电模式")
            b.temperatureC?.let { t -> if (t >= 40f) sb.append("，机身有点烫") }
        }
        place?.let { p ->
            sb.append("\n- ").append(userNickname).append("大概在：").append(displayLabel(p, weather))
        }
        weather?.let { w ->
            sb.append("\n- 那边现在的天气：").append(w.text).append("，").append(fmtTemp(w.tempC))
            w.feelsC?.let { f ->
                if (kotlin.math.abs(f - w.tempC) >= 2.0) {
                    sb.append("（体感 ").append(fmtTemp(f)).append("）")
                }
            }
            w.humidity?.let { h -> sb.append("，湿度 ").append(h).append("%") }
            w.windKmh?.let { v -> if (v >= 15) sb.append("，风有点大（").append(v.toInt()).append(" km/h）") }
        }
        return sb.toString()
    }

    private fun fmtTemp(t: Double): String =
        (if (t >= 0) "" else "-") + "%.1f".format(kotlin.math.abs(t)) + "℃"

    /**
     * 设置页「现在读一次」用：把当前能读到的全部读一遍，返回人话。
     *
     * 和聊天路径共用同一套读取函数，所以它显示的就是模型会看到的东西 ——
     * 一个只能"演示"的按钮没有意义。
     */
    suspend fun snapshot(context: Context, need: Set<Need>): String {
        val sb = StringBuilder()
        if (Need.BATTERY in need) {
            val b = battery(context, force = true)
            sb.append(
                if (b == null) "电量：读不到" else
                    "电量：" + b.percent + "%" +
                        (if (b.charging) "（充电中" + (if (b.source.isNotBlank()) "·" + b.source else "") + "）"
                         else "（未充电）") +
                        (if (b.powerSave) "·省电模式" else "")
            )
            sb.append('\n')
        }
        val wantPlace = Need.LOCATION in need || Need.WEATHER in need
        val p = if (wantPlace) place(context, force = true) else null
        // 天气先查：位置的兜底地名要用到时区。
        val w0 = if (Need.WEATHER in need && p != null) weather(p, force = true) else null
        if (Need.LOCATION in need) {
            sb.append(
                if (p == null) "位置：读不到（权限 / 定位开关 / 没信号）"
                else "位置：" + displayLabel(p, w0)
            )
            sb.append('\n')
        }
        if (Need.WEATHER in need) {
            val w = w0
            sb.append(
                when {
                    p == null -> "天气：需要先拿到位置"
                    w == null -> "天气：查不到（网络不通或服务不可用）"
                    else -> "天气：" + w.text + " " + fmtTemp(w.tempC) +
                        (w.feelsC?.let { "（体感 " + fmtTemp(it) + "）" } ?: "") +
                        (w.humidity?.let { " 湿度" + it + "%" } ?: "")
                }
            )
        }
        return sb.toString().trim()
    }
}
