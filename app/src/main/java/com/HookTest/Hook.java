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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
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

    // ======================== Hook 统一执行入口 ========================

    private void executeHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (hooksExecuted) {
            Log.e(TAG, "Hooks 已执行过，跳过重复执行");
            return;
        }
        hooksExecuted = true;
        Log.e(TAG, "开始执行所有 Hooks");
        loadPrefs();
        hookAMapLocation(lpparam);
        hookSystemLocation(lpparam);
        hookTencentLocation(lpparam);
        hookCellLocation(lpparam);
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

                // 尝试获取DecorView的根视图
                android.view.ViewRootImpl root = (android.view.ViewRootImpl) contentParent.getParent();
                if (root == null) {
                    // 尝试通过DecorView检查
                    Log.e(TAG, "ViewRootImpl为空，重试(" + retryCount + ")");
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

    // ======================== Hook 定位 ========================

    private void hookAMapLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> aMapLocationClass = cl.loadClass("com.amap.api.location.AMapLocation");
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
            Log.e(TAG, "高德定位Hook成功");
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
                Log.e(TAG, "腾讯定位 TencentLocation Hook成功");
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
                        Object tencentLoc = param.getResult();
                        if (tencentLoc == null) return;
                        String latStr = sh.getString("lat", "");
                        String lngStr = sh.getString("lng", "");
                        if (latStr.isEmpty() || lngStr.isEmpty()) return;
                        // Hook TencentLocation对象的方法已经在上面处理，这里不需要额外操作
                        // 但如果APP直接使用TencentLocationManager获取对象后调用其他方法，确保返回对象不为null
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
        Log.e(TAG, "配置已加载: loc=" + locationEnabled + " cam=" + cameraEnabled);
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
