package fitcamx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
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

    // Wi-Fi and Camera subsystems
    private WifiBridge wifiBridge;
    private CameraClient cameraClient;
    private UsbDownloader usbDownloader;

    // UI Elements - Top bar
    private View layoutLogoHeader;
    private TextView tvTitle;
    private TextView tvWifiStatus;
    private Button btnRescan;
    private Button btnTabLive;
    private Button btnTabGallery;
    private Button btnExportLog;
    private Button btnExitApp;

    // Diagnostic bar
    private View layoutDiagBar;
    private TextView tvDiagInfo;

    // VLC High-Performance Player Engine
    private VlcPlayerManager vlcPlayerManager;

    // Live Mode Elements
    private View layoutLiveMode;
    private SurfaceView surfaceLive;
    private Surface liveSurface = null;
    private boolean isStartingLive = false;
    private int liveRequestGeneration = 0;
    private ProgressBar pbLiveLoading;
    private TextView tvStreamBadge;
    private TextView tvRecBadge;
    private Button btnToggleCam;
    private Button btnSnapshot;
    private Button btnEmergencyLock;
    private Button btnToggleMic;
    private Button btnToggleRecord;
    private Button btnCameraRes;
    private Button btnSyncTime;

    // Gallery Mode Elements
    private View layoutGalleryMode;
    private View frameGalleryContainer;
    private View layoutGallerySeekOverlay;
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
    private int currentViewMode = CameraClient.VIEW_FRONT; // 0=Front, 1=Rear, 2=Dual (Both)
    private int targetViewMode = CameraClient.VIEW_FRONT;
    private int activeStreamMode = CameraClient.VIEW_FRONT;
    private boolean isRecording = true;
    private boolean isMicOn = true;
    private boolean isConnected = false;
    private boolean hasRearCamera = true;
    private String currentSsid = "";
    private String currentDiagDetails = "";
    private final List<ClipItem> allClips = new ArrayList<ClipItem>();
    private final List<ClipItem> displayedClips = new ArrayList<ClipItem>();
    private int currentFilter = 0; // 0=All, 1=Front, 2=Rear, 3=Locked, 4=Photos
    private ClipAdapter clipAdapter;
    private ClipItem selectedClip = null;

    private final List<ScanResult> cachedScanResults = new ArrayList<ScanResult>();
    private final LinkedList<String> diagHistory = new LinkedList<String>();

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
                public void onConnected(String cameraIp, String ssid, CameraClient.SocType socType, int httpPort, String diagDetails) {
                    isConnected = true;
                    galleryCacheDirty = true;
                    currentSsid = ssid;
                    currentDiagDetails = diagDetails;
                    cameraClient.setCameraIp(cameraIp);
                    cameraClient.setSocType(socType, httpPort);
                    cameraClient.resetSessionState();

                    String msg = "Connected: " + ssid + " [" + cameraIp + "]";
                    addDiagLog(msg + " - " + socType.displayName);

                    if (tvWifiStatus != null) {
                        tvWifiStatus.setText(msg);
                        tvWifiStatus.setTextColor(getResources().getColor(R.color.accent_green));
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
                        // Queue settings sync & car clock sync right behind live stream startup
                        syncCameraSettings();
                        cameraClient.syncCarTime(null);
                    }
                }

                @Override
                public void onDisconnected() {
                    isConnected = false;
                    isStartingLive = false;
                    liveRequestGeneration++;
                    addDiagLog("Wi-Fi disconnected");
                    if (tvWifiStatus != null) {
                        tvWifiStatus.setText("Disconnected");
                        tvWifiStatus.setTextColor(getResources().getColor(R.color.accent_gold));
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
                                if (btnToggleMic != null) {
                                    btnToggleMic.setText(isMicOn ? "Mic On" : "Mic Muted");
                                }
                                DebugLogger.log("MAIN", "Synced camera mic status from firmware: " + (isMicOn ? "ON" : "MUTED"));
                            }

                            // 3. Check Recording status (cmd=2001: 1=recording, 0=stopped)
                            Matcher m2001 = Pattern.compile("(?i)<Cmd>2001</Cmd>\\s*<Status>(\\d+)</Status>").matcher(xml);
                            if (m2001.find()) {
                                int recStatus = Integer.parseInt(m2001.group(1));
                                isRecording = (recStatus == 1);
                                if (tvRecBadge != null) tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                                if (btnToggleRecord != null) btnToggleRecord.setText(isRecording ? "Stop Recording" : "Start Recording");
                                DebugLogger.log("MAIN", "Synced camera rec status from firmware: " + (isRecording ? "RECORDING" : "STOPPED"));
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
        layoutLogoHeader = findViewById(R.id.layout_logo_header);
        tvTitle = (TextView) findViewById(R.id.tv_title);

        TextView tvAppVersion = (TextView) findViewById(R.id.tv_app_version);
        if (tvAppVersion != null) {
            try {
                String vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvAppVersion.setText("v" + vName);
            } catch (Exception e) {
                tvAppVersion.setText("v" + DebugLogger.getAppVersion());
            }
        }

        tvWifiStatus = (TextView) findViewById(R.id.tv_wifi_status);
        btnRescan = (Button) findViewById(R.id.btn_rescan);
        btnTabLive = (Button) findViewById(R.id.btn_tab_live);
        btnTabGallery = (Button) findViewById(R.id.btn_tab_gallery);
        btnExportLog = (Button) findViewById(R.id.btn_export_log);
        btnExitApp = (Button) findViewById(R.id.btn_exit_app);

        // Diagnostic Bar
        layoutDiagBar = findViewById(R.id.layout_diag_bar);
        tvDiagInfo = (TextView) findViewById(R.id.tv_diag_info);

        // Live Views
        layoutLiveMode = findViewById(R.id.layout_live_mode);
        surfaceLive = (SurfaceView) findViewById(R.id.surface_live);
        if (surfaceLive != null) {
            surfaceLive.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    liveSurface = holder.getSurface();
                    DebugLogger.log("MAIN", "Live Surface created: " + liveSurface);
                    if (layoutLiveMode.getVisibility() == View.VISIBLE && isConnected && isActivityVisible && !isExiting) {
                        startLiveStream();
                    }
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    liveSurface = null;
                    isStartingLive = false;
                    liveRequestGeneration++;
                    DebugLogger.log("MAIN", "Live Surface destroyed");
                }
            });
        }
        pbLiveLoading = (ProgressBar) findViewById(R.id.pb_live_loading);
        tvStreamBadge = (TextView) findViewById(R.id.tv_stream_badge);
        tvRecBadge = (TextView) findViewById(R.id.tv_rec_badge);
        btnToggleCam = (Button) findViewById(R.id.btn_toggle_cam);
        btnSnapshot = (Button) findViewById(R.id.btn_snapshot);
        btnEmergencyLock = (Button) findViewById(R.id.btn_emergency_lock);
        btnToggleMic = (Button) findViewById(R.id.btn_toggle_mic);
        btnToggleRecord = (Button) findViewById(R.id.btn_toggle_record);
        btnCameraRes = (Button) findViewById(R.id.btn_camera_res);
        if (btnCameraRes != null) btnCameraRes.setText("Rec: 4K UHD");
        btnSyncTime = (Button) findViewById(R.id.btn_sync_time);

        // Gallery Views
        layoutGalleryMode = findViewById(R.id.layout_gallery_mode);
        frameGalleryContainer = findViewById(R.id.frame_gallery_container);
        layoutGallerySeekOverlay = findViewById(R.id.layout_gallery_seek_overlay);
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
    }

    private void setupListeners() {
        // Tap FITCAMX logo header at top-left to save a screenshot strictly to attached USB drive
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
        if (layoutLogoHeader != null) {
            layoutLogoHeader.setOnClickListener(screenshotClickListener);
        } else if (tvTitle != null) {
            tvTitle.setOnClickListener(screenshotClickListener);
        }

        // Manual Rescan / Re-verify
        if (btnRescan != null) {
            btnRescan.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(MainActivity.this, "Scanning for Wi-Fi / Probing Camera...", Toast.LENGTH_SHORT).show();
                    wifiBridge.triggerManualRescan();
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
                        btnToggleMic.setText(isMicOn ? "Mic On" : "Mic Muted");
                        addDiagLog("Microphone " + (isMicOn ? "enabled" : "muted"));
                        Toast.makeText(MainActivity.this, isMicOn ? "Microphone enabled" : "Microphone muted", Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onError(Exception error) {
                        btnToggleMic.setText(isMicOn ? "Mic On" : "Mic Muted"); // Revert
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
                        btnToggleRecord.setText(isRecording ? "Stop Recording" : "Start Recording");
                        addDiagLog(isRecording ? "Loop recording resumed" : "Loop recording paused");
                    }
                    @Override
                    public void onError(Exception error) {
                        btnToggleRecord.setEnabled(true);
                        tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                        btnToggleRecord.setText(isRecording ? "Stop Recording" : "Start Recording");
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
                    tvSelectedClipName.setText(selectedClip.filename);
                    tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " | Time: " + selectedClip.timeStr);
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

        // Tap anywhere on the Gallery video container to toggle the overlaid scrub bar
        if (frameGalleryContainer != null) {
            frameGalleryContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selectedClip == null || selectedClip.isPhoto || layoutGallerySeekOverlay == null) return;
                    if (layoutGallerySeekOverlay.getVisibility() == View.VISIBLE) {
                        if (!isUserSeeking) {
                            hideGallerySeekOverlay();
                        }
                    } else {
                        boolean autoHide = (vlcPlayerManager != null && vlcPlayerManager.isGalleryPlaying() && !galleryEnded);
                        showGallerySeekOverlay(autoHide);
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
                            tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " | Seek: " + timeLabel);
                        } else {
                            int pct = Math.min(100, Math.max(0, progress / 10));
                            if (tvSeekPosition != null) {
                                tvSeekPosition.setText(pct + "%");
                            }
                            tvClipDetails.setText(String.format(java.util.Locale.US, "Size: %s | Seek: %d%% | Time: %s",
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
                        boolean isVisible = (layoutGalleryListColumn.getVisibility() == View.VISIBLE);
                        layoutGalleryListColumn.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                        btnToggleGalleryList.setText(isVisible ? "Show List" : "Hide List");
                        btnToggleGalleryList.setBackgroundColor(getResources().getColor(
                                isVisible ? R.color.accent_green : R.color.accent_blue));

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

    private void styleFilterButton(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundColor(getResources().getColor(active ? R.color.accent_blue : R.color.bg_button));
        btn.setTextColor(active ? 0xFFFFFFFF : getResources().getColor(R.color.text_primary));
    }

    private void setGalleryFilter(int filter) {
        currentFilter = filter;
        styleFilterButton(btnFilterAll, filter == 0);
        styleFilterButton(btnFilterFront, filter == 1);
        styleFilterButton(btnFilterRear, filter == 2);
        styleFilterButton(btnFilterLocked, filter == 3);
        styleFilterButton(btnFilterPhotos, filter == 4);
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
                tvEmptyGallery.setText("No still photos found on SD card.\nUse 'Snapshot' in Live View to capture photos.");
            } else if (currentFilter == 3 && !allClips.isEmpty()) {
                tvEmptyGallery.setText("No emergency-locked clips found on SD card.");
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
        if (!hasRearCamera) {
            Toast.makeText(this, "Single camera configuration (Front only)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (targetViewMode == CameraClient.VIEW_FRONT) {
            targetViewMode = CameraClient.VIEW_DUAL;
        } else if (targetViewMode == CameraClient.VIEW_DUAL) {
            targetViewMode = CameraClient.VIEW_REAR;
        } else {
            targetViewMode = CameraClient.VIEW_FRONT;
        }
        updateViewModeUI();
        startLiveStream();
    }

    private void showViewModeSelectionDialog() {
        if (!hasRearCamera) {
            Toast.makeText(this, "Single camera configuration (Front only)", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] options = new String[] {
            "Front Camera" + (activeStreamMode == CameraClient.VIEW_FRONT ? "  \u2713" : ""),
            "Dual View (Both)" + (activeStreamMode == CameraClient.VIEW_DUAL ? "  \u2713" : ""),
            "Rear Camera" + (activeStreamMode == CameraClient.VIEW_REAR ? "  \u2713" : "")
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Camera View");
        b.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int selectedMode;
                if (which == 0) selectedMode = CameraClient.VIEW_FRONT;
                else if (which == 1) selectedMode = CameraClient.VIEW_DUAL;
                else selectedMode = CameraClient.VIEW_REAR;

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
        if (btnToggleCam == null) return;
        if (!hasRearCamera) {
            btnToggleCam.setText("View: Front (Single Cam)");
            btnToggleCam.setEnabled(false);
            return;
        }
        btnToggleCam.setEnabled(true);
        if (isStartingLive && targetViewMode != activeStreamMode) {
            btnToggleCam.setText("View: " + getViewModeLabel(targetViewMode) + " \u2026");
        } else {
            if (activeStreamMode == CameraClient.VIEW_DUAL) {
                btnToggleCam.setText("View: DUAL (PIP)");
            } else {
                btnToggleCam.setText("View: " + getViewModeLabel(activeStreamMode));
            }
        }
    }

    private void checkCameraConfiguration() {
        if (allClips != null && !allClips.isEmpty()) {
            boolean foundRear = false;
            for (ClipItem clip : allClips) {
                if (!clip.isFront) {
                    foundRear = true;
                    break;
                }
            }
            if (hasRearCamera != foundRear) {
                hasRearCamera = foundRear;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyCameraConfigurationUI();
                    }
                });
            }
        }
    }

    private void applyCameraConfigurationUI() {
        if (!hasRearCamera) {
            targetViewMode = CameraClient.VIEW_FRONT;
            currentViewMode = CameraClient.VIEW_FRONT;
            activeStreamMode = CameraClient.VIEW_FRONT;

            if (btnToggleCam != null) {
                btnToggleCam.setText("View: Front (Single Cam)");
                btnToggleCam.setEnabled(false);
            }
            if (btnFilterRear != null) {
                btnFilterRear.setVisibility(View.GONE);
            }
            if (btnFilterAll != null) {
                btnFilterAll.setText("All");
            }
            DebugLogger.log("MAIN", "Single camera configuration active (Front only, rear/dual views disabled)");
        } else {
            if (btnToggleCam != null) {
                btnToggleCam.setEnabled(true);
                updateViewModeUI();
            }
            if (btnFilterRear != null) {
                btnFilterRear.setVisibility(View.VISIBLE);
            }
        }
    }

    private String getViewModeLabel() {
        return getViewModeLabel(activeStreamMode);
    }

    private String getViewModeLabel(int mode) {
        switch (mode) {
            case CameraClient.VIEW_FRONT:
                return "FRONT";
            case CameraClient.VIEW_DUAL:
                return "DUAL";
            case CameraClient.VIEW_REAR:
                return "REAR";
            default:
                return "CAM";
        }
    }

    private void switchTab(boolean showLive) {
        if (showLive) {
            galleryLoadGeneration++;
            hideGallerySeekOverlay();
            clearPhotoPreview();
            btnTabLive.setBackgroundColor(getResources().getColor(R.color.accent_blue));
            btnTabGallery.setBackgroundColor(getResources().getColor(R.color.bg_button));
            layoutLiveMode.setVisibility(View.VISIBLE);
            layoutGalleryMode.setVisibility(View.GONE);

            // Explicitly hide gallery surface and restore live surface to prevent punch-through ghosting
            if (surfaceGallery != null) {
                surfaceGallery.setVisibility(View.GONE);
            }
            if (surfaceLive != null) {
                surfaceLive.setVisibility(View.VISIBLE);
            }

            if (vlcPlayerManager != null) vlcPlayerManager.stopGalleryClip();
            // If liveSurface is already allocated, start stream; otherwise surfaceCreated() will start it once ready.
            if (liveSurface != null && liveSurface.isValid()) {
                startLiveStream();
            }
        } else {
            isStartingLive = false;
            liveRequestGeneration++;
            btnTabLive.setBackgroundColor(getResources().getColor(R.color.bg_button));
            btnTabGallery.setBackgroundColor(getResources().getColor(R.color.accent_blue));
            layoutLiveMode.setVisibility(View.GONE);
            layoutGalleryMode.setVisibility(View.VISIBLE);

            // Explicitly hide live surface and enable gallery view to eliminate video bleeding
            if (surfaceLive != null) {
                surfaceLive.setVisibility(View.GONE);
            }
            if (selectedClip != null && selectedClip.isPhoto) {
                if (surfaceGallery != null) surfaceGallery.setVisibility(View.GONE);
                if (ivGalleryPhoto != null) ivGalleryPhoto.setVisibility(View.VISIBLE);
            } else {
                if (ivGalleryPhoto != null) ivGalleryPhoto.setVisibility(View.GONE);
                if (surfaceGallery != null) surfaceGallery.setVisibility(View.VISIBLE);
            }

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

    private void startLiveStream() {
        if (!isConnected || !isActivityVisible || isExiting || isFinishing()) return;
        if (layoutLiveMode != null && layoutLiveMode.getVisibility() != View.VISIBLE) {
            return;
        }
        if (isStartingLive) {
            DebugLogger.log("MAIN", "startLiveStream queued target mode: " + getViewModeLabel(targetViewMode));
            updateViewModeUI();
            return;
        }

        final int modeToStart = targetViewMode;
        final int reqGen = ++liveRequestGeneration;
        currentViewMode = modeToStart;
        isStartingLive = true;
        pbLiveLoading.setVisibility(View.VISIBLE);
        if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();

        updateViewModeUI();
        String prepMsg = "Initializing " + getViewModeLabel(modeToStart) + " feed...";
        addDiagLog(prepMsg);
        if (tvDiagInfo != null) tvDiagInfo.setText(prepMsg);
        tvStreamBadge.setText(getViewModeLabel(modeToStart) + " (CONNECTING...)");

        cameraClient.prepareAndStartLiveView(modeToStart, new CameraClient.Callback<List<String>>() {
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
                        if (btnToggleRecord != null) btnToggleRecord.setText("Stop Recording");

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

                        List<String> fallbacks = cameraClient.getCandidateStreamUrls(modeToStart);
                        if (vlcPlayerManager != null && vlcPlayerManager.isAvailable()) {
                            tryPlayVlcCandidate(fallbacks, 0, modeToStart, reqGen);
                        }
                    }
                });
            }
        });
    }

    private void tryPlayVlcCandidate(final List<String> urls, final int index, final int streamMode, final int reqGen) {
        if (!isLiveViewEligible(reqGen) || liveSurface == null || !liveSurface.isValid()) {
            return;
        }
        if (urls == null || index >= urls.size()) {
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
        String tryMsg = "Connecting [" + (index + 1) + "/" + urls.size() + "]: " + currentUrl;
        addDiagLog(tryMsg);
        DebugLogger.log("PLAYER", tryMsg);
        if (tvDiagInfo != null) {
            tvDiagInfo.setText(tryMsg);
        }
        tvStreamBadge.setText(getViewModeLabel(streamMode) + " [CONNECTING...]");

        vlcPlayerManager.startLiveStream(surfaceLive, currentUrl, new VlcPlayerManager.LiveListener() {
            @Override
            public void onPlaying() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isLiveViewEligible(reqGen)) {
                            if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();
                            return;
                        }
                        activeStreamMode = streamMode;
                        currentViewMode = streamMode;
                        pbLiveLoading.setVisibility(View.GONE);
                        String badge = getViewModeLabel(streamMode) + " (LIVE)";
                        tvStreamBadge.setText(badge);
                        updateViewModeUI();
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
                            pbLiveLoading.setVisibility(View.VISIBLE);
                        } else {
                            pbLiveLoading.setVisibility(View.GONE);
                        }
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isLiveViewEligible(reqGen)) return;
                        addDiagLog("VLC Stream failed [" + currentUrl + "]: " + message);
                        DebugLogger.log("PLAYER_ERR", "Candidate " + (index + 1) + " failed: " + message);
                        tryPlayVlcCandidate(urls, index + 1, streamMode, reqGen);
                    }
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reqGen == liveRequestGeneration && pbLiveLoading != null) {
                            pbLiveLoading.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    private void loadGalleryClips(boolean forceRefresh) {
        if (!forceRefresh && !galleryCacheDirty && !allClips.isEmpty()) {
            applyFilter();
            String cacheMsg = "SD card ready (" + allClips.size() + " items cached)";
            if (tvDiagInfo != null) {
                tvDiagInfo.setText(cacheMsg);
            }
            cameraClient.enterPlaybackMode(null);
            return;
        }

        String queryMsg = "Querying SD card from " + cameraClient.getCameraIp() + "...";
        tvClipCount.setText(queryMsg);
        if (tvDiagInfo != null) {
            tvDiagInfo.setText(queryMsg);
        }

        cameraClient.fetchClipList(new CameraClient.Callback<List<ClipItem>>() {
            @Override
            public void onSuccess(List<ClipItem> clips) {
                allClips.clear();
                if (clips != null) {
                    allClips.addAll(clips);
                }
                galleryCacheDirty = false;
                checkCameraConfiguration();
                applyFilter();

                String resMsg = "SD card loaded: " + allClips.size() + " items";
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
                tvClipCount.setText("SD Error: " + errMsg);
                tvEmptyGallery.setText("Could not read SD card.\n\n" + errMsg + "\n\nTip: Tap 'Export Log to USB' to save full diagnostic log to flash drive.");
                tvEmptyGallery.setVisibility(View.VISIBLE);
                lvClips.setVisibility(View.GONE);
                if (tvDiagInfo != null) {
                    tvDiagInfo.setText("SD read failed: " + errMsg);
                }
            }
        });
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

    private final Runnable hideSeekOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (isUserSeeking || galleryEnded) return;
            if (vlcPlayerManager != null && vlcPlayerManager.isGalleryPlaying()) {
                if (layoutGallerySeekOverlay != null) {
                    layoutGallerySeekOverlay.setVisibility(View.GONE);
                }
            }
        }
    };

    private void showGallerySeekOverlay(boolean autoHide) {
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
        if (layoutGallerySeekOverlay != null) {
            layoutGallerySeekOverlay.setVisibility(View.VISIBLE);
        }
        if (autoHide) {
            lifecycleHandler.postDelayed(hideSeekOverlayRunnable, SEEK_OVERLAY_AUTO_HIDE_MS);
        }
    }

    private void hideGallerySeekOverlay() {
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
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
        hideGallerySeekOverlay();
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

        File usbFolder = UsbDownloader.getUsbDestinationFolder(false);
        File candidateLocal = (usbFolder != null) ? new File(usbFolder, photo.filename) : null;
        File partFile = (usbFolder != null) ? new File(usbFolder, photo.filename + ".part") : null;
        final File verifiedLocal = (candidateLocal != null && candidateLocal.exists()
                && candidateLocal.length() > 0 && (partFile == null || !partFile.exists()))
                ? candidateLocal : null;
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
                                tvClipDetails.setText("Photo: " + origW + "x" + origH + " | Size: "
                                        + photo.getFormattedSize() + " | Time: " + photo.timeStr);
                            }
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
                                btnPlayClip.setText("Retry Photo");
                            }
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

        // Check if clip is already completely downloaded on USB drive (verify byte size & absence of .part file)
        File usbFolder = UsbDownloader.getUsbDestinationFolder(false);
        File localFile = (usbFolder != null) ? new File(usbFolder, clip.filename) : null;
        File partFile = (usbFolder != null) ? new File(usbFolder, clip.filename + ".part") : null;
        final String playUri;
        boolean verifiedLocalUsb = false;

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
                                tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " | " + timeLabel);
                            } else {
                                int pct = Math.min(100, Math.max(0, Math.round(lastKnownGalleryPosition * 100.0f)));
                                if (tvSeekPosition != null) {
                                    tvSeekPosition.setText(pct + "%");
                                }
                                tvClipDetails.setText(String.format(java.util.Locale.US, "Size: %s | Pos: %d%% | Time: %s",
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
                        hideGallerySeekOverlay();
                        btnPlayClip.setText("Play Preview");
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
        File usbDir = UsbDownloader.getUsbDestinationFolder(false);
        boolean downloading = (usbDownloader != null && usbDownloader.isDownloading());
        if (downloading) {
            btnDownloadToUsb.setText("Cancel");
            btnDownloadToUsb.setBackgroundColor(getResources().getColor(R.color.accent_red));
            btnDownloadToUsb.setEnabled(true);
        } else {
            btnDownloadToUsb.setText(R.string.btn_download_usb);
            btnDownloadToUsb.setBackgroundColor(getResources().getColor(R.color.accent_green));
        }

        if (usbDir != null) {
            tvUsbStatus.setText("USB Drive: Ready (" + usbDir.getAbsolutePath() + ")");
            tvUsbStatus.setTextColor(getResources().getColor(R.color.accent_green));
            if (!downloading) {
                btnDownloadToUsb.setEnabled(true);
            }
        } else {
            tvUsbStatus.setText("USB Drive: Not detected (insert drive in car USB port)");
            tvUsbStatus.setTextColor(getResources().getColor(R.color.accent_gold));
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
        btnDownloadToUsb.setBackgroundColor(getResources().getColor(R.color.accent_red));
        btnDownloadToUsb.setEnabled(true);

        boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
        usbDownloader.downloadClip(clip, cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort(), new UsbDownloader.DownloadCallback() {
            @Override
            public void onProgress(final int percent, final double speedMbPerSec, final long downloadedBytes, final long totalBytes) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbDownloadProgress.setProgress(percent);
                        tvDownloadStats.setText(String.format(java.util.Locale.US, "%d%% - %.2f MB/s", percent, speedMbPerSec));
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
        builder.setNegativeButton("View Wi-Fi Networks", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAvailableNetworksDialog();
            }
        });
        builder.show();
    }

    private void showFullLogViewerDialog() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText("Collecting system diagnostics...");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
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

    private void showAvailableNetworksDialog() {
        if (cachedScanResults.isEmpty()) {
            Toast.makeText(this, "Scanning for Wi-Fi networks...", Toast.LENGTH_SHORT).show();
            wifiBridge.triggerManualRescan();
            return;
        }

        final String[] ssids = new String[cachedScanResults.size()];
        for (int i = 0; i < cachedScanResults.size(); i++) {
            ScanResult r = cachedScanResults.get(i);
            String name = (r.SSID != null && !r.SSID.isEmpty()) ? r.SSID : "<Hidden Network>";
            ssids[i] = name + " (" + r.level + " dBm)";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Visible Networks (" + cachedScanResults.size() + ")");
        builder.setItems(ssids, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < cachedScanResults.size()) {
                    ScanResult chosen = cachedScanResults.get(which);
                    if (chosen.SSID != null && !chosen.SSID.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Connecting to " + chosen.SSID + "...", Toast.LENGTH_SHORT).show();
                        wifiBridge.connectToSpecificSsid(chosen.SSID, WifiBridge.DEFAULT_PASSWORD);
                    }
                }
            }
        });
        builder.setNegativeButton("Close", null);
        builder.show();
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

        if (suspendedInBackground) {
            suspendedInBackground = false;
            DebugLogger.log("LIFECYCLE", "Activity returned to foreground; reconnecting to dashcam");
            if (wifiBridge != null) {
                wifiBridge.connectToDashcam();
            }
        } else if (isConnected && layoutLiveMode != null && layoutLiveMode.getVisibility() == View.VISIBLE
                && liveSurface != null && liveSurface.isValid()) {
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
        liveRequestGeneration++;
        lifecycleHandler.removeCallbacks(hideSeekOverlayRunnable);
        if (vlcPlayerManager != null) {
            vlcPlayerManager.stopLiveStream();
            vlcPlayerManager.stopGalleryClip();
        }
        if (btnPlayClip != null && selectedClip != null && !selectedClip.isPhoto) {
            btnPlayClip.setText("Play Preview");
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
                if (lvClips != null) {
                    lvClips.setSelection(nextIdx);
                }
                tvSelectedClipName.setText(selectedClip.filename);
                tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " | Time: " + selectedClip.timeStr);
                checkUsbStatus();
                playGalleryPreview(selectedClip);
                return true;
            } else if (!inGallery && hasRearCamera) {
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
        lifecycleHandler.removeCallbacksAndMessages(null);
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
                            tvSelectedClipName.setText(item.filename);
                            tvClipDetails.setText("Size: " + item.getFormattedSize() + " | Time: " + item.timeStr);
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

            holder.tvFilename.setText(clip.filename);
            holder.tvTime.setText(clip.timeStr != null && !clip.timeStr.isEmpty() ? clip.timeStr : "-");
            holder.tvSize.setText(clip.getFormattedSize());

            // Clean colored text badges without colored background boxes
            if (clip.isFront) {
                holder.tvCamBadge.setText("FRONT");
                holder.tvCamBadge.setTextColor(getResources().getColor(R.color.accent_blue));
            } else {
                holder.tvCamBadge.setText("REAR");
                holder.tvCamBadge.setTextColor(getResources().getColor(R.color.accent_gold));
            }

            if (clip.isPhoto && clip.isLocked) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("PHOTO \u00B7 LOCKED");
                holder.tvLockBadge.setTextColor(getResources().getColor(R.color.accent_red));
            } else if (clip.isLocked) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("LOCKED");
                holder.tvLockBadge.setTextColor(getResources().getColor(R.color.accent_red));
            } else if (clip.isPhoto) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("PHOTO");
                holder.tvLockBadge.setTextColor(getResources().getColor(R.color.accent_green));
            } else {
                holder.tvLockBadge.setVisibility(View.GONE);
            }

            return convertView;
        }
    }
}
