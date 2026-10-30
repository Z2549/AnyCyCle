package io.github.z2549.anycycle.xposed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.DisplayCutout;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 环形信号指示器（纯 Java 实现，零 kotlin-stdlib 依赖）。
 *
 * 由 MainHook 注入 SystemUI 进程后添加到 WindowManager，在摄像头挖孔处
 * 自绘 1~3 层嵌套进度环：各环紧贴挖孔边缘、由内向外依次堆叠（顺序可配置，
 * 默认 WiFi=最内环、SIM1 次之、SIM2=最外环）。调整任一环粗细时该环向外
 * 扩展（内缘位置不变），永远不会遮挡摄像头。
 *
 * 数据源（本 View 内部注册/注销）：
 *  - WiFi 档位：ConnectivityManager.registerNetworkCallback(TRANSPORT_WIFI)
 *  - SIM 档位：SubscriptionManager 活卡列表 + TelephonyManager.TelephonyCallback
 *
 * 配置来源：MainHook 通过反射调用 applyConfig(String) 推送（volatile 字段）。
 *
 * 该类运行在 SystemUI 进程，任何异常都必须内部消化，不允许抛出。
 * onDraw 整体 try-catch 跳帧；监听器回调内部各自 try-catch。
 */
public class SignalRingView extends View {

    // 显示位掩码（与 AnyCyCleConfig 保持一致：bit0=WIFI、bit1=SIM1、bit2=SIM2）
    private static final int BIT_WIFI = 1;
    private static final int BIT_SIM1 = 2;
    private static final int BIT_SIM2 = 4;

    // 环 ID（嵌套顺序数组里使用）
    private static final int RING_WIFI = 0;
    private static final int RING_SIM1 = 1;
    private static final int RING_SIM2 = 2;

    /** 最内环内缘与摄像头挖孔边缘的间隙（dp）：紧贴但不压住相机装饰环 */
    private static final float EDGE_GAP_DP = 1.0f;

    /** 相邻两环之间的间距（dp）：0 = 互相紧贴（用户要求） */
    private static final float RING_GAP_DP = 0f;

    /** 挖孔中心垂直校正（dp）：小米 15 实测系统报告的 cutout 中心比视觉中心高约 0.6dp（张总逐环比对实测） */
    private static final float CUTOUT_Y_CORRECTION_DP = 0.6f;

    // ------------------------------------------------------------------
    // 档位变色规则（不可变，applyConfig 时整体替换）
    // ------------------------------------------------------------------
    private static final class Rule {
        final boolean enabled;
        final int maxLevel;
        final int color;

        Rule(boolean enabled, int maxLevel, int color) {
            this.enabled = enabled;
            this.maxLevel = maxLevel;
            this.color = color;
        }
    }

    private static final class RingConf {
        final float strokeDp;
        final int baseColor;
        final boolean showTrack;
        final Rule[] rules;

        RingConf(float strokeDp, int baseColor, boolean showTrack, Rule[] rules) {
            this.strokeDp = strokeDp;
            this.baseColor = baseColor;
            this.showTrack = showTrack;
            this.rules = rules;
        }
    }

    // ------------------------------------------------------------------
    // 配置（volatile，由 MainHook 线程推送，onDraw/UI 线程读取）
    // ------------------------------------------------------------------
    private volatile int mask = BIT_WIFI;
    private volatile float cutoutExtraDp = 0f;
    private volatile float offsetXDp = 0f;
    private volatile float offsetYDp = 0f;
    /** 环间分割线：宽度 dp（0=关）与颜色 */
    private volatile float dividerWidthDp = 0f;
    private volatile int dividerColor = 0xFF000000;
    /** 嵌套顺序（内 → 外），元素为 RING_*，必须是三者的完整排列 */
    private volatile int[] ringOrder = new int[]{RING_WIFI, RING_SIM1, RING_SIM2};
    private volatile RingConf wifiConf = new RingConf(2.5f, 0xFFFFFFFF, true, new Rule[0]);
    private volatile RingConf sim1Conf = new RingConf(2.5f, 0xFF2196F3, true, new Rule[0]);
    private volatile RingConf sim2Conf = new RingConf(2.5f, 0xFFAB47BC, true, new Rule[0]);

    // ------------------------------------------------------------------
    // 信号状态（volatile，监听器线程写入，UI 线程读取；-1 = 无数据/不绘制）
    // ------------------------------------------------------------------
    private volatile int wifiLevel = -1;
    private volatile int sim1Level = -1;
    private volatile int sim2Level = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF ringRect = new RectF();

    // ------------------------------------------------------------------
    // 数据源句柄（注销用）
    // ------------------------------------------------------------------
    private final ConnectivityManager.NetworkCallback wifiCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            try {
                wifiLevel = normalizeWifiLevel(networkCapabilities);
                postInvalidate();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onLost(Network network) {
            try {
                wifiLevel = -1;
                postInvalidate();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onUnavailable() {
            try {
                wifiLevel = -1;
                postInvalidate();
            } catch (Throwable ignored) {
            }
        }
    };

    private boolean wifiRegistered = false;

    private final List<TelephonyManager> simTelephonyManagers = new ArrayList<TelephonyManager>();
    private final List<TelephonyCallback> simCallbacks = new ArrayList<TelephonyCallback>();

    private SubscriptionManager.OnSubscriptionsChangedListener subListener;
    private boolean subListenerRegistered = false;

    /** 指定卡槽（0=SIM1，1=SIM2）的信号档位监听器 */
    private final class SimLevelCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {

        final int slot;

        SimLevelCallback(int slot) {
            this.slot = slot;
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            try {
                int lvl = signalStrength == null ? -1 : signalStrength.getLevel();
                setSimLevel(slot, lvl);
            } catch (Throwable ignored) {
            }
        }
    }

    public SignalRingView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ------------------------------------------------------------------
    // 公开接口（MainHook 通过反射调用，签名不可改动）
    // ------------------------------------------------------------------

    /**
     * 推送配置（即时生效）。JSON 格式由 MainHook 生成：
     * <pre>{ "mask":3, "ox":0.0, "oy":0.0, "cutoutExtra":0.0,
     *       "order":["wifi","sim1","sim2"],
     *       "wifi":{"stroke":2.5,"baseColor":-1,"showTrack":true,
     *               "rules":[{"en":true,"max":0,"color":-1}, ...]},
     *       "sim1":{...}, "sim2":{...} }</pre>
     * order 为嵌套顺序（内 → 外），各环自挖孔边缘起向外紧贴堆叠。
     */
    public void applyConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            mask = o.optInt("mask", BIT_WIFI);
            cutoutExtraDp = clamp((float) o.optDouble("cutoutExtra", 0.0), -6f, 6f);
            offsetXDp = (float) o.optDouble("ox", 0.0);
            offsetYDp = (float) o.optDouble("oy", 0.0);
            dividerWidthDp = clamp((float) o.optDouble("divW", 0.0), 0f, 3f);
            dividerColor = o.optInt("divColor", 0xFF000000);
            ringOrder = parseOrder(o.optJSONArray("order"));
            wifiConf = parseRing(o.optJSONObject("wifi"), 0xFFFFFFFF);
            sim1Conf = parseRing(o.optJSONObject("sim1"), 0xFF2196F3);
            sim2Conf = parseRing(o.optJSONObject("sim2"), 0xFFAB47BC);
            postInvalidate();
        } catch (Throwable ignored) {
            // 配置解析失败保持旧配置，绝不抛出
        }
    }

    // ------------------------------------------------------------------
    // 配置解析
    // ------------------------------------------------------------------

    private static RingConf parseRing(JSONObject o, int fallbackColor) {
        if (o == null) {
            return new RingConf(2.5f, fallbackColor, true, new Rule[0]);
        }
        float stroke = (float) o.optDouble("stroke", 2.5);
        stroke = clamp(stroke, 1f, 8f);
        int baseColor = o.optInt("baseColor", fallbackColor);
        boolean showTrack = o.optBoolean("showTrack", true);

        Rule[] rules = new Rule[0];
        JSONArray arr = o.optJSONArray("rules");
        if (arr != null) {
            List<Rule> list = new ArrayList<Rule>();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject ro = arr.optJSONObject(i);
                    if (ro == null) continue;
                    list.add(new Rule(
                            ro.optBoolean("en", true),
                            ro.optInt("max", 4),
                            ro.optInt("color", baseColor)));
                } catch (Throwable ignored) {
                    // 单条规则损坏跳过
                }
            }
            rules = list.toArray(new Rule[0]);
        }
        return new RingConf(stroke, baseColor, showTrack, rules);
    }

    /** 解析嵌套顺序（内 → 外）。必须是 {wifi,sim1,sim2} 的完整排列，非法时回退默认（WiFi 最内）。 */
    private static int[] parseOrder(JSONArray arr) {
        int[] def = new int[]{RING_WIFI, RING_SIM1, RING_SIM2};
        if (arr == null || arr.length() != 3) return def;
        int[] out = new int[3];
        boolean[] used = new boolean[3];
        for (int i = 0; i < 3; i++) {
            String s;
            try {
                s = arr.optString(i);
            } catch (Throwable ignored) {
                return def;
            }
            int id;
            if ("wifi".equals(s)) {
                id = RING_WIFI;
            } else if ("sim1".equals(s)) {
                id = RING_SIM1;
            } else if ("sim2".equals(s)) {
                id = RING_SIM2;
            } else {
                return def;
            }
            if (used[id]) return def;
            out[i] = id;
            used[id] = true;
        }
        return out;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // 生命周期：注册/注销数据源
    // ------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            registerWifiCallback();
        } catch (Throwable ignored) {
        }
        try {
            registerSubListener();
        } catch (Throwable ignored) {
        }
        try {
            rebindSims();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            unregisterWifiCallback();
        } catch (Throwable ignored) {
        }
        try {
            unregisterSims();
        } catch (Throwable ignored) {
        }
        try {
            unregisterSubListener();
        } catch (Throwable ignored) {
        }
        super.onDetachedFromWindow();
    }

    private void registerWifiCallback() {
        if (wifiRegistered) return;
        ConnectivityManager cm =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        cm.registerNetworkCallback(request, wifiCallback);
        wifiRegistered = true;
    }

    private void unregisterWifiCallback() {
        if (!wifiRegistered) return;
        ConnectivityManager cm =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.unregisterNetworkCallback(wifiCallback);
        }
        wifiRegistered = false;
    }

    private void registerSubListener() {
        if (subListenerRegistered) return;
        SubscriptionManager sm = subscriptionManager();
        if (sm == null) return;
        subListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override
            public void onSubscriptionsChanged() {
                try {
                    rebindSims();
                } catch (Throwable ignored) {
                }
            }
        };
        sm.addOnSubscriptionsChangedListener(getContext().getMainExecutor(), subListener);
        subListenerRegistered = true;
    }

    private void unregisterSubListener() {
        if (!subListenerRegistered) return;
        SubscriptionManager sm = subscriptionManager();
        if (sm != null && subListener != null) {
            sm.removeOnSubscriptionsChangedListener(subListener);
        }
        subListener = null;
        subListenerRegistered = false;
    }

    /** 按当前活卡列表重新绑定 SIM1/SIM2 的信号监听（卡列表变化时重复调用） */
    @SuppressLint("MissingPermission")
    // 本视图运行在 SystemUI 进程内（宿主已持有 READ_PHONE_STATE）；
    // 且 getActiveSubscriptionInfoList 已捕获 SecurityException 优雅降级。
    private void rebindSims() {
        unregisterSims();

        SubscriptionManager sm = subscriptionManager();
        TelephonyManager tm =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (sm == null || tm == null) return;

        List<SubscriptionInfo> subs = null;
        try {
            subs = sm.getActiveSubscriptionInfoList();
        } catch (SecurityException se) {
            return; // 无 READ_PHONE_STATE，SIM 环不显示
        } catch (Throwable ignored) {
            return;
        }
        if (subs == null || subs.isEmpty()) return;

        List<SubscriptionInfo> sorted = new ArrayList<SubscriptionInfo>(subs);
        try {
            Collections.sort(sorted, new Comparator<SubscriptionInfo>() {
                @Override
                public int compare(SubscriptionInfo a, SubscriptionInfo b) {
                    return Integer.compare(a.getSimSlotIndex(), b.getSimSlotIndex());
                }
            });
        } catch (Throwable ignored) {
            // 排序失败按原始顺序
        }

        for (int i = 0; i < sorted.size() && i < 2; i++) {
            try {
                SubscriptionInfo info = sorted.get(i);
                TelephonyManager subTm = tm.createForSubscriptionId(info.getSubscriptionId());
                SimLevelCallback cb = new SimLevelCallback(i);
                subTm.registerTelephonyCallback(getContext().getMainExecutor(), cb);
                simTelephonyManagers.add(subTm);
                simCallbacks.add(cb);
                // 立即读一次当前档位，避免等下一次信号变化
                try {
                    SignalStrength ss = subTm.getSignalStrength();
                    if (ss != null) {
                        setSimLevel(i, ss.getLevel());
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
                // 单卡绑定失败不影响另一张卡
            }
        }
        postInvalidate();
    }

    private void unregisterSims() {
        for (int i = 0; i < simCallbacks.size(); i++) {
            try {
                TelephonyManager tm = simTelephonyManagers.get(i);
                if (tm != null) {
                    tm.unregisterTelephonyCallback(simCallbacks.get(i));
                }
            } catch (Throwable ignored) {
            }
        }
        simCallbacks.clear();
        simTelephonyManagers.clear();
        sim1Level = -1;
        sim2Level = -1;
    }

    private SubscriptionManager subscriptionManager() {
        try {
            return (SubscriptionManager)
                    getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setSimLevel(int slot, int level) {
        int clamped = (level < 0 || level > 4) ? -1 : level;
        if (slot == 0) {
            sim1Level = clamped;
        } else if (slot == 1) {
            sim2Level = clamped;
        }
        postInvalidate();
    }

    /**
     * Wi-Fi 档位归一化。getSignalStrength() 对蜂窝网返回 0-4 档，
     * 对 Wi-Fi 返回 RSSI（dBm，负值），此处统一映射为 0-4。
     */
    private int normalizeWifiLevel(NetworkCapabilities nc) {
        if (nc == null) return -1;
        int s;
        try {
            s = nc.getSignalStrength();
        } catch (Throwable ignored) {
            return -1;
        }
        if (s >= 0 && s <= 4) return s;
        if (s <= 0 && s >= -128) { // RSSI dBm（阈值参考 WifiManager.calculateSignalLevel）
            if (s > -55) return 4;
            if (s > -66) return 3;
            if (s > -77) return 2;
            if (s > -88) return 1;
            return 0;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        postInvalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换后重绘（横屏不绘制）
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawRings(canvas);
        } catch (Throwable ignored) {
            // SystemUI 进程内禁止抛出异常，出错跳帧
        }
    }

    private void drawRings(Canvas canvas) {
        // 横屏时不显示
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        if (getWidth() == 0 || getHeight() == 0) return;

        float density = getResources().getDisplayMetrics().density;
        float ox = offsetXDp * density;
        float oy = offsetYDp * density;
        // 自校准：窗口可能被 WMS policy 定位到状态栏上方/下方（lp.y 负偏移后 frame.top<0），
        // 用 getLocationOnScreen 把"屏幕坐标系下挖孔中心"换算回本 View 的绘制坐标系
        int[] loc = new int[2];
        try {
            getLocationOnScreen(loc);
        } catch (Throwable ignored) {
            loc[0] = 0;
            loc[1] = 0;
        }
        // 圆心 = 前置摄像头挖孔中心（屏幕坐标，lsp_CycleBattery 同款定位），
        // 找不到挖孔时回退屏幕顶部中央
        float[] c = cutoutCenter();
        float cx = c[0] + ox - loc[0];
        float cy = c[1] + oy - loc[1];

        // 累进制布局（用户要求）：
        //  - 最内环内缘贴着摄像头挖孔边缘（仅留 EDGE_GAP 微隙，视觉"紧贴"）；
        //  - 后一环内缘贴着前一环外缘（RING_GAP=0，互相紧贴）；
        //  - 调整任意环粗细时圆心不动、该环向外扩展 —— 内缘位置不变，永不遮挡摄像头。
        float cursor = cutoutRadiusPx() + dp(EDGE_GAP_DP);

        int m = mask;
        int[] order = ringOrder;
        float divW = dividerWidthDp * density;
        boolean drewAny = false;
        for (int i = 0; i < order.length; i++) {
            RingConf conf;
            int level;
            switch (order[i]) {
                case RING_SIM1:
                    conf = sim1Conf;
                    level = (m & BIT_SIM1) != 0 ? sim1Level : -1;
                    break;
                case RING_SIM2:
                    conf = sim2Conf;
                    level = (m & BIT_SIM2) != 0 ? sim2Level : -1;
                    break;
                case RING_WIFI:
                default:
                    conf = wifiConf;
                    level = (m & BIT_WIFI) != 0 ? wifiLevel : -1;
                    break;
            }
            if (conf == null || level < 0) continue;
            // 环间分割线：在已画环与下一环之间画一圈纯色细环（占据 ringGap 位置）
            if (drewAny && divW > 0.5f) {
                paint.setStrokeWidth(divW);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setColor(dividerColor);
                float dr = cursor + divW / 2f;
                ringRect.set(cx - dr, cy - dr, cx + dr, cy + dr);
                canvas.drawArc(ringRect, 0f, 360f, false, paint);
                cursor += divW;
            }
            float stroke = Math.max(conf.strokeDp * density, 1f);
            float radius = cursor + stroke / 2f;
            drawOneRing(canvas, cx, cy, radius, stroke, conf, level);
            cursor = radius + stroke / 2f + dp(RING_GAP_DP);
            drewAny = true;
        }
    }

    private void drawOneRing(Canvas canvas, float cx, float cy, float radius, float stroke,
                             RingConf conf, int level) {
        if (conf == null || level < 0) return;
        radius = Math.max(radius, stroke / 2f + 1f);

        int color = resolveColor(conf, level);
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        paint.setStrokeWidth(stroke);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);

        // 背景轨道：整圆，当前环色，alpha≈15%，同一粗细
        if (conf.showTrack) {
            int alpha = (int) (Color.alpha(color) * 0.15f) & 0xFF;
            paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawArc(ringRect, 0f, 360f, false, paint);
        }

        // 进度弧：从 12 点方向顺时针，档位 0-4 → 0-360 度
        int clamped = Math.max(0, Math.min(level, 4));
        paint.setColor(color);
        canvas.drawArc(ringRect, -90f, clamped * 90f, false, paint);
    }

    /** 取第一条 en 且 level <= max 的规则颜色，无匹配用 baseColor（rules 按 max 升序） */
    private static int resolveColor(RingConf conf, int level) {
        Rule[] rules = conf.rules;
        if (rules != null) {
            for (int i = 0; i < rules.length; i++) {
                Rule r = rules[i];
                if (r != null && r.enabled && level <= r.maxLevel) {
                    return r.color;
                }
            }
        }
        return conf.baseColor;
    }

    // ------------------------------------------------------------------
    // 定位辅助
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 挖孔锚点：抵御 MIUI 焦点通知（灵动岛）对 DisplayCutout 的动态改写。
    // 实测（小米 15）：焦点通知激活时，窗口收到的 cutout 矩形会被系统临时
    // 改写为包含胶囊的区域（中心 600→468、宽 68→75px），导致环跟着胶囊跑；
    // dumpsys 顶层的物理 cutout 不变。锚点始终记录"最窄且最接近屏幕中心"
    // 的报告，焦点通知消失后自动收敛回真实挖孔。
    // ------------------------------------------------------------------
    private volatile boolean cutoutAnchorReady = false;
    private volatile float cutoutAnchorCx = 0f;
    private volatile float cutoutAnchorCy = 0f;
    private volatile float cutoutAnchorR = 0f;

    /** 尝试把一次挖孔报告设为锚点；仅当报告比现有锚点更可信（更窄 / 更接近屏幕中心且不更宽）时采纳 */
    private boolean adoptCutoutAnchor(float cx, float cy, float r) {
        float screenW = getResources().getDisplayMetrics().widthPixels;
        if (!cutoutAnchorReady) {
            // 首帧合理性检查：居中挖孔机型上，报告中心显著偏离屏幕中心说明
            // 此刻 cutout 已被焦点通知改写（SystemUI 启动瞬间通知可能已激活），
            // 按居中挖孔锚定；非居中挖孔机型可后续用水平偏移滑条补偿。
            if (Math.abs(cx - screenW / 2f) > screenW * 0.10f) {
                cx = screenW / 2f;
            }
            cutoutAnchorCx = cx;
            cutoutAnchorCy = cy;
            cutoutAnchorR = r;
            cutoutAnchorReady = true;
            return true;
        }
        float screenCx = screenW / 2f;
        boolean narrower = r < cutoutAnchorR - 0.5f;
        boolean closerCenter = r <= cutoutAnchorR + 0.5f
                && Math.abs(cx - screenCx) < Math.abs(cutoutAnchorCx - screenCx) - 0.5f;
        if (narrower || closerCenter) {
            cutoutAnchorCx = cx;
            cutoutAnchorCy = cy;
            cutoutAnchorR = r;
            return true;
        }
        return false;
    }

    /**
     * 前置摄像头挖孔中心（屏幕坐标系，借鉴 lsp_CycleBattery 的 CameraRingView 定位）。
     * 取四边中第一个非空的切孔矩形；找不到挖孔时回退屏幕顶部中央。
     * 垂直方向内置 CUTOUT_Y_CORRECTION_DP 校正（小米 15 实测系统报告的矩形中心略偏上）。
     */
    private float[] cutoutCenter() {
        float[] out = new float[2];
        try {
            DisplayCutout cutout = getDisplay() == null ? null : getDisplay().getCutout();
            Rect r = cutoutRect(cutout);
            if (r != null && !r.isEmpty()) {
                float cx = r.exactCenterX();
                float cy = r.exactCenterY();
                float rad = Math.min(r.width(), r.height()) / 2f;
                if (adoptCutoutAnchor(cx, cy, rad)) {
                    out[0] = cx;
                } else {
                    // 当前报告疑似被焦点通知改写，沿用锚点
                    out[0] = cutoutAnchorCx;
                    cy = cutoutAnchorCy;
                }
                out[1] = cy + dp(CUTOUT_Y_CORRECTION_DP);
                return out;
            }
        } catch (Throwable ignored) {
        }
        out[0] = getWidth() / 2f;
        out[1] = statusBarHeightPx() / 2f + dp(CUTOUT_Y_CORRECTION_DP);
        return out;
    }

    /**
     * 摄像头挖孔半径（px）：取切孔矩形宽、高中【较小】者的一半 ——
     * 圆形挖孔时宽=高=孔径；水滴/刘海式挖孔（如小米 15：Rect 68x140）矩形的
     * 长边是刘海深度、短边才是孔径，用 max 会把刘海深度当成半径导致环过大出屏。
     * 锚点就绪后优先用锚点半径（焦点通知激活时报告的矩形会临时变宽）。
     * cutoutExtraDp 为用户微调量（正 = 外扩）。
     */
    private float cutoutRadiusPx() {
        if (cutoutAnchorReady) {
            return Math.max(cutoutAnchorR + cutoutExtraDp * getResources().getDisplayMetrics().density, dp(4f));
        }
        float r = 0f;
        try {
            DisplayCutout cutout = getDisplay() == null ? null : getDisplay().getCutout();
            Rect rect = cutoutRect(cutout);
            if (rect != null && !rect.isEmpty()) {
                r = Math.min(rect.width(), rect.height()) / 2f;
            }
        } catch (Throwable ignored) {
        }
        if (r <= 0f) {
            r = statusBarHeightPx() * 0.32f;
        }
        r += cutoutExtraDp * getResources().getDisplayMetrics().density;
        return Math.max(r, dp(4f));
    }

    private Rect cutoutRect(DisplayCutout cutout) {
        if (cutout == null) return null;
        Rect r = cutout.getBoundingRectTop();
        if (r != null && !r.isEmpty()) return r;
        r = cutout.getBoundingRectLeft();
        if (r != null && !r.isEmpty()) return r;
        r = cutout.getBoundingRectRight();
        if (r != null && !r.isEmpty()) return r;
        r = cutout.getBoundingRectBottom();
        if (r != null && !r.isEmpty()) return r;
        return null;
    }

    private int statusBarHeightPx() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Throwable ignored) {
        }
        return (int) dp(24f);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
