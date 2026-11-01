package io.github.z2549.anycycle

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Wifi
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.z2549.anycycle.xposed.AnyCyCleConfig
import io.github.z2549.anycycle.xposed.AnyCyCleConfig.defaultRulesSim1
import io.github.z2549.anycycle.xposed.AnyCyCleConfig.defaultRulesSim2
import io.github.z2549.anycycle.xposed.AnyCyCleConfig.defaultRulesWifi
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                RootScreen()
            }
        }
    }
}

/** HyperOS 风格主题（miuix，HyperCeiler 同款组件库）：跟随系统深浅色 */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        content()
    }
}

// ------------------------------------------------------------------
// 根结构：大标题顶栏（含重启 SystemUI）+ 页面切换 + 悬浮底栏
// ------------------------------------------------------------------

private const val PAGE_HOME = 0
private const val PAGE_WIFI = 1
private const val PAGE_SIM1 = 2
private const val PAGE_SIM2 = 3

private fun pageTitle(page: Int): String = when (page) {
    PAGE_WIFI -> "WiFi 环"
    PAGE_SIM1 -> "SIM 卡 1 环"
    PAGE_SIM2 -> "SIM 卡 2 环"
    else -> "AnyCyCle"
}

@Composable
fun RootScreen() {
    val context = LocalContext.current
    var page by remember { mutableStateOf(PAGE_HOME) }

    var prefs by remember { mutableStateOf<SharedPreferences?>(null) }
    var serviceBound by remember { mutableStateOf(false) }
    var cfg by remember { mutableStateOf(AnyCyCleConfig.defaults()) }

    DisposableEffect(Unit) {
        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                serviceBound = true
                try {
                    val p = service.getRemotePreferences(AnyCyCleConfig.PREF_GROUP)
                    prefs = p
                    migrateV11(p)
                    cfg = AnyCyCleConfig.read(p)
                    // 桌面图标隐藏状态与配置同步（App 重装/数据清理后自动纠正）
                    applyHideLauncher(context, cfg.hideLauncher)
                    // 不再注册 RemotePreferences 变更回调回读：配置只有本 App 一个写入方，
                    // 该回调偶发带回旧值把 UI 弹回（"开关状态与交互不一致"的根源）。
                    // UI 一致性由 commitWith 乐观更新 + ON_RESUME 重读保证。
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            override fun onServiceDied(service: XposedService) {
                serviceBound = false
                prefs = null
            }
        }
        XposedServiceHelper.registerListener(listener)
        onDispose { }
    }

    // 回到前台时重读一次 RemotePreferences 兜底（迁移/导入等批量写入后同步 UI）
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val p = prefs
                if (p != null) {
                    cfg = try {
                        AnyCyCleConfig.read(p)
                    } catch (_: Throwable) {
                        cfg
                    }
                }
            }
        }
        activity?.lifecycle?.addObserver(obs)
        onDispose { activity?.lifecycle?.removeObserver(obs) }
    }

    fun commit(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.edit()?.let { editor ->
            block(editor)
            editor.apply()
        }
    }

    /**
     * 乐观提交：先把变更应用到本地 cfg（UI 立即一致），再写入 RemotePreferences。
     * 修复"开关有时候失效 / 与交互后状态不一致"——此前 HomePage 只写 prefs，
     * UI 依赖 RemotePreferences 的变更回调回读，该回调在部分场景不可靠。
     */
    fun commitWith(
        update: (AnyCyCleConfig.Cfg) -> AnyCyCleConfig.Cfg,
        block: (SharedPreferences.Editor) -> Unit,
    ) {
        cfg = update(cfg)
        commit(block)
    }

    fun updateRing(ringKey: String, newRing: AnyCyCleConfig.RingCfg) {
        // 按阈值（maxLevel）升序稳定排序后写回
        val sorted = newRing.copy(rules = newRing.rules.sortedBy { it.maxLevel })
        cfg = when (ringKey) {
            AnyCyCleConfig.KEY_RING_WIFI -> cfg.copy(wifi = sorted)
            AnyCyCleConfig.KEY_RING_SIM1 -> cfg.copy(sim1 = sorted)
            else -> cfg.copy(sim2 = sorted)
        }
        val p = prefs
        if (p != null) AnyCyCleConfig.writeRing(p, ringKey, sorted)
    }

    /** 设置嵌套顺序第 slot 槽位（0=内环）为环 id：与该槽位原环对调，恒为合法排列 */
    fun setOrderSlot(slot: Int, id: String) {
        val newOrder = cfg.ringOrder.toMutableList()
        val oldIdx = newOrder.indexOf(id)
        val replaced = if (slot < newOrder.size) newOrder[slot] else AnyCyCleConfig.RING_WIFI_ID
        if (oldIdx >= 0 && oldIdx != slot) {
            newOrder[oldIdx] = replaced
        }
        if (slot < newOrder.size) {
            newOrder[slot] = id
        } else {
            newOrder.add(id)
        }
        cfg = cfg.copy(ringOrder = newOrder)
        commit { it.putString(AnyCyCleConfig.KEY_RING_ORDER, newOrder.joinToString(",")) }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = pageTitle(page),
                largeTitle = pageTitle(page),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { restartSystemUi(context) }) {
                        Icon(
                            imageVector = Lucide.RotateCcw,
                            contentDescription = "重启系统界面",
                        )
                    }
                },
            )
        },
        bottomBar = {
            // HyperOS 悬浮底栏（miuix 原生组件，HyperCeiler 同款）：圆角胶囊悬浮居中，
            // 内部自带导航栏/手势条 inset 与阴影处理
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = page == PAGE_HOME,
                    onClick = { page = PAGE_HOME },
                    icon = Lucide.House,
                    label = "主页",
                )
                FloatingNavigationBarItem(
                    selected = page == PAGE_WIFI,
                    onClick = { page = PAGE_WIFI },
                    icon = Lucide.Wifi,
                    label = "WiFi",
                )
                FloatingNavigationBarItem(
                    selected = page == PAGE_SIM1,
                    onClick = { page = PAGE_SIM1 },
                    icon = Lucide.Smartphone,
                    label = "SIM 1",
                )
                FloatingNavigationBarItem(
                    selected = page == PAGE_SIM2,
                    onClick = { page = PAGE_SIM2 },
                    icon = Lucide.Smartphone,
                    label = "SIM 2",
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (targetState >= initialState) 1 else -1
                (
                    slideInHorizontally(tween(260)) { dir * it / 5 } + fadeIn(tween(200))
                    ) togetherWith (
                    slideOutHorizontally(tween(220)) { -dir * it / 5 } + fadeOut(tween(160))
                    )
            },
            label = "pageSwitch",
            modifier = Modifier
                .fillMaxSize()
                // 列表滚动驱动顶栏大标题收缩（HyperOS 行为）
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { p ->
            when (p) {
                PAGE_HOME -> HomePage(
                    cfg = cfg,
                    serviceBound = serviceBound,
                    commitWith = { u, b -> commitWith(u, b) },
                    setOrderSlot = { s, id -> setOrderSlot(s, id) },
                    bottomPadding = padding.calculateBottomPadding(),
                )
                PAGE_WIFI -> RingPage(
                    ringId = AnyCyCleConfig.RING_WIFI_ID,
                    ring = cfg.wifi,
                    controlsEnabled = serviceBound,
                    bottomPadding = padding.calculateBottomPadding(),
                ) { updateRing(AnyCyCleConfig.KEY_RING_WIFI, it) }
                PAGE_SIM1 -> RingPage(
                    ringId = AnyCyCleConfig.RING_SIM1_ID,
                    ring = cfg.sim1,
                    controlsEnabled = serviceBound,
                    bottomPadding = padding.calculateBottomPadding(),
                ) { updateRing(AnyCyCleConfig.KEY_RING_SIM1, it) }
                else -> RingPage(
                    ringId = AnyCyCleConfig.RING_SIM2_ID,
                    ring = cfg.sim2,
                    controlsEnabled = serviceBound,
                    bottomPadding = padding.calculateBottomPadding(),
                ) { updateRing(AnyCyCleConfig.KEY_RING_SIM2, it) }
            }
        }
    }
}

// ------------------------------------------------------------------
// 主页：连接状态 + 总开关 + 通用设置 + 应用功能
// ------------------------------------------------------------------

@Composable
private fun HomePage(
    cfg: AnyCyCleConfig.Cfg,
    serviceBound: Boolean,
    commitWith: ((AnyCyCleConfig.Cfg) -> AnyCyCleConfig.Cfg, (SharedPreferences.Editor) -> Unit) -> Unit,
    setOrderSlot: (Int, String) -> Unit,
    bottomPadding: Dp,
) {
    val context = LocalContext.current

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 备份：SAF 导入 / 导出 ----------------
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            try {
                val json = buildExportJson(cfg)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法打开输出流")
                toast("配置已导出")
            } catch (t: Throwable) {
                toast("导出失败: ${t.message}")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { inStream ->
                    inStream.bufferedReader().readText()
                } ?: throw IllegalStateException("无法读取文件")
                val keys = importJson(text)
                // 乐观更新：先把导入的键值折叠进本地 cfg，再整体落盘
                val patched = keys.entries.fold(cfg) { acc, (k, v) -> applyCfgKey(acc, k, v) }
                commitWith({ _ -> patched }) { e ->
                    keys.forEach { (k, v) ->
                        when (v) {
                            is Boolean -> e.putBoolean(k, v)
                            is Int -> e.putInt(k, v)
                            is Float -> e.putFloat(k, v)
                            is String -> e.putString(k, v)
                        }
                    }
                }
                toast("配置已导入（${keys.size} 项）")
            } catch (t: Throwable) {
                toast("导入失败: ${t.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        if (!serviceBound) {
            StatusCard(
                title = "未连接到 Xposed 服务",
                detail = "请在 LSPosed 中启用本模块，然后点击右上角按钮重启\"系统界面\"。未连接时以下设置仅显示默认值且不可编辑。",
                error = true,
            )
        } else {
            StatusCard(
                title = "已连接 Xposed 服务",
                detail = "配置通过 RemotePreferences 实时写入 SystemUI 进程；各环详细设置在底部导航的 WiFi / SIM 1 / SIM 2 页。",
                error = false,
            )
        }

        SmallTitle(text = "基础")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = cfg.enabled,
                onCheckedChange = { v ->
                    commitWith({ c -> c.copy(enabled = v) }) {
                        it.putBoolean(AnyCyCleConfig.KEY_ENABLED, v)
                    }
                },
                title = "启用 AnyCyCle",
                enabled = serviceBound,
            )
        }

        SmallTitle(text = "显示内容")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = (cfg.displayMask and AnyCyCleConfig.BIT_WIFI) != 0,
                onCheckedChange = { v ->
                    val newMask = if (v) cfg.displayMask or AnyCyCleConfig.BIT_WIFI
                    else cfg.displayMask and AnyCyCleConfig.BIT_WIFI.inv()
                    commitWith({ c -> c.copy(displayMask = newMask) }) {
                        it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask)
                    }
                },
                title = "WiFi（默认）",
                enabled = serviceBound,
            )
            SwitchPreference(
                checked = (cfg.displayMask and AnyCyCleConfig.BIT_SIM1) != 0,
                onCheckedChange = { v ->
                    val newMask = if (v) cfg.displayMask or AnyCyCleConfig.BIT_SIM1
                    else cfg.displayMask and AnyCyCleConfig.BIT_SIM1.inv()
                    commitWith({ c -> c.copy(displayMask = newMask) }) {
                        it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask)
                    }
                },
                title = "SIM 卡 1",
                enabled = serviceBound,
            )
            SwitchPreference(
                checked = (cfg.displayMask and AnyCyCleConfig.BIT_SIM2) != 0,
                onCheckedChange = { v ->
                    val newMask = if (v) cfg.displayMask or AnyCyCleConfig.BIT_SIM2
                    else cfg.displayMask and AnyCyCleConfig.BIT_SIM2.inv()
                    commitWith({ c -> c.copy(displayMask = newMask) }) {
                        it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask)
                    }
                },
                title = "SIM 卡 2",
                enabled = serviceBound,
            )
            Text(
                "各环紧贴摄像头挖孔、由内向外嵌套；默认仅 WiFi（最内环）",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            )
        }

        SmallTitle(text = "嵌套顺序（内 → 外）")
        Card(modifier = Modifier.fillMaxWidth()) {
            OrderSlot("内环（最先贴孔）", 0, cfg.ringOrder, serviceBound) { setOrderSlot(0, it) }
            OrderSlot("中环", 1, cfg.ringOrder, serviceBound) { setOrderSlot(1, it) }
            OrderSlot("外环", 2, cfg.ringOrder, serviceBound) { setOrderSlot(2, it) }
        }

        SmallTitle(text = "状态栏")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = cfg.hideIcons,
                onCheckedChange = { v ->
                    commitWith({ c -> c.copy(hideIcons = v) }) {
                        it.putBoolean(AnyCyCleConfig.KEY_HIDE_ICONS, v)
                    }
                },
                title = "隐藏原版图标",
                summary = "隐藏状态栏中与显示环对应的原版图标。切换后若未立即生效，请点右上角重启\"系统界面\"。",
                enabled = serviceBound,
            )
        }

        SmallTitle(text = "外观")
        Card(modifier = Modifier.fillMaxWidth()) {
            SliderRow(
                label = "挖孔半径微调",
                value = cfg.cutoutExtraDp,
                range = AnyCyCleConfig.CUTOUT_EXTRA_MIN_DP..AnyCyCleConfig.CUTOUT_EXTRA_MAX_DP,
                decimals = 1,
                unit = "dp",
                enabled = serviceBound,
            ) { v ->
                commitWith({ c -> c.copy(cutoutExtraDp = v) }) {
                    it.putFloat(AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP, v)
                }
            }
            Text(
                "环大小由挖孔与各环粗细决定，此处微调估算的挖孔半径（正值 = 整体外扩）",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            )
            SliderRow(
                label = "水平偏移（右为正）",
                value = cfg.offsetXDp,
                range = AnyCyCleConfig.OFFSET_MIN_DP..AnyCyCleConfig.OFFSET_MAX_DP,
                decimals = 1,
                unit = "dp",
                enabled = serviceBound,
            ) { v ->
                commitWith({ c -> c.copy(offsetXDp = v) }) {
                    it.putFloat(AnyCyCleConfig.KEY_OFFSET_X_DP, v)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            SliderRow(
                label = "垂直偏移（下为正）",
                value = cfg.offsetYDp,
                range = AnyCyCleConfig.OFFSET_MIN_DP..AnyCyCleConfig.OFFSET_MAX_DP,
                decimals = 1,
                unit = "dp",
                enabled = serviceBound,
            ) { v ->
                commitWith({ c -> c.copy(offsetYDp = v) }) {
                    it.putFloat(AnyCyCleConfig.KEY_OFFSET_Y_DP, v)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        SmallTitle(text = "环间分割线")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = cfg.dividerWidthDp > 0f,
                onCheckedChange = { v ->
                    val newWidth = if (v) 1.5f else 0f
                    commitWith({ c -> c.copy(dividerWidthDp = newWidth) }) {
                        it.putFloat(AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP, newWidth)
                    }
                },
                title = "启用分割线",
                summary = "在相邻两环之间画一圈细线，区分紧贴的环",
                enabled = serviceBound,
            )
            if (cfg.dividerWidthDp > 0f) {
                SliderRow(
                    label = "分割线粗细",
                    value = cfg.dividerWidthDp,
                    range = AnyCyCleConfig.DIVIDER_MIN_DP..AnyCyCleConfig.DIVIDER_MAX_DP,
                    decimals = 1,
                    unit = "dp",
                    enabled = serviceBound,
                ) { v ->
                    commitWith({ c -> c.copy(dividerWidthDp = v) }) {
                        it.putFloat(AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP, v)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                ColorPickRow(
                    label = "分割线颜色",
                    current = cfg.dividerColor,
                    enabled = serviceBound,
                ) { c ->
                    commitWith({ old -> old.copy(dividerColor = c) }) {
                        it.putInt(AnyCyCleConfig.KEY_DIVIDER_COLOR, c)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        SmallTitle(text = "应用")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = cfg.hideLauncher,
                onCheckedChange = { v ->
                    commitWith({ c -> c.copy(hideLauncher = v) }) {
                        it.putBoolean(AnyCyCleConfig.KEY_HIDE_LAUNCHER, v)
                    }
                    applyHideLauncher(context, v)
                    toast(
                        if (v) "桌面图标已隐藏，可从 LSPosed 管理器重新打开"
                        else "桌面图标已恢复显示",
                    )
                },
                title = "隐藏桌面图标",
                summary = "隐藏后可从 LSPosed 管理器的模块页面重新打开设置。",
                enabled = serviceBound,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        exportLauncher.launch("anycycle-backup-${System.currentTimeMillis()}.json")
                    },
                    enabled = serviceBound,
                    modifier = Modifier.weight(1f),
                ) { Text("导出配置") }
                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    enabled = serviceBound,
                    modifier = Modifier.weight(1f),
                ) { Text("导入配置") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            ) {
                Button(
                    onClick = {
                        // 乐观更新：本地 cfg 直接置为默认值，UI 立即一致
                        commitWith({ _ -> AnyCyCleConfig.defaults() }) { e -> resetDefaults(e) }
                        applyHideLauncher(context, false)
                        toast("已恢复默认配置")
                    },
                    enabled = serviceBound,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        StatusCard(
            title = "提示",
            detail = "所有参数即时生效，无需重启系统界面；隐藏图标等少数状态相关设置切换后，可点右上角按钮重启\"系统界面\"强制刷新。",
            error = false,
        )

        // 悬浮底栏浮在内容之上，列表底部留出其高度
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

// ------------------------------------------------------------------
// 环详情页（WiFi / SIM1 / SIM2 共用）
// ------------------------------------------------------------------

@Composable
private fun RingPage(
    ringId: String,
    ring: AnyCyCleConfig.RingCfg,
    controlsEnabled: Boolean,
    bottomPadding: Dp,
    onUpdate: (AnyCyCleConfig.RingCfg) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        SmallTitle(text = "外观")
        Card(modifier = Modifier.fillMaxWidth()) {
            SliderRow(
                label = "线条粗细",
                value = ring.strokeDp,
                range = AnyCyCleConfig.STROKE_MIN_DP..AnyCyCleConfig.STROKE_MAX_DP,
                decimals = 1,
                unit = "dp",
                enabled = controlsEnabled,
            ) { v ->
                onUpdate(ring.copy(strokeDp = v))
            }
            Spacer(modifier = Modifier.height(6.dp))
            SwitchPreference(
                checked = ring.showTrack,
                onCheckedChange = { v ->
                    onUpdate(ring.copy(showTrack = v))
                },
                title = "显示背景轨道",
                enabled = controlsEnabled,
            )
            Spacer(modifier = Modifier.height(2.dp))
            ColorPickRow(
                label = "基础颜色",
                current = ring.baseColor,
                enabled = controlsEnabled,
            ) { c ->
                onUpdate(ring.copy(baseColor = c))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        SmallTitle(text = "档位变色规则")
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "信号档位 ≤ 阈值时使用对应颜色；按阈值从小到大匹配第一条启用的规则，无匹配时用基础颜色。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            // 规则不足 5 条时用默认值补齐（按环各自的默认色补）
            val defaultRules = when (ringId) {
                AnyCyCleConfig.RING_SIM1_ID -> defaultRulesSim1()
                AnyCyCleConfig.RING_SIM2_ID -> defaultRulesSim2()
                else -> defaultRulesWifi()
            }
            val rules = List(5) { i ->
                ring.rules.getOrNull(i) ?: defaultRules[i]
            }
            rules.forEachIndexed { i, rule ->
                RuleRow(
                    index = i,
                    rule = rule,
                    controlsEnabled = controlsEnabled,
                ) { new ->
                    onUpdate(ring.copy(rules = rules.toMutableList().also { it[i] = new }))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

// ------------------------------------------------------------------
// 备份 / 恢复默认
// ------------------------------------------------------------------

private fun buildExportJson(cfg: AnyCyCleConfig.Cfg): String {
    val settings = org.json.JSONObject()
        .put(AnyCyCleConfig.KEY_ENABLED, cfg.enabled)
        .put(AnyCyCleConfig.KEY_DISPLAY_MASK, cfg.displayMask)
        .put(AnyCyCleConfig.KEY_HIDE_ICONS, cfg.hideIcons)
        .put(AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP, cfg.cutoutExtraDp.toDouble())
        .put(AnyCyCleConfig.KEY_RING_ORDER, cfg.ringOrder.joinToString(","))
        .put(AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP, cfg.dividerWidthDp.toDouble())
        .put(AnyCyCleConfig.KEY_DIVIDER_COLOR, cfg.dividerColor)
        .put(AnyCyCleConfig.KEY_HIDE_LAUNCHER, cfg.hideLauncher)
        .put(AnyCyCleConfig.KEY_OFFSET_X_DP, cfg.offsetXDp.toDouble())
        .put(AnyCyCleConfig.KEY_OFFSET_Y_DP, cfg.offsetYDp.toDouble())
    val rings = org.json.JSONObject()
        .put("wifi", org.json.JSONObject(AnyCyCleConfig.ringToJson(cfg.wifi)))
        .put("sim1", org.json.JSONObject(AnyCyCleConfig.ringToJson(cfg.sim1)))
        .put("sim2", org.json.JSONObject(AnyCyCleConfig.ringToJson(cfg.sim2)))
    return org.json.JSONObject()
        .put("app", "AnyCyCle")
        .put("version", 2)
        .put("settings", settings)
        .put("rings", rings)
        .toString(2)
}

/**
 * 单个配置键值变更映射到本地 Cfg（乐观更新 / 导入折叠用）。
 * 未知键忽略；环 JSON 经 ringFromJson 校验，非法时回退当前值。
 */
private fun applyCfgKey(c: AnyCyCleConfig.Cfg, key: String, value: Any?): AnyCyCleConfig.Cfg =
    when (key) {
        AnyCyCleConfig.KEY_ENABLED -> c.copy(enabled = value as Boolean)
        AnyCyCleConfig.KEY_DISPLAY_MASK -> c.copy(displayMask = value as Int)
        AnyCyCleConfig.KEY_HIDE_ICONS -> c.copy(hideIcons = value as Boolean)
        AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP -> c.copy(cutoutExtraDp = value as Float)
        AnyCyCleConfig.KEY_OFFSET_X_DP -> c.copy(offsetXDp = value as Float)
        AnyCyCleConfig.KEY_OFFSET_Y_DP -> c.copy(offsetYDp = value as Float)
        AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP -> c.copy(dividerWidthDp = value as Float)
        AnyCyCleConfig.KEY_DIVIDER_COLOR -> c.copy(dividerColor = value as Int)
        AnyCyCleConfig.KEY_HIDE_LAUNCHER -> c.copy(hideLauncher = value as Boolean)
        AnyCyCleConfig.KEY_RING_ORDER ->
            c.copy(ringOrder = AnyCyCleConfig.normalizeRingOrder(value as? String))
        AnyCyCleConfig.KEY_RING_WIFI ->
            c.copy(wifi = AnyCyCleConfig.ringFromJson(value as? String, c.wifi))
        AnyCyCleConfig.KEY_RING_SIM1 ->
            c.copy(sim1 = AnyCyCleConfig.ringFromJson(value as? String, c.sim1))
        AnyCyCleConfig.KEY_RING_SIM2 ->
            c.copy(sim2 = AnyCyCleConfig.ringFromJson(value as? String, c.sim2))
        else -> c
    }

/** 解析备份 JSON，返回 键 -> 值 的扁平映射（含三环 JSON 字符串）；格式非法抛异常 */
private fun importJson(text: String): Map<String, Any> {
    val root = org.json.JSONObject(text)
    if (root.optString("app") != "AnyCyCle") throw IllegalArgumentException("不是 AnyCyCle 的备份文件")
    val out = LinkedHashMap<String, Any>()
    val settings = root.optJSONObject("settings")
    if (settings != null) {
        out[AnyCyCleConfig.KEY_ENABLED] = settings.optBoolean(AnyCyCleConfig.KEY_ENABLED, AnyCyCleConfig.DEFAULT_ENABLED)
        out[AnyCyCleConfig.KEY_DISPLAY_MASK] = settings.optInt(AnyCyCleConfig.KEY_DISPLAY_MASK, AnyCyCleConfig.DEFAULT_DISPLAY_MASK)
        out[AnyCyCleConfig.KEY_HIDE_ICONS] = settings.optBoolean(AnyCyCleConfig.KEY_HIDE_ICONS, AnyCyCleConfig.DEFAULT_HIDE_ICONS)
        out[AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP] = settings.optDouble(AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP, 0.0).toFloat()
        out[AnyCyCleConfig.KEY_RING_ORDER] = settings.optString(AnyCyCleConfig.KEY_RING_ORDER, AnyCyCleConfig.DEFAULT_RING_ORDER)
        out[AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP] = settings.optDouble(AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP, 0.0).toFloat()
        out[AnyCyCleConfig.KEY_DIVIDER_COLOR] = settings.optInt(AnyCyCleConfig.KEY_DIVIDER_COLOR, AnyCyCleConfig.DEFAULT_DIVIDER_COLOR)
        out[AnyCyCleConfig.KEY_HIDE_LAUNCHER] = settings.optBoolean(AnyCyCleConfig.KEY_HIDE_LAUNCHER, false)
        out[AnyCyCleConfig.KEY_OFFSET_X_DP] = settings.optDouble(AnyCyCleConfig.KEY_OFFSET_X_DP, 0.0).toFloat()
        out[AnyCyCleConfig.KEY_OFFSET_Y_DP] = settings.optDouble(AnyCyCleConfig.KEY_OFFSET_Y_DP, 0.0).toFloat()
    }
    val rings = root.optJSONObject("rings")
    if (rings != null) {
        val wifi = rings.optJSONObject("wifi")
        val sim1 = rings.optJSONObject("sim1")
        val sim2 = rings.optJSONObject("sim2")
        // 用 ringFromJson 校验后再落盘（非法 JSON 会被回退为默认）
        out[AnyCyCleConfig.KEY_RING_WIFI] =
            AnyCyCleConfig.ringToJson(AnyCyCleConfig.ringFromJson(wifi?.toString(), AnyCyCleConfig.defaultWifi()))
        out[AnyCyCleConfig.KEY_RING_SIM1] =
            AnyCyCleConfig.ringToJson(AnyCyCleConfig.ringFromJson(sim1?.toString(), AnyCyCleConfig.defaultSim1()))
        out[AnyCyCleConfig.KEY_RING_SIM2] =
            AnyCyCleConfig.ringToJson(AnyCyCleConfig.ringFromJson(sim2?.toString(), AnyCyCleConfig.defaultSim2()))
    }
    return out
}

private fun resetDefaults(e: SharedPreferences.Editor) {
    e.putBoolean(AnyCyCleConfig.KEY_ENABLED, AnyCyCleConfig.DEFAULT_ENABLED)
    e.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, AnyCyCleConfig.DEFAULT_DISPLAY_MASK)
    e.putBoolean(AnyCyCleConfig.KEY_HIDE_ICONS, AnyCyCleConfig.DEFAULT_HIDE_ICONS)
    e.putFloat(AnyCyCleConfig.KEY_CUTOUT_EXTRA_DP, AnyCyCleConfig.DEFAULT_CUTOUT_EXTRA_DP)
    e.putFloat(AnyCyCleConfig.KEY_OFFSET_X_DP, AnyCyCleConfig.DEFAULT_OFFSET_X_DP)
    e.putFloat(AnyCyCleConfig.KEY_OFFSET_Y_DP, AnyCyCleConfig.DEFAULT_OFFSET_Y_DP)
    e.putFloat(AnyCyCleConfig.KEY_DIVIDER_WIDTH_DP, AnyCyCleConfig.DEFAULT_DIVIDER_WIDTH_DP)
    e.putInt(AnyCyCleConfig.KEY_DIVIDER_COLOR, AnyCyCleConfig.DEFAULT_DIVIDER_COLOR)
    e.putString(AnyCyCleConfig.KEY_RING_ORDER, AnyCyCleConfig.DEFAULT_RING_ORDER)
    e.putBoolean(AnyCyCleConfig.KEY_HIDE_LAUNCHER, false)
    AnyCyCleConfig.writeRingJson(e, AnyCyCleConfig.KEY_RING_WIFI, AnyCyCleConfig.defaultWifi())
    AnyCyCleConfig.writeRingJson(e, AnyCyCleConfig.KEY_RING_SIM1, AnyCyCleConfig.defaultSim1())
    AnyCyCleConfig.writeRingJson(e, AnyCyCleConfig.KEY_RING_SIM2, AnyCyCleConfig.defaultSim2())
}

// ------------------------------------------------------------------
// 应用功能工具
// ------------------------------------------------------------------

/** 重启 SystemUI（通过 root 执行 killall；结果以 Toast 提示） */
private fun restartSystemUi(context: Context) {
    Thread {
        var msg: String
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui"))
            val code = p.waitFor()
            msg = if (code == 0) "已重启系统界面" else "重启失败（su 退出码 $code）"
        } catch (t: Throwable) {
            msg = "重启失败: ${t.message}"
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }.start()
}

/** 桌面图标显隐：切换 launcher alias 组件启用状态（MainActivity 保留 LSPosed MODULE_SETTINGS 入口） */
private fun applyHideLauncher(context: Context, hide: Boolean) {
    try {
        val pm = context.packageManager
        val comp = ComponentName(context, "io.github.z2549.anycycle.LauncherAlias")
        val newState =
            if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        pm.setComponentEnabledSetting(
            comp,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    } catch (t: Throwable) {
        t.printStackTrace()
    }
}

/**
 * v1.1 一次性迁移：挖孔中心垂直校正（+0.5dp）已内置到绘制层，
 * 把历史版本手动补偿的垂直偏移清零，避免双重偏移。只执行一次。
 */
private fun migrateV11(p: SharedPreferences) {
    try {
        if (p.getBoolean(AnyCyCleConfig.KEY_MIGRATED_V11, false)) return
        val editor = p.edit()
        if (p.getFloat(AnyCyCleConfig.KEY_OFFSET_Y_DP, 0f) != 0f) {
            editor.putFloat(AnyCyCleConfig.KEY_OFFSET_Y_DP, 0f)
        }
        editor.putBoolean(AnyCyCleConfig.KEY_MIGRATED_V11, true).apply()
    } catch (t: Throwable) {
        t.printStackTrace()
    }
}

// ------------------------------------------------------------------
// 通用组件（miuix 风格）
// ------------------------------------------------------------------

/** 滑条行：标签 + 可输入数值框 + miuix Slider。拖动期间用本地值，松手才写入，避免频繁 IPC */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int,
    unit: String,
    enabled: Boolean = true,
    onCommit: (Float) -> Unit,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var local by remember(value) { mutableStateOf(value) }
    var text by remember(value) { mutableStateOf(formatNum(value, decimals)) }

    fun commitText() {
        val parsed = text.toFloatOrNull()?.coerceIn(range.start, range.endInclusive)
        if (parsed != null) {
            local = parsed
            onCommit(parsed)
        }
        text = formatNum(local, decimals)
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { input ->
                        // 仅允许数字、小数点、负号
                        if (input.matches(Regex("^-?[0-9]*\\.?[0-9]*$"))) {
                            text = input
                        }
                    },
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    singleLine = true,
                    enabled = enabled,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commitText() }),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${formatNum(local, decimals)}$unit",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Slider(
            value = local,
            onValueChange = {
                local = it
                text = formatNum(it, decimals)
            },
            valueRange = range,
            enabled = enabled,
            onValueChangeFinished = { onCommit(local) },
        )
    }
}

/** 颜色选择行：色块 + 标签 + Hex 值，点击弹出选色对话框 */
@Composable
private fun ColorPickRow(label: String, current: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(current))
                .border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(
            "#%06X".format(current and 0xFFFFFF),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    if (showPicker) {
        ColorPickerDialog(
            title = label,
            initial = current,
            onDismiss = { showPicker = false },
            onPick = {
                onPick(it)
                showPicker = false
            },
        )
    }
}

/**
 * 嵌套顺序槽位选择行（ArrowPreference 风格）：summary 显示当前环名，
 * 点击弹出 HyperOS 风格单选对话框，选中即与当前槽位的环对调。
 */
@Composable
private fun OrderSlot(
    label: String,
    slot: Int,
    order: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val all = listOf(
        AnyCyCleConfig.RING_WIFI_ID,
        AnyCyCleConfig.RING_SIM1_ID,
        AnyCyCleConfig.RING_SIM2_ID,
    )
    val current = order.getOrNull(slot) ?: all[slot]

    ArrowPreference(
        title = label,
        summary = ringLabel(current),
        enabled = enabled,
        onClick = { showPicker = true },
    )
    if (showPicker) {
        OverlayDialog(
            show = true,
            title = "选择$label",
            summary = "选中后与当前槽位的环对调，顺序恒为合法排列",
            onDismissRequest = { showPicker = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                all.forEach { opt ->
                    val selected = opt == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPicker = false
                                onPick(opt)
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                                .border(
                                    width = if (selected) 0.dp else 1.5.dp,
                                    color = MiuixTheme.colorScheme.dividerLine,
                                    shape = CircleShape,
                                ),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            ringLabel(opt),
                            fontSize = 16.sp,
                            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showPicker = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun ringLabel(id: String): String = when (id) {
    AnyCyCleConfig.RING_WIFI_ID -> "WiFi"
    AnyCyCleConfig.RING_SIM1_ID -> "SIM 1"
    AnyCyCleConfig.RING_SIM2_ID -> "SIM 2"
    else -> id
}

@Composable
private fun StatusCard(title: String, detail: String, error: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (error) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.primaryContainer,
            contentColor = if (error) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ------------------------------------------------------------------
// 环设置：规则行
// ------------------------------------------------------------------

@Composable
private fun RuleRow(
    index: Int,
    rule: AnyCyCleConfig.ColorRule,
    controlsEnabled: Boolean,
    onUpdate: (AnyCyCleConfig.ColorRule) -> Unit,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var levelText by remember(rule.maxLevel) { mutableStateOf(rule.maxLevel.toString()) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rule.enabled,
                enabled = controlsEnabled,
                onCheckedChange = { v -> onUpdate(rule.copy(enabled = v)) },
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text("规则 ${index + 1}", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("≤", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                BasicTextField(
                    value = levelText,
                    onValueChange = { input ->
                        if (input.matches(Regex("^[0-9]*$"))) {
                            levelText = input
                        }
                    },
                    modifier = Modifier.width(44.dp),
                    singleLine = true,
                    enabled = controlsEnabled,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        val parsed = levelText.toIntOrNull()?.coerceIn(0, 4)
                        if (parsed != null) {
                            onUpdate(rule.copy(maxLevel = parsed))
                        }
                        levelText = parsed?.toString() ?: rule.maxLevel.toString()
                        focusManager.clearFocus()
                    }),
                )
            }
        }
        ColorPickRow(
            label = "颜色",
            current = rule.color,
            enabled = controlsEnabled,
        ) { c ->
            onUpdate(rule.copy(color = c))
        }
    }
}

// ------------------------------------------------------------------
// 选色弹窗：预设色板 + 饱和度/明度面板 + 色相条 + Hex（miuix 对话框壳）
// ------------------------------------------------------------------

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var argb by remember { mutableStateOf(initial) }
    val initHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var svX by remember { mutableFloatStateOf(initHsv[1]) }
    var svY by remember { mutableFloatStateOf(1f - initHsv[2]) }

    fun updateSv(x: Float, y: Float, w: Float, h: Float) {
        svX = (x / w).coerceIn(0f, 1f)
        svY = (y / h).coerceIn(0f, 1f)
        argb = Color.hsv(hue, svX, 1f - svY).toArgb()
    }

    var hexText by remember(argb) { mutableStateOf("#%06X".format(argb and 0xFFFFFF)) }

    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 预览
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(hexText, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            // 预设色板
            PALETTE.chunked(6).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (c == argb) 3.dp else 1.dp,
                                    color = if (c == argb) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.dividerLine
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { argb = c },
                        )
                    }
                }
            }
            // 饱和度/明度面板
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .pointerInput(hue) {
                        detectTapGestures { off -> updateSv(off.x, off.y, size.width.toFloat(), size.height.toFloat()) }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            if (change.positionChange() != Offset.Zero) change.consume()
                            updateSv(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))), size = size)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), size = size)
                drawCircle(
                    Color.White,
                    radius = 10f,
                    center = Offset(svX * w, svY * h),
                    style = Stroke(width = 4f),
                )
                drawCircle(
                    Color.Black.copy(alpha = 0.6f),
                    radius = 10f,
                    center = Offset(svX * w, svY * h),
                    style = Stroke(width = 2f),
                )
            }
            // 色相条
            Text("色相", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    argb = Color.hsv(it, svX, 1f - svY).toArgb()
                },
                valueRange = 0f..360f,
            )
            // Hex 输入
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            BasicTextField(
                value = hexText,
                onValueChange = { input ->
                    hexText = input
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val cleaned = hexText.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
                    if (cleaned.matches(Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$"))) {
                        val v = cleaned.toLong(16)
                        val c = (if (cleaned.length == 6) v or 0xFF000000L else v).toInt()
                        argb = c
                        val hsv2 = FloatArray(3)
                        android.graphics.Color.colorToHSV(c, hsv2)
                        hue = hsv2[0]
                        svX = hsv2[1]
                        svY = 1f - hsv2[2]
                    }
                    focusManager.clearFocus()
                }),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确定",
                    onClick = { onPick(argb) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 工具
// ------------------------------------------------------------------

private fun formatNum(v: Float, decimals: Int): String =
    if (decimals == 0) {
        v.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.${decimals}f", v)
    }

private val PALETTE = longArrayOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E,
    0xFFF44336, 0xFFE91E63, 0xFF9C27B0,
    0xFF673AB7, 0xFF3F51B5, 0xFF2196F3,
    0xFF00BCD4, 0xFF009688, 0xFF4CAF50,
    0xFF8BC34A, 0xFFFFC107, 0xFFFF9800,
    0xFFFF5722, 0xFF34C759, 0xFFFF3B30,
).map { it.toInt() }
