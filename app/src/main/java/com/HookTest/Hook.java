package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.media.ExifInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HookTest";
    private static final String PREFS_NAME = "hookdata";

    // 全局状态
    private static boolean locationEnabled = false;
    private static boolean cameraEnabled = false;
    private static int cameraMode = 0; // 0=单张, 1=多张循环
    private static String customLat = "";
    private static String customLng = "";

    // 相机替换相关
    private static String singleImagePath = "";
    private static int singleImageRotation = 0;
    private static List<String> multiImagePaths = new ArrayList<>();
    private static List<Integer> imageRotations = new ArrayList<>();
    private static int currentImageIndex = 0;
    private static Activity homeActivity;
    private static final int REQ_PICK_SINGLE = 299;
    private static final int REQ_PICK_MULTI = 300;

    // 面板中的EditText引用
    private static EditText latEdit;
    private static EditText lngEdit;
    private static TextView imageStatusText;
    private static LinearLayout imagePreviewContainer;

    // 悬浮窗相关
    private static Activity hostActivity;
    private static ViewGroup contentParent;
    private static View floatView;
    private static View panelView;
    private static boolean isPanelShowing = false;
    private static int statusBarHeight = 0;

    private static Context appContext;
    private static Handler uiHandler = new Handler(Looper.getMainLooper());

    // 已Hook的PictureCallback类去重列表
    private static java.util.List<String> hookedPictureClasses = new java.util.ArrayList<>();

    // Hook执行标志，防止LSPatch等环境下重复执行
    private static volatile boolean hooksExecuted = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Hook蓝月亮APP包名
        if (!lpparam.packageName.equals("cn.com.bluemoon.sfa")) {
            return;
        }

        Log.e(TAG, "包名匹配(蓝月亮sfa)，开始Hook");

        // LSPatch等环境下，currentApplication() 通常能直接获取到 Application
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "handleLoadPackage 直接获取到Context: " + appContext);
                executeHooks(lpparam);
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleLoadPackage 获取Context失败", t);
        }

        // 使用 Application.attach 获取 Context（传统Xposed / 兜底）
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                appContext = (Context) param.args[0];
                Log.e(TAG, "Application.attach 获取到Context: " + appContext);
                executeHooks(lpparam);
            }
        });

        // 兜底获取Context
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null && appContext == null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "兜底 currentApplication 获取到Context: " + appContext);
                executeHooks(lpparam);
            }
        } catch (Throwable t) {
            Log.e(TAG, "currentApplication 兜底获取失败", t);
        }

        // Hook MainActivity（它是真正的启动入口，会立即finish并启动RNMainActivity）
        try {
            XposedHelpers.findAndHookMethod("cn.com.bluemoon.sfa.MainActivity",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Log.e(TAG, "MainActivity onCreate - 启动入口");
                    // 尝试在这里获取Context并提前初始化
                    if (appContext == null) {
                        Activity activity = (Activity) param.thisObject;
                        appContext = activity.getApplicationContext();
                        executeHooks(lpparam);
                    }
                }
            });
            Log.e(TAG, "MainActivity Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook MainActivity 失败", t);
        }

        // Hook RNMainActivity.onCreate 注入悬浮窗 + 处理相册返回
        try {
            XposedHelpers.findAndHookMethod("cn.com.bluemoon.sfa.RNMainActivity",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    homeActivity = activity;
                    hostActivity = activity;
                    Log.e(TAG, "RNMainActivity onCreate, 注入悬浮窗");
                    injectFloatWindowWithRetry(activity, 0);
                }
            });
            XposedHelpers.findAndHookMethod("cn.com.bluemoon.sfa.RNMainActivity",
                    lpparam.classLoader, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (floatView == null) {
                        Log.e(TAG, "RNMainActivity onResume 兜底注入悬浮窗");
                        injectFloatWindowWithRetry(activity, 0);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("cn.com.bluemoon.sfa.RNMainActivity",
                    lpparam.classLoader, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    handleActivityResult(requestCode, resultCode, data);
                }
            });
            Log.e(TAG, "RNMainActivity Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook RNMainActivity 失败", t);
        }

        // 兜底：Hook 所有 bluemoon Activity.onCreate 显示悬浮窗
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                private boolean shown = false;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (shown) return;
                    Activity activity = (Activity) param.thisObject;
                    if (activity.getClass().getName().contains("bluemoon")) {
                        shown = true;
                        if (homeActivity == null && activity.getClass().getName().contains("Main")) {
                            homeActivity = activity;
                        }
                        Log.e(TAG, "Activity onCreate 兜底显示悬浮窗: " + activity.getClass().getName());
                        injectFloatWindowWithRetry(activity, 0);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onCreate 兜底失败", t);
        }

        // Hook 所有Activity的 onActivityResult 作为图片选择结果兜底
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    if (requestCode == REQ_PICK_SINGLE || requestCode == REQ_PICK_MULTI) {
                        int resultCode = (int) param.args[1];
                        Intent data = (Intent) param.args[2];
                        handleActivityResult(requestCode, resultCode, data);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onActivityResult 失败", t);
        }
    }

    // 模拟定位的地址信息（逆地理编码结果）
    private static String mockAddress = "";
    private static String mockProvince = "";
    private static String mockCity = "";
    private static String mockDistrict = "";
    private static String mockStreet = "";

    // ======================== Hook 统一执行入口 ========================

    private void executeHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (hooksExecuted) {
            Log.e(TAG, "Hooks 已执行过，跳过重复执行");
            return;
        }
        hooksExecuted = true;
        Log.e(TAG, "开始执行所有 Hooks");
        loadPrefs();
        hookAntiDetection(lpparam);
        hookAMapLocation(lpparam);
        hookSystemLocation(lpparam);
        hookTencentLocation(lpparam);
        hookCellLocation(lpparam);
        hookLocationResult(lpparam);
        hookCamera(lpparam);
        Log.e(TAG, "所有 Hooks 执行完毕");
    }

    // ======================== 悬浮窗注入（带重试） ========================

    /**
     * 使用重试机制注入悬浮窗，解决RN异步渲染导致contentParent暂时为空的问题
     * @param activity 目标Activity
     * @param retryCount 当前重试次数
     */
    private void injectFloatWindowWithRetry(final Activity activity, final int retryCount) {
        if (floatView != null) {
            Log.e(TAG, "悬浮窗已存在，跳过注入");
            return;
        }
        uiHandler.postDelayed(() -> {
            try {
                // 检查Activity是否仍然存活
                if (activity.isFinishing() || activity.isDestroyed()) {
                    Log.e(TAG, "Activity已finish/destroy，停止注入");
                    return;
                }

                hostActivity = activity;
                contentParent = (ViewGroup) activity.findViewById(android.R.id.content);

                // 检查DecorView是否已准备好
                if (contentParent == null) {
                    Log.e(TAG, "contentParent为空，重试(" + retryCount + ")");
                    if (retryCount < 20) {
                        injectFloatWindowWithRetry(activity, retryCount + 1);
                    } else {
                        Log.e(TAG, "重试20次仍无法获取contentParent，放弃注入");
                    }
                    return;
                }

                // 检查DecorView是否已挂载到Window上
                try {
                    Object parent = contentParent.getParent();
                    if (parent == null) {
                        Log.e(TAG, "contentParent.parent为空，视图树未就绪，重试(" + retryCount + ")");
                        if (retryCount < 20) {
                            injectFloatWindowWithRetry(activity, retryCount + 1);
                        }
                        return;
                    }
                } catch (Throwable checkErr) {
                    Log.e(TAG, "检查视图树异常，重试(" + retryCount + ")");
                    if (retryCount < 20) {
                        injectFloatWindowWithRetry(activity, retryCount + 1);
                    }
                    return;
                }

                Log.e(TAG, "contentParent就绪，注入悬浮窗 (retry=" + retryCount + ")");
                showFloatWindow(activity);
            } catch (Throwable t) {
                Log.e(TAG, "injectFloatWindowWithRetry异常", t);
                if (retryCount < 20) {
                    injectFloatWindowWithRetry(activity, retryCount + 1);
                }
            }
        }, 500); // 每500ms重试一次
    }

    // ======================== 悬浮窗UI ========================

    @SuppressLint("ClickableViewAccessibility")
    private void showFloatWindow(Activity activity) {
        if (floatView != null) return;

        hostActivity = activity;
        contentParent = (ViewGroup) activity.findViewById(android.R.id.content);

        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
        }

        final LinearLayout floatContainer = new LinearLayout(activity);
        floatContainer.setOrientation(LinearLayout.VERTICAL);
        floatContainer.setGravity(Gravity.TOP | Gravity.END);
        floatContainer.setPadding(0, statusBarHeight + 50, 16, 0);
        floatContainer.setClickable(false);
        floatContainer.setFocusable(false);

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        final LinearLayout floatBtn = new LinearLayout(activity);
        floatBtn.setOrientation(LinearLayout.HORIZONTAL);
        floatBtn.setBackgroundColor(0xCC000000);
        floatBtn.setPadding(16, 8, 16, 8);
        floatBtn.setGravity(Gravity.CENTER);
        floatBtn.setClickable(true);
        floatBtn.setFocusable(true);

        TextView btnText = new TextView(activity);
        btnText.setText("Hook");
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        floatBtn.addView(btnText);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        floatBtn.setLayoutParams(btnParams);

        floatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel(activity);
            }
        });

        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final long[] startTime = new long[1];
        final boolean[] isDragging = {false};

        floatBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX[0] = (int) v.getX();
                        initialY[0] = (int) v.getY();
                        touchX[0] = event.getRawX();
                        touchY[0] = event.getRawY();
                        startTime[0] = System.currentTimeMillis();
                        isDragging[0] = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX[0]);
                        int dy = (int) (event.getRawY() - touchY[0]);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging[0] = true;
                        }
                        v.setX(initialX[0] + dx);
                        v.setY(initialY[0] + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging[0] && System.currentTimeMillis() - startTime[0] < 300) {
                            return false;
                        }
                        return true;
                }
                return false;
            }
        });

        floatContainer.addView(floatBtn);

        try {
            contentParent.addView(floatContainer, containerParams);
            floatView = floatContainer;
            Log.e(TAG, "悬浮按钮显示成功");
        } catch (Throwable t) {
            Log.e(TAG, "悬浮按钮显示失败", t);
        }
    }

    // ======================== 面板控制 ========================

    private void togglePanel(Context ctx) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(ctx);
        }
    }

    private void hidePanel() {
        if (panelView != null && contentParent != null) {
            try {
                contentParent.removeView(panelView);
            } catch (Throwable t) {
                Log.e(TAG, "移除面板失败", t);
            }
            panelView = null;
            isPanelShowing = false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPanel(Context ctx) {
        if (isPanelShowing) return;

        if (contentParent == null && hostActivity != null) {
            contentParent = (ViewGroup) hostActivity.findViewById(android.R.id.content);
        }
        if (contentParent == null && homeActivity != null) {
            hostActivity = homeActivity;
            contentParent = (ViewGroup) homeActivity.findViewById(android.R.id.content);
        }
        if (contentParent == null) {
            Log.e(TAG, "contentParent 为空，无法显示面板");
            showFallbackDialog(ctx);
            return;
        }

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int panelWidth = Math.min(900, (int) (screenWidth * 0.9));

        LinearLayout mainContainer = new LinearLayout(ctx);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setBackgroundColor(0xE0FFFFFF);
        mainContainer.setPadding(16, 16, 16, 16);

        LinearLayout titleBar = new LinearLayout(ctx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleBar.setLayoutParams(titleParams);

        TextView titleText = new TextView(ctx);
        titleText.setText("Hook 设置面板");
        titleText.setTextColor(0xFF000000);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleText);

        Button closeBtn = new Button(ctx);
        closeBtn.setText("X");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setBackgroundColor(0x00000000);
        closeBtn.setTextColor(0xFF666666);
        closeBtn.setOnClickListener(v -> hidePanel());
        titleBar.addView(closeBtn);
        mainContainer.addView(titleBar);

        ScrollView contentScroll = new ScrollView(ctx);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout contentLayout = new LinearLayout(ctx);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);

        // ---- 模块1: 定位 ----
        addCollapsibleSection(ctx, contentLayout, "模拟定位", locationEnabled,
                (buttonView, isChecked) -> {
                    locationEnabled = isChecked;
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean("locationEnabled", isChecked).apply();
                },
                (layout) -> addLocationContent(ctx, layout));

        // ---- 模块2: 相机替换 ----
        addCollapsibleSection(ctx, contentLayout, "相机替换", cameraEnabled,
                (buttonView, isChecked) -> cameraEnabled = isChecked,
                (layout) -> addCameraContent(ctx, layout));

        mainContainer.addView(contentScroll);

        Button saveBtn = new Button(ctx);
        saveBtn.setText("保存所有设置");
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setPadding(0, 20, 0, 20);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = 16;
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show();
        });
        mainContainer.addView(saveBtn);

        FrameLayout.LayoutParams panelLayoutParams = new FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        panelLayoutParams.gravity = Gravity.CENTER;

        final int[] panelStartLeft = new int[1];
        final int[] panelStartTop = new int[1];
        final float[] touchStartX = new float[1];
        final float[] touchStartY = new float[1];

        titleBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        panelStartLeft[0] = panelLayoutParams.leftMargin;
                        panelStartTop[0] = panelLayoutParams.topMargin;
                        touchStartX[0] = event.getRawX();
                        touchStartY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLayoutParams.leftMargin = panelStartLeft[0] + (int) (event.getRawX() - touchStartX[0]);
                        panelLayoutParams.topMargin = panelStartTop[0] + (int) (event.getRawY() - touchStartY[0]);
                        panelLayoutParams.gravity = Gravity.TOP | Gravity.START;
                        if (contentParent != null && panelView != null) {
                            try {
                                contentParent.updateViewLayout(panelView, panelLayoutParams);
                            } catch (Throwable t) {
                                Log.e(TAG, "更新面板位置失败", t);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            contentParent.addView(mainContainer, panelLayoutParams);
            panelView = mainContainer;
            isPanelShowing = true;
            Log.e(TAG, "设置面板显示成功");
        } catch (Throwable t) {
            Log.e(TAG, "设置面板显示失败", t);
            showFallbackDialog(ctx);
        }
    }

    // ---- 折叠模块 ----
    private void addCollapsibleSection(Context ctx, LinearLayout parentLayout, String title,
            boolean isChecked,
            CompoundButton.OnCheckedChangeListener toggleListener,
            java.util.function.Consumer<LinearLayout> contentBuilder) {

        final boolean[] checked = {isChecked};

        LinearLayout sectionLayout = new LinearLayout(ctx);
        sectionLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLayout.setLayoutParams(sectionParams);

        LinearLayout headerLayout = new LinearLayout(ctx);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(8, 12, 8, 12);
        headerLayout.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerLayout.setLayoutParams(headerParams);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerLayout.addView(tv);

        TextView statusToggle = new TextView(ctx);
        statusToggle.setText(checked[0] ? "开启" : "关闭");
        statusToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
        statusToggle.setGravity(Gravity.CENTER);
        statusToggle.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusToggle.setLayoutParams(toggleParams);
        headerLayout.addView(statusToggle);

        sectionLayout.addView(headerLayout);

        LinearLayout contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(8, 0, 8, 8);
        contentContainer.setVisibility(View.GONE);

        contentBuilder.accept(contentContainer);

        sectionLayout.addView(contentContainer);

        View divider = new View(ctx);
        divider.setBackgroundColor(0xFFDDDDDD);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divider.setLayoutParams(divParams);
        sectionLayout.addView(divider);

        final boolean[] isExpanded = {false};
        headerLayout.setOnClickListener(v -> {
            isExpanded[0] = !isExpanded[0];
            contentContainer.setVisibility(isExpanded[0] ? View.VISIBLE : View.GONE);
        });

        statusToggle.setOnClickListener(v -> {
            checked[0] = !checked[0];
            statusToggle.setText(checked[0] ? "开启" : "关闭");
            statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
            toggleListener.onCheckedChanged(null, checked[0]);
        });

        parentLayout.addView(sectionLayout);
    }

    // ---- 定位内容（含地图选点） ----
    private void addLocationContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        layout.addView(createLabel(ctx, "经度 (Longitude):"));
        EditText lngEditLocal = createEditText(ctx, customLng, "例如: 121.808512");
        lngEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLng = s;
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lng", s).apply();
        }));
        lngEdit = lngEditLocal;
        layout.addView(lngEditLocal);

        layout.addView(createLabel(ctx, "纬度 (Latitude):"));
        EditText latEditLocal = createEditText(ctx, customLat, "例如: 31.141585");
        latEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLat = s;
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lat", s).apply();
        }));
        latEdit = latEditLocal;
        layout.addView(latEditLocal);

        addDivider(layout);

        Button mapPickerBtn = new Button(ctx);
        mapPickerBtn.setText("地图选点");
        mapPickerBtn.setOnClickListener(v -> showMapPicker(ctx));
        layout.addView(mapPickerBtn);

        addDivider(layout);

        // ---- 模拟地址信息（用于逆地理编码Hook） ----
        layout.addView(createLabel(ctx, "模拟地址（选填，用于上方\"当前位置\"显示）:"));
        EditText addrEdit = createEditText(ctx, "", "例如: 湖北省武汉市江岸区车站路103号");
        addrEdit.addTextChangedListener(new SimpleTextWatcher(s -> {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("mockAddress", s).apply();
        }));
        layout.addView(addrEdit);

        layout.addView(createLabel(ctx, "省份（选填）:"));
        EditText provinceEdit = createEditText(ctx, "", "例如: 湖北省");
        provinceEdit.addTextChangedListener(new SimpleTextWatcher(s -> {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("mockProvince", s).apply();
        }));
        layout.addView(provinceEdit);

        LinearLayout cityDistrictRow = new LinearLayout(ctx);
        cityDistrictRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cityDistrictRow.setLayoutParams(rowParams);
        cityDistrictRow.setWeightSum(2f);

        LinearLayout cityCol = new LinearLayout(ctx);
        cityCol.setOrientation(LinearLayout.VERTICAL);
        cityCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cityCol.addView(createLabel(ctx, "城市:"));
        EditText cityEdit = createEditText(ctx, "", "武汉市");
        cityEdit.addTextChangedListener(new SimpleTextWatcher(s -> {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("mockCity", s).apply();
        }));
        cityCol.addView(cityEdit);

        LinearLayout districtCol = new LinearLayout(ctx);
        districtCol.setOrientation(LinearLayout.VERTICAL);
        districtCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        districtCol.addView(createLabel(ctx, "区县:"));
        EditText districtEdit = createEditText(ctx, "", "江岸区");
        districtEdit.addTextChangedListener(new SimpleTextWatcher(s -> {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("mockDistrict", s).apply();
        }));
        districtCol.addView(districtEdit);

        cityDistrictRow.addView(cityCol);
        cityDistrictRow.addView(districtCol);
        layout.addView(cityDistrictRow);

        addDivider(layout);

        Button curLocBtn = new Button(ctx);
        curLocBtn.setText("获取当前位置");
        curLocBtn.setOnClickListener(v -> {
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    Toast.makeText(ctx, "无法获取位置服务", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                            && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(ctx, "缺少位置权限", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    customLat = String.valueOf(loc.getLatitude());
                    customLng = String.valueOf(loc.getLongitude());
                    lngEdit.setText(customLng);
                    latEdit.setText(customLat);
                    SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                    ed.putString("lat", customLat);
                    ed.putString("lng", customLng);
                    ed.apply();
                    Toast.makeText(ctx, "已获取当前位置", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "无法获取当前位置", Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException se) {
                Toast.makeText(ctx, "位置权限被拒绝", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(ctx, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(curLocBtn);
    }

    // ---- 腾讯地图Web选点 ----
    @SuppressLint("SetJavaScriptEnabled")
    private void showMapPicker(Context ctx) {
        Activity act = ctx instanceof Activity ? (Activity) ctx : homeActivity;
        if (act == null) {
            Toast.makeText(ctx, "无法打开地图选点", Toast.LENGTH_SHORT).show();
            return;
        }
        final Activity activity = act;
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.setTitle("地图选点");

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        WebView webView = new WebView(activity);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(webParams);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return handleMapUrl(url, dialog, activity);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMapUrl(url, dialog, activity);
            }

            private boolean handleMapUrl(String url, android.app.Dialog dialog, Context ctx) {
                if (url.startsWith("https://www.baidu.com")) {
                    Uri uri = Uri.parse(url);
                    String latng = uri.getQueryParameter("latng");
                    if (latng != null && !latng.isEmpty()) {
                        String[] parts = latng.split(",");
                        if (parts.length == 2) {
                            customLat = parts[0].trim();
                            customLng = parts[1].trim();
                            if (latEdit != null) latEdit.setText(customLat);
                            if (lngEdit != null) lngEdit.setText(customLng);
                            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            ed.putString("lat", customLat);
                            ed.putString("lng", customLng);
                            ed.apply();
                            Toast.makeText(ctx, "已选择位置: " + customLat + ", " + customLng, Toast.LENGTH_SHORT).show();
                        }
                    }
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl("https://mapapi.qq.com/web/mapComponents/locationPicker/v/index.html?search=1&type=0&backurl=https://www.baidu.com&key=54NBZ-F3IWI-2DJGQ-UXGAY-YOY2F-MXFKE");

        container.addView(webView);
        dialog.setContentView(container);

        dialog.show();
        android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    // ---- 相机替换内容 ----
    private void addCameraContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        layout.addView(createLabel(ctx, "替换模式:"));
        RadioGroup modeGroup = new RadioGroup(ctx);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);

        RadioButton singleMode = new RadioButton(ctx);
        singleMode.setText("单张替换");
        singleMode.setId(View.generateViewId());
        singleMode.setChecked(cameraMode == 0);

        RadioButton multiMode = new RadioButton(ctx);
        multiMode.setText("多张循环");
        multiMode.setId(View.generateViewId());
        multiMode.setChecked(cameraMode == 1);

        modeGroup.addView(singleMode);
        modeGroup.addView(multiMode);
        layout.addView(modeGroup);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == singleMode.getId()) {
                cameraMode = 0;
            } else if (checkedId == multiMode.getId()) {
                cameraMode = 1;
            }
            hidePanel();
            uiHandler.postDelayed(() -> showPanel(ctx), 100);
        });

        addDivider(layout);

        if (cameraMode == 0) {
            layout.addView(createLabel(ctx, "单张图片替换:"));

            Button selectSingleBtn = new Button(ctx);
            selectSingleBtn.setText("选择图片");
            selectSingleBtn.setOnClickListener(v -> openImagePicker(ctx, true));
            layout.addView(selectSingleBtn);

            if (singleImagePath != null && !singleImagePath.isEmpty()) {
                imagePreviewContainer = new LinearLayout(ctx);
                imagePreviewContainer.setOrientation(LinearLayout.HORIZONTAL);

                ImageView imgView = new ImageView(ctx);
                LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(dpToPx(ctx, 140), dpToPx(ctx, 140));
                imgView.setLayoutParams(imgParams);
                imgView.setBackgroundColor(0xFFEEEEEE);
                imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(singleImagePath);
                    if (bmp != null) {
                        if (singleImageRotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(singleImageRotation);
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                        }
                        imgView.setImageBitmap(bmp);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "加载单张缩略图失败", e);
                }
                imagePreviewContainer.addView(imgView);
                layout.addView(imagePreviewContainer);

                Button rotateBtn = new Button(ctx);
                rotateBtn.setText("旋转90 (当前" + singleImageRotation + ")");
                rotateBtn.setOnClickListener(v -> {
                    singleImageRotation = (singleImageRotation + 90) % 360;
                    savePrefs();
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                    Toast.makeText(ctx, "单张图片旋转 " + singleImageRotation, Toast.LENGTH_SHORT).show();
                });
                layout.addView(rotateBtn);

                TextView pathText = createLabel(ctx, singleImagePath);
                pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                pathText.setTextColor(0xFF666666);
                layout.addView(pathText);
            }
        } else {
            layout.addView(createLabel(ctx, "多张图片循环替换:"));

            Button selectMultiBtn = new Button(ctx);
            selectMultiBtn.setText("添加图片（逐张选择）");
            selectMultiBtn.setOnClickListener(v -> openImagePicker(ctx, false));
            layout.addView(selectMultiBtn);

            if (!multiImagePaths.isEmpty()) {
                Button addMoreBtn = new Button(ctx);
                addMoreBtn.setText("继续添加更多图片");
                addMoreBtn.setOnClickListener(v -> openImagePicker(ctx, false));
                layout.addView(addMoreBtn);
            }

            if (!multiImagePaths.isEmpty()) {
                TextView countText = createLabel(ctx, "已选 " + multiImagePaths.size() + " 张图片");
                layout.addView(countText);

                imagePreviewContainer = new LinearLayout(ctx);
                imagePreviewContainer.setOrientation(LinearLayout.HORIZONTAL);
                refreshImagePreviews(ctx);
                layout.addView(imagePreviewContainer);

                imageStatusText = createLabel(ctx, "当前使用: 第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张");
                layout.addView(imageStatusText);

                TextView dragHint = createLabel(ctx, "提示: 长按图片可调整顺序，点击可旋转90");
                dragHint.setTextColor(0xFF666666);
                dragHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                layout.addView(dragHint);
                
                Button clearAllBtn = new Button(ctx);
                clearAllBtn.setText("清空所有图片");
                clearAllBtn.setOnClickListener(v -> {
                    multiImagePaths.clear();
                    imageRotations.clear();
                    currentImageIndex = 0;
                    cameraEnabled = false;
                    savePrefs();
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                    Toast.makeText(ctx, "已清空所有图片", Toast.LENGTH_SHORT).show();
                });
                layout.addView(clearAllBtn);
            }
        }
    }

    private int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshImagePreviews(Context ctx) {
        if (imagePreviewContainer == null) return;
        imagePreviewContainer.removeAllViews();

        for (int i = 0; i < multiImagePaths.size(); i++) {
            final int index = i;
            String path = multiImagePaths.get(i);

            FrameLayout itemLayout = new FrameLayout(ctx);
            int itemW = dpToPx(ctx, 130);
            int itemH = dpToPx(ctx, 150);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(itemW, itemH);
            itemParams.setMargins(dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4));
            itemLayout.setLayoutParams(itemParams);

            ImageView imgView = new ImageView(ctx);
            int imgSize = dpToPx(ctx, 120);
            FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(imgSize, imgSize);
            imgParams.gravity = Gravity.CENTER;
            imgView.setLayoutParams(imgParams);
            imgView.setBackgroundColor(0xFFEEEEEE);
            imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            try {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    int rot = imageRotations.get(i);
                    if (rot != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rot);
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                    }
                    imgView.setImageBitmap(bmp);
                }
            } catch (Exception e) {
                Log.e(TAG, "加载缩略图失败", e);
            }

            if (i == currentImageIndex) {
                imgView.setPadding(dpToPx(ctx, 3), dpToPx(ctx, 3), dpToPx(ctx, 3), dpToPx(ctx, 3));
                imgView.setBackgroundColor(0xFF4CAF50);
            }

            int btnSize = dpToPx(ctx, 28);
            int btnPad = dpToPx(ctx, 2);

            TextView numText = new TextView(ctx);
            numText.setText(String.valueOf(i + 1));
            numText.setTextColor(0xFFFFFFFF);
            numText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            numText.setBackgroundColor(0xCC000000);
            numText.setPadding(btnPad, btnPad, btnPad, btnPad);
            FrameLayout.LayoutParams numParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            numParams.gravity = Gravity.TOP | Gravity.START;
            numParams.setMargins(dpToPx(ctx, 2), dpToPx(ctx, 2), 0, 0);
            numText.setLayoutParams(numParams);

            TextView delBtn = new TextView(ctx);
            delBtn.setText("x");
            delBtn.setTextColor(0xFFFFFFFF);
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            delBtn.setBackgroundColor(0xDDF44336);
            delBtn.setPadding(btnPad, 0, btnPad, 0);
            delBtn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams delParams = new FrameLayout.LayoutParams(btnSize, btnSize);
            delParams.gravity = Gravity.TOP | Gravity.END;
            delParams.setMargins(0, dpToPx(ctx, 2), dpToPx(ctx, 2), 0);
            delBtn.setLayoutParams(delParams);
            delBtn.setOnClickListener(v -> {
                multiImagePaths.remove(index);
                imageRotations.remove(index);
                if (currentImageIndex >= multiImagePaths.size()) {
                    currentImageIndex = 0;
                }
                if (multiImagePaths.isEmpty()) {
                    cameraEnabled = false;
                }
                savePrefs();
                refreshImagePreviews(ctx);
                updateImageStatus();
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show();
            });

            TextView rotateBtn = new TextView(ctx);
            rotateBtn.setText("R");
            rotateBtn.setTextColor(0xFFFFFFFF);
            rotateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rotateBtn.setBackgroundColor(0xDD2196F3);
            rotateBtn.setPadding(btnPad, 0, btnPad, 0);
            rotateBtn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams rotParams = new FrameLayout.LayoutParams(btnSize, btnSize);
            rotParams.gravity = Gravity.BOTTOM | Gravity.END;
            rotParams.setMargins(0, 0, dpToPx(ctx, 2), dpToPx(ctx, 2));
            rotateBtn.setLayoutParams(rotParams);
            final int imgIndex = i;
            rotateBtn.setOnClickListener(v -> {
                int currentRot = imageRotations.get(imgIndex);
                int newRot = (currentRot + 90) % 360;
                imageRotations.set(imgIndex, newRot);
                savePrefs();
                refreshImagePreviews(ctx);
                Toast.makeText(ctx, "图片 " + (imgIndex + 1) + " 旋转 " + newRot, Toast.LENGTH_SHORT).show();
            });

            imgView.setOnLongClickListener(v -> {
                final String[] items = new String[multiImagePaths.size()];
                for (int j = 0; j < items.length; j++) {
                    items[j] = "位置 " + (j + 1);
                }
                new android.app.AlertDialog.Builder(ctx)
                    .setTitle("调整图片到位置")
                    .setItems(items, (dialog, which) -> {
                        if (which != index) {
                            String pathToMove = multiImagePaths.remove(index);
                            multiImagePaths.add(which, pathToMove);
                            int rotToMove = imageRotations.remove(index);
                            imageRotations.add(which, rotToMove);
                            if (currentImageIndex == index) {
                                currentImageIndex = which;
                            } else if (currentImageIndex > index && currentImageIndex <= which) {
                                currentImageIndex--;
                            } else if (currentImageIndex < index && currentImageIndex >= which) {
                                currentImageIndex++;
                            }
                            savePrefs();
                            refreshImagePreviews(ctx);
                            updateImageStatus();
                        }
                    })
                    .show();
                return true;
            });

            imgView.setOnClickListener(v -> {
                currentImageIndex = index;
                refreshImagePreviews(ctx);
                updateImageStatus();
                Toast.makeText(ctx, "已切换到第 " + (currentImageIndex + 1) + " 张", Toast.LENGTH_SHORT).show();
            });

            itemLayout.addView(imgView);
            itemLayout.addView(numText);
            itemLayout.addView(delBtn);
            itemLayout.addView(rotateBtn);
            imagePreviewContainer.addView(itemLayout);
        }
    }

    private void updateImageStatus() {
        if (imageStatusText != null && !multiImagePaths.isEmpty()) {
            imageStatusText.setText("当前使用: 第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张");
        }
    }

    private void openImagePicker(Context ctx, boolean single) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            if (!single && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            Activity act = ctx instanceof Activity ? (Activity) ctx : homeActivity;
            if (act != null) {
                act.startActivityForResult(intent, single ? REQ_PICK_SINGLE : REQ_PICK_MULTI);
            }
        } catch (Throwable t) {
            Log.e(TAG, "打开图片选择器失败", t);
        }
    }

    private void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_SINGLE) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = getPathFromUri(uri);
                if (path != null) {
                    singleImagePath = path;
                    savePrefs();
                    Log.e(TAG, "单张图片已选择: " + path);
                    if (appContext != null) {
                        uiHandler.post(() -> Toast.makeText(appContext, "已选择单张图片", Toast.LENGTH_SHORT).show());
                    }
                }
            }
        } else if (requestCode == REQ_PICK_MULTI) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && data.getClipData() != null) {
                android.content.ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    android.content.ClipData.Item item = clipData.getItemAt(i);
                    Uri uri = item.getUri();
                    if (uri != null) {
                        String path = getPathFromUri(uri);
                        if (path != null) {
                            multiImagePaths.add(path);
                            imageRotations.add(0);
                        }
                    }
                }
            } else {
                Uri uri = data.getData();
                if (uri != null) {
                    String path = getPathFromUri(uri);
                    if (path != null) {
                        multiImagePaths.add(path);
                        imageRotations.add(0);
                    }
                }
            }
            savePrefs();
            if (appContext != null) {
                uiHandler.post(() -> Toast.makeText(appContext, "已添加 " + multiImagePaths.size() + " 张图片", Toast.LENGTH_SHORT).show());
            }
        }
    }

    private String getPathFromUri(Uri uri) {
        try {
            if (appContext == null) return null;
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                return uri.getPath();
            }
            // 复制到私有目录
            java.io.File cacheDir = appContext.getExternalCacheDir();
            if (cacheDir == null) cacheDir = appContext.getCacheDir();
            java.io.File outFile = new java.io.File(cacheDir, System.currentTimeMillis() + ".jpg");
            InputStream in = appContext.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            FileOutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "getPathFromUri 失败", t);
        }
        return null;
    }

    // ======================== Hook 反检测绕过 ========================

    /**
     * 绕过APP的Xposed/Root/虚拟环境检测
     * 基于DEX分析的准确方法名
     */
    private void hookAntiDetection(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // ===== Hook SecurityCheckUtil - 蓝月亮安全检测工具类 =====
            try {
                Class<?> securityCheckClass = cl.loadClass("cn.com.bluemoon.sfa.utils.check.SecurityCheckUtil");

                // === Xposed检测方法 ===
                String[] xposedMethods = {
                        "isXposedExistByThrow",   // 通过抛出异常检测Xposed
                        "isXposedExists",         // 检测Xposed是否存在
                        "tryShutdownXposed"       // 尝试关闭Xposed
                };
                for (String methodName : xposedMethods) {
                    try {
                        XposedHelpers.findAndHookMethod(securityCheckClass, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(false);
                                Log.e(TAG, "Hook SecurityCheckUtil." + param.method.getName() + " -> 返回false");
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // === Root检测方法 ===
                String[] rootMethods = {
                        "checkRootMethod1", "checkRootMethod2", "checkRootMethod3",
                        "isRoot", "isRooted", "isDeviceRooted", "isRootEnv",
                        "getIsRooted", "initRooted"
                };
                for (String methodName : rootMethods) {
                    try {
                        XposedHelpers.findAndHookMethod(securityCheckClass, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(false);
                                Log.e(TAG, "Hook SecurityCheckUtil." + param.method.getName() + " -> 返回false");
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // === 虚拟环境检测方法 ===
                // isInstallVirtual 和 isRunInVirtualApp 返回boolean
                String[] virtualMethods = {
                        "isInstallVirtual",     // 是否安装了虚拟环境
                        "isRunInVirtualApp"     // 是否运行在虚拟App中
                };
                for (String methodName : virtualMethods) {
                    try {
                        XposedHelpers.findAndHookMethod(securityCheckClass, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(false);
                                Log.e(TAG, "Hook SecurityCheckUtil." + param.method.getName() + " -> 返回false");
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // checkVirtual 可能返回int错误码（如-2），单独处理
                try {
                    XposedHelpers.findAndHookMethod(securityCheckClass, "checkVirtual", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            java.lang.reflect.Method m = (java.lang.reflect.Method) param.method;
                            if (m.getReturnType() == int.class) {
                                param.setResult(0); // 0表示正常
                                Log.e(TAG, "Hook SecurityCheckUtil.checkVirtual -> 返回0(正常)");
                            } else {
                                param.setResult(false);
                                Log.e(TAG, "Hook SecurityCheckUtil.checkVirtual -> 返回false");
                            }
                        }
                    });
                } catch (Throwable ignored) {}

                // === 其他检测方法 ===
                String[] otherMethods = {
                        "isAmsHooked",          // 检测AMS是否被hook
                        "isDebugEnv",           // 检测调试环境
                        "isAmpEnv", "isInAmpEnv",
                        "isSafeEntryName",
                        "checkLocationEnvironment"  // 检测定位环境
                };
                for (String methodName : otherMethods) {
                    try {
                        XposedHelpers.findAndHookMethod(securityCheckClass, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                java.lang.reflect.Method m = (java.lang.reflect.Method) param.method;
                                if (m.getReturnType() == boolean.class) {
                                    param.setResult(false);
                                }
                                Log.e(TAG, "Hook SecurityCheckUtil." + param.method.getName());
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // Hook初始化方法，确保检测不被触发
                try {
                    XposedHelpers.findAndHookMethod(securityCheckClass, "initSecurityCheck", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "Hook SecurityCheckUtil.initSecurityCheck -> 跳过初始化");
                            param.setResult(null);
                        }
                    });
                } catch (Throwable ignored) {}

                // Hook getAppRunEnvironment - 返回环境检测结果（可能返回错误码-2）
                try {
                    XposedHelpers.findAndHookMethod(securityCheckClass, "getAppRunEnvironment", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "Hook SecurityCheckUtil.getAppRunEnvironment -> 返回0(正常环境)");
                            param.setResult(0); // 0表示正常环境
                        }
                    });
                } catch (Throwable ignored) {}

                // Hook getAppRunEnvironmentTest - 测试方法
                try {
                    XposedHelpers.findAndHookMethod(securityCheckClass, "getAppRunEnvironmentTest", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(0);
                        }
                    });
                } catch (Throwable ignored) {}

                Log.e(TAG, "SecurityCheckUtil Hook成功 (反检测绕过)");
            } catch (Throwable t) {
                Log.e(TAG, "SecurityCheckUtil Hook失败: " + t.getMessage());
            }

            // ===== Hook VirtualApkCheckUtil - 虚拟APK检测工具类 =====
            try {
                Class<?> virtualCheckClass = cl.loadClass("cn.com.bluemoon.sfa.utils.check.VirtualApkCheckUtil");

                // Hook所有检测方法
                String[] virtualApkMethods = {
                        "isVirtualApk", "checkVirtualApk",
                        "isVirtual", "isRunningInVirtual", "isVirtualEnvironment"
                };

                for (String methodName : virtualApkMethods) {
                    try {
                        XposedHelpers.findAndHookMethod(virtualCheckClass, methodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(false);
                                Log.e(TAG, "Hook VirtualApkCheckUtil." + param.method.getName() + " -> 返回false");
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                Log.e(TAG, "VirtualApkCheckUtil Hook成功 (反检测绕过)");
            } catch (Throwable t) {
                Log.e(TAG, "VirtualApkCheckUtil Hook失败: " + t.getMessage());
            }

            // ===== 通用Xposed检测绕过 - 栈追踪过滤 =====
            // 注意：暂时禁用，避免导致栈溢出或性能问题
            // 改用其他方式绕过检测
            Log.e(TAG, "跳过 Throwable.getStackTrace Hook (避免性能问题)");

            // ===== Hook System.getProperty 绕过属性检测 =====
            try {
                XposedHelpers.findAndHookMethod(System.class, "getProperty", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        if (key != null) {
                            String lowerKey = key.toLowerCase();
                            if (lowerKey.contains("xposed") || lowerKey.contains("magisk")
                                    || lowerKey.contains("lsposed")) {
                                param.setResult(null);
                            }
                        }
                    }
                });
                Log.e(TAG, "System.getProperty Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "System.getProperty Hook失败: " + t.getMessage());
            }

            // ===== Hook Runtime.exec 绕过命令检测 =====
            try {
                XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String cmd = (String) param.args[0];
                        if (cmd != null) {
                            String lowerCmd = cmd.toLowerCase();
                            if (lowerCmd.contains("su") || lowerCmd.contains("magisk")
                                    || lowerCmd.contains("busybox") || lowerCmd.contains("xposed")) {
                                param.setThrowable(new java.io.IOException());
                                Log.e(TAG, "Hook Runtime.exec: 拦截命令 " + cmd);
                            }
                        }
                    }
                });
                Log.e(TAG, "Runtime.exec Hook成功 (Root检测绕过)");
            } catch (Throwable t) {
                Log.e(TAG, "Runtime.exec Hook失败: " + t.getMessage());
            }

            // ===== Hook ClassLoader.loadClass 绕过类加载检测 =====
            // 注意：暂时禁用，避免影响正常类加载导致APP崩溃
            // Xposed检测通过其他方式绕过
            Log.e(TAG, "跳过 ClassLoader.loadClass Hook (避免影响类加载)");

            // ===== Hook System.loadLibrary 绕过native层检测 =====
            // 注意：暂时不阻止so加载，避免APP崩溃，通过Java层hook绕过检测结果
            try {
                XposedHelpers.findAndHookMethod(System.class, "loadLibrary", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String libName = (String) param.args[0];
                        if (libName != null) {
                            String lowerName = libName.toLowerCase();
                            if (lowerName.contains("security") || lowerName.contains("check")
                                    || lowerName.contains("anti") || lowerName.contains("detect")
                                    || lowerName.contains("virtual") || lowerName.contains("shield")) {
                                Log.e(TAG, "检测到安全检测so库: " + libName + " (允许加载，通过Java层hook绕过)");
                            }
                        }
                    }
                });
                Log.e(TAG, "System.loadLibrary Hook成功 (监测native检测so)");
            } catch (Throwable t) {
                Log.e(TAG, "System.loadLibrary Hook失败: " + t.getMessage());
            }

            // ===== Hook Application.onCreate 确保检测之前执行hook =====
            // 尝试hook Application的onCreate，在其中再次确认反检测生效
            try {
                XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "Application.onCreate 执行，确认反检测hook已生效");
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Application.onCreate Hook失败: " + t.getMessage());
            }

        } catch (Throwable t) {
            Log.e(TAG, "反检测Hook异常: " + t.getMessage());
        }
    }

    // ======================== Hook 定位 ========================

    private void hookAMapLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> aMapLocationClass = cl.loadClass("com.amap.api.location.AMapLocation");

            // ====== 上游Hook: 在SDK将坐标传递给逆地理编码之前就替换坐标 ======
            // Hook AMapLocationClient.getLastKnownLocation - 返回模拟位置
            try {
                Class<?> clientClass = cl.loadClass("com.amap.api.location.AMapLocationClient");
                XposedHelpers.findAndHookMethod(clientClass, "getLastKnownLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        Object result = param.getResult();
                        if (result != null) {
                            XposedHelpers.callMethod(result, "setLatitude", Double.parseDouble(latStr));
                            XposedHelpers.callMethod(result, "setLongitude", Double.parseDouble(lngStr));
                            Log.e(TAG, "Hook AMapLocationClient.getLastKnownLocation: 已替换坐标为 " + latStr + "," + lngStr);
                        }
                    }
                });
                Log.e(TAG, "AMapLocationClient.getLastKnownLocation Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "AMapLocationClient.getLastKnownLocation Hook失败: " + t.getMessage());
            }

            // Hook RegeocodeQuery.getPoint - 让逆地理编码查询使用模拟坐标
            // 这是覆盖APP显式调用GeocodeSearch的最上游hook点
            try {
                Class<?> regeocodeQueryClass = cl.loadClass("com.amap.api.services.geocoder.RegeocodeQuery");
                XposedHelpers.findAndHookMethod(regeocodeQueryClass, "getPoint", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        Object originalPoint = param.getResult();
                        if (originalPoint != null) {
                            double origLat = (Double) XposedHelpers.callMethod(originalPoint, "getLatitude");
                            double origLng = (Double) XposedHelpers.callMethod(originalPoint, "getLongitude");
                            Log.e(TAG, "Hook RegeocodeQuery.getPoint: 原始坐标 " + origLat + "," + origLng
                                    + " -> 替换为 " + latStr + "," + lngStr);
                            XposedHelpers.callMethod(originalPoint, "setLatitude", Double.parseDouble(latStr));
                            XposedHelpers.callMethod(originalPoint, "setLongitude", Double.parseDouble(lngStr));
                            param.setResult(originalPoint);
                        }
                    }
                });
                Log.e(TAG, "RegeocodeQuery.getPoint Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "RegeocodeQuery.getPoint Hook失败: " + t.getMessage());
            }

            // Hook RegeocodeQuery构造函数 - 从源头替换坐标
            // 这是最上游的hook，RegeocodeQuery创建时就把坐标替换为模拟坐标
            try {
                Class<?> regeocodeQueryClass = cl.loadClass("com.amap.api.services.geocoder.RegeocodeQuery");
                Class<?> latLonPointClass = cl.loadClass("com.amap.api.services.core.LatLonPoint");
                XposedHelpers.findAndHookConstructor(regeocodeQueryClass, latLonPointClass, float.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        Object point = param.args[0];
                        if (point != null) {
                            double origLat = (Double) XposedHelpers.callMethod(point, "getLatitude");
                            double origLng = (Double) XposedHelpers.callMethod(point, "getLongitude");
                            // 直接修改传入的point对象的坐标
                            XposedHelpers.callMethod(point, "setLatitude", Double.parseDouble(latStr));
                            XposedHelpers.callMethod(point, "setLongitude", Double.parseDouble(lngStr));
                            Log.e(TAG, "Hook RegeocodeQuery构造函数: 原始坐标 " + origLat + "," + origLng
                                    + " -> 替换为 " + latStr + "," + lngStr);
                        }
                    }
                });
                Log.e(TAG, "RegeocodeQuery构造函数 Hook成功 (最上游坐标拦截)");
            } catch (Throwable t) {
                Log.e(TAG, "RegeocodeQuery构造函数 Hook失败: " + t.getMessage());
            }

            // Hook LatLng - 高德地图UI组件使用的坐标类
            // 地图选点、地图移动等场景都会用到这个类
            try {
                Class<?> latLngClass = cl.loadClass("com.amap.api.maps.model.LatLng");
                // Hook构造函数 - 创建时就替换坐标
                XposedHelpers.findAndHookConstructor(latLngClass, double.class, double.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        // 只在坐标与模拟坐标差异较大时替换（避免无限循环）
                        double origLat = (Double) param.args[0];
                        double origLng = (Double) param.args[1];
                        double mockLat = Double.parseDouble(latStr);
                        double mockLng = Double.parseDouble(lngStr);
                        if (Math.abs(origLat - mockLat) > 0.0001 || Math.abs(origLng - mockLng) > 0.0001) {
                            param.args[0] = mockLat;
                            param.args[1] = mockLng;
                            Log.e(TAG, "Hook LatLng构造函数: 原始 " + origLat + "," + origLng
                                    + " -> 模拟 " + mockLat + "," + mockLng);
                        }
                    }
                });
                Log.e(TAG, "LatLng构造函数 Hook成功 (地图UI坐标拦截)");
            } catch (Throwable t) {
                Log.e(TAG, "LatLng Hook失败: " + t.getMessage());
            }

            // Hook GeocodeSearch.getFromLocation - 同步逆地理编码
            try {
                Class<?> geocodeSearchClass = cl.loadClass("com.amap.api.services.geocoder.GeocodeSearch");
                Class<?> regeocodeQueryClass = cl.loadClass("com.amap.api.services.geocoder.RegeocodeQuery");
                XposedHelpers.findAndHookMethod(geocodeSearchClass, "getFromLocation", regeocodeQueryClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        Object query = param.args[0];
                        if (query != null) {
                            Object point = XposedHelpers.callMethod(query, "getPoint");
                            if (point != null) {
                                XposedHelpers.callMethod(point, "setLatitude", Double.parseDouble(latStr));
                                XposedHelpers.callMethod(point, "setLongitude", Double.parseDouble(lngStr));
                                Log.e(TAG, "Hook GeocodeSearch.getFromLocation: 已替换查询坐标");
                            }
                        }
                    }
                });
                Log.e(TAG, "GeocodeSearch.getFromLocation Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "GeocodeSearch.getFromLocation Hook失败: " + t.getMessage());
            }

            // Hook GeocodeSearch.getFromLocationAsyn - 异步逆地理编码
            try {
                Class<?> geocodeSearchClass = cl.loadClass("com.amap.api.services.geocoder.GeocodeSearch");
                Class<?> regeocodeQueryClass = cl.loadClass("com.amap.api.services.geocoder.RegeocodeQuery");
                XposedHelpers.findAndHookMethod(geocodeSearchClass, "getFromLocationAsyn", regeocodeQueryClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        Object query = param.args[0];
                        if (query != null) {
                            Object point = XposedHelpers.callMethod(query, "getPoint");
                            if (point != null) {
                                XposedHelpers.callMethod(point, "setLatitude", Double.parseDouble(latStr));
                                XposedHelpers.callMethod(point, "setLongitude", Double.parseDouble(lngStr));
                                Log.e(TAG, "Hook GeocodeSearch.getFromLocationAsyn: 已替换查询坐标");
                            }
                        }
                    }
                });
                Log.e(TAG, "GeocodeSearch.getFromLocationAsyn Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "GeocodeSearch.getFromLocationAsyn Hook失败: " + t.getMessage());
            }

            // Hook LatLonPoint.getLatitude/getLongitude - 覆盖所有坐标读取
            try {
                Class<?> latLonPointClass = cl.loadClass("com.amap.api.services.core.LatLonPoint");
                XposedHelpers.findAndHookMethod(latLonPointClass, "getLatitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        if (latStr.isEmpty()) return;
                        param.setResult(Double.parseDouble(latStr));
                    }
                });
                XposedHelpers.findAndHookMethod(latLonPointClass, "getLongitude", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String lngStr = sh.getString("lng", "");
                        if (lngStr.isEmpty()) return;
                        param.setResult(Double.parseDouble(lngStr));
                    }
                });
                Log.e(TAG, "LatLonPoint.getLatitude/getLongitude Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "LatLonPoint Hook失败: " + t.getMessage());
            }

            // Hook getAddress - 返回模拟地址字符串
            // 关键修复：即使mockAddress为空，也不返回真实地址，而是触发异步逆地理编码
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getAddress", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String addr = sh.getString("mockAddress", "");
                    if (!addr.isEmpty()) {
                        param.setResult(addr);
                        Log.e(TAG, "Hook AMapLocation.getAddress: " + addr);
                    } else {
                        // mockAddress为空时，返回空字符串而不是真实地址
                        // 同时触发异步逆地理编码
                        param.setResult("");
                        Log.e(TAG, "Hook AMapLocation.getAddress: mockAddress为空，返回空字符串并触发逆地理编码");
                        // 触发异步逆地理编码（避免阻塞主线程）
                        final String latStr = sh.getString("lat", "");
                        final String lngStr = sh.getString("lng", "");
                        if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                            // 使用uiHandler post到主线程触发（reverseGeocodeAsync内部会开子线程）
                            uiHandler.post(() -> reverseGeocodeAsync());
                        }
                    }
                }
            });

            // Hook setAddress - 在SDK设置地址时就替换为模拟地址
            // 这是上游hook，在地址被写入AMapLocation之前就替换
            try {
                XposedHelpers.findAndHookMethod(aMapLocationClass, "setAddress", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String mockAddr = sh.getString("mockAddress", "");
                        if (!mockAddr.isEmpty()) {
                            String originalAddr = (String) param.args[0];
                            if (originalAddr != null && !originalAddr.equals(mockAddr)) {
                                Log.e(TAG, "Hook AMapLocation.setAddress: 原始地址 \"" + originalAddr + "\" -> 模拟地址 \"" + mockAddr + "\"");
                            }
                            param.args[0] = mockAddr;
                        }
                        // 如果mockAddress为空，允许设置真实地址（但getAddress会返回空字符串）
                    }
                });
                Log.e(TAG, "AMapLocation.setAddress Hook成功 (上游地址拦截)");
            } catch (Throwable t) {
                Log.e(TAG, "AMapLocation.setAddress Hook失败: " + t.getMessage());
            }

            // Hook getProvince
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getProvince", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockProvince", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getCity
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getCity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockCity", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getDistrict
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getDistrict", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockDistrict", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getStreet
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getStreet", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockStreet", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getCityCode
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getCityCode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockCityCode", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getAdCode
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getAdCode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String val = sh.getString("mockAdCode", "");
                    if (!val.isEmpty()) param.setResult(val);
                }
            });

            // Hook getLatitude
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String latStr = sh.getString("lat", "");
                    if (latStr.isEmpty()) return;
                    try {
                        double lat = Double.parseDouble(latStr);
                        Log.e(TAG, "Hook AMapLocation.getLatitude: " + lat);
                        param.setResult(lat);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "纬度格式错误", e);
                    }
                }
            });
            // Hook getLongitude
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String lngStr = sh.getString("lng", "");
                    if (lngStr.isEmpty()) return;
                    try {
                        double lng = Double.parseDouble(lngStr);
                        Log.e(TAG, "Hook AMapLocation.getLongitude: " + lng);
                        param.setResult(lng);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "经度格式错误", e);
                    }
                }
            });

            // Hook isMock 返回 false，防止被检测为模拟位置
            try {
                XposedHelpers.findAndHookMethod(aMapLocationClass, "isMock", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (sh.getBoolean("locationEnabled", false)) {
                            param.setResult(false);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook setLatitude/setLongitude - 让SDK内部设置坐标时就被替换为模拟坐标
            // 这是最上游的hook，SDK内部无论是从GPS/网络/WiFi拿到坐标后，
            // 都会调用setLatitude/setLongitude写入AMapLocation
            // 我们在这里拦截，让写入的就是模拟坐标
            // 这样SDK内部后续的逆地理编码就会用模拟坐标去查询
            try {
                XposedHelpers.findAndHookMethod(aMapLocationClass, "setLatitude", double.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        if (latStr.isEmpty()) return;
                        try {
                            double originalLat = (Double) param.args[0];
                            double fakeLat = Double.parseDouble(latStr);
                            if (Math.abs(originalLat - fakeLat) > 0.0001) {
                                Log.e(TAG, "Hook AMapLocation.setLatitude: 原始 " + originalLat + " -> 模拟 " + fakeLat);
                            }
                            param.args[0] = fakeLat;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                XposedHelpers.findAndHookMethod(aMapLocationClass, "setLongitude", double.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String lngStr = sh.getString("lng", "");
                        if (lngStr.isEmpty()) return;
                        try {
                            double originalLng = (Double) param.args[0];
                            double fakeLng = Double.parseDouble(lngStr);
                            if (Math.abs(originalLng - fakeLng) > 0.0001) {
                                Log.e(TAG, "Hook AMapLocation.setLongitude: 原始 " + originalLng + " -> 模拟 " + fakeLng);
                            }
                            param.args[0] = fakeLng;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                Log.e(TAG, "AMapLocation.setLatitude/setLongitude Hook成功 (上游坐标拦截)");
            } catch (Throwable t) {
                Log.e(TAG, "AMapLocation.setLatitude Hook失败: " + t.getMessage());
            }

            Log.e(TAG, "高德定位Hook成功(含上游坐标拦截+地址+逆地理编码)");
        } catch (Throwable t) {
            Log.e(TAG, "高德定位Hook失败", t);
        }
    }

    private void hookSystemLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Hook Location.getLatitude
            XposedHelpers.findAndHookMethod("android.location.Location", cl, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String latStr = sh.getString("lat", "");
                    if (latStr.isEmpty()) return;
                    try {
                        param.setResult(Double.parseDouble(latStr));
                    } catch (NumberFormatException ignored) {}
                }
            });

            // Hook Location.getLongitude
            XposedHelpers.findAndHookMethod("android.location.Location", cl, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String lngStr = sh.getString("lng", "");
                    if (lngStr.isEmpty()) return;
                    try {
                        param.setResult(Double.parseDouble(lngStr));
                    } catch (NumberFormatException ignored) {}
                }
            });

            // Hook LocationManager.getLastKnownLocation
            XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                    "getLastKnownLocation", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String latStr = sh.getString("lat", "");
                    String lngStr = sh.getString("lng", "");
                    if (latStr.isEmpty() || lngStr.isEmpty()) return;
                    android.location.Location loc = (android.location.Location) param.getResult();
                    if (loc == null) {
                        loc = new android.location.Location(android.location.LocationManager.GPS_PROVIDER);
                    }
                    loc.setLatitude(Double.parseDouble(latStr));
                    loc.setLongitude(Double.parseDouble(lngStr));
                    param.setResult(loc);
                    Log.e(TAG, "Hook LocationManager.getLastKnownLocation: " + latStr + ", " + lngStr);
                }
            });

            Log.e(TAG, "系统定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "系统定位Hook失败", t);
        }
    }

    private void hookTencentLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Hook TencentLocation.getLatitude / getLongitude
            try {
                Class<?> tencentLocClass = cl.loadClass("com.tencent.map.geolocation.TencentLocation");
                XposedHelpers.findAndHookMethod(tencentLocClass, "getLatitude", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String latStr = sh.getString("lat", "");
                        if (latStr.isEmpty()) return;
                        try {
                            param.setResult(Double.parseDouble(latStr));
                        } catch (NumberFormatException ignored) {}
                    }
                });
                XposedHelpers.findAndHookMethod(tencentLocClass, "getLongitude", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String lngStr = sh.getString("lng", "");
                        if (lngStr.isEmpty()) return;
                        try {
                            param.setResult(Double.parseDouble(lngStr));
                        } catch (NumberFormatException ignored) {}
                    }
                });
                // Hook TencentLocation.getAddress
                XposedHelpers.findAndHookMethod(tencentLocClass, "getAddress", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        String addr = sh.getString("mockAddress", "");
                        if (!addr.isEmpty()) {
                            param.setResult(addr);
                            Log.e(TAG, "Hook TencentLocation.getAddress: " + addr);
                        } else {
                            // mockAddress为空时，返回空字符串而不是真实地址
                            param.setResult("");
                            Log.e(TAG, "Hook TencentLocation.getAddress: mockAddress为空，返回空字符串");
                            // 触发异步逆地理编码
                            final String latStr = sh.getString("lat", "");
                            final String lngStr = sh.getString("lng", "");
                            if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                                uiHandler.post(() -> reverseGeocodeAsync());
                            }
                        }
                    }
                });
                // Hook TencentLocation.getCity
                try {
                    XposedHelpers.findAndHookMethod(tencentLocClass, "getCity", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            if (!sh.getBoolean("locationEnabled", false)) return;
                            String val = sh.getString("mockCity", "");
                            if (!val.isEmpty()) param.setResult(val);
                        }
                    });
                } catch (Throwable ignored) {}
                // Hook TencentLocation.getDistrict
                try {
                    XposedHelpers.findAndHookMethod(tencentLocClass, "getDistrict", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            if (!sh.getBoolean("locationEnabled", false)) return;
                            String val = sh.getString("mockDistrict", "");
                            if (!val.isEmpty()) param.setResult(val);
                        }
                    });
                } catch (Throwable ignored) {}
                Log.e(TAG, "腾讯定位 TencentLocation Hook成功(含地址)");
            } catch (Throwable t) {
                Log.e(TAG, "腾讯定位 TencentLocation Hook失败", t);
            }

            // Hook TencentLocationManager.getLastKnownLocation
            try {
                Class<?> tencentLocMgrClass = cl.loadClass("com.tencent.map.geolocation.TencentLocationManager");
                XposedHelpers.findAndHookMethod(tencentLocMgrClass, "getLastKnownLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (!sh.getBoolean("locationEnabled", false)) return;
                        Log.e(TAG, "Hook TencentLocationManager.getLastKnownLocation");
                    }
                });
                Log.e(TAG, "腾讯定位 TencentLocationManager Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "腾讯定位 TencentLocationManager Hook失败", t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "腾讯定位Hook整体失败", t);
        }
    }

    private void hookCellLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Hook TelephonyManager.getCellLocation
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl,
                    "getCellLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    // 构造一个假的GsmCellLocation，避免APP因获取不到基站而异常
                    try {
                        android.telephony.gsm.GsmCellLocation fakeCell = new android.telephony.gsm.GsmCellLocation();
                        fakeCell.setLacAndCid(12345, 67890);
                        param.setResult(fakeCell);
                        Log.e(TAG, "Hook TelephonyManager.getCellLocation -> fake GSM cell");
                    } catch (Throwable inner) {
                        // 如果构造失败，返回null
                        param.setResult(null);
                    }
                }
            });

            // Hook TelephonyManager.getAllCellInfo
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl,
                    "getAllCellInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    param.setResult(new ArrayList<>());
                    Log.e(TAG, "Hook TelephonyManager.getAllCellInfo -> empty list");
                }
            });

            Log.e(TAG, "基站定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "基站定位Hook失败", t);
        }
    }

    /**
     * Hook LocationResult.getGpsAddress - 蓝月亮内部定位结果类
     * 确保APP内部所有读取gpsAddress的地方都拿到模拟地址
     */
    private void hookLocationResult(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> locationResultClass = cl.loadClass("cn.com.bluemoon.sfa.utils.location.LocationResult");
            XposedHelpers.findAndHookMethod(locationResultClass, "getGpsAddress", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String addr = sh.getString("mockAddress", "");
                    if (!addr.isEmpty()) {
                        param.setResult(addr);
                        Log.e(TAG, "Hook LocationResult.getGpsAddress: " + addr);
                    } else {
                        // mockAddress为空时，返回空字符串而不是真实地址
                        param.setResult("");
                        Log.e(TAG, "Hook LocationResult.getGpsAddress: mockAddress为空，返回空字符串");
                        // 触发异步逆地理编码
                        final String latStr = sh.getString("lat", "");
                        final String lngStr = sh.getString("lng", "");
                        if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                            uiHandler.post(() -> reverseGeocodeAsync());
                        }
                    }
                }
            });
            Log.e(TAG, "LocationResult.getGpsAddress Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "LocationResult.getGpsAddress Hook失败: " + t.getMessage());
        }

        // Hook LocationParam.getGpsAddress（另一套定位参数类）
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> locationParamClass = cl.loadClass("bluemoon.com.lib_x5.bean.LocationParam");
            XposedHelpers.findAndHookMethod(locationParamClass, "getGpsAddress", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    if (!sh.getBoolean("locationEnabled", false)) return;
                    String addr = sh.getString("mockAddress", "");
                    if (!addr.isEmpty()) {
                        param.setResult(addr);
                        Log.e(TAG, "Hook LocationParam.getGpsAddress: " + addr);
                    } else {
                        // mockAddress为空时，返回空字符串而不是真实地址
                        param.setResult("");
                        Log.e(TAG, "Hook LocationParam.getGpsAddress: mockAddress为空，返回空字符串");
                        // 触发异步逆地理编码
                        final String latStr = sh.getString("lat", "");
                        final String lngStr = sh.getString("lng", "");
                        if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                            uiHandler.post(() -> reverseGeocodeAsync());
                        }
                    }
                }
            });
            Log.e(TAG, "LocationParam.getGpsAddress Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "LocationParam.getGpsAddress Hook失败: " + t.getMessage());
        }
    }

    // ======================== Hook 相机 ========================

    private void hookCamera(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.e(TAG, "【PicHook】start PicHook...");
        final ClassLoader cl = lpparam.classLoader;

        // ===== Hook 1: 钉钉方盒 CameraActivity2/3 =====
        hookCameraActivityMethod(cl, "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2");
        hookCameraActivityMethod(cl, "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3");

        // ===== Hook 2: Camera.takePicture -> 级联Hook onPictureTaken =====
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                    "takePicture",
                    android.hardware.Camera.ShutterCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object jpegCallback = param.args[3];
                    if (jpegCallback == null) return;
                    
                    Class<?> callbackClass = jpegCallback.getClass();
                    String className = callbackClass.getName();
                    
                    if (hookedPictureClasses.contains(className)) return;
                    hookedPictureClasses.add(className);
                    
                    Log.e(TAG, "【PicHook】Camera.takePicture callback class: " + className);
                    hookPictureCallbackClass(callbackClass, className);
                }
            });
            Log.e(TAG, "【PicHook】Camera.takePicture Hook OK");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】Camera.takePicture Hook FAIL: " + t.getMessage());
        }

        // ===== Hook 3: 水印处理类自动扫描 =====
        hookWatermarkClass(cl);

        // ===== Hook 4: MediaStore.insertImage 兜底 =====
        try {
            XposedHelpers.findAndHookMethod(MediaStore.Images.Media.class, "insertImage",
                    android.content.ContentResolver.class, String.class, String.class, String.class,
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (cameraEnabled) {
                        String imagePath = (String) param.args[1];
                        replaceSavedImage(imagePath);
                    }
                }
            });
            Log.e(TAG, "【PicHook】MediaStore.insertImage Hook OK");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】MediaStore Hook FAIL: " + t.getMessage());
        }

        // ===== Hook 5: 蓝月亮 BMCameraActivity (CameraX) =====
        hookBMCamera(lpparam);

        Log.e(TAG, "【PicHook】PicHook loaded done");
    }

    /**
     * Hook 蓝月亮 BMCameraActivity 的 CameraX 拍照回调
     */
    private void hookBMCamera(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            // 尝试Hook BMCameraActivity$takePhoto$1.onImageSaved
            // 蓝月亮使用的是 androidx.camera.core.ImageCapture$OnImageSavedCallback
            // 内部类名为 cn.com.json.lib_picture.BMCameraActivity$takePhoto$1
            Class<?> callbackClass = cl.loadClass("cn.com.json.lib_picture.BMCameraActivity$takePhoto$1");
            
            // 找到 onImageSaved 方法
            java.lang.reflect.Method targetMethod = null;
            for (java.lang.reflect.Method m : callbackClass.getDeclaredMethods()) {
                if ("onImageSaved".equals(m.getName())) {
                    targetMethod = m;
                    break;
                }
            }
            
            if (targetMethod == null) {
                Log.e(TAG, "【BMPicHook】未找到 onImageSaved 方法");
                return;
            }
            
            final String methodName = targetMethod.getName();
            final Class<?>[] paramTypes = targetMethod.getParameterTypes();
            
            XposedHelpers.findAndHookMethod(callbackClass, methodName, (Object[]) paramTypes, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cameraEnabled) return;
                    
                    // 获取 this$0 (BMCameraActivity实例)
                    Object activity = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    if (activity == null) return;
                    
                    // 获取 tempPicFilePath 字段
                    String tempPath = (String) XposedHelpers.getObjectField(activity, "tempPicFilePath");
                    if (tempPath == null || tempPath.isEmpty()) return;
                    
                    String fakePath = getCurrentImagePath();
                    if (fakePath == null || fakePath.isEmpty()) return;
                    
                    File tempFile = new File(tempPath);
                    if (!tempFile.getParentFile().exists()) return;
                    
                    // 将指定图片复制/替换到 tempPath
                    boolean success = copyImageFile(fakePath, tempPath);
                    if (success) {
                        Log.e(TAG, "【BMPicHook】已替换蓝月亮相机图片: " + tempPath);
                        advanceImageIndex();
                    }
                }
            });
            
            Log.e(TAG, "【BMPicHook】BMCameraActivity$takePhoto$1.onImageSaved Hook成功");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "【BMPicHook】BMCameraActivity$takePhoto$1 类未找到，可能APP版本不同");
        } catch (Throwable t) {
            Log.e(TAG, "【BMPicHook】Hook BMCameraActivity 失败: " + t.getMessage(), t);
        }
    }

    /**
     * 复制图片文件到目标路径（支持旋转处理）
     */
    private boolean copyImageFile(String srcPath, String destPath) {
        try {
            File srcFile = new File(srcPath);
            if (!srcFile.exists()) return false;
            
            Bitmap bitmap = BitmapFactory.decodeFile(srcPath);
            if (bitmap == null) return false;
            
            // 应用手动旋转（单张模式用singleImageRotation，多张模式用imageRotations）
            int rotation = 0;
            if (cameraMode == 0) {
                rotation = singleImageRotation;
            } else {
                int idx = multiImagePaths.indexOf(srcPath);
                if (idx >= 0 && idx < imageRotations.size()) {
                    rotation = imageRotations.get(idx);
                }
            }
            
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            
            FileOutputStream fos = new FileOutputStream(destPath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】copyImageFile 失败", t);
            return false;
        }
    }

    private void hookCameraActivityMethod(ClassLoader cl, String baseClassName) {
        final String tag = "【PicHook】";
        
        String[] hardcoded = {"$3", "$1", "$2", "$4", "$5"};
        for (String suffix : hardcoded) {
            try {
                String fullClassName = baseClassName + suffix;
                Class<?> cls = cl.loadClass(fullClassName);
                boolean found = false;
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Bitmap.class) {
                        doHookOnTakePicture(cls, fullClassName);
                        found = true;
                        break;
                    }
                }
                if (found) return;
            } catch (Throwable ignored) {}
        }
        
        for (int idx = 1; idx <= 20; idx++) {
            try {
                String fullClassName = baseClassName + "$" + idx;
                Class<?> cls = cl.loadClass(fullClassName);
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Bitmap.class) {
                        doHookOnTakePicture(cls, fullClassName);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        try {
            Class<?> cls = cl.loadClass(baseClassName);
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == Bitmap.class) {
                    doHookOnTakePicture(cls, baseClassName);
                    return;
                }
            }
        } catch (Throwable ignored) {}
        
        Log.e(TAG, tag + baseClassName + " 未找到 onTakePicture(Bitmap) 方法");
    }

    private void doHookOnTakePicture(final Class<?> cls, final String className) {
        try {
            XposedHelpers.findAndHookMethod(cls, "onTakePicture", Bitmap.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cameraEnabled) return;
                    
                    String path = getCurrentImagePath();
                    if (path == null || path.isEmpty()) return;
                    
                    File file = new File(path);
                    if (!file.exists()) return;
                    
                    Bitmap bitmap = BitmapFactory.decodeFile(path);
                    if (bitmap == null) return;
                    
                    if (cameraMode == 0 && singleImageRotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(singleImageRotation);
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    }
                    
                    param.args[0] = bitmap;
                    Log.e(TAG, "【PicHook】" + className + " Bitmap替换成功");
                    advanceImageIndex();
                }
            });
            Log.e(TAG, "【PicHook】" + className + " Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】" + className + " Hook失败: " + t.getMessage());
        }
    }

    private void hookPictureCallbackClass(Class<?> callbackClass, String className) {
        try {
            try {
                XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                        byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        Log.e(TAG, "【PicHook】" + className + ".onPictureTaken triggered");
                        
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[0] = fakeData;
                            Log.e(TAG, "【PicHook】" + className + " byte[]替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】" + className + " onPictureTaken(byte[],Camera) Hook成功");
                return;
            } catch (Throwable ignored) {}
            
            try {
                XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                        byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        Log.e(TAG, "【PicHook】" + className + ".onPictureTaken(byte[]) triggered");
                        
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[0] = fakeData;
                            Log.e(TAG, "【PicHook】" + className + " byte[]替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】" + className + " onPictureTaken(byte[]) Hook成功");
                return;
            } catch (Throwable ignored) {}
            
            for (java.lang.reflect.Method m : callbackClass.getDeclaredMethods()) {
                if ("onPictureTaken".equals(m.getName()) && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0] == byte[].class) {
                    XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                            m.getParameterTypes(), new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cameraEnabled) return;
                            String path = getCurrentImagePath();
                            if (path == null || path.isEmpty()) return;
                            byte[] fakeData = readImageFileWithExif(path);
                            if (fakeData != null) {
                                param.args[0] = fakeData;
                                Log.e(TAG, "【PicHook】" + className + " onPictureTaken 替换成功");
                                advanceImageIndex();
                            }
                        }
                    });
                    Log.e(TAG, "【PicHook】" + className + " onPictureTaken Hook成功(反射)");
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】Hook " + className + " 失败: " + t.getMessage());
        }
    }

    private void hookWatermarkClass(ClassLoader cl) {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String receiveClassName = prefs.getString("ReceiveClass", "");
            
            if (receiveClassName == null || receiveClassName.isEmpty()) {
                receiveClassName = autoScanReceiveClass(cl);
                if (receiveClassName != null && !receiveClassName.isEmpty()) {
                    prefs.edit().putString("ReceiveClass", receiveClassName).apply();
                    Log.e(TAG, "【PicHook】扫描到水印处理类: " + receiveClassName);
                }
            }
            
            if (receiveClassName != null && !receiveClassName.isEmpty()) {
                Class<?> receiveClass = cl.loadClass(receiveClassName);
                XposedHelpers.findAndHookMethod(receiveClass, "a",
                        Context.class, byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[1] = fakeData;
                            Log.e(TAG, "【PicHook】ReceiveClass.a 替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】ReceiveClass: " + receiveClassName + " Hook成功");
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】ReceiveClass Hook失败: " + t.getMessage());
        }
    }

    private String autoScanReceiveClass(ClassLoader cl) {
        try {
            String[] knownClasses = {
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2$a",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3$a",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2$1",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3$1",
            };
            for (String className : knownClasses) {
                try {
                    Class<?> cls = cl.loadClass(className);
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        if ("a".equals(m.getName()) && m.getParameterTypes().length == 2
                                && m.getParameterTypes()[0] == Context.class
                                && m.getParameterTypes()[1] == byte[].class) {
                            Log.e(TAG, "【PicHook】找到已知水印处理类: " + className);
                            return className;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            String[] baseClasses = {
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3",
            };
            for (String baseClass : baseClasses) {
                for (int idx = 1; idx <= 20; idx++) {
                    try {
                        String fullClassName = baseClass + "$" + idx;
                        Class<?> cls = cl.loadClass(fullClassName);
                        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                            if ("a".equals(m.getName()) && m.getParameterTypes().length == 2
                                    && m.getParameterTypes()[0] == Context.class
                                    && m.getParameterTypes()[1] == byte[].class) {
                                Log.e(TAG, "【PicHook】扫描到水印处理类: " + fullClassName);
                                return fullClassName;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】自动扫描失败", t);
        }
        return null;
    }

    private String getCurrentImagePath() {
        String path = null;
        if (cameraMode == 0) {
            path = singleImagePath;
        } else {
            if (!multiImagePaths.isEmpty() && currentImageIndex < multiImagePaths.size()) {
                path = multiImagePaths.get(currentImageIndex);
            }
        }
        if (path == null || path.isEmpty()) {
            path = "/sdcard/Download/00.jpg";
        }
        return path;
    }

    private void advanceImageIndex() {
        if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
            currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
            savePrefs();
        }
    }

    private byte[] readImageFileWithExif(String path) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) {
                Log.e(TAG, "【PicHook】readImageFileWithExif: decodeFile返回null");
                return null;
            }
            
            int exifRotation = getExifRotation(path);
            if (exifRotation == 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(270);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                Log.e(TAG, "【PicHook】readImageFileWithExif: EXIF正常，强制旋转270");
            }
            
            int pathIndex = multiImagePaths.indexOf(path);
            if (pathIndex >= 0 && pathIndex < imageRotations.size()) {
                int manualRot = imageRotations.get(pathIndex);
                if (manualRot != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(manualRot);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    Log.e(TAG, "【PicHook】readImageFileWithExif: 叠加手动旋转 " + manualRot);
                }
            }
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】readImageFileWithExif 失败", t);
        }
        return null;
    }

    private int getExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】读取EXIF失败", t);
        }
        return 0;
    }

    private void replaceSavedImage(String originalPath) {
        try {
            byte[] fakeData = getFakeImageData();
            if (fakeData != null && originalPath != null) {
                FileOutputStream fos = new FileOutputStream(originalPath);
                fos.write(fakeData);
                fos.close();
                Log.e(TAG, "已替换保存的图片: " + originalPath);

                if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
                    currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
                    uiHandler.post(() -> {
                        updateImageStatus();
                        if (imagePreviewContainer != null && appContext != null) {
                            refreshImagePreviews(appContext);
                        }
                        Toast.makeText(appContext,
                                "已切换到第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "替换保存图片失败", t);
        }
    }

    private byte[] getFakeImageData() {
        try {
            String path = getCurrentImagePath();
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                if (file.exists()) {
                    byte[] data = readImageFileWithExif(path);
                    if (data != null) {
                        Log.e(TAG, "getFakeImageData: 已加载 " + path);
                        return data;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "获取假图片数据失败", t);
        }
        return null;
    }

    // ======================== 配置读写 ========================

    private void loadPrefs() {
        if (appContext == null) return;
        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        locationEnabled = sh.getBoolean("locationEnabled", false);
        cameraEnabled = sh.getBoolean("cameraEnabled", false);
        cameraMode = sh.getInt("cameraMode", 0);
        customLat = sh.getString("lat", "");
        customLng = sh.getString("lng", "");
        singleImagePath = sh.getString("singleImagePath", "");
        singleImageRotation = sh.getInt("singleImageRotation", 0);
        currentImageIndex = sh.getInt("currentImageIndex", 0);
        String multiPathsJson = sh.getString("multiImagePaths", "[]");
        try {
            JSONArray arr = new JSONArray(multiPathsJson);
            multiImagePaths.clear();
            for (int i = 0; i < arr.length(); i++) {
                multiImagePaths.add(arr.getString(i));
            }
        } catch (Exception e) {
            multiImagePaths.clear();
        }
        String rotationsJson = sh.getString("imageRotations", "[]");
        try {
            JSONArray rotArr = new JSONArray(rotationsJson);
            imageRotations.clear();
            for (int i = 0; i < rotArr.length(); i++) {
                imageRotations.add(rotArr.getInt(i));
            }
        } catch (Exception e) {
            imageRotations.clear();
        }
        // 加载模拟地址信息到全局变量
        mockAddress = sh.getString("mockAddress", "");
        mockProvince = sh.getString("mockProvince", "");
        mockCity = sh.getString("mockCity", "");
        mockDistrict = sh.getString("mockDistrict", "");
        Log.e(TAG, "配置已加载: loc=" + locationEnabled + " cam=" + cameraEnabled
                + " addr=" + mockAddress);
        // 如果经纬度已设置但地址为空，自动触发逆地理编码
        if (!customLat.isEmpty() && !customLng.isEmpty() && mockAddress.isEmpty()) {
            Log.e(TAG, "经纬度已设置但地址为空，自动触发逆地理编码");
            reverseGeocodeAsync();
        }
    }

    private void savePrefs() {
        if (appContext == null) return;
        SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean("locationEnabled", locationEnabled);
        editor.putBoolean("cameraEnabled", cameraEnabled);
        editor.putInt("cameraMode", cameraMode);
        editor.putString("lat", customLat);
        editor.putString("lng", customLng);
        editor.putString("singleImagePath", singleImagePath);
        editor.putInt("singleImageRotation", singleImageRotation);
        editor.putInt("currentImageIndex", currentImageIndex);
        JSONArray arr = new JSONArray();
        for (String path : multiImagePaths) {
            arr.put(path);
        }
        editor.putString("multiImagePaths", arr.toString());
        JSONArray rotArr = new JSONArray();
        for (int rot : imageRotations) {
            rotArr.put(rot);
        }
        editor.putString("imageRotations", rotArr.toString());
        editor.apply();
        Log.e(TAG, "配置已保存");
        // 经纬度变化时自动触发逆地理编码
        if (!customLat.isEmpty() && !customLng.isEmpty()) {
            reverseGeocodeAsync();
        }
    }

    // 逆地理编码相关
    private static volatile boolean isReverseGeocoding = false;
    private static volatile long lastReverseGeocodeTime = 0;

    /**
     * 使用高德SDK的GeocodeSearch进行逆地理编码，将虚拟经纬度自动转换为地址
     * 使用SDK自带的key，确保有效性
     */
    private void reverseGeocodeAsync() {
        if (appContext == null || customLat.isEmpty() || customLng.isEmpty()) return;
        // 防重复触发：3秒内不重复请求
        long now = System.currentTimeMillis();
        if (isReverseGeocoding || (now - lastReverseGeocodeTime) < 3000) {
            Log.e(TAG, "逆地理编码跳过（正在进行中或间隔太短）");
            return;
        }
        isReverseGeocoding = true;
        lastReverseGeocodeTime = now;
        final String targetLat = customLat;
        final String targetLng = customLng;

        // 使用UI线程执行GeocodeSearch（SDK要求在主线程回调）
        uiHandler.post(() -> {
            try {
                ClassLoader cl = appContext.getClassLoader();
                // 加载GeocodeSearch相关类
                Class<?> geocodeSearchClass = cl.loadClass("com.amap.api.services.geocoder.GeocodeSearch");
                Class<?> latLonPointClass = cl.loadClass("com.amap.api.services.core.LatLonPoint");
                Class<?> regeocodeQueryClass = cl.loadClass("com.amap.api.services.geocoder.RegeocodeQuery");
                Class<?> onGeocodeSearchListenerClass = cl.loadClass("com.amap.api.services.geocoder.GeocodeSearch$OnGeocodeSearchListener");

                // 创建LatLonPoint
                Object latLonPoint = XposedHelpers.newInstance(latLonPointClass,
                        new Class<?>[]{double.class, double.class},
                        Double.parseDouble(targetLat), Double.parseDouble(targetLng));

                // 创建RegeocodeQuery (LatLonPoint point, float radius, String latLonType)
                final Object regeocodeQuery = XposedHelpers.newInstance(regeocodeQueryClass,
                        new Class<?>[]{latLonPointClass, float.class, String.class},
                        latLonPoint, 1000f, "autonavi");

                // 创建GeocodeSearch
                final Object geocodeSearch = XposedHelpers.newInstance(geocodeSearchClass,
                        new Class<?>[]{Context.class}, appContext);

                // 设置监听器 - 使用动态代理创建OnGeocodeSearchListener
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{onGeocodeSearchListenerClass},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                String methodName = method.getName();
                                if ("onRegeocodeSearched".equals(methodName)) {
                                    // args[0] = RegeocodeResult, args[1] = resultCode
                                    handleRegeocodeResult(args[0], (Integer) args[1]);
                                } else if ("onGeocodeSearched".equals(methodName)) {
                                    // 地理编码（地址转坐标）的回调，忽略
                                }
                                return null;
                            }
                        });

                XposedHelpers.callMethod(geocodeSearch, "setOnGeocodeSearchListener", listener);

                // 发起异步逆地理编码请求
                XposedHelpers.callMethod(geocodeSearch, "getFromLocationAsyn", regeocodeQuery);

                Log.e(TAG, "已发起SDK逆地理编码请求: " + targetLat + "," + targetLng);

            } catch (Throwable e) {
                Log.e(TAG, "SDK逆地理编码启动失败，尝试Web API方式", e);
                // SDK方式失败，降级使用Web API
                reverseGeocodeByWebApi(targetLat, targetLng);
            }
        });
    }

    /**
     * 处理逆地理编码结果
     */
    private void handleRegeocodeResult(Object regeocodeResult, int resultCode) {
        try {
            // resultCode = 1000 表示成功
            if (resultCode == 1000 && regeocodeResult != null) {
                // 获取RegeocodeAddress
                Object regeocodeAddress = XposedHelpers.callMethod(regeocodeResult, "getRegeocodeAddress");
                if (regeocodeAddress != null) {
                    // 获取格式化地址
                    String formattedAddress = (String) XposedHelpers.callMethod(regeocodeAddress, "getFormatAddress");
                    if (formattedAddress == null) formattedAddress = "";

                    // 获取地址组件
                    String province = "";
                    String city = "";
                    String district = "";
                    String street = "";
                    String adcode = "";
                    String citycode = "";

                    try {
                        Object streetNumber = XposedHelpers.callMethod(regeocodeAddress, "getStreetNumber");
                        if (streetNumber != null) {
                            street = (String) XposedHelpers.callMethod(streetNumber, "getStreet");
                            if (street == null) street = "";
                        }
                    } catch (Throwable ignored) {}

                    try {
                        province = (String) XposedHelpers.callMethod(regeocodeAddress, "getProvince");
                        if (province == null) province = "";
                    } catch (Throwable ignored) {}

                    try {
                        city = (String) XposedHelpers.callMethod(regeocodeAddress, "getCity");
                        if (city == null) city = "";
                    } catch (Throwable ignored) {}

                    try {
                        district = (String) XposedHelpers.callMethod(regeocodeAddress, "getDistrict");
                        if (district == null) district = "";
                    } catch (Throwable ignored) {}

                    try {
                        adcode = (String) XposedHelpers.callMethod(regeocodeAddress, "getAdCode");
                        if (adcode == null) adcode = "";
                    } catch (Throwable ignored) {}

                    try {
                        citycode = (String) XposedHelpers.callMethod(regeocodeAddress, "getCityCode");
                        if (citycode == null) citycode = "";
                    } catch (Throwable ignored) {}

                    // 保存到SharedPreferences
                    SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                    ed.putString("mockAddress", formattedAddress);
                    ed.putString("mockProvince", province);
                    ed.putString("mockCity", city);
                    ed.putString("mockDistrict", district);
                    ed.putString("mockStreet", street);
                    ed.putString("mockAdCode", adcode);
                    ed.putString("mockCityCode", citycode);
                    ed.apply();

                    // 更新全局变量
                    mockAddress = formattedAddress;
                    mockProvince = province;
                    mockCity = city;
                    mockDistrict = district;

                    isReverseGeocoding = false;

                    Log.e(TAG, "SDK逆地理编码成功: " + formattedAddress + " [" + province + city + district + "]");

                    final String finalAddr = formattedAddress;
                    uiHandler.post(() -> {
                        if (appContext != null) {
                            Toast.makeText(appContext, "已自动获取地址: " + finalAddr, Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
            }

            Log.e(TAG, "SDK逆地理编码失败: resultCode=" + resultCode);
            // SDK方式失败，尝试Web API
            reverseGeocodeByWebApi(customLat, customLng);

        } catch (Throwable e) {
            Log.e(TAG, "处理逆地理编码结果异常", e);
            // 异常时尝试Web API
            reverseGeocodeByWebApi(customLat, customLng);
        }
    }

    /**
     * 使用高德地图Web API进行逆地理编码（备用方案）
     */
    private void reverseGeocodeByWebApi(final String targetLat, final String targetLng) {
        if (appContext == null || targetLat.isEmpty() || targetLng.isEmpty()) {
            isReverseGeocoding = false;
            return;
        }
        new Thread(() -> {
            try {
                // 使用蓝月亮APK中提取的高德key（注意：Android SDK key可能不适用于Web API）
                String apiKey = "ed6016242c8f18984ac27a01ec82d241";
                String urlStr = "https://restapi.amap.com/v3/geocode/regeo?key=" + apiKey
                        + "&location=" + targetLng + "," + targetLat
                        + "&extensions=all";
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    String jsonStr = sb.toString();
                    Log.e(TAG, "Web API逆地理编码响应: " + jsonStr);
                    JSONObject json = new JSONObject(jsonStr);
                    if ("1".equals(json.optString("status"))) {
                        JSONObject regeocode = json.optJSONObject("regeocode");
                        if (regeocode != null) {
                            String formattedAddress = regeocode.optString("formatted_address", "");
                            JSONObject addrComp = regeocode.optJSONObject("addressComponent");
                            String province = "";
                            String city = "";
                            String district = "";
                            String street = "";
                            String adcode = "";
                            String citycode = "";
                            if (addrComp != null) {
                                province = addrComp.optString("province", "");
                                city = addrComp.optString("city", "");
                                if (city.isEmpty()) city = addrComp.optString("citycode", "");
                                district = addrComp.optString("district", "");
                                street = addrComp.optString("street", "");
                                adcode = addrComp.optString("adcode", "");
                                citycode = addrComp.optString("citycode", "");
                            }
                            // 保存到SharedPreferences
                            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            ed.putString("mockAddress", formattedAddress);
                            ed.putString("mockProvince", province);
                            ed.putString("mockCity", city);
                            ed.putString("mockDistrict", district);
                            ed.putString("mockStreet", street);
                            ed.putString("mockAdCode", adcode);
                            ed.putString("mockCityCode", citycode);
                            ed.apply();
                            // 更新全局变量
                            mockAddress = formattedAddress;
                            mockProvince = province;
                            mockCity = city;
                            mockDistrict = district;
                            Log.e(TAG, "Web API逆地理编码成功: " + formattedAddress + " [" + province + city + district + "]");
                            uiHandler.post(() -> {
                                if (appContext != null) {
                                    Toast.makeText(appContext, "已自动获取地址: " + formattedAddress, Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    } else {
                        String info = json.optString("info", "未知错误");
                        Log.e(TAG, "Web API逆地理编码错误: " + info);
                    }
                } else {
                    Log.e(TAG, "Web API逆地理编码HTTP错误: " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "Web API逆地理编码异常", e);
            } finally {
                isReverseGeocoding = false;
            }
        }).start();
    }

    // ---- UI辅助 ----

    private TextView createLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 12;
        params.bottomMargin = 4;
        tv.setLayoutParams(params);
        return tv;
    }

    private EditText createEditText(Context ctx, String text, String hint) {
        EditText et = new EditText(ctx);
        et.setText(text);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        et.setPadding(16, 12, 16, 12);
        et.setBackgroundColor(0xFFF0F0F0);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(params);
        return et;
    }

    private void addDivider(LinearLayout layout) {
        View divider = new View(layout.getContext());
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = 12;
        params.bottomMargin = 12;
        divider.setLayoutParams(params);
        layout.addView(divider);
    }

    private void showFallbackDialog(Context ctx) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("Hook设置")
                    .setMessage("悬浮窗权限未授予，请授予后重试。\n\n" +
                            "当前状态:\n" +
                            "定位: " + (locationEnabled ? "开启" : "关闭") + "\n" +
                            "相机: " + (cameraEnabled ? "开启" : "关闭"))
                    .setCancelable(true)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "显示回退Dialog失败", t);
        }
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Callback callback;
        interface Callback {
            void onTextChanged(String text);
        }
        SimpleTextWatcher(Callback callback) {
            this.callback = callback;
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            callback.onTextChanged(s.toString());
        }
        @Override
        public void afterTextChanged(android.text.Editable s) {}
    }
}
