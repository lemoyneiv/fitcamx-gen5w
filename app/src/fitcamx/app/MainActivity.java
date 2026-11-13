package fitcamx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "FitcamxMain";
    private static final int MAX_PHOTO_PREVIEW_BYTES = 24 * 1024 * 1024;

    // Extended OEM Backup Camera View Modes (additive to CameraClient.VIEW_FRONT=0, VIEW_REAR=1, VIEW_DUAL=2)
    public static final int VIEW_OEM_DUAL = 3; // Side-by-Side: Front Fitcamx + Car Backup Camera
    public static final int VIEW_OEM_REAR = 4; // Fullscreen Car Backup Camera

    private static final String KEY_FORCE_EXTRA_OEM_VIEWS = "force_extra_oem_views";
    private static final String KEY_KNOWN_FITCAMX_REAR_PREFIX = "known_fitcamx_rear_";
    private static final String ACTION_OEM_CAMERA_APP_START = "com.hkmc.intent.action.CAMERA_APP_START";

    // Wi-Fi and Camera subsystems
    private WifiBridge wifiBridge;
    private CameraClient cameraClient;
    private UsbDownloader usbDownloader;
    private OemRearCameraClient oemRearCameraClient;

    // Active Material 3 Theme Palette
    private ThemeManager.Palette activePalette = ThemeManager.getDefaultPalette();

    // UI Elements - Root & Left Navigation Rail
    private View layoutRoot;
    private View layoutSidebar;
    private View dividerSidebar;
    private View layoutLogoHeader;
    private TextView tvTitle;
    private TextView tvAppVersion;
    private TextView tvAuthorCredit;
    private View layoutWifiCard;
    private TextView tvWifiStatus;
    private Button btnRescan;
    private TextView tvNavSectionLabel;
    private Button btnTabLive;
    private Button btnTabGallery;
    private Button btnTheme;
    private Button btnExportLog;
    private Button btnExitApp;

    // Diagnostic bar
    private View layoutDiagBar;
    private TextView tvDiagInfo;
    private TextView tvDiagTapHint;

    // VLC High-Performance Player Engine
    private VlcPlayerManager vlcPlayerManager;

    // Live Mode Elements
    private View layoutLiveMode;
    private View frameLiveContainer;
    private View frameLiveFrontPane;
    private View dividerOemSplit;
    private View frameLiveOemPane;
    private OemRearView viewOemRear;
    private TextView tvOemPaneBadge;
    private View dividerLiveControls;
    private View layoutLiveControls;
    private TextView tvCamControlsLabel;
    private SurfaceView surfaceLive;
    private Surface liveSurface = null;
    private boolean isStartingLive = false;
    private boolean isConnectingLiveVlc = false;
    private int liveRequestGeneration = 0;
    private ProgressBar pbLiveLoading;
    private TextView tvStreamBadge;
    private TextView tvRecBadge;
    private Button btnToggleCam;
    private Button btnMirrorLive;
    private Button btnSnapshot;
    private Button btnEmergencyLock;
    private Button btnToggleMic;
    private Button btnToggleRecord;
    private Button btnCameraRes;
    private Button btnSyncTime;

    // Gallery Mode Elements
    private View layoutGalleryMode;
    private View dividerGalleryColumns;
    private View frameGalleryContainer;
    private View layoutGalleryTopOverlay;
    private View layoutGallerySeekOverlay;
    private View layoutSeekBarRow;
    private TextView tvSeekPosition;
    private ListView lvClips;
    private TextView tvClipCount;
    private TextView tvEmptyGallery;
    private Button btnRefreshGallery;
    private Button btnFilterAll;
    private Button btnFilterFront;
    private Button btnFilterRear;
    private Button btnFilterLocked;
    private Button btnFilterPhotos;
    private TextView tvSelectedClipName;
    private SurfaceView surfaceGallery;
    private Surface gallerySurface = null;
    private ImageView ivGalleryPhoto;
    private Bitmap currentPhotoBitmap = null;
    private volatile int galleryLoadGeneration = 0;
    private ProgressBar pbGalleryLoading;
    private TextView tvClipDetails;
    private TextView tvUsbStatus;
    private ProgressBar pbDownloadProgress;
    private TextView tvDownloadStats;
    private SeekBar sbGallerySeek;
    private Button btnPlaybackRate;
    private Button btnPlayClip;
    private Button btnDownloadToUsb;
    private View layoutGalleryListColumn;
    private Button btnToggleGalleryList;

    private static final long SEEK_OVERLAY_AUTO_HIDE_MS = 3500L;
    private static final long LIVE_KEEPALIVE_INTERVAL_MS = 15000L;
    private static final long LIVE_HEALTHY_RESET_MS = 8000L;
    private static final long LIVE_SILENT_REFRESH_SPINNER_DELAY_MS = 900L;
    private static final String PREFS_UI_SETTINGS = "fitcamx_ui_settings";

    // State
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private boolean isExiting = false;
    private boolean isActivityVisible = true;
    private boolean suspendedInBackground = false;
    private boolean isUserSeeking = false;
    private boolean galleryEnded = false;
    private boolean galleryCacheDirty = true;
    private boolean galleryHasVout = false;
    private long lastGalleryBufferingMs = 0L;
    private long currentGalleryDurationMs = 0L;
    private boolean currentGalleryClipIsHevc = false;
    private float lastKnownGalleryPosition = 0.0f;

    private boolean is1080pMode = false; // Default to 4K UHD (native front camera resolution)
    private int camera4kIndex = 6; // Default to 6 (Fitcamx NT96670 4K UHD mode index)
    private int currentViewMode = CameraClient.VIEW_FRONT;
    private int targetViewMode = CameraClient.VIEW_FRONT;
    private int activeStreamMode = CameraClient.VIEW_FRONT;
    private final boolean[] mirrorByViewMode = new boolean[5];
    private String activeLiveStreamUrl = null;
    private int liveAutoReconnectCount = 0;
    private Runnable pendingLiveRecoveryRunnable = null;
    private Runnable pendingSilentSpinnerRunnable = null;
    private boolean silentRefreshUiSuppressed = false;

    private boolean manualWifiSetupPending = false;
    private String lastFailedWifiSsid = null;
    private String lastWifiFailureReason = null;
    private AlertDialog activeWifiDialog = null;

    private boolean isRecording = true;
    private boolean isMicOn = true;
    private boolean isConnected = false;
    private boolean hasRearCamera = true;       // Physical rear Fitcamx camera channel
    private boolean hasOemRearCamera = false;   // Supported Gen5W analog OEM backup camera (type=0)
    private boolean forceExtraOemViews = false; // Hidden Advanced Options override for dual Fitcamx
    private boolean hasAnyRearClips = false;    // Rear Fitcamx clips OR USB Dashcam clips
    private boolean isFormattingSdCard = false;
    private boolean stockCamReceiverRegistered = false;
    private String currentSsid = "";
    private String currentDiagDetails = "";
    private final List<ClipItem> allClips = new ArrayList<ClipItem>();
    private final List<ClipItem> displayedClips = new ArrayList<ClipItem>();
    private int currentFilter = 0; // 0=All, 1=Front, 2=Rear, 3=Locked, 4=Photos
    private ClipAdapter clipAdapter;
    private ClipItem selectedClip = null;

    private final List<ScanResult> cachedScanResults = new ArrayList<ScanResult>();
    private final LinkedList<String> diagHistory = new LinkedList<String>();

    private final BroadcastReceiver stockCameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_OEM_CAMERA_APP_START.equals(intent.getAction())) {
                if (oemRearCameraClient != null && oemRearCameraClient.isStreamActive()) {
                    DebugLogger.log("OEM_CAM", "Received CAMERA_APP_START broadcast; releasing OEM camera handle");
                    oemRearCameraClient.notifyStockCameraStarting();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            DebugLogger.init(this);
            DebugLogger.log("MAIN", "MainActivity created (Gen5W FITCAMX v" + DebugLogger.getAppVersion() + ")");
            setContentView(R.layout.activity_main);
            initViews();
            setupListeners();

            cameraClient = new CameraClient();
            usbDownloader = new UsbDownloader();
            wifiBridge = new WifiBridge(this);
            vlcPlayerManager = new VlcPlayerManager(this);
            oemRearCameraClient = new OemRearCameraClient();

            try {
                registerReceiver(stockCameraReceiver, new IntentFilter(ACTION_OEM_CAMERA_APP_START));
                stockCamReceiverRegistered = true;
            } catch (Throwable ignored) {}

            // Silently probe Gen5W OEM analog backup camera support (hidden on SVM / unsupported trims)
            oemRearCameraClient.probeSupportAsync(new OemRearCameraClient.ProbeCallback() {
                @Override
                public void onProbeResult(boolean supported, int cameraType, String detail) {
                    hasOemRearCamera = supported;
                    if (supported) {
                        addDiagLog("Car backup camera supported (Gen5W analog)");
                    }
                    applyCameraConfigurationUI();
                }
            });

            wifiBridge.setListener(new WifiBridge.Listener() {
                @Override
                public void onStatusChanged(String message) {
                    addDiagLog(message);
                    if (tvWifiStatus != null && !isConnected) {
                        tvWifiStatus.setText(message);
                    }
                    if (tvDiagInfo != null) {
                        tvDiagInfo.setText(message);
                    }
                }

                @Override
                public void onConnected(final String cameraIp, final String ssid, CameraClient.SocType socType, int httpPort, String diagDetails) {
                    isConnected = true;
                    manualWifiSetupPending = false;
                    lastFailedWifiSsid = null;
                    lastWifiFailureReason = null;
                    dismissActiveWifiDialog();
                    styleRescanButton();
                    galleryCacheDirty = true;
                    currentSsid = ssid;
                    currentDiagDetails = diagDetails;
                    loadKnownFitcamxRearForSsid(ssid);
                    applyCameraConfigurationUI();
                    cameraClient.setCameraIp(cameraIp);
                    cameraClient.setSocType(socType, httpPort);
                    cameraClient.resetSessionState();

                    String msg = "Connected: " + ssid + " [" + cameraIp + "]";
                    addDiagLog(msg + " - " + socType.displayName);

                    if (tvWifiStatus != null) {
                        tvWifiStatus.setText(msg);
                        tvWifiStatus.setTextColor(activePalette.statusPositive);
                    }
                    if (tvDiagInfo != null) {
                        tvDiagInfo.setText("Camera: " + socType.displayName + " (" + cameraIp + ") | " + diagDetails);
                    }

                    if (layoutGalleryMode != null && layoutGalleryMode.getVisibility() == View.VISIBLE) {
                        loadGalleryClips(true);
                        cameraClient.syncCarTime(null);
                    } else {
                        // Start Live stream immediately first on the CGI queue so video appears ASAP
                        startLiveStream();
                        // Queue settings sync (which also infers rear channel from cmd=3014 before any probe) & clock sync
                        syncCameraSettings(ssid);
                        cameraClient.syncCarTime(null);
                    }
                }

                @Override
                public void onDisconnected() {
                    isConnected = false;
                    isStartingLive = false;
                    isConnectingLiveVlc = false;
                    activeLiveStreamUrl = null;
                    cancelLiveMaintenanceTimers();
                    liveRequestGeneration++;
                    addDiagLog("Wi-Fi disconnected");
                    if (tvWifiStatus != null) {
                        tvWifiStatus.setText("Disconnected");
                        tvWifiStatus.setTextColor(activePalette.statusWarning);
                    }
                    if (tvDiagInfo != null) {
                        tvDiagInfo.setText("Wi-Fi disconnected");
                    }
                }

                @Override
                public void onScanResultsUpdated(List<ScanResult> results) {
                    cachedScanResults.clear();
                    if (results != null) {
                        cachedScanResults.addAll(results);
                    }
                }

                @Override
                public void onManualSetupNeeded(String failedSsid, String reason, List<ScanResult> availableNetworks) {
                    if (availableNetworks != null && !availableNetworks.isEmpty()) {
                        cachedScanResults.clear();
                        cachedScanResults.addAll(availableNetworks);
                    }
                    manualWifiSetupPending = true;
                    lastFailedWifiSsid = failedSsid;
                    lastWifiFailureReason = reason;
                    styleRescanButton();
                    addDiagLog("Manual Wi-Fi setup offered: " + (reason != null ? reason : "credentials needed"));

                    if (isActivityVisible && !isFinishing() && !isExiting && !isConnected
                            && (activeWifiDialog == null || !activeWifiDialog.isShowing())) {
                        if (failedSsid != null && !failedSsid.isEmpty()) {
                            showWifiPasswordDialog(failedSsid, getCapabilitiesForSsid(failedSsid), reason, true);
                        } else {
                            showAvailableNetworksDialog(reason);
                        }
                    }
                }
            });

            // Start connecting to CAR_CAM_
            wifiBridge.connectToDashcam();
            checkUsbStatus();
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in onCreate", t);
            Toast.makeText(this, "FITCAMX Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addDiagLog(String log) {
        DebugLogger.log("DIAG", log);
        diagHistory.addFirst(String.format("[%tT] %s", System.currentTimeMillis(), log));
        if (diagHistory.size() > 50) {
            diagHistory.removeLast();
        }
    }

    private void syncCameraSettings() {
        syncCameraSettings(currentSsid);
    }

    private void syncCameraSettings(final String ssid) {
        cameraClient.queryCameraStatus(new CameraClient.Callback<String>() {
            @Override
            public void onSuccess(final String xml) {
                if (xml == null || xml.isEmpty()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 1. Check Movie Resolution (cmd=2002: 0=4K UHD, 1=1080P FHD)
                            Matcher m2002 = Pattern.compile("(?i)<Cmd>2002</Cmd>\\s*<Status>(\\d+)</Status>").matcher(xml);
                            if (m2002.find()) {
                                int resStatus = Integer.parseInt(m2002.group(1));
                                is1080pMode = (resStatus == 1);
                                if (!is1080pMode && resStatus > 0) {
                                    camera4kIndex = resStatus;
                                }
                                if (btnCameraRes != null) {
                                    btnCameraRes.setText("Rec: " + (is1080pMode ? "1080P" : "4K UHD"));
                                }
                                DebugLogger.log("MAIN", "Synced camera resolution from firmware: " + (is1080pMode ? "1080P" : "4K UHD") + " (index=" + resStatus + ")");
                            }

                            // 2. Check Microphone Audio Recording (cmd=2007: 1=on, 0=muted)
                            Matcher m2007 = Pattern.compile("(?i)<Cmd>2007</Cmd>\\s*<Status>(\\d+)</Status>").matcher(xml);
                            if (m2007.find()) {
                                int micStatus = Integer.parseInt(m2007.group(1));
                                isMicOn = (micStatus == 1);
                                styleMicButton();
                                DebugLogger.log("MAIN", "Synced camera mic status from firmware: " + (isMicOn ? "ON" : "MUTED"));
                            }

                            // 3. Check Recording status (cmd=2001: 1=recording, 0=stopped)
                            Matcher m2001 = Pattern.compile("(?i)<Cmd>2001</Cmd>\\s*<Status>(\\d+)</Status>").matcher(xml);
                            if (m2001.find()) {
                                int recStatus = Integer.parseInt(m2001.group(1));
                                isRecording = (recStatus == 1);
                                if (tvRecBadge != null) tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                                styleRecordButton();
                                DebugLogger.log("MAIN", "Synced camera rec status from firmware: " + (isRecording ? "RECORDING" : "STOPPED"));
                            }

                            // 4. Infer rear Fitcamx channel from cmd=3014 status XML only when not yet known
                            // for this SSID (so SD-card ground truth from Gallery is not overwritten on dual-capable
                            // units whose rear camera cable is unplugged, unless firmware omits 3028 altogether).
                            Boolean inferredRear = CameraClient.inferRearChannelFromStatusXml(xml);
                            if (Boolean.FALSE.equals(inferredRear)
                                    || (inferredRear != null && !hasKnownFitcamxRearForSsid(ssid))) {
                                saveKnownFitcamxRearForSsid(ssid, inferredRear.booleanValue());
                                if (hasRearCamera != inferredRear.booleanValue()) {
                                    hasRearCamera = inferredRear.booleanValue();
                                    applyCameraConfigurationUI();
                                }
                            } else if (!hasKnownFitcamxRearForSsid(ssid)) {
                                cameraClient.probeRearChannelNonDisruptive(new CameraClient.Callback<Boolean>() {
                                    @Override
                                    public void onSuccess(Boolean detectedRear) {
                                        if (detectedRear != null) {
                                            saveKnownFitcamxRearForSsid(ssid, detectedRear.booleanValue());
                                            if (hasRearCamera != detectedRear.booleanValue()) {
                                                hasRearCamera = detectedRear.booleanValue();
                                                applyCameraConfigurationUI();
                                            }
                                        }
                                    }
                                    @Override
                                    public void onError(Exception error) {}
                                });
                            }
                        } catch (Exception e) {
                            DebugLogger.log("MAIN", "Error parsing camera status XML: " + e.getMessage());
                        }
                    }
                });
            }
            @Override
            public void onError(Exception error) {
                DebugLogger.log("MAIN", "queryCameraStatus notice: " + error.getMessage());
            }
        });
    }

    private void initViews() {
        activePalette = ThemeManager.loadSavedPalette(this);
        loadSavedMirrorSettings();
        loadSavedAdvancedSettings();

        layoutRoot = findViewById(R.id.layout_root);
        layoutSidebar = findViewById(R.id.layout_sidebar);
        dividerSidebar = findViewById(R.id.divider_sidebar);
        layoutLogoHeader = findViewById(R.id.layout_logo_header);
        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvAppVersion = (TextView) findViewById(R.id.tv_app_version);
        tvAuthorCredit = (TextView) findViewById(R.id.tv_author_credit);
        if (tvAppVersion != null) {
            try {
                String vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvAppVersion.setText("v" + vName);
            } catch (Exception e) {
                tvAppVersion.setText("v" + DebugLogger.getAppVersion());
            }
        }

        layoutWifiCard = findViewById(R.id.layout_wifi_card);
        tvWifiStatus = (TextView) findViewById(R.id.tv_wifi_status);
        btnRescan = (Button) findViewById(R.id.btn_rescan);
        tvNavSectionLabel = (TextView) findViewById(R.id.tv_nav_section_label);
        btnTabLive = (Button) findViewById(R.id.btn_tab_live);
        btnTabGallery = (Button) findViewById(R.id.btn_tab_gallery);
        btnTheme = (Button) findViewById(R.id.btn_theme);
        btnExportLog = (Button) findViewById(R.id.btn_export_log);
        btnExitApp = (Button) findViewById(R.id.btn_exit_app);

        // Diagnostic Bar
        layoutDiagBar = findViewById(R.id.layout_diag_bar);
        tvDiagInfo = (TextView) findViewById(R.id.tv_diag_info);
        tvDiagTapHint = (TextView) findViewById(R.id.tv_diag_tap_hint);

        // Live Views
        layoutLiveMode = findViewById(R.id.layout_live_mode);
        frameLiveContainer = findViewById(R.id.frame_live_container);
        frameLiveFrontPane = findViewById(R.id.frame_live_front_pane);
        dividerOemSplit = findViewById(R.id.divider_oem_split);
        frameLiveOemPane = findViewById(R.id.frame_live_oem_pane);
        viewOemRear = (OemRearView) findViewById(R.id.view_oem_rear);
        tvOemPaneBadge = (TextView) findViewById(R.id.tv_oem_pane_badge);
        dividerLiveControls = findViewById(R.id.divider_live_controls);
        layoutLiveControls = findViewById(R.id.layout_live_controls);
        tvCamControlsLabel = (TextView) findViewById(R.id.tv_cam_controls_label);
        surfaceLive = (SurfaceView) findViewById(R.id.surface_live);
        if (surfaceLive != null) {
            surfaceLive.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    liveSurface = holder.getSurface();
                    DebugLogger.log("MAIN", "Live Surface created: " + liveSurface);
                    if (layoutLiveMode.getVisibility() == View.VISIBLE && isConnected && isActivityVisible
                            && !isExiting && targetViewMode != VIEW_OEM_REAR) {
                        startLiveStream();
                    }
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    liveSurface = null;
                    activeLiveStreamUrl = null;
                    if (targetViewMode != VIEW_OEM_REAR && activeStreamMode != VIEW_OEM_REAR) {
                        isStartingLive = false;
                        isConnectingLiveVlc = false;
                        cancelLiveMaintenanceTimers();
                        liveRequestGeneration++;
                    }
                    DebugLogger.log("MAIN", "Live Surface destroyed");
                }
            });
        }
        pbLiveLoading = (ProgressBar) findViewById(R.id.pb_live_loading);
        tvStreamBadge = (TextView) findViewById(R.id.tv_stream_badge);
        tvRecBadge = (TextView) findViewById(R.id.tv_rec_badge);
        btnToggleCam = (Button) findViewById(R.id.btn_toggle_cam);
        btnMirrorLive = (Button) findViewById(R.id.btn_mirror_live);
        btnSnapshot = (Button) findViewById(R.id.btn_snapshot);
        btnEmergencyLock = (Button) findViewById(R.id.btn_emergency_lock);
        btnToggleMic = (Button) findViewById(R.id.btn_toggle_mic);
        btnToggleRecord = (Button) findViewById(R.id.btn_toggle_record);
        btnCameraRes = (Button) findViewById(R.id.btn_camera_res);
        if (btnCameraRes != null) btnCameraRes.setText("Rec: 4K UHD");
        btnSyncTime = (Button) findViewById(R.id.btn_sync_time);

        // Gallery Views
        layoutGalleryMode = findViewById(R.id.layout_gallery_mode);
        dividerGalleryColumns = findViewById(R.id.divider_gallery_columns);
        frameGalleryContainer = findViewById(R.id.frame_gallery_container);
        layoutGalleryTopOverlay = findViewById(R.id.layout_gallery_top_overlay);
        layoutGallerySeekOverlay = findViewById(R.id.layout_gallery_seek_overlay);
        layoutSeekBarRow = findViewById(R.id.layout_seek_bar_row);
        tvSeekPosition = (TextView) findViewById(R.id.tv_seek_position);
        surfaceGallery = (SurfaceView) findViewById(R.id.surface_gallery);
        if (surfaceGallery != null) {
            surfaceGallery.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    gallerySurface = holder.getSurface();
                    DebugLogger.log("MAIN", "Gallery Surface created: " + gallerySurface);
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    gallerySurface = null;
                    DebugLogger.log("MAIN", "Gallery Surface destroyed");
                }
            });
        }
        ivGalleryPhoto = (ImageView) findViewById(R.id.iv_gallery_photo);
        lvClips = (ListView) findViewById(R.id.lv_clips);
        tvClipCount = (TextView) findViewById(R.id.tv_clip_count);
        tvEmptyGallery = (TextView) findViewById(R.id.tv_empty_gallery);
        btnRefreshGallery = (Button) findViewById(R.id.btn_refresh_gallery);
        btnFilterAll = (Button) findViewById(R.id.btn_filter_all);
        btnFilterFront = (Button) findViewById(R.id.btn_filter_front);
        btnFilterRear = (Button) findViewById(R.id.btn_filter_rear);
        btnFilterLocked = (Button) findViewById(R.id.btn_filter_locked);
        btnFilterPhotos = (Button) findViewById(R.id.btn_filter_photos);
        tvSelectedClipName = (TextView) findViewById(R.id.tv_selected_clip_name);
        pbGalleryLoading = (ProgressBar) findViewById(R.id.pb_gallery_loading);
        tvClipDetails = (TextView) findViewById(R.id.tv_clip_details);
        tvUsbStatus = (TextView) findViewById(R.id.tv_usb_status);
        pbDownloadProgress = (ProgressBar) findViewById(R.id.pb_download_progress);
        tvDownloadStats = (TextView) findViewById(R.id.tv_download_stats);
        sbGallerySeek = (SeekBar) findViewById(R.id.sb_gallery_seek);
        btnPlaybackRate = (Button) findViewById(R.id.btn_playback_rate);
        btnPlayClip = (Button) findViewById(R.id.btn_play_clip);
        btnDownloadToUsb = (Button) findViewById(R.id.btn_download_to_usb);
        layoutGalleryListColumn = findViewById(R.id.layout_gallery_list_column);
        btnToggleGalleryList = (Button) findViewById(R.id.btn_toggle_gallery_list);

        clipAdapter = new ClipAdapter();
        lvClips.setAdapter(clipAdapter);

        applyTheme();
    }

    private void setupListeners() {
        // Tap FITCAMX logo header at top-left to save a screenshot strictly to attached USB drive;
        // Long-press logo header to open hidden Advanced Options menu.
        View.OnClickListener screenshotClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ScreenshotHelper.captureToUsb(MainActivity.this, tvTitle, new ScreenshotHelper.CompletionListener() {
                    @Override
                    public void onComplete(File savedFile, String errorMessage) {
                        if (savedFile != null) {
                            addDiagLog("Screenshot saved to USB: " + savedFile.getName());
                            if (tvDiagInfo != null) {
                                tvDiagInfo.setText("Screenshot saved: " + savedFile.getName());
                            }
                        } else if (errorMessage != null) {
                            addDiagLog("Screenshot notice: " + errorMessage);
                        }
                    }
                });
            }
        };
        View.OnLongClickListener advancedOptionsLongClickListener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAdvancedOptionsDialog();
                return true;
            }
        };
        if (layoutLogoHeader != null) {
            layoutLogoHeader.setOnClickListener(screenshotClickListener);
            layoutLogoHeader.setOnLongClickListener(advancedOptionsLongClickListener);
        } else if (tvTitle != null) {
            tvTitle.setOnClickListener(screenshotClickListener);
            tvTitle.setOnLongClickListener(advancedOptionsLongClickListener);
        }

        // Manual Rescan / Wi-Fi Setup Fallback (Long-press anytime to pick SSID / Password)
        if (btnRescan != null) {
            btnRescan.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (manualWifiSetupPending && !isConnected) {
                        openManualWifiSetup();
                    } else {
                        Toast.makeText(MainActivity.this, "Scanning for Wi-Fi / Probing Camera...", Toast.LENGTH_SHORT).show();
                        wifiBridge.triggerManualRescan();
                    }
                }
            });
            btnRescan.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showAvailableNetworksDialog("Select your dashcam's Wi-Fi network to enter or update its password.");
                    return true;
                }
            });
        }

        // Tap Wi-Fi status or diagnostic bar to view comprehensive diagnostic details
        View.OnClickListener diagClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnosticDialog();
            }
        };
        tvWifiStatus.setOnClickListener(diagClickListener);
        if (layoutWifiCard != null) {
            layoutWifiCard.setOnClickListener(diagClickListener);
        }
        if (layoutDiagBar != null) {
            layoutDiagBar.setOnClickListener(diagClickListener);
        }

        // Tab switching
        btnTabLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(true);
            }
        });

        btnTabGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(false);
            }
        });

        // Theme Picker / Quick Cycler
        if (btnTheme != null) {
            btnTheme.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showThemeSelectionDialog();
                }
            });
            btnTheme.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    cycleTheme();
                    return true;
                }
            });
        }

        // Export Log to USB
        if (btnExportLog != null) {
            btnExportLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    triggerUsbLogExport(true);
                }
            });
        }

        // Exit with dialog options (reboot head unit or standard exit)
        btnExitApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExitDialog();
            }
        });

        // Cycle Camera Stream View: Front -> Dual (PIP) -> Rear
        btnToggleCam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleViewMode();
            }
        });
        btnToggleCam.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showViewModeSelectionDialog();
                return true;
            }
        });

        // Horizontal Mirror Toggle (persisted independently per camera view mode)
        if (btnMirrorLive != null) {
            btnMirrorLive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLiveMirror();
                }
            });
        }

        // Take Snapshot
        btnSnapshot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnSnapshot.setEnabled(false);
                cameraClient.takeSnapshot(new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        btnSnapshot.setEnabled(true);
                        galleryCacheDirty = true;
                        addDiagLog("Snapshot photo captured to SD card");
                        Toast.makeText(MainActivity.this, "Snapshot captured to SD card", Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onError(Exception error) {
                        btnSnapshot.setEnabled(true);
                        addDiagLog("Snapshot failed: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "Snapshot failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Emergency Lock Clip
        btnEmergencyLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnEmergencyLock.setEnabled(false);
                cameraClient.emergencyLock(new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        btnEmergencyLock.setEnabled(true);
                        galleryCacheDirty = true;
                        addDiagLog("Emergency lock: current clip flagged in RO/Emergency folder");
                        Toast.makeText(MainActivity.this, "Current clip locked to Emergency folder", Toast.LENGTH_LONG).show();
                    }
                    @Override
                    public void onError(Exception error) {
                        btnEmergencyLock.setEnabled(true);
                        addDiagLog("Lock failed: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "Lock failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Toggle Audio Mic
        btnToggleMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean targetMicState = !isMicOn;
                btnToggleMic.setText(targetMicState ? "Mic On" : "Mic Muted");
                cameraClient.setAudioEnabled(targetMicState, new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        isMicOn = targetMicState;
                        styleMicButton();
                        addDiagLog("Microphone " + (isMicOn ? "enabled" : "muted"));
                        Toast.makeText(MainActivity.this, isMicOn ? "Microphone enabled" : "Microphone muted", Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onError(Exception error) {
                        styleMicButton(); // Revert
                        addDiagLog("Mic toggle failed: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "Mic toggle failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Toggle Manual Recording
        btnToggleRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean targetRecState = !isRecording;
                btnToggleRecord.setEnabled(false);
                tvRecBadge.setVisibility(targetRecState ? View.VISIBLE : View.GONE);
                btnToggleRecord.setText(targetRecState ? "Stop Recording" : "Start Recording");
                cameraClient.setRecording(targetRecState, new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        btnToggleRecord.setEnabled(true);
                        isRecording = targetRecState;
                        galleryCacheDirty = true;
                        tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                        styleRecordButton();
                        addDiagLog(isRecording ? "Loop recording resumed" : "Loop recording paused");
                    }
                    @Override
                    public void onError(Exception error) {
                        btnToggleRecord.setEnabled(true);
                        tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                        styleRecordButton();
                        addDiagLog("Recording toggle failed: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "Recording toggle failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Sync Clock
        btnSyncTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cameraClient.syncCarTime(new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        syncCameraSettings();
                        Toast.makeText(MainActivity.this, "Dashcam clock synchronized with car", Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onError(Exception error) {
                        Toast.makeText(MainActivity.this, "Time sync failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Camera Recording Resolution Toggle (4K UHD vs 1080P FHD)
        if (btnCameraRes != null) {
            btnCameraRes.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final boolean target1080p = !is1080pMode;
                    final int targetIndex = target1080p ? 1 : (camera4kIndex > 0 ? camera4kIndex : 0);
                    final String modeName = target1080p ? "1080P FHD" : "4K UHD";
                    btnCameraRes.setEnabled(false);
                    btnCameraRes.setText("Setting " + modeName + "...");
                    cameraClient.setVideoResolution(targetIndex, new CameraClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            btnCameraRes.setEnabled(true);
                            is1080pMode = target1080p;
                            galleryCacheDirty = true;
                            btnCameraRes.setText("Rec: " + (is1080pMode ? "1080P" : "4K UHD"));
                            addDiagLog("Dashcam resolution set to " + modeName);
                            Toast.makeText(MainActivity.this, "Camera resolution set to " + modeName, Toast.LENGTH_LONG).show();
                            if (isConnected && layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE) {
                                startLiveStream();
                            }
                        }
                        @Override
                        public void onError(Exception error) {
                            btnCameraRes.setEnabled(true);
                            btnCameraRes.setText("Rec: " + (is1080pMode ? "1080P" : "4K UHD"));
                            addDiagLog("Resolution change failed: " + error.getMessage());
                            Toast.makeText(MainActivity.this, "Resolution change: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }

        // Refresh Gallery
        btnRefreshGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                galleryCacheDirty = true;
                loadGalleryClips(true);
            }
        });

        // Filter: All
        if (btnFilterAll != null) {
            btnFilterAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setGalleryFilter(0);
                }
            });
        }

        // Filter: Front
        if (btnFilterFront != null) {
            btnFilterFront.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setGalleryFilter(1);
                }
            });
        }

        // Filter: Rear
        if (btnFilterRear != null) {
            btnFilterRear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setGalleryFilter(2);
                }
            });
        }

        // Filter: Locked / Emergency
        if (btnFilterLocked != null) {
            btnFilterLocked.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setGalleryFilter(3);
                }
            });
        }

        // Filter: Still Photos
        if (btnFilterPhotos != null) {
            btnFilterPhotos.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setGalleryFilter(4);
                }
            });
        }

        // Gallery Item Selection
        lvClips.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < displayedClips.size()) {
                    selectedClip = displayedClips.get(position);
                    clipAdapter.notifyDataSetChanged();
                    tvSelectedClipName.setText(selectedClip.displayLabel);
                    tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " · " + selectedClip.timeStr);
                    checkUsbStatus();
                    playGalleryPreview(selectedClip);
                }
            }
        });

        // Play / Pause / Replay Selected Clip (or Reload Photo)
        btnPlayClip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedClip != null) {
                    if (selectedClip.isPhoto) {
                        playGalleryPreview(selectedClip);
                    } else if (galleryEnded) {
                        playGalleryPreview(selectedClip);
                    } else if (vlcPlayerManager != null && vlcPlayerManager.isGalleryPlaying()) {
                        vlcPlayerManager.pauseGalleryClip();
                        btnPlayClip.setText("Resume");
                        showGallerySeekOverlay(false);
                    } else if (vlcPlayerManager != null && vlcPlayerManager.isGalleryPaused()) {
                        btnPlayClip.setText("Pause");
                        vlcPlayerManager.resumeGalleryClip();
                        showGallerySeekOverlay(true);
                    } else {
                        playGalleryPreview(selectedClip);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Select an item first", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Tap anywhere on the Gallery viewport to toggle floating top & bottom M3 overlays
        if (frameGalleryContainer != null) {
            frameGalleryContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean topVisible = (layoutGalleryTopOverlay != null && layoutGalleryTopOverlay.getVisibility() == View.VISIBLE);
                    boolean botVisible = (layoutGallerySeekOverlay != null && layoutGallerySeekOverlay.getVisibility() == View.VISIBLE);
                    if (topVisible || botVisible) {
                        boolean activeTransfer = (usbDownloader != null && usbDownloader.isDownloading());
                        if (!isUserSeeking && !activeTransfer && selectedClip != null) {
                            hideGallerySeekOverlay();
                        }
                    } else {
                        showGallerySeekOverlay(shouldAutoHideGalleryOverlay());
                    }
                }
            });
        }

        // Playback Speed Toggle (4K HEVC: 0.33x <-> 0.5x | 1080p AVC: 1.0x -> 0.5x -> 0.33x -> 1.0x)
        if (btnPlaybackRate != null) {
            btnPlaybackRate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selectedClip == null || vlcPlayerManager == null) {
                        Toast.makeText(MainActivity.this, "Select a clip first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (selectedClip.isPhoto) {
                        return;
                    }
                    float current = vlcPlayerManager.getGalleryRate();
                    float nextRate = VlcPlayerManager.getNextGalleryRate(current, currentGalleryClipIsHevc);
                    updatePlaybackRateButton(nextRate);
                    vlcPlayerManager.setGalleryRate(nextRate, currentGalleryClipIsHevc);
                }
            });
        }

        // Gallery Timeline Scrubbing SeekBar (supports byte-percentage seeking on MPEG-TS & time seeking on MP4)
        if (sbGallerySeek != null) {
            sbGallerySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && selectedClip != null) {
                        if (currentGalleryDurationMs > 0) {
                            long targetSec = ((currentGalleryDurationMs * progress) / 1000L) / 1000L;
                            long totSec = currentGalleryDurationMs / 1000L;
                            String timeLabel = String.format(java.util.Locale.US, "%02d:%02d / %02d:%02d",
                                    targetSec / 60, targetSec % 60, totSec / 60, totSec % 60);
                            if (tvSeekPosition != null) {
                                tvSeekPosition.setText(timeLabel);
                            }
                            tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " \u00B7 Seek: " + timeLabel);
                        } else {
                            int pct = Math.min(100, Math.max(0, progress / 10));
                            if (tvSeekPosition != null) {
                                tvSeekPosition.setText(pct + "%");
                            }
                            tvClipDetails.setText(String.format(java.util.Locale.US, "Size: %s \u00B7 Seek: %d%% \u00B7 %s",
                                    selectedClip.getFormattedSize(), pct, selectedClip.timeStr));
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    isUserSeeking = true;
                    showGallerySeekOverlay(false);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    float targetPos = Math.max(0.0f, Math.min(0.995f, seekBar.getProgress() / 1000.0f));
                    isUserSeeking = false;
                    if (selectedClip != null && vlcPlayerManager != null) {
                        if (galleryEnded) {
                            startGalleryPreviewAt(selectedClip, targetPos, false);
                        } else if (vlcPlayerManager.isGallerySeekable() || vlcPlayerManager.isGalleryOpening()) {
                            lastKnownGalleryPosition = targetPos;
                            vlcPlayerManager.seekGalleryPosition(targetPos);
                            showGallerySeekOverlay(!vlcPlayerManager.isGalleryPaused());
                        } else {
                            sbGallerySeek.setProgress(Math.round(lastKnownGalleryPosition * 1000.0f));
                            showGallerySeekOverlay(!vlcPlayerManager.isGalleryPaused());
                            Toast.makeText(MainActivity.this,
                                    "Camera Wi-Fi stream does not support scrubbing — Save to USB to scrub freely",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        // Download to USB Drive (or Cancel active transfer)
        btnDownloadToUsb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (usbDownloader != null && usbDownloader.isDownloading()) {
                    usbDownloader.cancelDownload();
                    Toast.makeText(MainActivity.this, "Cancelling USB transfer...", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selectedClip == null) {
                    Toast.makeText(MainActivity.this, "Please select an item to save", Toast.LENGTH_SHORT).show();
                    return;
                }
                startUsbDownload(selectedClip);
            }
        });

        // Hide / Show Gallery Clips List Column (Toggle Fullscreen Video Preview)
        if (btnToggleGalleryList != null) {
            btnToggleGalleryList.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (layoutGalleryListColumn != null) {
                        boolean wasVisible = (layoutGalleryListColumn.getVisibility() == View.VISIBLE);
                        layoutGalleryListColumn.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
                        if (dividerGalleryColumns != null) {
                            dividerGalleryColumns.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
                        }
                        styleGalleryListToggleButton();
                        showGallerySeekOverlay(shouldAutoHideGalleryOverlay());

                        // Recalculate aspect-preserving layout for expanded/collapsed preview area
                        if (surfaceGallery != null && vlcPlayerManager != null) {
                            surfaceGallery.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    vlcPlayerManager.recomputeLayout(surfaceGallery, false);
                                }
                            }, 50);
                        }
                    }
                }
            });
        }
    }

    private void showThemeSelectionDialog() {
        final ThemeManager.Palette[] palettes = ThemeManager.getPalettes();
        final CharSequence[] labels = new CharSequence[palettes.length];
        for (int i = 0; i < palettes.length; i++) {
            ThemeManager.Palette p = palettes[i];
            boolean isCurrent = p.id.equals(activePalette.id);
            labels[i] = p.displayName + (isCurrent ? "   \u2713" : "");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Interface Theme");
        b.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < palettes.length) {
                    activePalette = palettes[which];
                    ThemeManager.savePalette(MainActivity.this, activePalette);
                    applyTheme();
                    addDiagLog("Theme changed to " + activePalette.displayName);
                }
            }
        });
        b.setNegativeButton("Close", null);
        b.show();
    }

    private void cycleTheme() {
        String nextId = ThemeManager.nextPaletteId(activePalette.id);
        activePalette = ThemeManager.getPaletteById(nextId);
        ThemeManager.savePalette(this, activePalette);
        applyTheme();
        addDiagLog("Theme cycled to " + activePalette.displayName);
        Toast.makeText(this, "Theme: " + activePalette.displayName, Toast.LENGTH_SHORT).show();
    }

    public static String getMirrorPrefKey(int viewMode) {
        switch (viewMode) {
            case CameraClient.VIEW_REAR:
                return "mirror_rear";
            case CameraClient.VIEW_DUAL:
                return "mirror_dual";
            case VIEW_OEM_DUAL:
                return "mirror_oem_dual";
            case VIEW_OEM_REAR:
                return "mirror_oem_rear";
            default:
                return "mirror_front";
        }
    }

    public static String formatLiveStreamBadge(String modeLabel, boolean mirrored, String statusSuffix) {
        String safeMode = (modeLabel != null && !modeLabel.isEmpty()) ? modeLabel : "CAM";
        String prefix = mirrored ? (safeMode + " \u00B7 MIRROR") : safeMode;
        if (statusSuffix == null || statusSuffix.isEmpty()) {
            return prefix;
        }
        return prefix + " (" + statusSuffix + ")";
    }

    public static long computeLiveRecoveryDelayMs(int attempt) {
        if (attempt <= 1) return 50L;
        if (attempt == 2) return 400L;
        if (attempt == 3) return 1000L;
        return 2500L;
    }

    public static boolean isSilentLiveRefresh(int attempt) {
        return attempt == 1;
    }

    /**
     * Computes the ordered list of available camera view modes:
     * - Unsupported OEM trim (hasOemRear == false):
     *     Single Fitcamx -> [VIEW_FRONT]
     *     Dual Fitcamx   -> [VIEW_FRONT, VIEW_DUAL, VIEW_REAR]
     * - Supported Gen5W OEM trim (hasOemRear == true):
     *     Single Fitcamx -> [VIEW_FRONT, VIEW_OEM_DUAL, VIEW_OEM_REAR]
     *     Dual Fitcamx (default) -> [VIEW_FRONT, VIEW_DUAL, VIEW_REAR]
     *     Dual Fitcamx (forceExtraOemViews == true) -> [VIEW_FRONT, VIEW_DUAL, VIEW_REAR, VIEW_OEM_DUAL, VIEW_OEM_REAR]
     */
    public static int[] computeAvailableViewModes(boolean hasFitcamxRear, boolean hasOemRear, boolean forceExtraOemViews) {
        if (!hasOemRear) {
            if (hasFitcamxRear) {
                return new int[] {
                    CameraClient.VIEW_FRONT,
                    CameraClient.VIEW_DUAL,
                    CameraClient.VIEW_REAR
                };
            }
            return new int[] { CameraClient.VIEW_FRONT };
        }
        if (!hasFitcamxRear) {
            return new int[] {
                CameraClient.VIEW_FRONT,
                VIEW_OEM_DUAL,
                VIEW_OEM_REAR
            };
        }
        if (forceExtraOemViews) {
            return new int[] {
                CameraClient.VIEW_FRONT,
                CameraClient.VIEW_DUAL,
                CameraClient.VIEW_REAR,
                VIEW_OEM_DUAL,
                VIEW_OEM_REAR
            };
        }
        return new int[] {
            CameraClient.VIEW_FRONT,
            CameraClient.VIEW_DUAL,
            CameraClient.VIEW_REAR
        };
    }

    public static int getNextViewMode(int currentMode, int[] availableModes) {
        if (availableModes == null || availableModes.length == 0) {
            return CameraClient.VIEW_FRONT;
        }
        for (int i = 0; i < availableModes.length; i++) {
            if (availableModes[i] == currentMode) {
                return availableModes[(i + 1) % availableModes.length];
            }
        }
        return availableModes[0];
    }

    /**
     * Computes the RDMA1 hardware preview-overlay X coordinate for a 720x480 OEM backup camera
     * stream so it stays on-screen for Telechips VIOC while remaining 100% occluded by opaque
     * RDMA0 UI views (and strictly right of surface_live's RDMA0 punch-through hole in Side-by-Side).
     */
    public static int computeOemOverlayX(int screenWidthPx, int viewMode) {
        int safeScreen = Math.max(720, screenWidthPx);
        if (viewMode == VIEW_OEM_REAR) {
            return Math.max(0, (safeScreen - 720) / 2);
        }
        return Math.max(0, safeScreen - 720);
    }

    /**
     * Computes the width (in px) for the left Front Fitcamx pane in Side-by-Side mode so:
     * 1) 16:9 Front video and 3:2 Backup video render at matching vertical heights (~54.2% : 45.8%), AND
     * 2) containerLeftOnScreenPx + frontWidthPx stays strictly <= overlayX so surface_live's
     *    transparent RDMA0 SurfaceView hole never overlaps RDMA1 [overlayX .. overlayX + 720].
     */
    public static int computeSideBySideFrontWidthPx(int containerWidthPx, int containerLeftOnScreenPx, int screenWidthPx) {
        if (containerWidthPx <= 0) return 0;
        // 16:9 beside 3:2 equal-height ratio: (16/9) / ((16/9) + (3/2)) = 32 / 59
        int idealWidth = Math.round((containerWidthPx * 32.0f) / 59.0f);
        int overlayX = computeOemOverlayX(screenWidthPx, VIEW_OEM_DUAL);
        int maxBeforeRdma1 = overlayX - Math.max(0, containerLeftOnScreenPx) - 2;
        int chosen = idealWidth;
        if (maxBeforeRdma1 >= 240 && chosen > maxBeforeRdma1) {
            chosen = maxBeforeRdma1;
        }
        int minPane = Math.min(240, containerWidthPx / 2);
        int maxPane = Math.max(minPane, containerWidthPx - minPane);
        return Math.max(minPane, Math.min(maxPane, chosen));
    }

    public static String getViewModeDisplayOption(int mode) {
        switch (mode) {
            case CameraClient.VIEW_FRONT:
                return "Front Camera";
            case CameraClient.VIEW_DUAL:
                return "Dual View (Fitcamx PIP)";
            case CameraClient.VIEW_REAR:
                return "Rear Camera (Fitcamx)";
            case VIEW_OEM_DUAL:
                return "Side-by-Side (Front + Car Backup)";
            case VIEW_OEM_REAR:
                return "Car Backup Camera";
            default:
                return "Camera View";
        }
    }

    private int[] getEffectiveAvailableViewModes() {
        boolean effectiveFitcamxRear = isConnected && hasRearCamera;
        return computeAvailableViewModes(effectiveFitcamxRear, hasOemRearCamera, forceExtraOemViews);
    }

    private void loadSavedMirrorSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            if (prefs != null) {
                mirrorByViewMode[CameraClient.VIEW_FRONT] = prefs.getBoolean(getMirrorPrefKey(CameraClient.VIEW_FRONT), false);
                mirrorByViewMode[CameraClient.VIEW_REAR] = prefs.getBoolean(getMirrorPrefKey(CameraClient.VIEW_REAR), false);
                mirrorByViewMode[CameraClient.VIEW_DUAL] = prefs.getBoolean(getMirrorPrefKey(CameraClient.VIEW_DUAL), false);
                mirrorByViewMode[VIEW_OEM_DUAL] = prefs.getBoolean(getMirrorPrefKey(VIEW_OEM_DUAL), false);
                mirrorByViewMode[VIEW_OEM_REAR] = prefs.getBoolean(getMirrorPrefKey(VIEW_OEM_REAR), false);
            }
        } catch (Exception ignored) {}
    }

    private void loadSavedAdvancedSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            if (prefs != null) {
                forceExtraOemViews = prefs.getBoolean(KEY_FORCE_EXTRA_OEM_VIEWS, false);
            }
        } catch (Exception ignored) {}
    }

    private boolean hasKnownFitcamxRearForSsid(String ssid) {
        if (ssid == null || ssid.isEmpty()) return false;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            return prefs != null && prefs.contains(KEY_KNOWN_FITCAMX_REAR_PREFIX + ssid);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadKnownFitcamxRearForSsid(String ssid) {
        if (ssid == null || ssid.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            String key = KEY_KNOWN_FITCAMX_REAR_PREFIX + ssid;
            if (prefs != null && prefs.contains(key)) {
                hasRearCamera = prefs.getBoolean(key, true);
            }
        } catch (Exception ignored) {}
    }

    private void saveKnownFitcamxRearForSsid(String ssid, boolean hasRear) {
        if (ssid == null || ssid.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            String key = KEY_KNOWN_FITCAMX_REAR_PREFIX + ssid;
            if (prefs != null && (!prefs.contains(key) || prefs.getBoolean(key, !hasRear) != hasRear)) {
                prefs.edit().putBoolean(key, hasRear).apply();
            }
        } catch (Exception ignored) {}
    }

    private void showAdvancedOptionsDialog() {
        int padH = ThemeManager.dp(this, 20f);
        int padV = ThemeManager.dp(this, 14f);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padH, padV, padH, padV);

        // Silently hide OEM camera option on unsupported trims (e.g. 360 SVM trims)
        if (hasOemRearCamera) {
            final CheckBox cbForceOem = new CheckBox(this);
            cbForceOem.setText("Enable Car Backup Camera views even when Rear Fitcamx is present");
            cbForceOem.setTextSize(15f);
            cbForceOem.setChecked(forceExtraOemViews);
            cbForceOem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    forceExtraOemViews = isChecked;
                    try {
                        SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
                        if (prefs != null) {
                            prefs.edit().putBoolean(KEY_FORCE_EXTRA_OEM_VIEWS, isChecked).apply();
                        }
                    } catch (Exception ignored) {}
                    addDiagLog("Extra Car Backup Camera views " + (isChecked ? "enabled" : "disabled"));
                    applyCameraConfigurationUI();
                }
            });
            root.addView(cbForceOem);

            TextView tvHint = new TextView(this);
            tvHint.setText("Adds Side-by-Side and Car Backup views to the view rotation without replacing any Fitcamx views.");
            tvHint.setTextSize(12.5f);
            tvHint.setPadding(ThemeManager.dp(this, 8f), ThemeManager.dp(this, 4f), 0, ThemeManager.dp(this, 14f));
            root.addView(tvHint);
        }

        final AlertDialog[] holder = new AlertDialog[1];

        TextView tvMaintHeader = new TextView(this);
        tvMaintHeader.setText("Storage Maintenance");
        tvMaintHeader.setTextSize(13f);
        tvMaintHeader.setPadding(0, ThemeManager.dp(this, 4f), 0, ThemeManager.dp(this, 6f));
        root.addView(tvMaintHeader);

        Button btnFormatSd = new Button(this);
        boolean canFormat = isConnected && !isFormattingSdCard;
        btnFormatSd.setText(canFormat ? "Format Fitcamx SD Card..." : "Format Fitcamx SD Card (Connect Wi-Fi First)");
        btnFormatSd.setAllCaps(false);
        btnFormatSd.setEnabled(canFormat);
        ThemeManager.Palette p = activePalette;
        if (canFormat) {
            ThemeManager.stylePillButton(this, btnFormatSd, p.errorContainer, p.onErrorContainer, 0, 0, true);
        } else {
            ThemeManager.stylePillButton(this, btnFormatSd, p.surfaceContainerHigh, p.onSurfaceVariant, 0, 0, false);
        }
        btnFormatSd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    try { holder[0].dismiss(); } catch (Exception ignored) {}
                }
                showFormatSdGuard1Dialog();
            }
        });
        root.addView(btnFormatSd);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Advanced Options");
        b.setView(root);
        b.setNegativeButton("Close", null);
        AlertDialog dlg = b.create();
        holder[0] = dlg;
        dlg.show();
    }

    private void showFormatSdGuard1Dialog() {
        if (!isConnected || cameraClient == null) {
            Toast.makeText(this, "Connect to Fitcamx Wi-Fi before formatting the SD card.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Format Fitcamx SD Card? (Step 1 of 2)");
        b.setMessage("WARNING: Formatting the Fitcamx microSD card will permanently erase:\n\n"
                + "\u2022 All Front & Rear loop recordings\n"
                + "\u2022 All Emergency / Locked (RO) clips\n"
                + "\u2022 All still Snapshot photos on the dashcam\n\n"
                + "Files already saved to your car's USB flash drive will not be affected.\n\n"
                + "Do you want to proceed to final confirmation?");
        b.setNegativeButton("Cancel", null);
        b.setPositiveButton("Continue...", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showFormatSdGuard2Dialog();
            }
        });
        b.show();
    }

    private void showFormatSdGuard2Dialog() {
        if (!isConnected || cameraClient == null) {
            Toast.makeText(this, "Fitcamx Wi-Fi disconnected — format cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        int padH = ThemeManager.dp(this, 20f);
        int padV = ThemeManager.dp(this, 14f);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padH, padV, padH, padV);

        TextView tvWarn = new TextView(this);
        tvWarn.setText("This action cannot be undone. Check the box below to unlock the Format button:");
        tvWarn.setTextSize(14f);
        tvWarn.setPadding(0, 0, 0, ThemeManager.dp(this, 10f));
        root.addView(tvWarn);

        final CheckBox cbConfirm = new CheckBox(this);
        cbConfirm.setText("I understand all Fitcamx SD card footage and locked clips will be permanently erased.");
        cbConfirm.setTextSize(14f);
        cbConfirm.setChecked(false);
        root.addView(cbConfirm);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Final Confirmation — Format SD Card (Step 2 of 2)");
        b.setView(root);
        b.setNegativeButton("Cancel", null);
        b.setPositiveButton("Format SD Card Now", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (cbConfirm.isChecked()) {
                    executeFormatSdCard();
                }
            }
        });

        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button positive = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                if (positive != null) {
                    positive.setEnabled(false);
                    cbConfirm.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            positive.setEnabled(isChecked);
                        }
                    });
                }
            }
        });
        dlg.show();
    }

    private void executeFormatSdCard() {
        if (!isConnected || cameraClient == null || isFormattingSdCard) {
            return;
        }
        isFormattingSdCard = true;
        cancelLiveMaintenanceTimers();
        isStartingLive = false;
        isConnectingLiveVlc = false;
        activeLiveStreamUrl = null;
        liveRequestGeneration++;
        if (vlcPlayerManager != null) {
            vlcPlayerManager.stopLiveStream();
            vlcPlayerManager.stopGalleryClip();
        }
        clearPhotoPreview();

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Formatting SD Card");
        progress.setMessage("Erasing Fitcamx microSD card and restarting loop recording...\nPlease do not power off the vehicle.");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        try {
            progress.show();
        } catch (Exception ignored) {}

        addDiagLog("Formatting Fitcamx SD card...");
        cameraClient.formatSdCard(new CameraClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                isFormattingSdCard = false;
                try {
                    if (progress.isShowing()) progress.dismiss();
                } catch (Exception ignored) {}
                isRecording = true;
                if (tvRecBadge != null) tvRecBadge.setVisibility(View.VISIBLE);
                styleRecordButton();

                selectedClip = null;
                galleryCacheDirty = true;
                allClips.clear();
                displayedClips.clear();
                if (clipAdapter != null) clipAdapter.notifyDataSetChanged();

                addDiagLog("Fitcamx SD card formatted successfully; loop recording resumed");
                Toast.makeText(MainActivity.this, "SD card formatted — loop recording resumed", Toast.LENGTH_LONG).show();

                if (layoutGalleryMode != null && layoutGalleryMode.getVisibility() == View.VISIBLE) {
                    loadGalleryClips(true);
                } else if (layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE) {
                    startLiveStream();
                }
            }

            @Override
            public void onError(Exception error) {
                isFormattingSdCard = false;
                try {
                    if (progress.isShowing()) progress.dismiss();
                } catch (Exception ignored) {}
                String errMsg = error != null ? error.getMessage() : "Unknown error";
                addDiagLog("SD card format failed: " + errMsg);
                Toast.makeText(MainActivity.this, "Format failed: " + errMsg, Toast.LENGTH_LONG).show();
                if (layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE) {
                    startLiveStream();
                }
            }
        });
    }

    private boolean isViewModeMirrored(int viewMode) {
        if (viewMode < 0 || viewMode >= mirrorByViewMode.length) {
            return false;
        }
        return mirrorByViewMode[viewMode];
    }

    private void setViewModeMirrored(int viewMode, boolean mirrored) {
        if (viewMode < 0 || viewMode >= mirrorByViewMode.length) return;
        if (mirrorByViewMode[viewMode] == mirrored) return;
        mirrorByViewMode[viewMode] = mirrored;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_UI_SETTINGS, Context.MODE_PRIVATE);
            if (prefs != null) {
                prefs.edit().putBoolean(getMirrorPrefKey(viewMode), mirrored).apply();
            }
        } catch (Exception ignored) {}
    }

    private void styleMirrorButton() {
        if (btnMirrorLive == null) return;
        ThemeManager.Palette p = activePalette;
        boolean mirrored = isViewModeMirrored(targetViewMode);
        btnMirrorLive.setText(mirrored ? "Mirror: ON" : "Mirror: OFF");
        if (mirrored) {
            ThemeManager.stylePillButton(this, btnMirrorLive, p.primaryContainer, p.onPrimaryContainer,
                    Math.max(1, ThemeManager.dp(this, 1f)), p.primary, true);
        } else {
            ThemeManager.stylePillButton(this, btnMirrorLive, p.surfaceContainerHigh, p.onSurface, 0, 0, false);
        }
    }

    private void styleRescanButton() {
        if (btnRescan == null) return;
        ThemeManager.Palette p = activePalette;
        if (manualWifiSetupPending && !isConnected) {
            btnRescan.setText("Wi-Fi Setup");
            ThemeManager.stylePillButton(this, btnRescan, p.tertiaryContainer, p.onTertiaryContainer, 0, 0, true);
        } else {
            btnRescan.setText(R.string.btn_rescan);
            ThemeManager.stylePillButton(this, btnRescan, p.surfaceContainerHigh, p.onSurface, 0, 0, false);
        }
    }

    private void toggleLiveMirror() {
        int mode = targetViewMode;
        boolean nextMirrored = !isViewModeMirrored(mode);
        setViewModeMirrored(mode, nextMirrored);
        styleMirrorButton();

        String modeLabel = getViewModeLabel(mode);
        String msg = modeLabel + " live mirror: " + (nextMirrored ? "ON" : "OFF");
        addDiagLog(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        // For OEM Backup Camera modes, apply orientation directly on OemRearView with zero RTSP restart!
        if (mode == VIEW_OEM_DUAL || mode == VIEW_OEM_REAR) {
            if (viewOemRear != null) {
                viewOemRear.setMirrorMode(nextMirrored);
            }
            if (tvOemPaneBadge != null && mode == VIEW_OEM_DUAL) {
                tvOemPaneBadge.setText(nextMirrored ? "CAR BACKUP \u00B7 MIRROR" : "CAR BACKUP");
            }
            if (tvStreamBadge != null) {
                tvStreamBadge.setText(formatLiveStreamBadge(modeLabel, nextMirrored, "LIVE"));
            }
            return;
        }

        if (!isStartingLive && isLiveViewEligible(liveRequestGeneration)
                && activeLiveStreamUrl != null && liveSurface != null && liveSurface.isValid()) {
            if (tvStreamBadge != null) {
                tvStreamBadge.setText(formatLiveStreamBadge(modeLabel, nextMirrored, "LIVE"));
            }
            List<String> singleFirst = new ArrayList<String>();
            singleFirst.add(activeLiveStreamUrl);
            for (String c : cameraClient.getCandidateStreamUrls(mode)) {
                if (!singleFirst.contains(c)) singleFirst.add(c);
            }
            cancelLiveMaintenanceTimers();
            final int reqGen = ++liveRequestGeneration;
            tryPlayVlcCandidate(singleFirst, 0, mode, reqGen, true);
        } else if (tvStreamBadge != null) {
            tvStreamBadge.setText(formatLiveStreamBadge(modeLabel, nextMirrored, isConnected ? "LIVE" : "IDLE"));
        }
    }

    private void styleMicButton() {
        if (btnToggleMic == null) return;
        ThemeManager.Palette p = activePalette;
        btnToggleMic.setText(isMicOn ? "Mic On" : "Mic Muted");
        if (isMicOn) {
            ThemeManager.stylePillButton(this, btnToggleMic, p.secondaryContainer, p.onSecondaryContainer, 0, 0, false);
        } else {
            ThemeManager.stylePillButton(this, btnToggleMic, p.surfaceContainerHigh, p.statusWarning,
                    Math.max(1, ThemeManager.dp(this, 1f)), p.outline, true);
        }
    }

    private void styleRecordButton() {
        if (btnToggleRecord == null) return;
        ThemeManager.Palette p = activePalette;
        btnToggleRecord.setText(isRecording ? "Stop Recording" : "Start Recording");
        if (isRecording) {
            ThemeManager.stylePillButton(this, btnToggleRecord, p.errorContainer, p.onErrorContainer, 0, 0, true);
        } else {
            ThemeManager.stylePillButton(this, btnToggleRecord, p.primary, p.onPrimary, 0, 0, true);
        }
    }

    private void styleGalleryListToggleButton() {
        if (btnToggleGalleryList == null) return;
        ThemeManager.Palette p = activePalette;
        boolean listVisible = (layoutGalleryListColumn == null || layoutGalleryListColumn.getVisibility() == View.VISIBLE);
        btnToggleGalleryList.setText(listVisible ? "Hide List" : "Show List");
        if (listVisible) {
            ThemeManager.stylePillButton(this, btnToggleGalleryList, p.secondaryContainer, p.onSecondaryContainer, 0, 0, true);
        } else {
            ThemeManager.stylePillButton(this, btnToggleGalleryList, p.primaryContainer, p.onPrimaryContainer, 0, 0, true);
        }
    }

    private void styleAllFilterChips() {
        ThemeManager.Palette p = activePalette;
        ThemeManager.styleFilterChip(this, btnFilterAll, p, "All", currentFilter == 0);
        ThemeManager.styleFilterChip(this, btnFilterFront, p, "Front", currentFilter == 1);
        ThemeManager.styleFilterChip(this, btnFilterRear, p, "Rear", currentFilter == 2);
        ThemeManager.styleFilterChip(this, btnFilterLocked, p, "Emergency", currentFilter == 3);
        ThemeManager.styleFilterChip(this, btnFilterPhotos, p, "Photos", currentFilter == 4);
    }

    private void applyTheme() {
        ThemeManager.Palette p = activePalette;
        int stroke1dp = Math.max(1, ThemeManager.dp(this, 1f));

        if (getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().setBackgroundColor(p.surface);
        }
        if (layoutRoot != null) {
            layoutRoot.setBackgroundColor(p.surface);
        }

        // Left Navigation Rail
        if (layoutSidebar != null) {
            layoutSidebar.setBackgroundColor(p.surfaceContainerLow);
        }
        if (dividerSidebar != null) {
            dividerSidebar.setBackgroundColor(p.outlineVariant);
        }
        if (tvTitle != null) {
            tvTitle.setTextColor(p.primary);
        }
        if (tvAppVersion != null) {
            tvAppVersion.setTextColor(p.onSurfaceVariant);
        }
        if (tvAuthorCredit != null) {
            tvAuthorCredit.setTextColor(ThemeManager.withAlpha(p.onSurfaceVariant, 190));
        }
        ThemeManager.styleInteractiveCard(this, layoutWifiCard, p.surfaceContainer, p.onSurface, 12f, 0, 0);
        if (tvWifiStatus != null) {
            tvWifiStatus.setTextColor(isConnected ? p.statusPositive : p.statusWarning);
        }
        styleRescanButton();
        if (tvNavSectionLabel != null) {
            tvNavSectionLabel.setTextColor(p.onSurfaceVariant);
        }

        boolean liveActive = (layoutGalleryMode == null || layoutGalleryMode.getVisibility() != View.VISIBLE);
        ThemeManager.styleNavRailButton(this, btnTabLive, p, getString(R.string.tab_live), liveActive);
        ThemeManager.styleNavRailButton(this, btnTabGallery, p, "SD Card", !liveActive);

        if (btnTheme != null) {
            ThemeManager.stylePillButton(this, btnTheme, p.surfaceContainerHigh, p.onSurface, stroke1dp, p.outlineVariant, false);
            btnTheme.setText(ThemeManager.buildThemeButtonLabel(p));
        }
        ThemeManager.stylePillButton(this, btnExportLog, p.surfaceContainerHigh, p.onSurfaceVariant, 0, 0, false);
        ThemeManager.stylePillButton(this, btnExitApp, p.primary, p.onPrimary, 0, 0, true);

        ThemeManager.styleInteractiveCard(this, layoutDiagBar, p.surfaceContainer, p.onSurface, 12f, 0, 0);
        if (tvDiagInfo != null) {
            tvDiagInfo.setTextColor(p.onSurfaceVariant);
        }
        if (tvDiagTapHint != null) {
            tvDiagTapHint.setTextColor(p.primary);
        }

        // Live Mode Controls & Badges
        ThemeManager.styleSurfaceCard(this, tvStreamBadge, p.scrimOverlay, 999f, stroke1dp, ThemeManager.OVERLAY_BORDER);
        if (tvStreamBadge != null) {
            tvStreamBadge.setTextColor(ThemeManager.OVERLAY_TEXT_PRIMARY);
        }
        if (dividerOemSplit != null) {
            dividerOemSplit.setBackgroundColor(p.outlineVariant);
        }
        ThemeManager.styleSurfaceCard(this, tvOemPaneBadge, p.scrimOverlay, 999f, stroke1dp, ThemeManager.OVERLAY_BORDER);
        if (tvOemPaneBadge != null) {
            tvOemPaneBadge.setTextColor(ThemeManager.OVERLAY_TEXT_PRIMARY);
        }
        ThemeManager.styleSurfaceCard(this, tvRecBadge, p.errorContainer, 999f, 0, 0);
        if (tvRecBadge != null) {
            tvRecBadge.setTextColor(p.onErrorContainer);
        }
        if (dividerLiveControls != null) {
            dividerLiveControls.setBackgroundColor(p.outlineVariant);
        }
        if (layoutLiveControls != null) {
            layoutLiveControls.setBackgroundColor(p.surfaceContainerLow);
        }
        if (tvCamControlsLabel != null) {
            tvCamControlsLabel.setTextColor(p.onSurfaceVariant);
        }
        ThemeManager.stylePillButton(this, btnToggleCam, p.primary, p.onPrimary, 0, 0, true);
        styleMirrorButton();
        ThemeManager.stylePillButton(this, btnSnapshot, p.secondaryContainer, p.onSecondaryContainer, 0, 0, false);
        ThemeManager.stylePillButton(this, btnEmergencyLock, p.tertiaryContainer, p.onTertiaryContainer, 0, 0, true);
        styleMicButton();
        styleRecordButton();
        ThemeManager.stylePillButton(this, btnCameraRes, p.surfaceContainerHigh, p.primary, stroke1dp, p.outlineVariant, true);
        ThemeManager.stylePillButton(this, btnSyncTime, p.surfaceContainerHigh, p.onSurfaceVariant, 0, 0, false);

        // SD Card / Gallery Mode
        if (layoutGalleryListColumn != null) {
            layoutGalleryListColumn.setBackgroundColor(p.surfaceContainerLow);
        }
        if (dividerGalleryColumns != null) {
            dividerGalleryColumns.setBackgroundColor(p.outlineVariant);
        }
        if (tvClipCount != null) {
            tvClipCount.setTextColor(p.onSurfaceVariant);
        }
        if (tvEmptyGallery != null) {
            tvEmptyGallery.setTextColor(p.onSurfaceVariant);
        }
        ThemeManager.stylePillButton(this, btnRefreshGallery, p.surfaceContainerHigh, p.onSurface, 0, 0, false);
        styleAllFilterChips();

        // Floating Smoked-Glass Video Overlays (high contrast over photographic video in all themes)
        ThemeManager.styleSurfaceCard(this, layoutGalleryTopOverlay, p.scrimOverlay, 999f, stroke1dp, ThemeManager.OVERLAY_BORDER);
        ThemeManager.styleSurfaceCard(this, layoutGallerySeekOverlay, p.scrimOverlay, 18f, stroke1dp, ThemeManager.OVERLAY_BORDER);
        if (tvSelectedClipName != null) {
            tvSelectedClipName.setTextColor(ThemeManager.OVERLAY_TEXT_PRIMARY);
        }
        if (tvSeekPosition != null) {
            tvSeekPosition.setTextColor(ThemeManager.OVERLAY_TEXT_PRIMARY);
        }
        if (tvClipDetails != null) {
            tvClipDetails.setTextColor(ThemeManager.OVERLAY_TEXT_SECONDARY);
        }
        if (tvDownloadStats != null) {
            tvDownloadStats.setTextColor(ThemeManager.OVERLAY_POSITIVE);
        }
        styleGalleryListToggleButton();

        int overlayChipBg = 0xFF28303B;
        int overlayAccentText = p.isLight ? 0xFF9CF0F8 : p.primary;
        ThemeManager.stylePillButton(this, btnPlaybackRate, overlayChipBg, overlayAccentText, 0, 0, true);
        ThemeManager.stylePillButton(this, btnPlayClip, overlayChipBg, ThemeManager.OVERLAY_TEXT_PRIMARY, 0, 0, false);

        int scrubberAccent = p.isLight ? 0xFF7FD8E2 : p.primary;
        ThemeManager.styleSeekBar(this, sbGallerySeek, 0x44FFFFFF, scrubberAccent);
        ThemeManager.styleHorizontalProgressBar(this, pbDownloadProgress, 0x38FFFFFF, scrubberAccent);

        checkUsbStatus();
        if (clipAdapter != null) {
            clipAdapter.notifyDataSetChanged();
        }
    }

    private void setGalleryFilter(int filter) {
        currentFilter = filter;
        styleAllFilterChips();
        applyFilter();
    }

    private void applyFilter() {
        displayedClips.clear();
        for (ClipItem c : allClips) {
            if (currentFilter == 1) {
                if (!c.isPhoto && c.isFront) displayedClips.add(c);
            } else if (currentFilter == 2) {
                if (!c.isPhoto && !c.isFront) displayedClips.add(c);
            } else if (currentFilter == 3) {
                if (c.isLocked) displayedClips.add(c);
            } else if (currentFilter == 4) {
                if (c.isPhoto) displayedClips.add(c);
            } else {
                displayedClips.add(c);
            }
        }
        clipAdapter.notifyDataSetChanged();
        String countPrefix = (currentFilter == 4) ? "Photos" : "Clips";
        tvClipCount.setText(countPrefix + " (" + displayedClips.size() + "/" + allClips.size() + ")");
        if (displayedClips.isEmpty()) {
            if (currentFilter == 4) {
                tvEmptyGallery.setText("No still photos found.\nUse 'Snapshot' in Live View to capture photos.");
            } else if (currentFilter == 3 && !allClips.isEmpty()) {
                tvEmptyGallery.setText("No emergency-locked clips found.");
            } else {
                tvEmptyGallery.setText(R.string.no_clips);
            }
            tvEmptyGallery.setVisibility(View.VISIBLE);
            lvClips.setVisibility(View.GONE);
        } else {
            tvEmptyGallery.setVisibility(View.GONE);
            lvClips.setVisibility(View.VISIBLE);
        }
    }

    private void cycleViewMode() {
        int[] modes = getEffectiveAvailableViewModes();
        if (modes.length <= 1) {
            Toast.makeText(this, "Single camera configuration (Front only)", Toast.LENGTH_SHORT).show();
            return;
        }
        targetViewMode = getNextViewMode(targetViewMode, modes);
        updateViewModeUI();
        startLiveStream();
    }

    private void showViewModeSelectionDialog() {
        final int[] modes = getEffectiveAvailableViewModes();
        if (modes.length <= 1) {
            Toast.makeText(this, "Single camera configuration (Front only)", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] options = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            int m = modes[i];
            options[i] = getViewModeDisplayOption(m) + (targetViewMode == m ? "  \u2713" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Camera View");
        b.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which < 0 || which >= modes.length) return;
                int selectedMode = modes[which];
                if (selectedMode != targetViewMode || selectedMode != activeStreamMode) {
                    targetViewMode = selectedMode;
                    updateViewModeUI();
                    startLiveStream();
                }
            }
        });
        b.show();
    }

    private void updateViewModeUI() {
        styleMirrorButton();
        if (btnToggleCam == null) return;
        int[] modes = getEffectiveAvailableViewModes();
        if (modes.length <= 1) {
            btnToggleCam.setText("View: Front (Single Cam)");
            btnToggleCam.setEnabled(false);
            return;
        }
        btnToggleCam.setEnabled(true);
        if ((isStartingLive || isConnectingLiveVlc) && targetViewMode != activeStreamMode) {
            btnToggleCam.setText("View: " + getViewModeLabel(targetViewMode) + " \u2026");
        } else if (targetViewMode == CameraClient.VIEW_DUAL) {
            btnToggleCam.setText("View: DUAL (PIP)");
        } else {
            btnToggleCam.setText("View: " + getViewModeLabel(targetViewMode));
        }
    }

    private void checkCameraConfiguration() {
        boolean foundFitcamxClips = false;
        boolean foundFitcamxRear = false;
        boolean foundAnyRear = false;
        if (allClips != null) {
            for (ClipItem clip : allClips) {
                if (clip == null) continue;
                if (!clip.isFront) {
                    foundAnyRear = true;
                }
                if (!clip.isOemUsbClip) {
                    foundFitcamxClips = true;
                    if (!clip.isFront) {
                        foundFitcamxRear = true;
                    }
                }
            }
        }
        boolean uiNeedsRefresh = false;
        if (hasAnyRearClips != foundAnyRear) {
            hasAnyRearClips = foundAnyRear;
            uiNeedsRefresh = true;
        }
        if (foundFitcamxClips && hasRearCamera != foundFitcamxRear) {
            hasRearCamera = foundFitcamxRear;
            saveKnownFitcamxRearForSsid(currentSsid, foundFitcamxRear);
            uiNeedsRefresh = true;
        }
        if (uiNeedsRefresh) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyCameraConfigurationUI();
                }
            });
        }
    }

    private void applyCameraConfigurationUI() {
        int[] modes = getEffectiveAvailableViewModes();
        boolean targetAllowed = false;
        for (int m : modes) {
            if (m == targetViewMode) {
                targetAllowed = true;
                break;
            }
        }
        boolean switchedFallback = false;
        if (!targetAllowed) {
            targetViewMode = modes.length > 0 ? modes[0] : CameraClient.VIEW_FRONT;
            currentViewMode = targetViewMode;
            switchedFallback = (activeStreamMode != targetViewMode);
        }

        if (btnToggleCam != null) {
            if (modes.length <= 1) {
                activeStreamMode = CameraClient.VIEW_FRONT;
                btnToggleCam.setText("View: Front (Single Cam)");
                btnToggleCam.setEnabled(false);
            } else {
                btnToggleCam.setEnabled(true);
                updateViewModeUI();
            }
        }
        if (btnFilterRear != null) {
            boolean showRearFilter = hasRearCamera || hasAnyRearClips;
            btnFilterRear.setVisibility(showRearFilter ? View.VISIBLE : View.GONE);
            if (!showRearFilter && currentFilter == 2) {
                currentFilter = 0;
            }
        }
        styleAllFilterChips();

        if (switchedFallback && isActivityVisible && !isExiting && !isFinishing()
                && layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE) {
            startLiveStream();
        }
    }

    private String getViewModeLabel() {
        return getViewModeLabel(activeStreamMode);
    }

    public static String getViewModeLabel(int mode) {
        switch (mode) {
            case CameraClient.VIEW_FRONT:
                return "FRONT";
            case CameraClient.VIEW_DUAL:
                return "DUAL";
            case CameraClient.VIEW_REAR:
                return "REAR";
            case VIEW_OEM_DUAL:
                return "SIDE-BY-SIDE";
            case VIEW_OEM_REAR:
                return "BACKUP";
            default:
                return "CAM";
        }
    }

    private void applyLiveStageLayout(final int mode) {
        int screenW = 1920;
        if (getResources() != null && getResources().getDisplayMetrics() != null
                && getResources().getDisplayMetrics().widthPixels > 0) {
            screenW = getResources().getDisplayMetrics().widthPixels;
        }
        final int finalScreenW = screenW;

        if (mode == VIEW_OEM_REAR) {
            if (surfaceLive != null) surfaceLive.setVisibility(View.GONE);
            if (frameLiveFrontPane != null) frameLiveFrontPane.setVisibility(View.GONE);
            if (dividerOemSplit != null) dividerOemSplit.setVisibility(View.GONE);
            if (frameLiveOemPane != null) {
                frameLiveOemPane.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams oemLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
                frameLiveOemPane.setLayoutParams(oemLp);
            }
            if (tvOemPaneBadge != null) {
                tvOemPaneBadge.setVisibility(View.GONE);
            }
            if (viewOemRear != null) {
                viewOemRear.setMirrorMode(isViewModeMirrored(VIEW_OEM_REAR));
            }
            if (oemRearCameraClient != null) {
                oemRearCameraClient.setOverlayX(computeOemOverlayX(finalScreenW, VIEW_OEM_REAR));
            }
            return;
        }

        if (mode == VIEW_OEM_DUAL) {
            if (frameLiveFrontPane != null) frameLiveFrontPane.setVisibility(View.VISIBLE);
            if (surfaceLive != null) surfaceLive.setVisibility(View.VISIBLE);
            if (dividerOemSplit != null) dividerOemSplit.setVisibility(View.VISIBLE);
            if (frameLiveOemPane != null) frameLiveOemPane.setVisibility(View.VISIBLE);
            if (tvOemPaneBadge != null) {
                tvOemPaneBadge.setVisibility(View.VISIBLE);
                tvOemPaneBadge.setText(isViewModeMirrored(VIEW_OEM_DUAL) ? "CAR BACKUP \u00B7 MIRROR" : "CAR BACKUP");
            }
            if (viewOemRear != null) {
                viewOemRear.setMirrorMode(isViewModeMirrored(VIEW_OEM_DUAL));
            }
            if (oemRearCameraClient != null) {
                oemRearCameraClient.setOverlayX(computeOemOverlayX(finalScreenW, VIEW_OEM_DUAL));
            }

            int containerW = (frameLiveContainer != null) ? frameLiveContainer.getWidth() : 0;
            if (containerW > 0 && frameLiveFrontPane != null && frameLiveOemPane != null) {
                int[] loc = new int[2];
                frameLiveContainer.getLocationOnScreen(loc);
                int frontW = computeSideBySideFrontWidthPx(containerW, loc[0], finalScreenW);
                frameLiveFrontPane.setLayoutParams(new LinearLayout.LayoutParams(
                        frontW, ViewGroup.LayoutParams.MATCH_PARENT, 0.0f));
                frameLiveOemPane.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
            } else if (frameLiveFrontPane != null && frameLiveOemPane != null) {
                frameLiveFrontPane.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 0.54f));
                frameLiveOemPane.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 0.46f));
                if (frameLiveContainer != null) {
                    frameLiveContainer.post(new Runnable() {
                        @Override
                        public void run() {
                            if (targetViewMode != VIEW_OEM_DUAL || frameLiveContainer == null) return;
                            int w = frameLiveContainer.getWidth();
                            if (w > 0 && frameLiveFrontPane != null && frameLiveOemPane != null) {
                                int[] loc = new int[2];
                                frameLiveContainer.getLocationOnScreen(loc);
                                int frontW = computeSideBySideFrontWidthPx(w, loc[0], finalScreenW);
                                frameLiveFrontPane.setLayoutParams(new LinearLayout.LayoutParams(
                                        frontW, ViewGroup.LayoutParams.MATCH_PARENT, 0.0f));
                                frameLiveOemPane.setLayoutParams(new LinearLayout.LayoutParams(
                                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
                            }
                        }
                    });
                }
            }
            if (surfaceLive != null && vlcPlayerManager != null) {
                surfaceLive.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (vlcPlayerManager != null && surfaceLive != null) {
                            vlcPlayerManager.recomputeLayout(surfaceLive, true);
                        }
                    }
                }, 50);
            }
            return;
        }

        // Standard full-stage Fitcamx view (VIEW_FRONT, VIEW_DUAL, VIEW_REAR)
        if (frameLiveOemPane != null) frameLiveOemPane.setVisibility(View.GONE);
        if (dividerOemSplit != null) dividerOemSplit.setVisibility(View.GONE);
        if (tvOemPaneBadge != null) tvOemPaneBadge.setVisibility(View.GONE);
        if (frameLiveFrontPane != null) {
            frameLiveFrontPane.setVisibility(View.VISIBLE);
            frameLiveFrontPane.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        }
        if (surfaceLive != null) {
            surfaceLive.setVisibility(View.VISIBLE);
            if (vlcPlayerManager != null) {
                surfaceLive.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (vlcPlayerManager != null && surfaceLive != null) {
                            vlcPlayerManager.recomputeLayout(surfaceLive, true);
                        }
                    }
                }, 50);
            }
        }
    }

    private void startOemBackupStream(final int mode) {
        if (oemRearCameraClient == null) return;
        int screenW = 1920;
        if (getResources() != null && getResources().getDisplayMetrics() != null
                && getResources().getDisplayMetrics().widthPixels > 0) {
            screenW = getResources().getDisplayMetrics().widthPixels;
        }
        int overlayX = computeOemOverlayX(screenW, mode);
        if (viewOemRear != null) {
            viewOemRear.setMirrorMode(isViewModeMirrored(mode));
            if (!oemRearCameraClient.isStreamingLive()) {
                viewOemRear.setStatusMessage("Connecting car backup camera...", ThemeManager.OVERLAY_TEXT_SECONDARY);
            }
        }

        oemRearCameraClient.startStream(overlayX, new OemRearCameraClient.StreamListener() {
            @Override
            public void onFrame(Bitmap bitmap, byte status) {
                if (!isActivityVisible || isExiting || isFinishing()
                        || layoutLiveMode == null || layoutLiveMode.getVisibility() != View.VISIBLE
                        || (targetViewMode != VIEW_OEM_DUAL && targetViewMode != VIEW_OEM_REAR)) {
                    stopOemBackupStream();
                    return;
                }
                if (viewOemRear != null) {
                    viewOemRear.setFrame(bitmap);
                }
                if (targetViewMode == VIEW_OEM_REAR) {
                    if (pbLiveLoading != null) pbLiveLoading.setVisibility(View.GONE);
                    activeStreamMode = VIEW_OEM_REAR;
                    currentViewMode = VIEW_OEM_REAR;
                    isStartingLive = false;
                    String suffix = (status == OemRearCameraClient.STATUS_SHARED_LIVE) ? "SHARED LIVE" : "LIVE";
                    if (tvStreamBadge != null) {
                        tvStreamBadge.setText(formatLiveStreamBadge(
                                getViewModeLabel(VIEW_OEM_REAR), isViewModeMirrored(VIEW_OEM_REAR), suffix));
                    }
                    updateViewModeUI();
                } else if (targetViewMode == VIEW_OEM_DUAL && tvOemPaneBadge != null) {
                    String base = isViewModeMirrored(VIEW_OEM_DUAL) ? "CAR BACKUP \u00B7 MIRROR" : "CAR BACKUP";
                    tvOemPaneBadge.setText(status == OemRearCameraClient.STATUS_SHARED_LIVE
                            ? (base + " \u00B7 SHARED") : base);
                }
            }

            @Override
            public void onStatusChanged(byte status, String message) {
                if (!isActivityVisible || isExiting || isFinishing()
                        || (targetViewMode != VIEW_OEM_DUAL && targetViewMode != VIEW_OEM_REAR)) {
                    return;
                }
                addDiagLog("Car backup: " + message);
                if (viewOemRear != null) {
                    if (status == OemRearCameraClient.STATUS_REVERSE) {
                        viewOemRear.setStatusMessage("Paused while in Reverse gear", ThemeManager.OVERLAY_WARNING);
                    } else if (status == OemRearCameraClient.STATUS_CAR_OFF) {
                        viewOemRear.setStatusMessage("Car ignition off — backup camera standby", ThemeManager.OVERLAY_TEXT_SECONDARY);
                    } else if (status == OemRearCameraClient.STATUS_ERROR) {
                        viewOemRear.setStatusMessage(message, activePalette.error);
                    } else if (status == OemRearCameraClient.STATUS_STARTING) {
                        viewOemRear.setStatusMessage(message, ThemeManager.OVERLAY_TEXT_SECONDARY);
                    }
                }
                if (targetViewMode == VIEW_OEM_REAR && status != OemRearCameraClient.STATUS_STARTING) {
                    if (pbLiveLoading != null) pbLiveLoading.setVisibility(View.GONE);
                    if (tvStreamBadge != null) {
                        String suffix = (status == OemRearCameraClient.STATUS_REVERSE) ? "REVERSE"
                                : (status == OemRearCameraClient.STATUS_CAR_OFF ? "STANDBY" : "STATUS");
                        tvStreamBadge.setText(formatLiveStreamBadge(
                                getViewModeLabel(VIEW_OEM_REAR), isViewModeMirrored(VIEW_OEM_REAR), suffix));
                    }
                }
            }
        });
    }

    private void stopOemBackupStream() {
        if (oemRearCameraClient != null) {
            oemRearCameraClient.stopStream();
        }
        if (viewOemRear != null) {
            viewOemRear.clearFrame(null, ThemeManager.OVERLAY_TEXT_SECONDARY);
        }
    }

    private void switchTab(boolean showLive) {
        if (showLive) {
            galleryLoadGeneration++;
            hideGallerySeekOverlay();
            clearPhotoPreview();
            ThemeManager.styleNavRailButton(this, btnTabLive, activePalette, getString(R.string.tab_live), true);
            ThemeManager.styleNavRailButton(this, btnTabGallery, activePalette, "SD Card", false);
            layoutLiveMode.setVisibility(View.VISIBLE);
            layoutGalleryMode.setVisibility(View.GONE);

            // Explicitly hide gallery surface and restore active live stage layout to prevent punch-through ghosting
            if (surfaceGallery != null) {
                surfaceGallery.setVisibility(View.GONE);
            }
            applyLiveStageLayout(targetViewMode);

            if (vlcPlayerManager != null) vlcPlayerManager.stopGalleryClip();
            if (targetViewMode == VIEW_OEM_REAR || targetViewMode == VIEW_OEM_DUAL
                    || (liveSurface != null && liveSurface.isValid())) {
                startLiveStream();
            }
        } else {
            isStartingLive = false;
            isConnectingLiveVlc = false;
            activeLiveStreamUrl = null;
            cancelLiveMaintenanceTimers();
            liveRequestGeneration++;
            stopOemBackupStream();
            ThemeManager.styleNavRailButton(this, btnTabLive, activePalette, getString(R.string.tab_live), false);
            ThemeManager.styleNavRailButton(this, btnTabGallery, activePalette, "SD Card", true);
            layoutLiveMode.setVisibility(View.GONE);
            layoutGalleryMode.setVisibility(View.VISIBLE);

            // Explicitly hide live surface and enable gallery view to eliminate video bleeding
            if (surfaceLive != null) {
                surfaceLive.setVisibility(View.GONE);
            }
            if (selectedClip != null && selectedClip.isPhoto) {
                if (surfaceGallery != null) surfaceGallery.setVisibility(View.GONE);
                if (ivGalleryPhoto != null) ivGalleryPhoto.setVisibility(View.VISIBLE);
                if (layoutSeekBarRow != null) layoutSeekBarRow.setVisibility(View.GONE);
            } else {
                if (ivGalleryPhoto != null) ivGalleryPhoto.setVisibility(View.GONE);
                if (surfaceGallery != null) surfaceGallery.setVisibility(View.VISIBLE);
                if (layoutSeekBarRow != null) {
                    layoutSeekBarRow.setVisibility(selectedClip != null ? View.VISIBLE : View.GONE);
                }
            }
            showGallerySeekOverlay(false);

            if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();
            loadGalleryClips(false);
            checkUsbStatus();
        }
    }

    private boolean isLiveViewEligible(int expectedGen) {
        return expectedGen == liveRequestGeneration
                && isConnected
                && isActivityVisible
                && !isExiting
                && !isFinishing()
                && layoutLiveMode != null
                && layoutLiveMode.getVisibility() == View.VISIBLE;
    }

    private final Runnable liveKeepaliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isLiveViewEligible(liveRequestGeneration) || activeLiveStreamUrl == null) {
                return;
            }
            if (cameraClient != null) {
                cameraClient.sendKeepaliveIfIdle();
            }
            lifecycleHandler.postDelayed(this, LIVE_KEEPALIVE_INTERVAL_MS);
        }
    };

    private final Runnable liveHealthyResetRunnable = new Runnable() {
        @Override
        public void run() {
            liveAutoReconnectCount = 0;
        }
    };

    private void cancelSilentRefreshSpinner() {
        silentRefreshUiSuppressed = false;
        if (pendingSilentSpinnerRunnable != null) {
            lifecycleHandler.removeCallbacks(pendingSilentSpinnerRunnable);
            pendingSilentSpinnerRunnable = null;
        }
    }

    private void cancelLiveMaintenanceTimers() {
        lifecycleHandler.removeCallbacks(liveKeepaliveRunnable);
        lifecycleHandler.removeCallbacks(liveHealthyResetRunnable);
        cancelSilentRefreshSpinner();
        if (pendingLiveRecoveryRunnable != null) {
            lifecycleHandler.removeCallbacks(pendingLiveRecoveryRunnable);
            pendingLiveRecoveryRunnable = null;
        }
    }

    private void scheduleLiveMaintenanceTimers() {
        lifecycleHandler.removeCallbacks(liveKeepaliveRunnable);
        lifecycleHandler.postDelayed(liveKeepaliveRunnable, LIVE_KEEPALIVE_INTERVAL_MS);
        lifecycleHandler.removeCallbacks(liveHealthyResetRunnable);
        lifecycleHandler.postDelayed(liveHealthyResetRunnable, LIVE_HEALTHY_RESET_MS);
    }

    private void handleLiveStreamStall(final List<String> urls, final int index, final int streamMode,
                                       final int reqGen, final String reason) {
        if (!isLiveViewEligible(reqGen)) return;
        activeLiveStreamUrl = null;
        cancelLiveMaintenanceTimers();
        liveAutoReconnectCount++;
        final int attempt = liveAutoReconnectCount;
        final boolean silentRefresh = isSilentLiveRefresh(attempt);
        long delayMs = computeLiveRecoveryDelayMs(attempt);

        String recoverMsg = "Live feed stalled (" + reason + ") — auto-refresh #" + attempt;
        addDiagLog(recoverMsg);
        DebugLogger.log("PLAYER_RECOVER", recoverMsg + " in " + delayMs + "ms"
                + (silentRefresh ? " [silent]" : ""));
        if (!silentRefresh && tvStreamBadge != null) {
            tvStreamBadge.setText(formatLiveStreamBadge(
                    getViewModeLabel(streamMode), isViewModeMirrored(streamMode), "REFRESHING..."));
        }

        pendingLiveRecoveryRunnable = new Runnable() {
            @Override
            public void run() {
                pendingLiveRecoveryRunnable = null;
                if (!isLiveViewEligible(reqGen)) return;

                if (attempt == 1) {
                    // Tier 1: Direct, silent zero-CGI RTSP reconnect (~170ms, zero spinner flash, zero SD interruption)
                    tryPlayVlcCandidate(urls, index, streamMode, reqGen, true);
                } else if (attempt == 2 && cameraClient != null) {
                    // Tier 2: Non-disruptive CGI channel/liveview nudge + RTSP reconnect
                    int fitcamxCh = (streamMode == VIEW_OEM_DUAL) ? CameraClient.VIEW_FRONT : streamMode;
                    cameraClient.nudgeLiveEncoder(fitcamxCh, new CameraClient.Callback<List<String>>() {
                        @Override
                        public void onSuccess(final List<String> refreshedUrls) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (isLiveViewEligible(reqGen)) {
                                        tryPlayVlcCandidate(refreshedUrls, 0, streamMode, reqGen, false);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onError(Exception error) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (isLiveViewEligible(reqGen)) {
                                        tryPlayVlcCandidate(urls, 0, streamMode, reqGen, false);
                                    }
                                }
                            });
                        }
                    });
                } else {
                    // Tier 3+: Full session re-initialization
                    if (cameraClient != null) {
                        cameraClient.resetSessionState();
                    }
                    startLiveStream();
                }
            }
        };
        lifecycleHandler.postDelayed(pendingLiveRecoveryRunnable, delayMs);
    }

    private void startLiveStream() {
        if (!isActivityVisible || isExiting || isFinishing() || isFormattingSdCard) return;
        if (layoutLiveMode != null && layoutLiveMode.getVisibility() != View.VISIBLE) {
            return;
        }

        final int modeToStart = targetViewMode;

        // 1. Fullscreen Car Backup Camera (works even when Fitcamx Wi-Fi is disconnected!)
        if (modeToStart == VIEW_OEM_REAR) {
            cancelLiveMaintenanceTimers();
            activeLiveStreamUrl = null;
            isStartingLive = false;
            isConnectingLiveVlc = false;
            liveRequestGeneration++;
            if (vlcPlayerManager != null) {
                vlcPlayerManager.stopLiveStream();
            }
            currentViewMode = VIEW_OEM_REAR;
            activeStreamMode = VIEW_OEM_REAR;
            applyLiveStageLayout(VIEW_OEM_REAR);
            if (pbLiveLoading != null) pbLiveLoading.setVisibility(View.VISIBLE);
            updateViewModeUI();
            String prepOem = "Initializing Car Backup Camera feed...";
            addDiagLog(prepOem);
            if (tvDiagInfo != null) tvDiagInfo.setText(prepOem);
            if (tvStreamBadge != null) {
                tvStreamBadge.setText(formatLiveStreamBadge(
                        getViewModeLabel(VIEW_OEM_REAR), isViewModeMirrored(VIEW_OEM_REAR), "CONNECTING..."));
            }
            if (isConnected && cameraClient != null) {
                cameraClient.setRecording(true, null);
                isRecording = true;
                if (tvRecBadge != null) tvRecBadge.setVisibility(View.VISIBLE);
                styleRecordButton();
            }
            startOemBackupStream(VIEW_OEM_REAR);
            return;
        }

        // 2. Side-by-Side (Front Fitcamx + Car Backup Camera) or Standard Fitcamx View
        applyLiveStageLayout(modeToStart);
        if (modeToStart == VIEW_OEM_DUAL) {
            startOemBackupStream(VIEW_OEM_DUAL);
            if (!isConnected) {
                activeStreamMode = VIEW_OEM_DUAL;
                currentViewMode = VIEW_OEM_DUAL;
                if (pbLiveLoading != null) pbLiveLoading.setVisibility(View.GONE);
                if (tvStreamBadge != null) {
                    tvStreamBadge.setText(formatLiveStreamBadge(
                            getViewModeLabel(VIEW_OEM_DUAL), isViewModeMirrored(VIEW_OEM_DUAL), "WAITING WI-FI"));
                }
                updateViewModeUI();
                return;
            }
        } else {
            stopOemBackupStream();
            if (!isConnected) {
                updateViewModeUI();
                return;
            }
        }

        // Fast zero-frame-drop transition between VIEW_FRONT and VIEW_OEM_DUAL when Front RTSP is already playing!
        if (!isStartingLive && activeLiveStreamUrl != null
                && liveSurface != null && liveSurface.isValid()
                && ((modeToStart == VIEW_OEM_DUAL && (activeStreamMode == CameraClient.VIEW_FRONT || activeStreamMode == VIEW_OEM_DUAL))
                    || (modeToStart == CameraClient.VIEW_FRONT && activeStreamMode == VIEW_OEM_DUAL))) {
            activeStreamMode = modeToStart;
            currentViewMode = modeToStart;
            if (pbLiveLoading != null) pbLiveLoading.setVisibility(View.GONE);
            if (tvStreamBadge != null) {
                tvStreamBadge.setText(formatLiveStreamBadge(
                        getViewModeLabel(modeToStart), isViewModeMirrored(modeToStart), "LIVE"));
            }
            updateViewModeUI();
            return;
        }

        // Ignore redundant lifecycle/surface triggers when LibVLC is already connecting this exact view mode
        if (!isStartingLive && isConnectingLiveVlc && modeToStart == currentViewMode
                && liveSurface != null && liveSurface.isValid()) {
            updateViewModeUI();
            return;
        }

        if (isStartingLive) {
            DebugLogger.log("MAIN", "startLiveStream queued target mode: " + getViewModeLabel(targetViewMode));
            updateViewModeUI();
            return;
        }

        cancelLiveMaintenanceTimers();
        activeLiveStreamUrl = null;
        isConnectingLiveVlc = false;
        final int fitcamxChannel = (modeToStart == VIEW_OEM_DUAL) ? CameraClient.VIEW_FRONT : modeToStart;
        final int reqGen = ++liveRequestGeneration;
        currentViewMode = modeToStart;
        isStartingLive = true;
        pbLiveLoading.setVisibility(View.VISIBLE);
        if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();

        updateViewModeUI();
        String prepMsg = "Initializing " + getViewModeLabel(modeToStart) + " feed...";
        addDiagLog(prepMsg);
        if (tvDiagInfo != null) tvDiagInfo.setText(prepMsg);
        tvStreamBadge.setText(formatLiveStreamBadge(
                getViewModeLabel(modeToStart), isViewModeMirrored(modeToStart), "CONNECTING..."));

        cameraClient.prepareAndStartLiveView(fitcamxChannel, new CameraClient.Callback<List<String>>() {
            @Override
            public void onSuccess(final List<String> candidates) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isLiveViewEligible(reqGen)) {
                            if (reqGen == liveRequestGeneration) {
                                isStartingLive = false;
                            }
                            return;
                        }
                        isStartingLive = false;
                        isRecording = true;
                        if (tvRecBadge != null) tvRecBadge.setVisibility(View.VISIBLE);
                        styleRecordButton();

                        // If user queued a different view mode while camera was switching, switch immediately!
                        if (targetViewMode != modeToStart) {
                            DebugLogger.log("MAIN", "Target mode changed during switch (" + getViewModeLabel(modeToStart) + " -> " + getViewModeLabel(targetViewMode) + "), switching to new target...");
                            startLiveStream();
                            return;
                        }

                        if (liveSurface == null || !liveSurface.isValid()) {
                            DebugLogger.log("MAIN", "Deferring VLC attach until Live Surface is created");
                            return;
                        }

                        if (vlcPlayerManager != null && vlcPlayerManager.isAvailable()) {
                            tryPlayVlcCandidate(candidates, 0, modeToStart, reqGen);
                        } else {
                            addDiagLog("VLC engine not ready for live stream");
                        }
                    }
                });
            }

            @Override
            public void onError(final Exception error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isLiveViewEligible(reqGen)) {
                            if (reqGen == liveRequestGeneration) {
                                isStartingLive = false;
                            }
                            return;
                        }
                        isStartingLive = false;
                        Log.w(TAG, "prepareAndStartLiveView warning: " + error.getMessage());

                        if (targetViewMode != modeToStart) {
                            startLiveStream();
                            return;
                        }

                        if (liveSurface == null || !liveSurface.isValid()) {
                            return;
                        }

                        List<String> fallbacks = cameraClient.getCandidateStreamUrls(fitcamxChannel);
                        if (vlcPlayerManager != null && vlcPlayerManager.isAvailable()) {
                            tryPlayVlcCandidate(fallbacks, 0, modeToStart, reqGen);
                        }
                    }
                });
            }
        });
    }

    private void tryPlayVlcCandidate(final List<String> urls, final int index, final int streamMode, final int reqGen) {
        tryPlayVlcCandidate(urls, index, streamMode, reqGen, false);
    }

    private void tryPlayVlcCandidate(final List<String> urls, final int index, final int streamMode,
                                     final int reqGen, final boolean silentRefresh) {
        if (!isLiveViewEligible(reqGen) || liveSurface == null || !liveSurface.isValid()) {
            if (reqGen == liveRequestGeneration) {
                isConnectingLiveVlc = false;
            }
            return;
        }
        cancelSilentRefreshSpinner();
        if (urls == null || index >= urls.size()) {
            isConnectingLiveVlc = false;
            pbLiveLoading.setVisibility(View.GONE);
            String label = getViewModeLabel(streamMode) + " (NO STREAM)";
            tvStreamBadge.setText(label);
            String exhaustMsg = "All " + (urls == null ? 0 : urls.size()) + " stream candidates failed for " + getViewModeLabel(streamMode);
            addDiagLog(exhaustMsg);
            DebugLogger.log("PLAYER", exhaustMsg + " on " + cameraClient.getCameraIp());
            if (tvDiagInfo != null) {
                tvDiagInfo.setText("No active feed responding. Tap 'Export Log' to save diagnostics.");
            }
            return;
        }

        final String currentUrl = urls.get(index);
        final boolean badgeMirrored = isViewModeMirrored(streamMode);
        // In Side-by-Side (VIEW_OEM_DUAL), mirrorByViewMode[VIEW_OEM_DUAL] controls OemRearView,
        // while the left Front Fitcamx pane uses VIEW_FRONT's mirror preference.
        final boolean vlcMirrorMode = (streamMode == VIEW_OEM_DUAL)
                ? isViewModeMirrored(CameraClient.VIEW_FRONT)
                : badgeMirrored;
        String tryMsg = "Connecting [" + (index + 1) + "/" + urls.size() + "]: " + currentUrl;
        addDiagLog(tryMsg);
        DebugLogger.log("PLAYER", tryMsg + (silentRefresh ? " [silent]" : ""));
        if (!silentRefresh) {
            if (tvDiagInfo != null) {
                tvDiagInfo.setText(tryMsg);
            }
            tvStreamBadge.setText(formatLiveStreamBadge(getViewModeLabel(streamMode), badgeMirrored, "CONNECTING..."));
        } else {
            silentRefreshUiSuppressed = true;
            pendingSilentSpinnerRunnable = new Runnable() {
                @Override
                public void run() {
                    pendingSilentSpinnerRunnable = null;
                    if (!isLiveViewEligible(reqGen) || !silentRefreshUiSuppressed) return;
                    silentRefreshUiSuppressed = false;
                    if (pbLiveLoading != null) {
                        pbLiveLoading.setVisibility(View.VISIBLE);
                    }
                    if (tvStreamBadge != null) {
                        tvStreamBadge.setText(formatLiveStreamBadge(
                                getViewModeLabel(streamMode), isViewModeMirrored(streamMode), "REFRESHING..."));
                    }
                }
            };
            lifecycleHandler.postDelayed(pendingSilentSpinnerRunnable, LIVE_SILENT_REFRESH_SPINNER_DELAY_MS);
        }

        isConnectingLiveVlc = true;
        vlcPlayerManager.startLiveStream(surfaceLive, currentUrl, vlcMirrorMode, new VlcPlayerManager.LiveListener() {
            @Override
            public void onPlaying() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cancelSilentRefreshSpinner();
                        if (!isLiveViewEligible(reqGen)) {
                            if (reqGen == liveRequestGeneration) {
                                isConnectingLiveVlc = false;
                            }
                            if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();
                            return;
                        }
                        isConnectingLiveVlc = false;
                        activeStreamMode = streamMode;
                        currentViewMode = streamMode;
                        activeLiveStreamUrl = currentUrl;
                        pbLiveLoading.setVisibility(View.GONE);
                        String badge = formatLiveStreamBadge(
                                getViewModeLabel(streamMode), isViewModeMirrored(streamMode), "LIVE");
                        tvStreamBadge.setText(badge);
                        updateViewModeUI();
                        scheduleLiveMaintenanceTimers();
                        String activeMsg = "Live feed active via LibVLC: " + currentUrl;
                        addDiagLog(activeMsg);
                        DebugLogger.log("PLAYER", activeMsg);
                        if (tvDiagInfo != null) {
                            tvDiagInfo.setText("Live Feed: " + badge + " on " + cameraClient.getCameraIp());
                        }

                        // If user selected another mode while stream was connecting, proceed to next mode
                        if (targetViewMode != activeStreamMode) {
                            startLiveStream();
                        }
                    }
                });
            }

            @Override
            public void onBuffering(final float percent) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isLiveViewEligible(reqGen)) return;
                        if (percent < 100.0f) {
                            if (!silentRefreshUiSuppressed) {
                                pbLiveLoading.setVisibility(View.VISIBLE);
                            }
                        } else {
                            pbLiveLoading.setVisibility(View.GONE);
                        }
                    }
                });
            }

            @Override
            public void onStreamStalled(final String reason) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reqGen == liveRequestGeneration) {
                            isConnectingLiveVlc = false;
                        }
                        cancelSilentRefreshSpinner();
                        handleLiveStreamStall(urls, index, streamMode, reqGen, reason);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reqGen == liveRequestGeneration) {
                            isConnectingLiveVlc = false;
                        }
                        cancelSilentRefreshSpinner();
                        if (!isLiveViewEligible(reqGen)) return;
                        addDiagLog("VLC Stream failed [" + currentUrl + "]: " + message);
                        DebugLogger.log("PLAYER_ERR", "Candidate " + (index + 1) + " failed: " + message);
                        if (index + 1 < urls.size()) {
                            tryPlayVlcCandidate(urls, index + 1, streamMode, reqGen, false);
                        } else if (liveAutoReconnectCount > 0) {
                            handleLiveStreamStall(urls, 0, streamMode, reqGen, message);
                        } else {
                            tryPlayVlcCandidate(urls, index + 1, streamMode, reqGen, false);
                        }
                    }
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reqGen == liveRequestGeneration) {
                            isConnectingLiveVlc = false;
                            cancelSilentRefreshSpinner();
                            if (pbLiveLoading != null) {
                                pbLiveLoading.setVisibility(View.GONE);
                            }
                        }
                    }
                });
            }
        });
    }

    private void loadGalleryClips(final boolean forceRefresh) {
        if (!forceRefresh && !galleryCacheDirty && !allClips.isEmpty()) {
            applyFilter();
            String cacheMsg = "Gallery ready (" + allClips.size() + " items cached)";
            if (tvDiagInfo != null) {
                tvDiagInfo.setText(cacheMsg);
            }
            if (isConnected && cameraClient != null) {
                cameraClient.enterPlaybackMode(null);
            }
            return;
        }

        final String queryMsg = isConnected
                ? ("Querying SD card from " + cameraClient.getCameraIp() + " & USB Dashcam...")
                : "Scanning USB drive for rear Dashcam recordings...";
        tvClipCount.setText(queryMsg);
        if (tvDiagInfo != null) {
            tvDiagInfo.setText(queryMsg);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ClipItem> usbClips = UsbDashcamScanner.scanConnectedUsbDrives();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isConnected || cameraClient == null) {
                            allClips.clear();
                            allClips.addAll(usbClips);
                            XmlListParser.sortClipsDescending(allClips);
                            checkCameraConfiguration();
                            applyFilter();
                            if (!usbClips.isEmpty()) {
                                String usbOnlyMsg = "USB Dashcam loaded: " + usbClips.size() + " items (Fitcamx Wi-Fi offline)";
                                addDiagLog(usbOnlyMsg);
                                if (tvDiagInfo != null) tvDiagInfo.setText(usbOnlyMsg);
                            } else {
                                tvClipCount.setText("Waiting for Fitcamx Wi-Fi...");
                                tvEmptyGallery.setText("Connect to Fitcamx Wi-Fi to browse SD card footage,\nor insert a USB drive containing Dashcam/ clips.");
                                tvEmptyGallery.setVisibility(View.VISIBLE);
                                lvClips.setVisibility(View.GONE);
                            }
                            return;
                        }

                        cameraClient.fetchClipList(new CameraClient.Callback<List<ClipItem>>() {
                            @Override
                            public void onSuccess(List<ClipItem> clips) {
                                allClips.clear();
                                if (clips != null) {
                                    allClips.addAll(clips);
                                }
                                if (!usbClips.isEmpty()) {
                                    allClips.addAll(usbClips);
                                }
                                XmlListParser.sortClipsDescending(allClips);
                                galleryCacheDirty = false;
                                checkCameraConfiguration();
                                applyFilter();

                                String resMsg = usbClips.isEmpty()
                                        ? ("SD card loaded: " + allClips.size() + " items")
                                        : ("Loaded " + allClips.size() + " items (" + usbClips.size() + " USB backup)");
                                addDiagLog(resMsg);
                                if (tvDiagInfo != null) {
                                    tvDiagInfo.setText(resMsg);
                                }
                            }

                            @Override
                            public void onError(Exception error) {
                                String errMsg = error.getMessage();
                                addDiagLog("SD card error: " + errMsg);
                                DebugLogger.log("SD_VIEW", "SD card query failed: " + errMsg);
                                if (!usbClips.isEmpty()) {
                                    allClips.clear();
                                    allClips.addAll(usbClips);
                                    XmlListParser.sortClipsDescending(allClips);
                                    checkCameraConfiguration();
                                    applyFilter();
                                    if (tvDiagInfo != null) {
                                        tvDiagInfo.setText("USB Dashcam: " + usbClips.size() + " items (SD error: " + errMsg + ")");
                                    }
                                } else {
                                    tvClipCount.setText("SD Error: " + errMsg);
                                    tvEmptyGallery.setText("Could not read SD card.\n\n" + errMsg + "\n\nTip: Tap 'Export Log to USB' to save full diagnostic log to flash drive.");
                                    tvEmptyGallery.setVisibility(View.VISIBLE);
                                    lvClips.setVisibility(View.GONE);
                                    if (tvDiagInfo != null) {
                                        tvDiagInfo.setText("SD read failed: " + errMsg);
                                    }
                                }
                            }
                        });
                    }
                });
            }
        }, "FitcamxUsbGalleryScan").start();
    }

    private void updatePlaybackRateButton(float rate) {
        if (btnPlaybackRate == null) return;
        if (Math.abs(rate - 0.33f) < 0.05f) {
            btnPlaybackRate.setText("0.33x");
        } else if (Math.abs(rate - 0.5f) < 0.05f) {
            btnPlaybackRate.setText("0.5x");
        } else {
            btnPlaybackRate.setText(String.format(java.util.Locale.US, "%.1fx", rate));
        }
    }

    private boolean shouldAutoHideGalleryOverlay() {
        if (selectedClip == null || isUserSeeking || galleryEnded) return false;
        if (usbDownloader != null && usbDownloader.isDownloading()) return false;
        if (selectedClip.isPhoto) {
            return currentPhotoBitmap != null;
        }
        return vlcPlayerManager != null && vlcPlayerManager.isGalleryPlaying();
    }

    private final Runnable hideSeekOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (shouldAutoHideGalleryOverlay()) {
                if (layoutGalleryTopOverlay != null) {
                    layoutGalleryTopOverlay.setVisibility(View.GONE);
                }
                if (layoutGallerySeekOverlay != null) {
                    layoutGallerySeekOverlay.setVisibility(View.GONE);
                }
            }
        }
    };

    private void showGallerySeekOverlay(boolean autoHide) {
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
        if (layoutGalleryTopOverlay != null) {
            layoutGalleryTopOverlay.setVisibility(View.VISIBLE);
        }
        if (layoutGallerySeekOverlay != null) {
            layoutGallerySeekOverlay.setVisibility(View.VISIBLE);
        }
        if (autoHide && (usbDownloader == null || !usbDownloader.isDownloading())) {
            lifecycleHandler.postDelayed(hideSeekOverlayRunnable, SEEK_OVERLAY_AUTO_HIDE_MS);
        }
    }

    private void hideGallerySeekOverlay() {
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
        if (layoutGalleryTopOverlay != null) {
            layoutGalleryTopOverlay.setVisibility(View.GONE);
        }
        if (layoutGallerySeekOverlay != null) {
            layoutGallerySeekOverlay.setVisibility(View.GONE);
        }
    }

    private void clearPhotoPreview() {
        if (ivGalleryPhoto != null) {
            ivGalleryPhoto.setImageDrawable(null);
            ivGalleryPhoto.setVisibility(View.GONE);
        }
        if (currentPhotoBitmap != null) {
            if (!currentPhotoBitmap.isRecycled()) {
                currentPhotoBitmap.recycle();
            }
            currentPhotoBitmap = null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int maxWidth, int maxHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > maxHeight || width > maxWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= maxHeight || (halfWidth / inSampleSize) >= maxWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static byte[] readPhotoBytes(File localFile, String httpUrl) throws Exception {
        InputStream in = null;
        HttpURLConnection conn = null;
        try {
            if (localFile != null && localFile.exists() && localFile.length() > 0) {
                in = new FileInputStream(localFile);
            } else {
                conn = (HttpURLConnection) new URL(httpUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code);
                }
                in = conn.getInputStream();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_PHOTO_PREVIEW_BYTES) {
                    throw new Exception("Photo exceeds 24 MB preview limit");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }

    private void showStillPhotoPreview(final ClipItem photo, final int loadGen) {
        galleryEnded = false;
        galleryHasVout = false;
        lastGalleryBufferingMs = 0L;
        currentGalleryDurationMs = 0L;
        if (layoutSeekBarRow != null) {
            layoutSeekBarRow.setVisibility(View.GONE);
        }
        showGallerySeekOverlay(false);
        if (vlcPlayerManager != null) {
            vlcPlayerManager.stopGalleryClip();
        }
        if (surfaceGallery != null) {
            surfaceGallery.setVisibility(View.GONE);
        }
        clearPhotoPreview();
        if (ivGalleryPhoto != null) {
            ivGalleryPhoto.setVisibility(View.VISIBLE);
        }
        if (pbGalleryLoading != null) {
            pbGalleryLoading.setVisibility(View.VISIBLE);
        }
        if (btnPlaybackRate != null) {
            btnPlaybackRate.setEnabled(false);
            btnPlaybackRate.setText("--");
        }
        if (btnPlayClip != null) {
            btnPlayClip.setText("Photo");
        }

        File directOemLocal = (photo.localFilePath != null) ? new File(photo.localFilePath) : null;
        final File verifiedLocal;
        if (directOemLocal != null && directOemLocal.exists() && directOemLocal.length() > 0) {
            verifiedLocal = directOemLocal;
        } else {
            File usbFolder = UsbDownloader.getUsbDestinationFolder(false);
            File candidateLocal = (usbFolder != null) ? new File(usbFolder, photo.filename) : null;
            File partFile = (usbFolder != null) ? new File(usbFolder, photo.filename + ".part") : null;
            verifiedLocal = (candidateLocal != null && candidateLocal.exists()
                    && candidateLocal.length() > 0 && (partFile == null || !partFile.exists()))
                    ? candidateLocal : null;
        }
        boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
        final String photoUrl = photo.getHttpUrl(cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort());

        addDiagLog("Loading photo " + (verifiedLocal != null ? "from USB: " + verifiedLocal.getName() : "via Wi-Fi: " + photoUrl));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = readPhotoBytes(verifiedLocal, photoUrl);
                    if (data == null || data.length == 0) {
                        throw new Exception("Empty photo payload");
                    }

                    BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
                    boundsOpts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);
                    final int origW = boundsOpts.outWidth;
                    final int origH = boundsOpts.outHeight;

                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inSampleSize = calculateInSampleSize(boundsOpts, 1920, 1080);
                    decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap decoded;
                    try {
                        decoded = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOpts);
                    } catch (OutOfMemoryError oom) {
                        decodeOpts.inSampleSize = Math.max(2, decodeOpts.inSampleSize * 2);
                        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
                        decoded = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOpts);
                    }
                    if (decoded == null) {
                        throw new Exception("Unsupported image format");
                    }

                    final Bitmap finalBmp = decoded;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (loadGen != galleryLoadGeneration || selectedClip != photo || isFinishing()) {
                                finalBmp.recycle();
                                return;
                            }
                            if (pbGalleryLoading != null) {
                                pbGalleryLoading.setVisibility(View.GONE);
                            }
                            if (ivGalleryPhoto != null) {
                                ivGalleryPhoto.setVisibility(View.VISIBLE);
                                ivGalleryPhoto.setImageBitmap(finalBmp);
                            }
                            if (currentPhotoBitmap != null && currentPhotoBitmap != finalBmp && !currentPhotoBitmap.isRecycled()) {
                                currentPhotoBitmap.recycle();
                            }
                            currentPhotoBitmap = finalBmp;
                            if (origW > 0 && origH > 0) {
                                tvClipDetails.setText("Photo: " + origW + "x" + origH + " \u00B7 "
                                        + photo.getFormattedSize() + " \u00B7 " + photo.timeStr);
                            } else {
                                tvClipDetails.setText("Photo \u00B7 " + photo.getFormattedSize() + " \u00B7 " + photo.timeStr);
                            }
                            showGallerySeekOverlay(true);
                            addDiagLog("Displayed still photo: " + photo.filename + " (" + origW + "x" + origH + ")");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (loadGen != galleryLoadGeneration || selectedClip != photo || isFinishing()) {
                                return;
                            }
                            if (pbGalleryLoading != null) {
                                pbGalleryLoading.setVisibility(View.GONE);
                            }
                            if (btnPlayClip != null) {
                                btnPlayClip.setText("Retry");
                            }
                            showGallerySeekOverlay(false);
                            String msg = "Photo preview error: " + e.getMessage();
                            addDiagLog(msg);
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "FitcamxPhotoLoader").start();
    }

    private void playGalleryPreview(final ClipItem clip) {
        if (clip == null) return;
        final int loadGen = ++galleryLoadGeneration;
        if (clip.isPhoto) {
            showStillPhotoPreview(clip, loadGen);
        } else {
            startGalleryPreviewAt(clip, -1.0f, true, loadGen);
        }
    }

    private void startGalleryPreviewAt(final ClipItem clip, final float startSeekPos, final boolean resetRate) {
        startGalleryPreviewAt(clip, startSeekPos, resetRate, ++galleryLoadGeneration);
    }

    private void startGalleryPreviewAt(final ClipItem clip, final float startSeekPos, final boolean resetRate, final int loadGen) {
        if (clip == null) return;
        clearPhotoPreview();
        galleryEnded = false;
        galleryHasVout = false;
        lastGalleryBufferingMs = 0L;
        currentGalleryDurationMs = 0L;
        lastKnownGalleryPosition = (startSeekPos >= 0.0f) ? startSeekPos : 0.0f;
        if (layoutSeekBarRow != null) {
            layoutSeekBarRow.setVisibility(View.VISIBLE);
        }
        if (sbGallerySeek != null) {
            sbGallerySeek.setProgress(Math.round(lastKnownGalleryPosition * 1000.0f));
            sbGallerySeek.setEnabled(false);
        }
        if (tvSeekPosition != null) {
            int initPct = Math.min(100, Math.max(0, Math.round(lastKnownGalleryPosition * 100.0f)));
            tvSeekPosition.setText(initPct + "%");
        }
        showGallerySeekOverlay(false);
        pbGalleryLoading.setVisibility(View.VISIBLE);
        if (surfaceGallery != null) {
            surfaceGallery.setVisibility(View.VISIBLE);
        }
        if (vlcPlayerManager != null) vlcPlayerManager.stopGalleryClip();

        // Check direct USB Dashcam clip path first, then Fitcamx DashcamFootage USB copy
        File directOemFile = (clip.localFilePath != null) ? new File(clip.localFilePath) : null;
        final String playUri;
        boolean verifiedLocalUsb = false;

        if (directOemFile != null && directOemFile.exists() && directOemFile.length() > 0) {
            verifiedLocalUsb = true;
            playUri = directOemFile.getAbsolutePath();
            addDiagLog("Previewing USB Backup clip: " + directOemFile.getName() + " (" + (directOemFile.length() / (1024 * 1024)) + "MB)");
        } else {
            File usbFolder = UsbDownloader.getUsbDestinationFolder(false);
            File localFile = (usbFolder != null) ? new File(usbFolder, clip.filename) : null;
            File partFile = (usbFolder != null) ? new File(usbFolder, clip.filename + ".part") : null;

            if (localFile != null && localFile.exists() && localFile.length() > 0
                    && (partFile == null || !partFile.exists())) {
                boolean sizeMatches = (clip.sizeBytes <= 0)
                        || (Math.abs(localFile.length() - clip.sizeBytes) <= 65536L);
                if (sizeMatches) {
                    verifiedLocalUsb = true;
                } else {
                    DebugLogger.log("USB_VERIFY", "Deleting incomplete local USB file " + localFile.getName()
                            + " (local=" + localFile.length() + ", expected=" + clip.sizeBytes + ")");
                    localFile.delete();
                }
            }

            if (verifiedLocalUsb) {
                playUri = localFile.getAbsolutePath();
                addDiagLog("Previewing from local USB (" + (clip.isFront ? "FRONT" : "REAR") + "): " + localFile.getName() + " (" + (localFile.length() / (1024 * 1024)) + "MB)");
            } else {
                boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
                playUri = clip.getHttpUrl(cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort());
                addDiagLog("Previewing clip via camera Wi-Fi (" + (clip.isFront ? "FRONT" : "REAR") + "): " + playUri);
            }
        }

        if (vlcPlayerManager == null || !vlcPlayerManager.isAvailable()) {
            pbGalleryLoading.setVisibility(View.GONE);
            Toast.makeText(MainActivity.this, "VLC Player engine not available", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isFront = clip.isFront;
        final boolean isLocalUsb = verifiedLocalUsb;

        // Rear clips are ALWAYS 1080p AVC
        if (!isFront) {
            startGalleryPlaybackInternal(clip, playUri, false, false, isLocalUsb, startSeekPos, resetRate, loadGen);
            return;
        }

        // For Front clips: check cached codec first
        if (clip.isHevc != null) {
            boolean is4kFront = clip.isHevc;
            startGalleryPlaybackInternal(clip, playUri, true, is4kFront, isLocalUsb, startSeekPos, resetRate, loadGen);
            return;
        }

        // Sniff media header in background thread (<20ms) to detect whether this specific file is HEVC or AVC
        new Thread(new Runnable() {
            @Override
            public void run() {
                int codec = TsHeaderSniffer.sniffCodec(playUri);
                final boolean is4kFront;
                if (codec == TsHeaderSniffer.CODEC_HEVC) {
                    clip.isHevc = true;
                    is4kFront = true;
                    DebugLogger.log("SNIFFER", "Clip " + clip.filename + " sniffed as 4K/2K HEVC");
                } else if (codec == TsHeaderSniffer.CODEC_AVC) {
                    clip.isHevc = false;
                    is4kFront = false;
                    DebugLogger.log("SNIFFER", "Clip " + clip.filename + " sniffed as 1080p AVC");
                } else {
                    // Fallback to active camera mode if sniffer cannot read header
                    is4kFront = !is1080pMode;
                    clip.isHevc = is4kFront;
                    DebugLogger.log("SNIFFER", "Clip " + clip.filename + " sniffer uncertain; falling back to camera mode: " + (is4kFront ? "4K HEVC" : "1080p AVC"));
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen == galleryLoadGeneration && selectedClip == clip) {
                            startGalleryPlaybackInternal(clip, playUri, true, is4kFront, isLocalUsb, startSeekPos, resetRate, loadGen);
                        }
                    }
                });
            }
        }, "FitcamxSniffer").start();
    }

    private void startGalleryPlaybackInternal(final ClipItem clip, final String playUri, final boolean isFront,
                                              final boolean is4kFront, final boolean isLocalUsb,
                                              final float startSeekPos, final boolean resetRate, final int loadGen) {
        final boolean isHevcClip = isFront && is4kFront;
        currentGalleryClipIsHevc = isHevcClip;
        final float chosenRate = resetRate
                ? VlcPlayerManager.getDefaultGalleryRate(isHevcClip)
                : VlcPlayerManager.sanitizeGalleryRate(vlcPlayerManager.getGalleryRate(), isHevcClip);
        if (btnPlaybackRate != null) {
            btnPlaybackRate.setEnabled(true);
        }
        updatePlaybackRateButton(chosenRate);

        vlcPlayerManager.playGalleryClip(surfaceGallery, playUri, isFront, is4kFront, chosenRate, startSeekPos, new VlcPlayerManager.GalleryListener() {
            @Override
            public void onPlaying(final boolean seekable, final long totalLengthMs) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        galleryEnded = false;
                        currentGalleryDurationMs = totalLengthMs;
                        pbGalleryLoading.setVisibility(View.GONE);
                        btnPlayClip.setText("Pause");
                        if (sbGallerySeek != null) {
                            sbGallerySeek.setEnabled(true);
                        }
                        showGallerySeekOverlay(true);
                        float activeRate = vlcPlayerManager.getGalleryRate();
                        updatePlaybackRateButton(activeRate);
                        addDiagLog("VLC playing " + (isHevcClip ? "front 4K HEVC" : (isFront ? "front 1080p" : "rear 1080p"))
                                + " (" + activeRate + "x)");
                    }
                });
            }

            @Override
            public void onVideoOutputStarted() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        galleryHasVout = true;
                        lastGalleryBufferingMs = 0L;
                        pbGalleryLoading.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onBuffering(final float percent) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        if (percent >= 100.0f) {
                            lastGalleryBufferingMs = 0L;
                            pbGalleryLoading.setVisibility(View.GONE);
                        } else {
                            lastGalleryBufferingMs = SystemClock.uptimeMillis();
                            if (VlcPlayerManager.shouldShowSpinnerOnBuffering(percent, galleryHasVout)) {
                                pbGalleryLoading.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                });
            }

            @Override
            public void onProgressChanged(final float position, final long currentTimeMs, final long totalLengthMs, final boolean seekable) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        currentGalleryDurationMs = totalLengthMs;
                        float effectivePos = position;
                        if (effectivePos <= 0.0f && totalLengthMs > 0 && currentTimeMs > 0) {
                            effectivePos = Math.min(1.0f, (float) currentTimeMs / (float) totalLengthMs);
                        }
                        if (effectivePos >= 0.0f && effectivePos <= 1.0f) {
                            lastKnownGalleryPosition = effectivePos;
                        }
                        boolean hasActiveVideo = galleryHasVout || effectivePos > 0.02f || currentTimeMs > 250L;
                        if (VlcPlayerManager.shouldClearSpinnerOnProgress(
                                pbGalleryLoading.getVisibility() == View.VISIBLE,
                                hasActiveVideo,
                                lastGalleryBufferingMs,
                                SystemClock.uptimeMillis())) {
                            lastGalleryBufferingMs = 0L;
                            pbGalleryLoading.setVisibility(View.GONE);
                        }
                        if (sbGallerySeek != null) {
                            if (!sbGallerySeek.isEnabled()) {
                                sbGallerySeek.setEnabled(true);
                            }
                            if (!isUserSeeking && effectivePos >= 0.0f && effectivePos <= 1.0f) {
                                int prog = Math.min(1000, Math.max(0, Math.round(effectivePos * 1000.0f)));
                                sbGallerySeek.setProgress(prog);
                            }
                        }
                        if (selectedClip != null && !isUserSeeking) {
                            if (totalLengthMs > 0 && currentTimeMs >= 0 && currentTimeMs <= totalLengthMs + 5000L) {
                                long curSec = currentTimeMs / 1000L;
                                long totSec = totalLengthMs / 1000L;
                                String timeLabel = String.format(java.util.Locale.US, "%02d:%02d / %02d:%02d",
                                        curSec / 60, curSec % 60, totSec / 60, totSec % 60);
                                if (tvSeekPosition != null) {
                                    tvSeekPosition.setText(timeLabel);
                                }
                                tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " \u00B7 " + timeLabel);
                            } else {
                                int pct = Math.min(100, Math.max(0, Math.round(lastKnownGalleryPosition * 100.0f)));
                                if (tvSeekPosition != null) {
                                    tvSeekPosition.setText(pct + "%");
                                }
                                tvClipDetails.setText(String.format(java.util.Locale.US, "Size: %s \u00B7 Pos: %d%% \u00B7 %s",
                                        selectedClip.getFormattedSize(), pct, selectedClip.timeStr));
                            }
                        }
                    }
                });
            }

            @Override
            public void onEndReached() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        galleryEnded = true;
                        lastGalleryBufferingMs = 0L;
                        pbGalleryLoading.setVisibility(View.GONE);
                        lastKnownGalleryPosition = 1.0f;
                        if (sbGallerySeek != null && !isUserSeeking) {
                            sbGallerySeek.setProgress(1000);
                        }
                        if (tvSeekPosition != null && !isUserSeeking) {
                            if (currentGalleryDurationMs > 0) {
                                long totSec = currentGalleryDurationMs / 1000L;
                                tvSeekPosition.setText(String.format(java.util.Locale.US, "%02d:%02d / %02d:%02d",
                                        totSec / 60, totSec % 60, totSec / 60, totSec % 60));
                            } else {
                                tvSeekPosition.setText("100%");
                            }
                        }
                        showGallerySeekOverlay(false);
                        btnPlayClip.setText("Replay");
                        addDiagLog("Clip preview finished");
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (loadGen != galleryLoadGeneration || selectedClip != clip) return;
                        lastGalleryBufferingMs = 0L;
                        pbGalleryLoading.setVisibility(View.GONE);
                        showGallerySeekOverlay(false);
                        btnPlayClip.setText("Play");
                        String err = "VLC playback error: " + message;
                        addDiagLog(err);
                        DebugLogger.log("GALLERY_ERR", err);
                        Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void checkUsbStatus() {
        ThemeManager.Palette p = activePalette;
        File usbDir = UsbDownloader.getUsbDestinationFolder(false);
        boolean downloading = (usbDownloader != null && usbDownloader.isDownloading());
        if (downloading) {
            btnDownloadToUsb.setText("Cancel");
            ThemeManager.stylePillButton(this, btnDownloadToUsb, p.errorContainer, p.onErrorContainer, 0, 0, true);
            btnDownloadToUsb.setEnabled(true);
        } else {
            btnDownloadToUsb.setText(R.string.btn_download_usb);
            ThemeManager.stylePillButton(this, btnDownloadToUsb, p.primary, p.onPrimary, 0, 0, true);
        }

        if (usbDir != null) {
            tvUsbStatus.setText("USB: Ready");
            tvUsbStatus.setTextColor(ThemeManager.OVERLAY_POSITIVE);
            if (!downloading) {
                btnDownloadToUsb.setEnabled(true);
            }
        } else {
            tvUsbStatus.setText("USB: Not detected");
            tvUsbStatus.setTextColor(ThemeManager.OVERLAY_WARNING);
            if (!downloading) {
                btnDownloadToUsb.setEnabled(false);
            }
        }
    }

    private void startUsbDownload(final ClipItem clip) {
        if (usbDownloader != null && usbDownloader.isDownloading()) {
            Toast.makeText(this, "A USB transfer is already in progress", Toast.LENGTH_SHORT).show();
            return;
        }
        pbDownloadProgress.setProgress(0);
        pbDownloadProgress.setVisibility(View.VISIBLE);
        tvDownloadStats.setText("Starting...");
        tvDownloadStats.setVisibility(View.VISIBLE);
        btnDownloadToUsb.setText("Cancel");
        ThemeManager.stylePillButton(this, btnDownloadToUsb, activePalette.errorContainer, activePalette.onErrorContainer, 0, 0, true);
        btnDownloadToUsb.setEnabled(true);
        showGallerySeekOverlay(false);

        boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
        usbDownloader.downloadClip(clip, cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort(), new UsbDownloader.DownloadCallback() {
            @Override
            public void onProgress(final int percent, final double speedMbPerSec, final long downloadedBytes, final long totalBytes) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbDownloadProgress.setProgress(percent);
                        tvDownloadStats.setText(String.format(java.util.Locale.US, "%d%% \u00B7 %.2f MB/s", percent, speedMbPerSec));
                    }
                });
            }

            @Override
            public void onComplete(final File destinationFile) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbDownloadProgress.setVisibility(View.GONE);
                        tvDownloadStats.setVisibility(View.GONE);
                        checkUsbStatus();
                        showGallerySeekOverlay(shouldAutoHideGalleryOverlay());
                        addDiagLog("Saved to USB: " + destinationFile.getName());
                        String kind = clip.isPhoto ? "Photo" : "Clip";
                        Toast.makeText(MainActivity.this, kind + " saved to USB:\n" + destinationFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(final Exception error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbDownloadProgress.setVisibility(View.GONE);
                        tvDownloadStats.setVisibility(View.GONE);
                        checkUsbStatus();
                        showGallerySeekOverlay(shouldAutoHideGalleryOverlay());
                        addDiagLog("USB transfer notice: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "USB Transfer: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showDiagnosticDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Wi-Fi & Network ===\n");
        sb.append("SSID: ").append(currentSsid.isEmpty() ? "None" : currentSsid).append("\n");
        sb.append("Details: ").append(currentDiagDetails.isEmpty() ? "Pending DHCP" : currentDiagDetails).append("\n\n");

        sb.append("=== Dashcam Hardware ===\n");
        sb.append("Target IP: ").append(cameraClient.getCameraIp()).append("\n");
        sb.append("Chipset: ").append(cameraClient.getSocType().displayName).append("\n");
        sb.append("HTTP Port: ").append(cameraClient.getHttpPort()).append("\n\n");

        sb.append("=== Stream Candidate Priority (" + getViewModeLabel() + ") ===\n");
        List<String> frontUrls = cameraClient.getCandidateStreamUrls(currentViewMode);
        for (int i = 0; i < Math.min(4, frontUrls.size()); i++) {
            sb.append(" [").append(i + 1).append("] ").append(frontUrls.get(i)).append("\n");
        }
        sb.append("\n=== Recent Events ===\n");
        for (int i = 0; i < Math.min(10, diagHistory.size()); i++) {
            sb.append(diagHistory.get(i)).append("\n");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("FITCAMX Diagnostics");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Export Log to USB", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                triggerUsbLogExport(true);
            }
        });
        builder.setNegativeButton("Wi-Fi Setup / SSID", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAvailableNetworksDialog("Select your dashcam's Wi-Fi network to enter or update its password.");
            }
        });
        builder.show();
    }

    private void showFullLogViewerDialog() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText("Collecting system diagnostics...");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(0xFFE1E6EE);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(16, 16, 16, 16);
        sv.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("FITCAMX Diagnostic Log Viewer");
        builder.setView(sv);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Save to USB", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                triggerUsbLogExport(true);
            }
        });
        builder.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = DebugLogger.dumpSystemDiagnostics(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            tv.setText(report);
                        }
                    }
                });
            }
        }, "FitcamxLogViewer").start();
    }

    public void triggerUsbLogExport(final boolean userInitiated) {
        if (!UsbDownloader.isUsbDriveAttached()) {
            if (userInitiated) {
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                b.setTitle("No USB Drive Detected");
                b.setMessage("No writable USB flash drive was found.\n\nPlease plug a USB flash drive into the vehicle's USB port before exporting logs.");
                b.setPositiveButton("OK", null);
                b.show();
            }
            return;
        }

        if (userInitiated) {
            Toast.makeText(this, "Exporting diagnostic log to USB...", Toast.LENGTH_SHORT).show();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DebugLogger.ExportResult result = DebugLogger.exportToUsb(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tvDiagInfo != null) {
                            tvDiagInfo.setText(result.success ? "Log exported to USB" : "USB log export failed");
                        }
                        if (userInitiated) {
                            AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                            b.setTitle(result.success ? "Debug Log Exported" : "Export Failed");
                            b.setMessage(result.summary);
                            b.setPositiveButton("OK", null);
                            if (result.success) {
                                b.setNeutralButton("View Log on Screen", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        showFullLogViewerDialog();
                                    }
                                });
                            }
                            b.show();
                        }
                    }
                });
            }
        }).start();
    }

    private void dismissActiveWifiDialog() {
        if (activeWifiDialog != null) {
            try {
                if (activeWifiDialog.isShowing()) {
                    activeWifiDialog.dismiss();
                }
            } catch (Exception ignored) {}
            activeWifiDialog = null;
        }
    }

    private String getCapabilitiesForSsid(String ssid) {
        if (ssid == null || ssid.isEmpty()) return null;
        if (wifiBridge != null) {
            ScanResult sr = wifiBridge.findScanResultBySsid(ssid);
            if (sr != null) return sr.capabilities;
        }
        String target = WifiBridge.cleanSsid(ssid);
        for (ScanResult r : cachedScanResults) {
            if (r != null && r.SSID != null && WifiBridge.cleanSsid(r.SSID).equalsIgnoreCase(target)) {
                return r.capabilities;
            }
        }
        return null;
    }

    private ScanResult findBestScanResult(String ssid) {
        String target = WifiBridge.cleanSsid(ssid);
        ScanResult best = null;
        for (ScanResult r : cachedScanResults) {
            if (r != null && r.SSID != null && WifiBridge.cleanSsid(r.SSID).equalsIgnoreCase(target)) {
                if (best == null || r.level > best.level) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void openManualWifiSetup() {
        if (lastFailedWifiSsid != null && !lastFailedWifiSsid.isEmpty()) {
            showWifiPasswordDialog(lastFailedWifiSsid, getCapabilitiesForSsid(lastFailedWifiSsid),
                    lastWifiFailureReason, true);
        } else {
            showAvailableNetworksDialog(lastWifiFailureReason);
        }
    }

    private void showAvailableNetworksDialog() {
        showAvailableNetworksDialog(null);
    }

    private void showAvailableNetworksDialog(final String reasonNote) {
        dismissActiveWifiDialog();

        List<String> rawSsids = new ArrayList<String>(cachedScanResults.size());
        for (ScanResult r : cachedScanResults) {
            if (r != null && r.SSID != null && !r.SSID.trim().isEmpty()) {
                rawSsids.add(r.SSID);
            }
        }

        String remembered = (wifiBridge != null) ? wifiBridge.getKnownSsid() : "";
        final List<String> orderedSsids = WifiBridge.prioritizeSsidsForPicker(
                rawSsids, lastFailedWifiSsid, remembered);

        final CharSequence[] items = new CharSequence[orderedSsids.size() + 1];
        for (int i = 0; i < orderedSsids.size(); i++) {
            String ssid = orderedSsids.get(i);
            ScanResult sr = findBestScanResult(ssid);
            int secMode = WifiBridge.classifySecurityMode(sr != null ? sr.capabilities : null);
            String secLabel = (secMode == WifiBridge.SEC_OPEN) ? "Open" : "WPA/WPA2";
            String rssiPart = (sr != null) ? (sr.level + " dBm, ") : "";
            boolean isPinned = WifiBridge.isMatchingSsid(ssid)
                    || (!remembered.isEmpty() && ssid.equalsIgnoreCase(remembered))
                    || (lastFailedWifiSsid != null && ssid.equalsIgnoreCase(lastFailedWifiSsid));
            items[i] = (isPinned ? "\u2605 " : "") + ssid + "  (" + rssiPart + secLabel + ")";
        }
        items[orderedSsids.size()] = "[Enter Hidden / Custom SSID...]";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (orderedSsids.isEmpty()) {
            builder.setTitle("Select Dashcam Wi-Fi (Scanning...)");
        } else {
            builder.setTitle("Select Dashcam Wi-Fi (" + orderedSsids.size() + " visible)");
        }
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < orderedSsids.size()) {
                    String chosenSsid = orderedSsids.get(which);
                    ScanResult sr = findBestScanResult(chosenSsid);
                    String caps = (sr != null) ? sr.capabilities : null;
                    if (WifiBridge.classifySecurityMode(caps) == WifiBridge.SEC_OPEN) {
                        manualWifiSetupPending = false;
                        styleRescanButton();
                        Toast.makeText(MainActivity.this, "Connecting to " + chosenSsid + "...", Toast.LENGTH_SHORT).show();
                        wifiBridge.connectToSpecificSsid(chosenSsid, "");
                    } else {
                        boolean failedThisSsid = (lastFailedWifiSsid != null && chosenSsid.equalsIgnoreCase(lastFailedWifiSsid));
                        showWifiPasswordDialog(chosenSsid, caps, failedThisSsid ? reasonNote : null, failedThisSsid);
                    }
                } else {
                    showCustomSsidEntryDialog();
                }
            }
        });
        builder.setNeutralButton("Rescan Networks", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                manualWifiSetupPending = false;
                styleRescanButton();
                Toast.makeText(MainActivity.this, "Rescanning Wi-Fi networks...", Toast.LENGTH_SHORT).show();
                wifiBridge.triggerManualRescan();
            }
        });
        builder.setNegativeButton("Close", null);
        activeWifiDialog = builder.create();
        activeWifiDialog.show();
    }

    private void showCustomSsidEntryDialog() {
        dismissActiveWifiDialog();
        int pad = ThemeManager.dp(this, 16f);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, pad / 2);

        TextView tvPrompt = new TextView(this);
        tvPrompt.setText("Enter your dashcam's exact Wi-Fi network name (SSID):");
        tvPrompt.setTextSize(14f);
        container.addView(tvPrompt);

        final EditText etSsid = new EditText(this);
        etSsid.setSingleLine(true);
        etSsid.setHint("e.g. CAR_CAM_xxxx or custom SSID");
        etSsid.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etSsid.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        container.addView(etSsid);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Enter Custom Camera SSID");
        b.setView(container);
        b.setPositiveButton("Next", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String typed = WifiBridge.cleanSsid(etSsid.getText().toString().trim());
                if (typed.isEmpty()) {
                    Toast.makeText(MainActivity.this, "SSID cannot be empty", Toast.LENGTH_SHORT).show();
                    showCustomSsidEntryDialog();
                    return;
                }
                showWifiPasswordDialog(typed, "WPA2-PSK-CCMP", null, false);
            }
        });
        b.setNeutralButton("Back to List", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAvailableNetworksDialog(null);
            }
        });
        b.setNegativeButton("Cancel", null);
        activeWifiDialog = b.create();
        activeWifiDialog.show();
    }

    private void showWifiPasswordDialog(final String ssid, final String capabilities,
                                        final String reasonNote, final boolean defaultJustFailed) {
        dismissActiveWifiDialog();
        final String cleanSsid = WifiBridge.cleanSsid(ssid);
        final int secMode = WifiBridge.classifySecurityMode(capabilities);

        int pad = ThemeManager.dp(this, 16f);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, pad / 4);

        TextView tvInfo = new TextView(this);
        StringBuilder msg = new StringBuilder();
        if (reasonNote != null && !reasonNote.isEmpty()) {
            msg.append(reasonNote).append("\n\n");
        }
        if (defaultJustFailed) {
            msg.append("Enter the Wi-Fi password for \"").append(cleanSsid).append("\":");
        } else {
            msg.append("Enter the Wi-Fi password for \"").append(cleanSsid)
               .append("\"\n(or leave blank to use default ").append(WifiBridge.DEFAULT_PASSWORD).append("):");
        }
        tvInfo.setText(msg.toString());
        tvInfo.setTextSize(13f);
        container.addView(tvInfo);

        final EditText etPassword = new EditText(this);
        etPassword.setSingleLine(true);
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (wifiBridge != null && wifiBridge.hasCustomPasswordForSsid(cleanSsid) && !defaultJustFailed) {
            String saved = wifiBridge.getSavedPasswordForSsid(cleanSsid);
            etPassword.setText(saved);
            etPassword.setSelection(saved.length());
        } else if (defaultJustFailed) {
            etPassword.setHint("Enter camera Wi-Fi password (8+ chars)");
        } else {
            etPassword.setHint("Default: " + WifiBridge.DEFAULT_PASSWORD);
        }
        container.addView(etPassword);

        CheckBox cbShow = new CheckBox(this);
        cbShow.setText("Show password");
        cbShow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                int sel = etPassword.getSelectionEnd();
                if (isChecked) {
                    etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                } else {
                    etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                }
                if (sel >= 0 && sel <= etPassword.getText().length()) {
                    etPassword.setSelection(sel);
                }
            }
        });
        container.addView(cbShow);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(defaultJustFailed ? "Camera Wi-Fi Password Required" : ("Connect to " + cleanSsid));
        builder.setView(container);
        builder.setPositiveButton("Connect", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String entered = etPassword.getText().toString();
                if (entered.isEmpty() && !defaultJustFailed) {
                    entered = WifiBridge.DEFAULT_PASSWORD;
                }
                if (!WifiBridge.isValidWifiPassword(entered, secMode)) {
                    Toast.makeText(MainActivity.this,
                            "Wi-Fi WPA password must be 8 to 63 characters.", Toast.LENGTH_LONG).show();
                    showWifiPasswordDialog(cleanSsid, capabilities, reasonNote, defaultJustFailed);
                    return;
                }
                manualWifiSetupPending = false;
                styleRescanButton();
                Toast.makeText(MainActivity.this, "Connecting to " + cleanSsid + "...", Toast.LENGTH_SHORT).show();
                wifiBridge.connectToSpecificSsid(cleanSsid, entered);
            }
        });
        builder.setNeutralButton("Select SSID...", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAvailableNetworksDialog(null);
            }
        });
        builder.setNegativeButton("Cancel", null);
        activeWifiDialog = builder.create();
        activeWifiDialog.show();
    }

    private void showExitDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Exit FITCAMX");
        builder.setMessage("Select an exit option:\n\n[Exit & Reboot]\nRestores Wi-Fi and reboots the head unit.\n\n[Exit]\nRestores Wi-Fi and returns to head unit menus.");
        builder.setPositiveButton("Exit & Reboot", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                exitWithReboot();
            }
        });
        builder.setNeutralButton("Exit", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                exitAndRestoreProjection();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private final Runnable backgroundSuspendRunnable = new Runnable() {
        @Override
        public void run() {
            if (isActivityVisible || isExiting || isFinishing()) return;
            if (usbDownloader != null && usbDownloader.isDownloading()) {
                // Keep dashcam Wi-Fi alive until active USB clip transfer finishes
                lifecycleHandler.postDelayed(backgroundSuspendRunnable, 2000L);
                return;
            }
            DebugLogger.log("LIFECYCLE", "Activity remains in background; restoring original Wi-Fi / SoftAP state");
            if (wifiBridge != null) {
                wifiBridge.suspendForBackground();
                suspendedInBackground = true;
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        isActivityVisible = true;
        lifecycleHandler.removeCallbacks(backgroundSuspendRunnable);
        if (isExiting) return;

        boolean liveVisible = (layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE);
        if (suspendedInBackground) {
            suspendedInBackground = false;
            DebugLogger.log("LIFECYCLE", "Activity returned to foreground; reconnecting to dashcam");
            if (wifiBridge != null) {
                wifiBridge.connectToDashcam();
            }
            if (liveVisible && (targetViewMode == VIEW_OEM_REAR || targetViewMode == VIEW_OEM_DUAL)) {
                startLiveStream();
            }
        } else if (liveVisible && (targetViewMode == VIEW_OEM_REAR || targetViewMode == VIEW_OEM_DUAL
                || (isConnected && liveSurface != null && liveSurface.isValid()))) {
            startLiveStream();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        isActivityVisible = false;
        if (isExiting || isFinishing()) return;

        DebugLogger.log("LIFECYCLE", "Activity moved to background; stopping active video decoders");
        isStartingLive = false;
        isConnectingLiveVlc = false;
        activeLiveStreamUrl = null;
        cancelLiveMaintenanceTimers();
        liveRequestGeneration++;
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
        stopOemBackupStream();
        if (vlcPlayerManager != null) {
            vlcPlayerManager.stopLiveStream();
            vlcPlayerManager.stopGalleryClip();
        }
        if (btnPlayClip != null && selectedClip != null && !selectedClip.isPhoto) {
            btnPlayClip.setText("Play");
        }

        // If camera was left in Playback/Gallery mode or recording was paused, resume loop recording BEFORE Wi-Fi suspends
        boolean inGallery = (layoutGalleryMode != null && layoutGalleryMode.getVisibility() == View.VISIBLE);
        boolean activeUsbTransfer = (usbDownloader != null && usbDownloader.isDownloading());
        if (isConnected && cameraClient != null && !activeUsbTransfer && (inGallery || !isRecording)) {
            cameraClient.ensureRecordingBeforeTeardown(new Runnable() {
                @Override
                public void run() {
                    isRecording = true;
                    if (!isActivityVisible && !isExiting) {
                        lifecycleHandler.removeCallbacks(backgroundSuspendRunnable);
                        lifecycleHandler.postDelayed(backgroundSuspendRunnable, 1200L);
                    }
                }
            }, 1000L);
        } else {
            lifecycleHandler.removeCallbacks(backgroundSuspendRunnable);
            lifecycleHandler.postDelayed(backgroundSuspendRunnable, 1500L);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean inGallery = (layoutGalleryMode != null && layoutGalleryMode.getVisibility() == View.VISIBLE);
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (inGallery && btnPlayClip != null) {
                btnPlayClip.performClick();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (inGallery && !displayedClips.isEmpty()) {
                int curIdx = (selectedClip != null) ? displayedClips.indexOf(selectedClip) : -1;
                int nextIdx;
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    nextIdx = (curIdx + 1) % displayedClips.size();
                } else {
                    nextIdx = (curIdx <= 0) ? (displayedClips.size() - 1) : (curIdx - 1);
                }
                selectedClip = displayedClips.get(nextIdx);
                if (clipAdapter != null) {
                    clipAdapter.notifyDataSetChanged();
                }
                if (lvClips != null) {
                    lvClips.setSelection(nextIdx);
                }
                tvSelectedClipName.setText(selectedClip.displayLabel);
                tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " \u00B7 " + selectedClip.timeStr);
                checkUsbStatus();
                playGalleryPreview(selectedClip);
                return true;
            } else if (!inGallery && getEffectiveAvailableViewModes().length > 1) {
                cycleViewMode();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void exitWithReboot() {
        if (isExiting) return;
        isExiting = true;
        liveRequestGeneration++;
        galleryLoadGeneration++;
        lifecycleHandler.removeCallbacks(backgroundSuspendRunnable);
        DebugLogger.log("EXIT", "User selected Exit & Reboot");
        Toast.makeText(this, "Restoring Wi-Fi and rebooting head unit...", Toast.LENGTH_SHORT).show();

        stopOemBackupStream();
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.cancelDownload();

        final Runnable finishRebootTeardown = new Runnable() {
            @Override
            public void run() {
                if (wifiBridge != null) {
                    wifiBridge.restoreAndExit();
                }
                lifecycleHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        performHeadUnitReboot();
                        finish();
                    }
                }, 750L);
            }
        };

        if (isConnected && cameraClient != null) {
            cameraClient.ensureRecordingBeforeTeardown(finishRebootTeardown, 1200L);
        } else {
            finishRebootTeardown.run();
        }
    }

    private void performHeadUnitReboot() {
        DebugLogger.log("SYSTEM", "Initiating head unit reboot...");

        // 1. Direct call to HMG-API root daemon via local abstract socket
        boolean hmgapidOk = HmgapidClient.reboot();
        if (hmgapidOk) {
            return;
        }

        // 2. Android standard PowerManager reboot
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.reboot(null);
            }
        } catch (Throwable t1) {
            DebugLogger.log("SYSTEM", "PowerManager.reboot notice: " + t1.getMessage());
        }

        // 3. Fallback direct shell execution
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/reboot"});
        } catch (Throwable t2) {
            try {
                Runtime.getRuntime().exec("reboot");
            } catch (Throwable t3) {
                DebugLogger.log("SYSTEM", "Shell reboot failed: " + t3.getMessage());
            }
        }

        // 4. Broadcast reboot action
        try {
            Intent intent = new Intent(Intent.ACTION_REBOOT);
            intent.putExtra("nowait", 1);
            intent.putExtra("interval", 1);
            intent.putExtra("window", 0);
            sendBroadcast(intent);
        } catch (Throwable t4) {
            DebugLogger.log("SYSTEM", "ACTION_REBOOT broadcast notice: " + t4.getMessage());
        }
    }

    private void exitAndRestoreProjection() {
        if (isExiting) return;
        isExiting = true;
        liveRequestGeneration++;
        galleryLoadGeneration++;
        lifecycleHandler.removeCallbacks(backgroundSuspendRunnable);
        Toast.makeText(this, "Restoring previous Wi-Fi state...", Toast.LENGTH_SHORT).show();

        stopOemBackupStream();
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.cancelDownload();

        final Runnable finishStandardTeardown = new Runnable() {
            @Override
            public void run() {
                if (wifiBridge != null) {
                    wifiBridge.restoreAndExit();
                }
                finish();
            }
        };

        if (isConnected && cameraClient != null) {
            cameraClient.ensureRecordingBeforeTeardown(finishStandardTeardown, 1200L);
        } else {
            finishStandardTeardown.run();
        }
    }

    @Override
    public void onBackPressed() {
        showExitDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissActiveWifiDialog();
        lifecycleHandler.removeCallbacksAndMessages(null);
        if (stockCamReceiverRegistered) {
            stockCamReceiverRegistered = false;
            try {
                unregisterReceiver(stockCameraReceiver);
            } catch (Throwable ignored) {}
        }
        stopOemBackupStream();
        clearPhotoPreview();
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.shutdown();
        if (wifiBridge != null) {
            wifiBridge.restoreAndExit();
            wifiBridge.shutdown();
        }
        if (cameraClient != null) {
            cameraClient.shutdown();
        }
    }

    // --- Clip List Adapter ---

    private class ClipAdapter extends BaseAdapter {
        private class ViewHolder {
            TextView tvFilename;
            TextView tvTime;
            TextView tvCamBadge;
            TextView tvLockBadge;
            TextView tvSize;
            Button btnItemDownload;
            ClipItem boundClip;
        }

        @Override
        public int getCount() {
            return displayedClips.size();
        }

        @Override
        public Object getItem(int position) {
            return displayedClips.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_clip, parent, false);
                holder = new ViewHolder();
                holder.tvFilename = (TextView) convertView.findViewById(R.id.tv_clip_filename);
                holder.tvTime = (TextView) convertView.findViewById(R.id.tv_clip_time);
                holder.tvCamBadge = (TextView) convertView.findViewById(R.id.tv_cam_badge);
                holder.tvLockBadge = (TextView) convertView.findViewById(R.id.tv_lock_badge);
                holder.tvSize = (TextView) convertView.findViewById(R.id.tv_clip_size);
                holder.btnItemDownload = (Button) convertView.findViewById(R.id.btn_item_download);
                if (holder.btnItemDownload != null) {
                    holder.btnItemDownload.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ClipItem item = holder.boundClip;
                            if (item == null) return;
                            selectedClip = item;
                            notifyDataSetChanged();
                            tvSelectedClipName.setText(item.displayLabel);
                            tvClipDetails.setText("Size: " + item.getFormattedSize() + " \u00B7 " + item.timeStr);
                            startUsbDownload(item);
                        }
                    });
                }
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final ClipItem clip = displayedClips.get(position);
            holder.boundClip = clip;

            ThemeManager.Palette p = activePalette;
            boolean isSelected = (selectedClip != null && clip == selectedClip);
            ThemeManager.styleClipRowCard(MainActivity.this, convertView, p, isSelected);

            holder.tvFilename.setText(clip.displayLabel);
            holder.tvFilename.setTextColor(isSelected ? p.onSecondaryContainer : p.onSurface);

            int metaColor = isSelected ? p.onSecondaryContainer : p.onSurfaceVariant;
            holder.tvTime.setText(clip.timeStr != null && !clip.timeStr.isEmpty() ? clip.timeStr : "-");
            holder.tvTime.setTextColor(metaColor);
            holder.tvSize.setText(clip.getFormattedSize());
            holder.tvSize.setTextColor(metaColor);

            // Clean colored text badges without colored background boxes
            if (clip.isOemUsbClip) {
                holder.tvCamBadge.setText("BACKUP \u00B7 USB");
                holder.tvCamBadge.setTextColor(p.tertiary);
            } else if (clip.isFront) {
                holder.tvCamBadge.setText("FRONT");
                holder.tvCamBadge.setTextColor(p.primary);
            } else {
                holder.tvCamBadge.setText("REAR");
                holder.tvCamBadge.setTextColor(p.statusWarning);
            }

            if (clip.isPhoto && clip.isLocked) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("PHOTO \u00B7 LOCKED");
                holder.tvLockBadge.setTextColor(p.error);
            } else if (clip.isLocked) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("LOCKED");
                holder.tvLockBadge.setTextColor(p.error);
            } else if (clip.isPhoto) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("PHOTO");
                holder.tvLockBadge.setTextColor(p.statusPositive);
            } else {
                holder.tvLockBadge.setVisibility(View.GONE);
            }

            if (holder.btnItemDownload != null) {
                ThemeManager.stylePillButton(
                        MainActivity.this,
                        holder.btnItemDownload,
                        isSelected ? p.primary : p.surfaceContainerHigh,
                        isSelected ? p.onPrimary : p.primary,
                        0,
                        0,
                        true);
                holder.btnItemDownload.setFocusable(false);
                holder.btnItemDownload.setFocusableInTouchMode(false);
            }

            return convertView;
        }
    }
}
