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
 *  - 通过 WindowManager 添加全屏透明、不可触摸的系统内部窗口
 *    （TYPE_STATUS_BAR_SUB_PANEL），由纯 Java 的 SignalRingView 绘制嵌套信号环；
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
            wm.addView(created, buildParams())
            overlayView = created
            log(Log.WARN, TAG, "signal ring overlay added")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "addView failed", t)
            return
        }
        applyViewConfig(created)
    }

    /**
     * 全屏透明系统窗口参数（借鉴 lsp_CycleBattery）：
     * 类型优先 TYPE_STATUS_BAR_ADDITIONAL（状态栏附加浮层，z 序在状态栏层，不被
     * statusBar inset 推到状态栏下方——实测 SUB_PANEL 会被推到 y=140 处），
     * 回退 TYPE_STATUS_BAR_SUB_PANEL / TYPE_APPLICATION_OVERLAY。
     * NOT_TOUCHABLE 保证不遮挡触摸。
     */
    private fun buildParams(): WindowManager.LayoutParams {
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
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "AnyCyCleRing"
            // 不 fit 任何 inset，窗口铺满全屏含状态栏区域
            try {
                setFitInsetsTypes(0)
            } catch (_: Throwable) {
            }
            // STATUS_BAR_ADDITIONAL / SUB_PANEL 类型被 WMS policy 强制定位在状态栏下方
            // （实测 frame=[0,140][1200,2670]），用负 y 偏移把窗口顶回状态栏区域。
            try {
                val res = appContext?.resources
                val sbh = res?.getDimensionPixelSize(
                    res.getIdentifier("status_bar_height", "dimen", "android")
                ) ?: 0
                if (sbh > 0) y = -sbh
            } catch (_: Throwable) {
            }
        }
    }

    /** 通过反射调用 SignalRingView.applyConfig(String json)（纯 Java 类，避免 Kotlin Intrinsics 问题） */
    private fun applyViewConfig(view: View) {
        try {
            val json = buildViewConfigJson(readCfg())
            val m = view.javaClass.getMethod("applyConfig", String::class.java)
            m.invoke(view, json)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "apply view config failed", t)
        }
    }

    /** 组装推送给 SignalRingView 的配置 JSON（与 AnyCyCleConfig 的序列化格式一致） */
    private fun buildViewConfigJson(cfg: AnyCyCleConfig.Cfg): String {
        val o = JSONObject()
        o.put("mask", cfg.displayMask)
        o.put("ox", cfg.offsetXDp.toDouble())
        o.put("oy", cfg.offsetYDp.toDouble())
        o.put("cutoutExtra", cfg.cutoutExtraDp.toDouble())
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

    private val WIFI_SLOTS = setOf("wifi", "demo_wifi")
    private val MOBILE_SLOTS = setOf("mobile", "demo_mobile", "no_sim")

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
            val mobileSlots = listOf("mobile", "demo_mobile", "no_sim")

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
