package io.github.z2549.anycycle

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.z2549.anycycle.ui.theme.AnyCyCleTheme
import io.github.z2549.anycycle.xposed.AnyCyCleConfig
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnyCyCleTheme {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun SettingsScreen() {
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
                    cfg = AnyCyCleConfig.read(p)
                    p.registerOnSharedPreferenceChangeListener { sp, _ ->
                        cfg = AnyCyCleConfig.read(sp)
                    }
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

    fun commit(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.edit()?.let { editor ->
            block(editor)
            editor.apply()
        }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "AnyCyCle 设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )

        // ---------------- 连接状态 ----------------
        if (!serviceBound) {
            StatusCard(
                title = "未连接到 Xposed 服务",
                detail = "请在 LSPosed 中启用本模块，然后重启\"系统界面\"。未连接时以下设置仅显示默认值且不可编辑。",
                error = true,
            )
        } else {
            StatusCard(
                title = "已连接 Xposed 服务",
                detail = "配置通过 RemotePreferences 实时写入 SystemUI 进程。",
                error = false,
            )
        }

        // ---------------- 启用开关 ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    label = "启用 AnyCyCle",
                    detail = null,
                    checked = cfg.enabled,
                    enabled = serviceBound,
                ) { v ->
                    cfg = cfg.copy(enabled = v)
                    commit { it.putBoolean(AnyCyCleConfig.KEY_ENABLED, v) }
                }
            }
        }

        // ---------------- 显示内容（位掩码多选） ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("显示内容", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                SwitchRow(
                    label = "WiFi（默认）",
                    detail = null,
                    checked = (cfg.displayMask and AnyCyCleConfig.BIT_WIFI) != 0,
                    enabled = serviceBound,
                ) { on ->
                    val newMask = if (on) {
                        cfg.displayMask or AnyCyCleConfig.BIT_WIFI
                    } else {
                        cfg.displayMask and AnyCyCleConfig.BIT_WIFI.inv()
                    }
                    cfg = cfg.copy(displayMask = newMask)
                    commit { it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask) }
                }
                SwitchRow(
                    label = "SIM 卡 1",
                    detail = null,
                    checked = (cfg.displayMask and AnyCyCleConfig.BIT_SIM1) != 0,
                    enabled = serviceBound,
                ) { on ->
                    val newMask = if (on) {
                        cfg.displayMask or AnyCyCleConfig.BIT_SIM1
                    } else {
                        cfg.displayMask and AnyCyCleConfig.BIT_SIM1.inv()
                    }
                    cfg = cfg.copy(displayMask = newMask)
                    commit { it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask) }
                }
                SwitchRow(
                    label = "SIM 卡 2",
                    detail = null,
                    checked = (cfg.displayMask and AnyCyCleConfig.BIT_SIM2) != 0,
                    enabled = serviceBound,
                ) { on ->
                    val newMask = if (on) {
                        cfg.displayMask or AnyCyCleConfig.BIT_SIM2
                    } else {
                        cfg.displayMask and AnyCyCleConfig.BIT_SIM2.inv()
                    }
                    cfg = cfg.copy(displayMask = newMask)
                    commit { it.putInt(AnyCyCleConfig.KEY_DISPLAY_MASK, newMask) }
                }
                Text(
                    "多选时多环嵌套套环显示；默认仅 WiFi",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 隐藏原版图标 ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    label = "隐藏原版图标",
                    detail = "启用后，按 HyperCeiler 的方式隐藏状态栏中与显示环对应的原版图标（选 WiFi 隐藏 WiFi 图标，选 SIM 卡隐藏信号图标）",
                    checked = cfg.hideIcons,
                    enabled = serviceBound,
                ) { v ->
                    cfg = cfg.copy(hideIcons = v)
                    commit { it.putBoolean(AnyCyCleConfig.KEY_HIDE_ICONS, v) }
                }
            }
        }

        // ---------------- 外观 ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("外观", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                SliderRow(
                    label = "环外径",
                    value = cfg.sizeDp,
                    range = AnyCyCleConfig.SIZE_MIN_DP..AnyCyCleConfig.SIZE_MAX_DP,
                    decimals = 0,
                    unit = "dp",
                    enabled = serviceBound,
                ) { v ->
                    cfg = cfg.copy(sizeDp = v)
                    commit { it.putFloat(AnyCyCleConfig.KEY_SIZE_DP, v) }
                }
                SliderRow(
                    label = "水平偏移（右为正）",
                    value = cfg.offsetXDp,
                    range = AnyCyCleConfig.OFFSET_MIN_DP..AnyCyCleConfig.OFFSET_MAX_DP,
                    decimals = 1,
                    unit = "dp",
                    enabled = serviceBound,
                ) { v ->
                    cfg = cfg.copy(offsetXDp = v)
                    commit { it.putFloat(AnyCyCleConfig.KEY_OFFSET_X_DP, v) }
                }
                SliderRow(
                    label = "垂直偏移（下为正）",
                    value = cfg.offsetYDp,
                    range = AnyCyCleConfig.OFFSET_MIN_DP..AnyCyCleConfig.OFFSET_MAX_DP,
                    decimals = 1,
                    unit = "dp",
                    enabled = serviceBound,
                ) { v ->
                    cfg = cfg.copy(offsetYDp = v)
                    commit { it.putFloat(AnyCyCleConfig.KEY_OFFSET_Y_DP, v) }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // ---------------- 三个环的设置 ----------------
        RingCard(
            title = "WiFi 环",
            ring = cfg.wifi,
            controlsEnabled = serviceBound,
        ) { updateRing(AnyCyCleConfig.KEY_RING_WIFI, it) }
        RingCard(
            title = "SIM 卡 1 环",
            ring = cfg.sim1,
            controlsEnabled = serviceBound,
        ) { updateRing(AnyCyCleConfig.KEY_RING_SIM1, it) }
        RingCard(
            title = "SIM 卡 2 环",
            ring = cfg.sim2,
            controlsEnabled = serviceBound,
        ) { updateRing(AnyCyCleConfig.KEY_RING_SIM2, it) }

        // ---------------- 提示 ----------------
        StatusCard(
            title = "提示",
            detail = "所有参数即时生效，无需重启系统界面；更改显示内容后，状态栏中原版图标的隐藏状态会同步切换。",
            error = false,
        )
    }
}

// ------------------------------------------------------------------
// 通用组件
// ------------------------------------------------------------------

@Composable
private fun SwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (detail != null) {
                Text(
                    detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
        )
    }
}

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
    val focusManager = LocalFocusManager.current
    // 拖动期间使用本地值，松手才写入，避免频繁 IPC
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

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        // 仅允许数字、小数点、负号
                        if (input.matches(Regex("^-?[0-9]*\\.?[0-9]*$"))) {
                            text = input
                        }
                    },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commitText() }),
                    trailingIcon = { Text(unit, fontSize = 11.sp) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatNum(local, decimals), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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

@Composable
private fun ColorRow(label: String, current: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    val focusManager = LocalFocusManager.current
    var hex by remember(current) { mutableStateOf("#%06X".format(current and 0xFFFFFF)) }
    var invalid by remember { mutableStateOf(false) }

    fun commitHex() {
        val cleaned = hex.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (!cleaned.matches(Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$"))) {
            invalid = true
            return
        }
        val argb = cleaned.toLong(16)
        // 6 位视为不透明；8 位为 AARRGGBB
        val color = (if (cleaned.length == 6) argb or 0xFF000000L else argb).toInt()
        onPick(color)
        hex = "#%06X".format(color and 0xFFFFFF)
        invalid = false
        focusManager.clearFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(current))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, fontSize = 14.sp)
            }
            OutlinedTextField(
                value = hex,
                onValueChange = {
                    hex = it
                    invalid = false
                },
                modifier = Modifier.width(150.dp),
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("Hex", fontSize = 11.sp) },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text("格式：34C759 / #34C759 / FF34C759", fontSize = 10.sp) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitHex() }),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PALETTE.forEach { c ->
                val selected = c == current
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable(enabled = enabled) { onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String, error: Boolean) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ------------------------------------------------------------------
// 环设置卡
// ------------------------------------------------------------------

@Composable
private fun RingCard(
    title: String,
    ring: AnyCyCleConfig.RingCfg,
    controlsEnabled: Boolean,
    onUpdate: (AnyCyCleConfig.RingCfg) -> Unit,
) {
    // 规则不足 5 条时用默认值补齐
    val defaultRules = AnyCyCleConfig.defaultRules()
    val rules = List(5) { i -> ring.rules.getOrNull(i) ?: defaultRules[i] }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
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
            SwitchRow(
                label = "显示背景轨道",
                detail = null,
                checked = ring.showTrack,
                enabled = controlsEnabled,
            ) { v ->
                onUpdate(ring.copy(showTrack = v))
            }
            ColorRow(
                label = "基础颜色",
                current = ring.baseColor,
                enabled = controlsEnabled,
            ) { c ->
                onUpdate(ring.copy(baseColor = c))
            }
            Text("档位变色规则", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "信号档位 ≤ 阈值时使用对应颜色；按阈值从小到大匹配第一条启用的规则，无匹配时用基础颜色。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rules.forEachIndexed { i, rule ->
                RuleRow(
                    index = i,
                    rule = rule,
                    controlsEnabled = controlsEnabled,
                ) { new ->
                    onUpdate(ring.copy(rules = rules.toMutableList().also { it[i] = new }))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RuleRow(
    index: Int,
    rule: AnyCyCleConfig.ColorRule,
    controlsEnabled: Boolean,
    onUpdate: (AnyCyCleConfig.ColorRule) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var levelText by remember(rule.maxLevel) { mutableStateOf(rule.maxLevel.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rule.enabled,
                enabled = controlsEnabled,
                onCheckedChange = { v -> onUpdate(rule.copy(enabled = v)) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("规则 ${index + 1}", fontSize = 14.sp, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = levelText,
                onValueChange = { input ->
                    if (input.matches(Regex("^[0-9]*$"))) {
                        levelText = input
                    }
                },
                modifier = Modifier.width(110.dp),
                singleLine = true,
                enabled = controlsEnabled,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("信号 ≤") },
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
        ColorRow(
            label = "颜色",
            current = rule.color,
            enabled = controlsEnabled,
        ) { c ->
            onUpdate(rule.copy(color = c))
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
