package io.github.z2549.anycycle.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * AnyCyCle 模块入口（libxposed API 102，META-INF/xposed/java_init.list 注册）。
 *
 * 作用域静态锁定为 com.android.systemui（META-INF/xposed/scope.list + module.prop staticScope）。
 *
 * 架构（借鉴 imRoxy2222/lsp_CycleBattery 的悬浮窗注入方案）：
 *  - 唯一的框架 Hook 是 Instrumentation#callApplicationOnCreate（永不混淆），
 *    在 SystemUI Application 创建后拿到上下文；
 *  - 通过 WindowManager 添加不可触摸的系统内部窗口（TYPE_STATUS_BAR_ADDITIONAL），
 *    窗口只覆盖挖孔附近的局部区域（2R×2R，半径由 SignalRingView 按配置自报），
 *    由纯 Java 的 SignalRingView 绘制嵌套信号环——避免全屏 layer 的整屏级
 *    合成重算与对 LTPO 刷新率决策的干扰；
 *  - 图标隐藏（借鉴 HyperCeiler 的 IconsFromSystemManager 实现）：Hook
 *    StatusBarIconControllerImpl#setIcon / #setIconVisibility，将显示环对应的
 *    原版状态栏图标（wifi / mobile / no_sim 等 slot）的 visible 置 false。
 *
 * 防崩：Hook 设 ExceptionMode.PROTECTIVE，所有回调逐层 try-catch；
 * 诊断信息用 WARN 级别（release 下 info 会被静默）。
 */
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "AnyCyCle"
        private const val TARGET_PACKAGE = "com.android.systemui"

        /** 模块自绘视图类（纯 Java，由模块 dex 经 PathClassLoader 加载） */
        private const val RING_VIEW_CLASS = "io.github.z2549.anycycle.xposed.SignalRingView"
    }

    private val prefs by lazy { getRemotePreferences(AnyCyCleConfig.PREF_GROUP) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var ringViewClass: Class<*>? = null

    /** SystemUI Application 上下文（Application onCreate 后可用） */
    private var appContext: Context? = null

    /** 当前已添加的悬浮窗视图 */
    private var overlayView: View? = null

    /** 仅首次打印开关（避免刷屏） */
    private var cfgLogged = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "module loaded, process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != TARGET_PACKAGE) return

        // 1) 加载模块 dex 中的 SignalRingView（纯 Java 类）
        try {
            val moduleInfo = moduleApplicationInfo
            val moduleCl = PathClassLoader(
                moduleInfo.sourceDir,
                moduleInfo.nativeLibraryDir,
                param.defaultClassLoader,
            )
            ringViewClass = try {
                moduleCl.loadClass(RING_VIEW_CLASS)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "cannot load SignalRingView from module apk", t)
                null
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "create module classloader failed", t)
        }

        // 2) 图标隐藏（独立于悬浮窗：即使悬浮窗失败也要生效）
        try {
            IconHider.init(this, param.defaultClassLoader) { prio, msg, t ->
                if (t != null) {
                    log(prio, TAG, msg, t)
                } else {
                    log(prio, TAG, msg)
                }
            }
            // HyperOS BLOCK_LIST 通道必须在状态栏图标控制器初始化之前就绪
            IconHider.initBlockList(param.defaultClassLoader)
            IconHider.updateRules(readCfg())
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "IconHider init failed", t)
        }

        // 3) 配置监听 + Application 创建时机
        try {
            registerPrefsListener()
            hookApplicationCreate()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook setup failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Hook：SystemUI Application 创建完成
    // ------------------------------------------------------------------

    private fun hookApplicationCreate() {
        try {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = try {
                            chain.proceed()
                        } catch (t: Throwable) {
                            null
                        }
                        try {
                            val app = chain.getArg(0) as? Application
                            if (app != null) {
                                appContext = app.applicationContext
                                syncOverlay(app.applicationContext)
                            }
                        } catch (t: Throwable) {
                            log(Log.WARN, TAG, "sync overlay failed", t)
                        }
                        return result
                    }
                })
            log(Log.INFO, TAG, "hooked Instrumentation#callApplicationOnCreate")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook application create failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private fun readCfg(): AnyCyCleConfig.Cfg = try {
        AnyCyCleConfig.read(prefs)
    } catch (_: Throwable) {
        AnyCyCleConfig.defaults()
    }

    private fun registerPrefsListener() {
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                try {
                    val cfg = readCfg()
                    IconHider.updateRules(cfg)
                    val ctx = appContext
                    if (ctx != null) syncOverlay(ctx)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "register prefs listener failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 悬浮窗管理
    // ------------------------------------------------------------------

    private fun syncOverlay(context: Context) {
        mainHandler.post {
            try {
                syncOverlayInternal(context.applicationContext)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "sync overlay internal failed", t)
            }
        }
    }

    private fun syncOverlayInternal(ctx: Context) {
        val cfg = readCfg()
        if (!cfgLogged) {
            cfgLogged = true
            log(
                Log.WARN, TAG,
                "cfg: enabled=${cfg.enabled} mask=${cfg.displayMask} hideIcons=${cfg.hideIcons} " +
                    "order=${cfg.ringOrder} wifiRules=${cfg.wifi.rules.size}",
            )
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (!cfg.enabled || cfg.displayMask == 0) {
            overlayView?.let { view ->
                try {
                    wm.removeView(view)
                } catch (_: Throwable) {
                }
            }
            overlayView = null
            return
        }

        val view = overlayView
        if (view != null) {
            // 已存在的窗口：applyConfig 后按 View 自报半径校正窗口尺寸/位置
            applyViewConfig(view)
            return
        }

        val cls = ringViewClass ?: return
        val created = try {
            val ctor = cls.getConstructor(Context::class.java)
            ctor.newInstance(ctx) as View
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "create SignalRingView failed", t)
            return
        }

        try {
            // 首帧 View 尚未 attach、无法自报半径，按 MainHook 估算值建局部窗口；
            // addView 后 applyViewConfig 会用 View 的权威半径校正
            wm.addView(created, buildParams(cfg, estimateRequiredRadiusPx(cfg)))
            overlayView = created
            log(Log.WARN, TAG, "signal ring overlay added (local window)")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "addView failed", t)
            return
        }
        applyViewConfig(created)
        scheduleWindowCalibration(created)
    }

    /**
     * 环悬浮窗参数：只覆盖挖孔附近的局部窗口（2R × 2R），不再全屏——
     * 全屏 layer 每次配置变更都要整屏级重算合成，且对 LTPO 刷新率决策有额外干扰。
     *
     * 类型仍优先 TYPE_STATUS_BAR_ADDITIONAL（回退 TYPE_STATUS_BAR_SUB_PANEL /
     * TYPE_APPLICATION_OVERLAY）。该类型被 WMS policy 垂直下推一个状态栏高度
     * （实测 frame.top = y + sbh），y 里先抵消；x 不被策略改写。
     *
     * @param radiusPx 覆盖全部启用环所需的外接半径 px（View 自报或 MainHook 估算）；
     *                 <= 0 时兜底回退全屏窗口（旧行为）
     */
    private fun buildParams(cfg: AnyCyCleConfig.Cfg, radiusPx: Float): WindowManager.LayoutParams {
        val wmLp = WindowManager.LayoutParams::class.java
        val type = listOf("TYPE_STATUS_BAR_ADDITIONAL", "TYPE_STATUS_BAR_SUB_PANEL")
            .mapNotNull { name ->
                try {
                    wmLp.getField(name).getInt(null)
                } catch (_: Throwable) {
                    null
                }
            }
            .firstOrNull() ?: WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val dm = appContext?.resources?.displayMetrics
        val sbh = statusBarHeightPx()
        val density = dm?.density ?: 3f
        val screenW = dm?.widthPixels ?: 1080

        if (radiusPx <= 0f || sbh <= 0) {
            // 兜底：拿不到环半径（反射失败/无状态栏高度）时回退全屏窗口
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "AnyCyCleRing"
                try {
                    setFitInsetsTypes(0)
                } catch (_: Throwable) {
                }
                y = -sbh
            }
        }

        // 环中心估计：屏幕水平中心 + 用户水平偏移；垂直 = 状态栏中线 + 用户垂直偏移。
        // View 端另有 0.6dp 垂直校正与 cutout 锚点精修，半径里的 6dp 余量可覆盖其差值。
        val cx = screenW / 2f + cfg.offsetXDp * density
        val cy = sbh / 2f + cfg.offsetYDp * density
        val size = (radiusPx * 2f + 0.5f).toInt().coerceAtLeast((8f * density).toInt())

        return WindowManager.LayoutParams(size, size, type, flags, PixelFormat.TRANSLUCENT).apply {
            title = "AnyCyCleRing"
            try {
                setFitInsetsTypes(0)
            } catch (_: Throwable) {
            }
            // WMS 对该类型的策略偏移（actual - requested）不固定：全屏窗口实测为 +sbh，
            // 局部窗口实测为 0（HyperOS）。不做硬编码抵消，由 attach 后的
            // scheduleWindowCalibration 实测 policyDelta 并在此补偿。
            x = (cx - size / 2f).toInt() - policyDelta[0]
            y = (cy - size / 2f).toInt() - policyDelta[1]
        }
    }

    /**
     * 通过反射调用 SignalRingView.applyConfig(String)（纯 Java 类，避免 Kotlin Intrinsics 问题）。
     *
     * @return View 自报的所需外接半径 px；<=0 表示调用失败（签名不匹配/异常），
     *         调用方保持现有窗口不动（buildParams 会兜底全屏）。
     */
    private fun applyViewConfig(view: View): Float {
        val radius: Float = try {
            val json = buildViewConfigJson(readCfg())
            val m = view.javaClass.getMethod("applyConfig", String::class.java)
            (m.invoke(view, json) as? Float) ?: -1f
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "apply view config failed", t)
            -1f
        }
        if (radius <= 0f) return radius

        // 窗口尺寸/位置按 View 自报半径校正（首帧估算偏差、配置变化都会走到这里）
        try {
            val want = buildParams(readCfg(), radius)
            val cur = view.layoutParams as? WindowManager.LayoutParams
            if (cur == null || cur.width != want.width || cur.height != want.height ||
                cur.x != want.x || cur.y != want.y
            ) {
                val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(view, want)
                scheduleWindowCalibration(view)
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "update window frame failed", t)
        }
        return radius
    }

    private fun statusBarHeightPx(): Int = try {
        val res = appContext?.resources
        res?.getDimensionPixelSize(
            res.getIdentifier("status_bar_height", "dimen", "android")
        ) ?: 0
    } catch (_: Throwable) {
        0
    }

    /**
     * WMS 对环窗口的策略偏移实测值（actual frame origin - requested x/y）。
     * 该值随 ROM 与窗口尺寸而异（全屏窗口实测 +sbh、局部窗口实测 0），
     * 首帧按 0 放置，attach 后由 scheduleWindowCalibration 实测回填。
     */
    private val policyDelta = IntArray(2)

    /** 在窗口完成一轮布局后实测策略偏移；有偏差就更新 policyDelta 并立即纠正窗口位置 */
    private fun scheduleWindowCalibration(view: View) {
        view.postDelayed({
            try {
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@postDelayed
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val dx = loc[0] - lp.x
                val dy = loc[1] - lp.y
                if (dx != 0 || dy != 0) {
                    policyDelta[0] = dx
                    policyDelta[1] = dy
                    lp.x -= dx
                    lp.y -= dy
                    val wm =
                        view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.updateViewLayout(view, lp)
                    log(
                        Log.WARN, TAG,
                        "window calibrated: policyDelta=($dx,$dy) frame=(${lp.x},${lp.y}) ${lp.width}x${lp.height}",
                    )
                } else {
                    log(
                        Log.WARN, TAG,
                        "window calibrated: no policy offset frame=(${lp.x},${lp.y}) ${lp.width}x${lp.height}",
                    )
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "window calibration failed", t)
            }
        }, 400L)
    }

    /** 估算挖孔半径 px（Display 级 cutout；拿不到回退状态栏高度的 32%，与 View 端口径一致） */
    private fun estimateCutoutRadiusPx(sbh: Int): Float {
        val ctx = appContext ?: return sbh * 0.32f
        val dm = ctx.resources.displayMetrics
        return try {
            val rect = ctx.display?.cutout?.boundingRectTop
            if (rect != null && !rect.isEmpty) {
                minOf(rect.width(), rect.height()) / 2f
            } else {
                sbh * 0.32f
            }
        } catch (_: Throwable) {
            sbh * 0.32f
        }
    }

    /**
     * 估算环外接半径 px（首帧建窗用，口径与 SignalRingView.requiredRadiusPx 一致：
     * cutout 半径 + EDGE_GAP + 各启用环粗细（含环间分割线）+ 6dp 余量）。
     * attach 后由 View 自报半径校正。
     */
    private fun estimateRequiredRadiusPx(cfg: AnyCyCleConfig.Cfg): Float {
        val dm = appContext?.resources?.displayMetrics ?: return -1f
        val sbh = statusBarHeightPx()
        if (sbh <= 0) return -1f
        val d = dm.density
        var cursor = estimateCutoutRadiusPx(sbh) + 1f * d
        val divW = cfg.dividerWidthDp * d
        var drewAny = false
        for (id in cfg.ringOrder) {
            val ring: AnyCyCleConfig.RingCfg?
            val bit: Int
            when (id) {
                AnyCyCleConfig.RING_WIFI_ID -> {
                    ring = cfg.wifi; bit = AnyCyCleConfig.BIT_WIFI
                }
                AnyCyCleConfig.RING_SIM1_ID -> {
                    ring = cfg.sim1; bit = AnyCyCleConfig.BIT_SIM1
                }
                else -> {
                    ring = cfg.sim2; bit = AnyCyCleConfig.BIT_SIM2
                }
            }
            if (ring == null || cfg.displayMask and bit == 0) continue
            if (drewAny && divW > 0.5f) cursor += divW
            cursor += maxOf(ring.strokeDp * d, 1f)
            drewAny = true
        }
        return cursor + 6f * d
    }

    /** 组装推送给 SignalRingView 的配置 JSON（与 AnyCyCleConfig 的序列化格式一致） */
    private fun buildViewConfigJson(cfg: AnyCyCleConfig.Cfg): String {
        val o = JSONObject()
        o.put("mask", cfg.displayMask)
        o.put("ox", cfg.offsetXDp.toDouble())
        o.put("oy", cfg.offsetYDp.toDouble())
        o.put("cutoutExtra", cfg.cutoutExtraDp.toDouble())
        // 环间分割线：宽度 dp（0=关）与颜色
        o.put("divW", cfg.dividerWidthDp.toDouble())
        o.put("divColor", cfg.dividerColor)
        // 嵌套顺序（内 → 外），SignalRingView 按此从挖孔边缘向外堆叠
        o.put("order", JSONArray(cfg.ringOrder))
        o.put("wifi", JSONObject(AnyCyCleConfig.ringToJson(cfg.wifi)))
        o.put("sim1", JSONObject(AnyCyCleConfig.ringToJson(cfg.sim1)))
        o.put("sim2", JSONObject(AnyCyCleConfig.ringToJson(cfg.sim2)))
        return o.toString()
    }
}

/**
 * 状态栏原版图标隐藏（实现方式借鉴 HyperCeiler 的 IconsFromSystemManager，AGPL-3.0）。
 *
 * Hook com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl（新路径）/
 * com.android.systemui.statusbar.phone.StatusBarIconControllerImpl（旧路径）：
 *  - setIcon(String, StatusBarIcon)：直接把 icon.visible 置 false
 *  - setIcon(String, StatusBarIconHolder)：取 holder.mIcon 再置 false
 *  - setIconVisibility(String, boolean)：强制 visible=false
 *
 * slot 名参考 HyperCeiler 的 SwapWiFiAndMobileNetwork（HyperOS）：
 *  - WiFi: wifi / demo_wifi
 *  - SIM: mobile / demo_mobile / no_sim
 */
object IconHider {

    // stacked_mobile：HyperOS 双卡叠加信号图标（小米 15 实测实际显示走这个 slot）
    private val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    private val MOBILE_SLOTS = setOf("mobile", "demo_mobile", "no_sim", "stacked_mobile")

    /** 诊断：记录系统实际使用过的图标 slot 名（限 60 条，用于定位 HyperOS 移动信号 slot） */
    private val seenSlots = LinkedHashSet<String>()
    private var slotLogCount = 0

    private fun logSlot(slot: String, logFn: (Int, String, Throwable?) -> Unit) {
        try {
            if (slotLogCount >= 60) return
            if (seenSlots.add(slot)) {
                slotLogCount++
                logFn(
                    Log.WARN,
                    "slot seen: \"$slot\" hideWifi=$hideWifi hideMobile=$hideMobile",
                    null,
                )
            }
        } catch (_: Throwable) {
        }
    }

    private const val TAG = "AnyCyCle"
    private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
    private const val STATUS_BAR_ICON_HOLDER = "com.android.systemui.statusbar.phone.StatusBarIconHolder"

    @Volatile
    private var hideWifi = false

    @Volatile
    private var hideMobile = false

    private var logger: ((Int, String, Throwable?) -> Unit)? = null
    private var hookedOnce = false

    /** HyperOS 的图标屏蔽列表宿主类（HyperCeiler StatusBarIcon.java 同款路径） */
    private var miuiUtilsClass: Class<*>? = null
    private var blockListReady = false

    /**
     * 初始化 HyperOS MiuiIconManagerUtils BLOCK_LIST 通道。
     * HyperCeiler 隐藏 wifi/mobile 图标实际走这条：把 slot 名加入
     * RIGHT_BLOCK_LIST / CONTROL_CENTER_BLOCK_LIST 静态列表。
     * 必须在状态栏图标控制器初始化前调用（onPackageLoaded 时机满足）。
     */
    fun initBlockList(hostClassLoader: ClassLoader) {
        miuiUtilsClass = runCatching {
            hostClassLoader.loadClass("com.android.systemui.statusbar.phone.MiuiIconManagerUtils")
        }.getOrNull()
        blockListReady = miuiUtilsClass != null
        logger?.invoke(
            Log.WARN, "block list host=${miuiUtilsClass?.name ?: "NOT FOUND"}", null,
        )
    }

    private fun getStaticMutableList(cls: Class<*>, name: String): MutableList<Any>? {
        return try {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(null) as? List<*> ?: return null
                    @Suppress("UNCHECKED_CAST")
                    return v as? MutableList<Any> ?: (v.toMutableList() as MutableList<Any>)
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun setStaticField(cls: Class<*>, name: String, value: Any?): Boolean {
        return try {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(null, value)
                    return true
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 按当前 hideWifi/hideMobile 更新 BLOCK_LIST（可重复调用：增删对账） */
    private fun applyBlockList() {
        val cls = miuiUtilsClass ?: return
        try {
            val statusBarList = getStaticMutableList(cls, "RIGHT_BLOCK_LIST") ?: return
            val controlList = getStaticMutableList(cls, "CONTROL_CENTER_BLOCK_LIST") ?: return
            val wifiSlots = listOf("wifi", "demo_wifi")
            val mobileSlots = listOf("mobile", "demo_mobile", "no_sim", "stacked_mobile")

            val add = ArrayList<String>()
            val remove = ArrayList<String>()
            if (hideWifi) add.addAll(wifiSlots) else remove.addAll(wifiSlots)
            if (hideMobile) add.addAll(mobileSlots) else remove.addAll(mobileSlots)

            for (s in add) {
                if (!statusBarList.contains(s)) statusBarList.add(s)
                if (!controlList.contains(s)) controlList.add(s)
            }
            for (s in remove) {
                statusBarList.remove(s)
                controlList.remove(s)
            }
            setStaticField(cls, "RIGHT_BLOCK_LIST", statusBarList)
            setStaticField(cls, "CONTROL_CENTER_BLOCK_LIST", controlList)
            logger?.invoke(
                Log.WARN,
                "block list updated hideWifi=$hideWifi hideMobile=$hideMobile size=${statusBarList.size}",
                null,
            )
        } catch (t: Throwable) {
            logger?.invoke(Log.WARN, "applyBlockList failed", t)
        }
    }

    fun init(
        module: XposedInterface,
        hostClassLoader: ClassLoader,
        logFn: (Int, String, Throwable?) -> Unit,
    ) {
        logger = logFn
        if (hookedOnce) return

        // 新旧两条类路径（HyperOS 3.x / AOSP 风格）
        val classes = listOf(
            runCatching {
                hostClassLoader.loadClass(
                    "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
                )
            }.getOrNull(),
            runCatching {
                hostClassLoader.loadClass(
                    "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl"
                )
            }.getOrNull(),
        ).filterNotNull()

        if (classes.isEmpty()) {
            logFn(Log.WARN, "StatusBarIconControllerImpl not found in host classloader", null)
            return
        }

        var hooked = false
        for (cls in classes) {
            hooked = hookControllerClass(module, cls, logFn) || hooked
        }
        hookedOnce = hooked
        logFn(Log.WARN, "icon hider hooked=$hooked classes=${classes.map { it.name }}", null)

        // 网络类型标识（"5G" 小字）隐藏：信号图标藏掉后它独立绘制、会遗留
        hookMobileTypeHide(module, hostClassLoader, logFn)
    }

    /**
     * 隐藏移动网络类型标识（"5G" 小字。实现参考 HyperCeiler MobileTypeSingle2Hook，AGPL-3.0）。
     *
     * HyperOS 的网络类型文字由 MobileTypeDrawable 绘制，不属于信号图标本体——
     * BLOCK_LIST / setIcon hook 把信号柱藏掉后它仍会遗留（张总实测环内右下侧 5G 残留）：
     *  - Android 36+（HyperOS 3）: com.miui.systemui.statusbar.views.MobileTypeDrawable
     *  - 其余: com.android.systemui.statusbar.views.MobileTypeDrawable
     *
     * hook 其 measure()：hideMobile=true 时直接返回 null（不测量 → 宽度为 0，不显示）；
     * false 时放行原逻辑。受 updateRules 的 hideMobile 动态门控，开关即时生效、无需重启。
     */
    private fun hookMobileTypeHide(
        module: XposedInterface,
        hostClassLoader: ClassLoader,
        logFn: (Int, String, Throwable?) -> Unit,
    ) {
        val candidates = listOf(
            "com.miui.systemui.statusbar.views.MobileTypeDrawable",
            "com.android.systemui.statusbar.views.MobileTypeDrawable",
        )
        for (name in candidates) {
            val cls = try {
                hostClassLoader.loadClass(name)
            } catch (_: Throwable) {
                null
            } ?: continue
            var hooked = false
            for (method in cls.declaredMethods) {
                if (method.name != "measure") continue
                try {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                return if (hideMobile) null else chain.proceed()
                            }
                        })
                    hooked = true
                } catch (t: Throwable) {
                    logFn(Log.WARN, "hook ${cls.simpleName}.measure failed", t)
                }
            }
            logFn(
                Log.WARN,
                "mobile type drawable ${cls.name} hooked=$hooked (gated by hideMobile)",
                null,
            )
            return
        }
        logFn(Log.WARN, "MobileTypeDrawable not found; small 5G hide unavailable", null)
    }

    private fun hookControllerClass(
        module: XposedInterface,
        cls: Class<*>,
        logFn: (Int, String, Throwable?) -> Unit,
    ): Boolean {
        var any = false
        // 内部 StatusBarIcon / Holder 类可能存在与否因 ROM 而异，逐个尝试
        val internalIconCls = runCatching { cls.classLoader.loadClass(STATUS_BAR_ICON) }.getOrNull()
        val holderCls = runCatching { cls.classLoader.loadClass(STATUS_BAR_ICON_HOLDER) }.getOrNull()

        for (method in cls.declaredMethods) {
            when {
                method.name == "setIcon" && paramsMatch(method, String::class.java, internalIconCls) -> {
                    // 直接反射把 icon.visible 置 false（引用对象，proceed 前改字段即可生效）
                    hookBefore(module, method, logFn) { chain ->
                        val slot = chain.getArg(0) as? String ?: return@hookBefore null
                        logSlot(slot, logFn)
                        if (shouldHide(slot)) {
                            chain.getArg(1)?.let { icon -> setFieldBoolean(icon, "visible", false) }
                        }
                        null
                    }
                    any = true
                    logFn(Log.WARN, "hooked setIcon(String, StatusBarIcon) on ${cls.name}", null)
                }

                method.name == "setIcon" && paramsMatch(method, String::class.java, holderCls) -> {
                    hookBefore(module, method, logFn) { chain ->
                        val slot = chain.getArg(0) as? String ?: return@hookBefore null
                        logSlot(slot, logFn)
                        if (shouldHide(slot)) {
                            chain.getArg(1)?.let { holder ->
                                val icon = getField(holder, "mIcon")
                                icon?.let { setFieldBoolean(it, "visible", false) }
                            }
                        }
                        null
                    }
                    any = true
                    logFn(Log.WARN, "hooked setIcon(String, StatusBarIconHolder) on ${cls.name}", null)
                }

                method.name == "setIconVisibility" &&
                    paramsMatch(method, String::class.java, java.lang.Boolean.TYPE) -> {
                    // 需要改参数：返回新参数数组走 chain.proceed(newArgs)
                    hookBefore(module, method, logFn) { chain ->
                        val slot = chain.getArg(0) as? String ?: return@hookBefore null
                        if (shouldHide(slot) && chain.getArg(1) == true) {
                            arrayOf(chain.getArg(0), false)
                        } else {
                            null
                        }
                    }
                    any = true
                    logFn(Log.WARN, "hooked setIconVisibility on ${cls.name}", null)
                }
            }
        }
        return any
    }

    /**
     * before 型 Hook 包装。before 回调返回 null = 原参数 proceed()；
     * 返回数组 = 以新参数 proceed(newArgs)。PROTECTIVE + 双层 catch，兜底逻辑自身绝不抛。
     */
    private fun hookBefore(
        module: XposedInterface,
        method: Method,
        logFn: (Int, String, Throwable?) -> Unit,
        before: (XposedInterface.Chain) -> Array<Any?>?,
    ) {
        try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val newArgs = try {
                            before(chain)
                        } catch (_: Throwable) {
                            null
                        }
                        return try {
                            if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                        } catch (t: Throwable) {
                            null
                        }
                    }
                })
        } catch (t: Throwable) {
            logFn(Log.WARN, "hook ${method.name} failed", t)
        }
    }

    private fun paramsMatch(method: Method, first: Class<*>, vararg rest: Class<*>?): Boolean {
        val p = method.parameterTypes
        if (p.size != rest.size + 1) return false
        if (p[0] != first) return false
        for (i in rest.indices) {
            if (rest[i] == null || p[i + 1] != rest[i]) return false
        }
        return true
    }

    private fun getField(obj: Any, name: String): Any? {
        return try {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun setFieldBoolean(obj: Any, name: String, value: Boolean): Boolean {
        return try {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(obj, value)
                    return true
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 按配置更新隐藏规则（MainHook 在 prefs 变化时调用） */
    fun updateRules(cfg: AnyCyCleConfig.Cfg) {
        hideWifi = cfg.hideIcons && (cfg.displayMask and AnyCyCleConfig.BIT_WIFI) != 0
        hideMobile =
            cfg.hideIcons && (cfg.displayMask and AnyCyCleConfig.BIT_SIM1) != 0 ||
            cfg.hideIcons && (cfg.displayMask and AnyCyCleConfig.BIT_SIM2) != 0
        // HyperOS 主路径：BLOCK_LIST（ControllerImpl hook 为 AOSP 兜底路径）
        if (blockListReady) applyBlockList()
    }

    private fun shouldHide(slot: String): Boolean = when (slot) {
        in WIFI_SLOTS -> hideWifi
        in MOBILE_SLOTS -> hideMobile
        else -> false
    }
}
