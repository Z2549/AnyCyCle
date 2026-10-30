package io.github.z2549.anycycle.xposed

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnyCyCle 配置键、默认值与 JSON 序列化。
 *
 * 同一份代码同时被模块 App（通过 RemotePreferences 写入）与
 * SystemUI 进程内的 Hook（只读 RemotePreferences）使用。
 * 配置通道：libxposed API 102 RemotePreferences（借鉴 lsp_CycleBattery 的做法）。
 */
object AnyCyCleConfig {

    /** RemotePreferences 分组名，两端必须一致 */
    const val PREF_GROUP = "anycycle_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_HIDE_ICONS = "hide_icons"
    const val KEY_OFFSET_X_DP = "offset_x_dp"
    const val KEY_OFFSET_Y_DP = "offset_y_dp"

    /** 挖孔半径微调（dp）：估算的挖孔半径偏大/偏小时校正，正值 = 向外扩 */
    const val KEY_CUTOUT_EXTRA_DP = "cutout_extra_dp"

    /** 环嵌套顺序（内 → 外），逗号分隔的环 ID：wifi / sim1 / sim2 */
    const val KEY_RING_ORDER = "ring_order"

    /** v1.1 一次性迁移标记：挖孔中心垂直校正内置后，清零历史手动补偿的垂直偏移 */
    const val KEY_MIGRATED_V11 = "migrated_v11_center"

    /** 显示内容位掩码：bit0=WIFI(1)、bit1=SIM1(2)、bit2=SIM2(4)，可叠加 */
    const val KEY_DISPLAY_MASK = "display_mask"

    /** 每环配置 JSON 键 */
    const val KEY_RING_WIFI = "ring_wifi"
    const val KEY_RING_SIM1 = "ring_sim1"
    const val KEY_RING_SIM2 = "ring_sim2"

    /** 显示位掩码 */
    const val BIT_WIFI = 1
    const val BIT_SIM1 = 2
    const val BIT_SIM2 = 4

    // ------------------------------------------------------------------
    // 默认值
    // ------------------------------------------------------------------

    const val DEFAULT_ENABLED = true

    /** 默认显示 WiFi（用户要求默认 WiFi） */
    const val DEFAULT_DISPLAY_MASK = BIT_WIFI

    /** 启用显示时隐藏状态栏对应原版图标（HyperCeiler 方式） */
    const val DEFAULT_HIDE_ICONS = true

    /** 环 ID（嵌套顺序配置使用） */
    const val RING_WIFI_ID = "wifi"
    const val RING_SIM1_ID = "sim1"
    const val RING_SIM2_ID = "sim2"

    /** 默认嵌套顺序（内 → 外）：最内环 WiFi（用户要求），SIM1、SIM2 依次向外 */
    const val DEFAULT_RING_ORDER = "wifi,sim1,sim2"

    /** 挖孔半径微调默认值与范围（dp） */
    const val DEFAULT_CUTOUT_EXTRA_DP = 0f
    const val CUTOUT_EXTRA_MIN_DP = -6f
    const val CUTOUT_EXTRA_MAX_DP = 6f

    /** 相对默认锚点（状态栏右侧）的偏移（dp） */
    const val DEFAULT_OFFSET_X_DP = 0f
    const val DEFAULT_OFFSET_Y_DP = 0f
    const val OFFSET_MIN_DP = -120f
    const val OFFSET_MAX_DP = 120f

    const val STROKE_MIN_DP = 1f
    const val STROKE_MAX_DP = 8f

    // ------------------------------------------------------------------
    // 每环配置模型
    // ------------------------------------------------------------------

    /**
     * 档位变色规则：信号档位 [0,4] 落在 (上一条 max, 本条 max] 区间时使用该颜色。
     * 匹配顺序按 maxLevel 升序，取第一条 level <= maxLevel 的启用规则；
     * 无匹配时用 baseColor。
     */
    data class ColorRule(
        val enabled: Boolean = true,
        val maxLevel: Int = 0,
        val color: Int = 0xFFFF3B30.toInt(),
    )

    data class RingCfg(
        val strokeDp: Float = 2.5f,
        val baseColor: Int = 0xFFFFFFFF.toInt(),
        val showTrack: Boolean = true,
        val rules: List<ColorRule> = defaultRules(),
    )

    /** 默认五档变色（红/橙/黄/浅绿/绿），阈值 = 档位号本身，用户可逐条改 */
    fun defaultRules(): List<ColorRule> = listOf(
        ColorRule(true, 0, 0xFFD32F2F.toInt()),
        ColorRule(true, 1, 0xFFF57C00.toInt()),
        ColorRule(true, 2, 0xFFFBC02D.toInt()),
        ColorRule(true, 3, 0xFF8BC34A.toInt()),
        ColorRule(true, 4, 0xFF43A047.toInt()),
    )

    fun defaultWifi(): RingCfg = RingCfg(baseColor = 0xFFFFFFFF.toInt())
    fun defaultSim1(): RingCfg = RingCfg(baseColor = 0xFF2196F3.toInt())
    fun defaultSim2(): RingCfg = RingCfg(baseColor = 0xFFAB47BC.toInt())

    // ------------------------------------------------------------------
    // JSON 序列化（org.json，Android 平台自带，纯 Java 环境可用）
    // ------------------------------------------------------------------

    fun ringToJson(cfg: RingCfg): String {
        val arr = JSONArray()
        for (r in cfg.rules) {
            arr.put(
                JSONObject()
                    .put("en", r.enabled)
                    .put("max", r.maxLevel)
                    .put("color", r.color)
            )
        }
        return JSONObject()
            .put("stroke", cfg.strokeDp.toDouble())
            .put("baseColor", cfg.baseColor)
            .put("showTrack", cfg.showTrack)
            .put("rules", arr)
            .toString()
    }

    fun ringFromJson(json: String?, fallback: RingCfg): RingCfg {
        if (json.isNullOrEmpty()) return fallback
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("rules")
            val rules = ArrayList<ColorRule>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val ro = arr.optJSONObject(i) ?: continue
                    rules.add(
                        ColorRule(
                            enabled = ro.optBoolean("en", true),
                            maxLevel = ro.optInt("max", 4),
                            color = ro.optInt("color", fallback.baseColor),
                        )
                    )
                }
            }
            RingCfg(
                strokeDp = o.optDouble("stroke", fallback.strokeDp.toDouble()).toFloat(),
                baseColor = o.optInt("baseColor", fallback.baseColor),
                showTrack = o.optBoolean("showTrack", fallback.showTrack),
                rules = rules.ifEmpty { fallback.rules },
            )
        } catch (_: Throwable) {
            fallback
        }
    }

    fun writeRing(prefs: SharedPreferences, key: String, cfg: RingCfg) {
        prefs.edit().putString(key, ringToJson(cfg)).apply()
    }

    // ------------------------------------------------------------------
    // Hook 端整体读取
    // ------------------------------------------------------------------

    /**
     * 解析并纠正嵌套顺序：只保留合法环 ID 并去重，缺失的按默认顺序补到末尾，
     * 保证结果恒为三个环 ID 的排列（内 → 外）。
     */
    fun normalizeRingOrder(raw: String?): List<String> {
        val all = listOf(RING_WIFI_ID, RING_SIM1_ID, RING_SIM2_ID)
        val picked = raw?.split(',')?.map { it.trim() }?.filter { it in all }?.distinct()
            ?: emptyList()
        return picked + all.filter { it !in picked }
    }

    data class Cfg(
        val enabled: Boolean = DEFAULT_ENABLED,
        val displayMask: Int = DEFAULT_DISPLAY_MASK,
        val hideIcons: Boolean = DEFAULT_HIDE_ICONS,
        val cutoutExtraDp: Float = DEFAULT_CUTOUT_EXTRA_DP,
        val ringOrder: List<String> = normalizeRingOrder(DEFAULT_RING_ORDER),
        val offsetXDp: Float = DEFAULT_OFFSET_X_DP,
        val offsetYDp: Float = DEFAULT_OFFSET_Y_DP,
        val wifi: RingCfg = defaultWifi(),
        val sim1: RingCfg = defaultSim1(),
        val sim2: RingCfg = defaultSim2(),
    )

    fun defaults(): Cfg = Cfg()

    fun read(prefs: SharedPreferences): Cfg = Cfg(
        enabled = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
        displayMask = prefs.getInt(KEY_DISPLAY_MASK, DEFAULT_DISPLAY_MASK),
        hideIcons = prefs.getBoolean(KEY_HIDE_ICONS, DEFAULT_HIDE_ICONS),
        cutoutExtraDp = prefs.getFloat(KEY_CUTOUT_EXTRA_DP, DEFAULT_CUTOUT_EXTRA_DP),
        ringOrder = normalizeRingOrder(prefs.getString(KEY_RING_ORDER, null)),
        offsetXDp = prefs.getFloat(KEY_OFFSET_X_DP, DEFAULT_OFFSET_X_DP),
        offsetYDp = prefs.getFloat(KEY_OFFSET_Y_DP, DEFAULT_OFFSET_Y_DP),
        wifi = ringFromJson(prefs.getString(KEY_RING_WIFI, null), defaultWifi()),
        sim1 = ringFromJson(prefs.getString(KEY_RING_SIM1, null), defaultSim1()),
        sim2 = ringFromJson(prefs.getString(KEY_RING_SIM2, null), defaultSim2()),
    )
}
