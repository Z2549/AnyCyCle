package io.github.z2549.anycycle.xposed;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * 由 MainHook 注入 SystemUI 进程后添加到 WindowManager，在状态栏右侧
 * 自绘 1~3 层嵌套进度环：WiFi=最外环、SIM1=次外环、SIM2=最内环。
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

    private static final float[] RATIOS_1 = new float[]{1.0f};
    private static final float[] RATIOS_2 = new float[]{1.0f, 0.62f};
    private static final float[] RATIOS_3 = new float[]{1.0f, 0.70f, 0.42f};

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
    private volatile float sizeDp = 42f;
    private volatile float offsetXDp = 0f;
    private volatile float offsetYDp = 0f;
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
     * <pre>{ "mask":3, "size":42.0, "ox":0.0, "oy":0.0,
     *       "wifi":{"stroke":2.5,"baseColor":-1,"showTrack":true,
     *               "rules":[{"en":true,"max":0,"color":-1}, ...]},
     *       "sim1":{...}, "sim2":{...} }</pre>
     */
    public void applyConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            mask = o.optInt("mask", BIT_WIFI);
            sizeDp = clamp((float) o.optDouble("size", 42.0), 20f, 80f);
            offsetXDp = (float) o.optDouble("ox", 0.0);
            offsetYDp = (float) o.optDouble("oy", 0.0);
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
        // 用 getLocationOnScreen 把"屏幕坐标系下状态栏中心"换算回本 View 的绘制坐标系
        int[] loc = new int[2];
        try {
            getLocationOnScreen(loc);
        } catch (Throwable ignored) {
            loc[0] = 0;
            loc[1] = 0;
        }
        float cx = getWidth() - dp(31f) + ox - loc[0];
        float cy = statusBarHeightPx() / 2f + oy - loc[1];
        float outerR = Math.max(sizeDp * density / 2f, 1f);

        int m = mask;
        boolean showWifi = (m & BIT_WIFI) != 0 && wifiLevel >= 0;
        boolean showSim1 = (m & BIT_SIM1) != 0 && sim1Level >= 0;
        boolean showSim2 = (m & BIT_SIM2) != 0 && sim2Level >= 0;

        int count = (showWifi ? 1 : 0) + (showSim1 ? 1 : 0) + (showSim2 ? 1 : 0);
        if (count == 0) return;

        float[] ratios;
        if (count == 1) {
            ratios = RATIOS_1;
        } else if (count == 2) {
            ratios = RATIOS_2;
        } else {
            ratios = RATIOS_3;
        }

        // 固定嵌套层级：WiFi=最外、SIM1=次外、SIM2=最内；
        // 被隐藏的环不占层级，比例数组按可见顺序依次分配
        int idx = 0;
        if (showWifi) {
            drawOneRing(canvas, cx, cy, outerR * ratios[idx++], wifiConf, wifiLevel);
        }
        if (showSim1) {
            drawOneRing(canvas, cx, cy, outerR * ratios[idx++], sim1Conf, sim1Level);
        }
        if (showSim2) {
            drawOneRing(canvas, cx, cy, outerR * ratios[idx], sim2Conf, sim2Level);
        }
    }

    private void drawOneRing(Canvas canvas, float cx, float cy, float radius, RingConf conf, int level) {
        if (conf == null || level < 0) return;
        float density = getResources().getDisplayMetrics().density;
        float stroke = Math.max(conf.strokeDp * density, 1f);
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
