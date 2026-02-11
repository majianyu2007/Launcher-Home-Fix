package com.lhf.launcherhomefix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PointF;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Simplified launcher-only fix:
 * 1) read early end-target prediction from calculateEndTarget()
 * 2) if prediction says HOME, block startRecentsActivity bridge and launch default HOME directly
 * 3) when settled on HOME, force finish running recents animation tail
 */
public class HomeFixEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LHF-HomeFix";
    private static final String PKG_LAUNCHER = "com.android.launcher";

    private static volatile long sLastPredictHomeMs = 0L;
    private static volatile long sLastPredictRecentsMs = 0L;
    private static volatile Object sLastSwipeUpHandler = null;

    // No fixed-ms gating: arm by gesture token + limited budget.
    private static volatile int sLastSeenGestureToken = 0;
    private static volatile int sArmedGestureToken = 0;
    private static volatile int sHomeLaunchedGestureToken = 0;
    private static volatile boolean sDirectHomeArmed = false;
    private static volatile int sDirectHomeBypassBudget = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG_LAUNCHER.equals(lpparam.packageName)) return;
        try {
            hookOnGestureEndedPredict(lpparam);
            hookDirectHomeAtGestureEnd(lpparam);
            hookCalculateEndTarget(lpparam);
            hookSystemUiProxyStartRecents(lpparam);
            hookOnSettledOnEndTarget(lpparam);
            logI("Simple launcher hooks installed (direct-home)");
        } catch (Throwable t) {
            logE("hook install failed", t);
        }
    }

    /**
     * Pre-predict in onGestureEnded (earlier than some calculateEndTarget call paths on ColorOS).
     */
    private void hookOnGestureEndedPredict(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handler = XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", lpparam.classLoader);
            XposedBridge.hookAllMethods(handler, "onGestureEnded", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof Float) || !(param.args[1] instanceof PointF)) return;

                        float velocity = (Float) param.args[0];
                        PointF pointF = (PointF) param.args[1];
                        sLastSwipeUpHandler = param.thisObject;

                        int token = resolveGestureToken(param.thisObject);
                        if (token != 0 && token != sLastSeenGestureToken) {
                            sLastSeenGestureToken = token;
                            clearDirectHomeArm("new gesture token=" + token);
                        }

                        // Try both fling flags; if either predicts HOME, we treat as HOME pre-signal.
                        String t1 = callCalculateEndTargetSafely(param.thisObject, pointF, velocity, false);
                        String t2 = callCalculateEndTargetSafely(param.thisObject, pointF, velocity, true);

                        long now = SystemClock.uptimeMillis();
                        if ("HOME".equals(t1) || "HOME".equals(t2)) {
                            sLastPredictHomeMs = now;
                            logI("pre-predict HOME (onGestureEnded), t1=" + t1 + ", t2=" + t2);
                        } else if ("RECENTS".equals(t1) || "RECENTS".equals(t2)) {
                            sLastPredictRecentsMs = now;
                        }
                    } catch (Throwable t) {
                        logE("onGestureEnded pre-predict error", t);
                    }
                }
            });
        } catch (Throwable t) {
            logW("onGestureEnded pre-predict hook unavailable: " + t.getMessage());
        }
    }

    private String callCalculateEndTargetSafely(Object handlerObj, PointF pointF, float velocity, boolean flingFlag) {
        try {
            Object target = XposedHelpers.callMethod(handlerObj, "calculateEndTarget", pointF, velocity, flingFlag, false);
            return target == null ? null : String.valueOf(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Core simple strategy user asked for:
     * hook gesture-end event and jump directly to third-party HOME when target is HOME.
     */
    private void hookDirectHomeAtGestureEnd(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handler = XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", lpparam.classLoader);
            int hooked = 0;
            for (Method m : handler.getDeclaredMethods()) {
                if (!m.getName().startsWith("lambda$onGestureEnded$")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length < 3) return;
                            if (!(param.args[0] instanceof Float) || !(param.args[1] instanceof Boolean) || !(param.args[2] instanceof PointF)) {
                                return;
                            }

                            float velocity = (Float) param.args[0];
                            boolean fling = (Boolean) param.args[1];
                            PointF pointF = (PointF) param.args[2];

                            Object targetObj;
                            try {
                                targetObj = XposedHelpers.callMethod(param.thisObject, "calculateEndTarget", pointF, velocity, fling, false);
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (targetObj == null || !"HOME".equals(String.valueOf(targetObj))) return;

                            Context ctx = extractContext(param.thisObject);
                            ComponentName defaultHome = resolveDefaultHome(ctx);
                            if (defaultHome == null || PKG_LAUNCHER.equals(defaultHome.getPackageName())) return;

                            // Restrict to true swipe-up gestures (avoid harming quick switch/app switch paths).
                            boolean verticalUp = pointF.y < -8f && Math.abs(pointF.y) >= Math.abs(pointF.x);
                            if (!verticalUp) return;

                            int token = resolveGestureToken(param.thisObject);
                            if (token == 0) token = System.identityHashCode(param.thisObject);

                            sArmedGestureToken = token;
                            sDirectHomeArmed = true;
                            sDirectHomeBypassBudget = 2;

                            try {
                                Object gestureState = findFieldValue(param.thisObject, new String[]{"mGestureState"});
                                if (gestureState != null) {
                                    XposedHelpers.callMethod(gestureState, "setEndTarget", targetObj, true);
                                }
                            } catch (Throwable ignored) {
                            }

                            // Start HOME immediately to avoid "home appears only after animation ends".
                            maybeStartHomeForGesture(ctx, token);
                            notifySwipeToRecentFinishedEarly(ctx);

                            logI("gesture HOME armed + early home start, fling=" + fling + ", token=" + token + " home=" + defaultHome.flattenToShortString());
                        } catch (Throwable t) {
                            logE("direct-home gesture hook error", t);
                        }
                    }
                });
                hooked++;
            }
            logI("hookDirectHomeAtGestureEnd installed, methods=" + hooked);
        } catch (Throwable t) {
            logW("hookDirectHomeAtGestureEnd unavailable: " + t.getMessage());
        }
    }

    private void hookCalculateEndTarget(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handler = XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", lpparam.classLoader);
            XposedBridge.hookAllMethods(handler, "calculateEndTarget", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        sLastSwipeUpHandler = param.thisObject;
                        Object result = param.getResult();
                        if (result == null) return;
                        String target = String.valueOf(result);
                        long now = SystemClock.uptimeMillis();
                        if ("HOME".equals(target)) {
                            sLastPredictHomeMs = now;
                            logI("predict HOME");
                        } else if ("RECENTS".equals(target)) {
                            sLastPredictRecentsMs = now;
                        }
                    } catch (Throwable t) {
                        logE("calculateEndTarget trace error", t);
                    }
                }
            });
        } catch (Throwable t) {
            logW("calculateEndTarget hook unavailable: " + t.getMessage());
        }
    }

    private void hookSystemUiProxyStartRecents(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> proxy = XposedHelpers.findClass("com.android.quickstep.SystemUiProxy", lpparam.classLoader);
            XposedBridge.hookAllMethods(proxy, "startRecentsActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = extractContext(param.thisObject);
                        if (ctx == null) return;
                        if (!shouldRedirectRecentsToHome(ctx)) return;

                        // Block recents rebound; keep operation light to avoid occasional freeze.
                        notifySwipeToRecentFinishedEarly(ctx);
                        maybeStartHomeForGesture(ctx, sArmedGestureToken);
                        consumeDirectHomeBypass();

                        Class<?> rt = ((Method) param.method).getReturnType();
                        if (rt == boolean.class || rt == Boolean.class) {
                            param.setResult(false);
                        } else {
                            param.setResult(null);
                        }
                        logI("blocked startRecentsActivity -> start HOME");
                    } catch (Throwable t) {
                        logE("startRecentsActivity hook error", t);
                    }
                }
            });
        } catch (Throwable t) {
            logW("SystemUiProxy.startRecentsActivity hook unavailable: " + t.getMessage());
        }
    }

    private void hookOnSettledOnEndTarget(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handler = XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", lpparam.classLoader);
            XposedBridge.hookAllMethods(handler, "onSettledOnEndTarget", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        sLastSwipeUpHandler = param.thisObject;
                        Object gestureState = findFieldValue(param.thisObject, new String[]{"mGestureState"});
                        if (gestureState == null) return;
                        Object end = XposedHelpers.callMethod(gestureState, "getEndTarget");
                        if (end == null || !"HOME".equals(String.valueOf(end))) return;

                        Context ctx = extractContext(param.thisObject);
                        ComponentName defaultHome = resolveDefaultHome(ctx);
                        if (defaultHome == null || PKG_LAUNCHER.equals(defaultHome.getPackageName())) return;

                        // Settled HOME: only light cleanup, avoid hard force-finish to reduce freeze risk.
                        notifySwipeToRecentFinishedEarly(ctx);
                        sDirectHomeBypassBudget = Math.min(sDirectHomeBypassBudget, 1);
                        logI("settled HOME -> light cleanup");
                    } catch (Throwable t) {
                        logE("onSettledOnEndTarget hook error", t);
                    }
                }
            });
        } catch (Throwable t) {
            logW("onSettledOnEndTarget hook unavailable: " + t.getMessage());
        }
    }

    private boolean shouldRedirectRecentsToHome(Context ctx) {
        ComponentName defaultHome = resolveDefaultHome(ctx);
        if (defaultHome == null) return false;
        if (PKG_LAUNCHER.equals(defaultHome.getPackageName())) return false;

        boolean armed = sDirectHomeArmed && sDirectHomeBypassBudget > 0;
        if (armed) {
            logI("redirect hit (armed): token=" + sArmedGestureToken + " budget=" + sDirectHomeBypassBudget + " home=" + defaultHome.flattenToShortString());
        }
        return armed;
    }

    private void maybeStartHomeForGesture(Context ctx, int token) {
        if (ctx == null) return;
        if (token != 0 && sHomeLaunchedGestureToken == token) return;
        startDefaultHomeNow(ctx);
        if (token != 0) {
            sHomeLaunchedGestureToken = token;
        }
    }

    private int resolveGestureToken(Object swipeUpHandlerObj) {
        try {
            Object gestureState = findFieldValue(swipeUpHandlerObj, new String[]{"mGestureState"});
            return gestureState == null ? 0 : System.identityHashCode(gestureState);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void clearDirectHomeArm(String reason) {
        sDirectHomeArmed = false;
        sDirectHomeBypassBudget = 0;
        sArmedGestureToken = 0;
        logI("clear arm: " + reason);
    }

    private void consumeDirectHomeBypass() {
        try {
            if (sDirectHomeBypassBudget > 0) {
                sDirectHomeBypassBudget--;
            }
            if (sDirectHomeBypassBudget <= 0) {
                clearDirectHomeArm("budget exhausted");
            }
        } catch (Throwable ignored) {
        }
    }


    private void notifySwipeToRecentFinishedEarly(Context ctx) {
        if (ctx == null) return;
        try {
            Class<?> sup = XposedHelpers.findClass("com.android.quickstep.SystemUiProxy", ctx.getClassLoader());
            Object holder = XposedHelpers.getStaticObjectField(sup, "INSTANCE");
            Object realProxy = XposedHelpers.callMethod(holder, "lambda$get$1", ctx);
            if (realProxy != null) {
                XposedHelpers.callMethod(realProxy, "notifySwipeToRecentFinished");
            }
        } catch (Throwable ignored) {
        }
    }

    private void startDefaultHomeNow(Context context) {
        if (context == null) return;
        try {
            ComponentName homeCmp = resolveDefaultHome(context);
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (homeCmp != null) {
                home.setComponent(homeCmp);
                home.setPackage(homeCmp.getPackageName());
            }
            context.startActivity(home);
        } catch (Throwable t) {
            logW("startDefaultHomeNow failed: " + t.getMessage());
        }
    }

    private Context extractContext(Object obj) {
        Object v = findFieldValue(obj, new String[]{"mContext", "context"});
        if (v instanceof Context) return (Context) v;
        return currentApplicationByReflection();
    }

    private ComponentName resolveDefaultHome(Context context) {
        if (context == null) return null;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            ActivityInfo ai = ri.activityInfo;
            if ("android".equals(ai.packageName)) return null;
            return new ComponentName(ai.packageName, ai.name);
        } catch (Throwable t) {
            logW("resolveDefaultHome failed: " + t.getMessage());
            return null;
        }
    }

    private Context currentApplicationByReflection() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            return (app instanceof Context) ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findFieldValue(Object obj, String[] candidates) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            for (String name : candidates) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void logI(String msg) {
        XposedBridge.log(TAG + " [I] " + msg);
    }

    private static void logW(String msg) {
        XposedBridge.log(TAG + " [W] " + msg);
    }

    private static void logE(String msg, Throwable t) {
        XposedBridge.log(TAG + " [E] " + msg + " : " + t);
        if (t != null) XposedBridge.log(t);
    }
}
