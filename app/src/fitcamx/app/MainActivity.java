package fitcamx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "FitcamxMain";

    // Wi-Fi and Camera subsystems
    private WifiBridge wifiBridge;
    private CameraClient cameraClient;
    private UsbDownloader usbDownloader;

    // UI Elements - Top bar
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
    private ListView lvClips;
    private TextView tvClipCount;
    private TextView tvEmptyGallery;
    private Button btnRefreshGallery;
    private Button btnFilterAll;
    private Button btnFilterFront;
    private Button btnFilterRear;
    private Button btnFilterLocked;
    private TextView tvSelectedClipName;
    private SurfaceView surfaceGallery;
    private Surface gallerySurface = null;
    private ProgressBar pbGalleryLoading;
    private TextView tvClipDetails;
    private TextView tvUsbStatus;
    private ProgressBar pbDownloadProgress;
    private TextView tvDownloadStats;
    private Button btnPlayClip;
    private Button btnDownloadToUsb;
    private View layoutGalleryListColumn;
    private Button btnToggleGalleryList;

    // State
    private boolean is1080pMode = false; // Default to 4K UHD (native front camera resolution)
    private int camera4kIndex = 6; // Default to 6 (Fitcamx NT96670 4K UHD mode index)
    private int currentViewMode = CameraClient.VIEW_FRONT; // 0=Front, 1=Rear, 2=Dual (Both)
    private int targetViewMode = CameraClient.VIEW_FRONT;
    private int activeStreamMode = CameraClient.VIEW_FRONT;
    private boolean isRecording = true;
    private boolean isMicOn = true;
    private boolean isConnected = false;
    private String currentSsid = "";
    private String currentDiagDetails = "";
    private final List<ClipItem> allClips = new ArrayList<ClipItem>();
    private final List<ClipItem> displayedClips = new ArrayList<ClipItem>();
    private int currentFilter = 0; // 0=All, 1=Front, 2=Rear, 3=Locked
    private ClipAdapter clipAdapter;
    private ClipItem selectedClip = null;

    private final List<ScanResult> cachedScanResults = new ArrayList<ScanResult>();
    private final LinkedList<String> diagHistory = new LinkedList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            DebugLogger.init(this);
            DebugLogger.log("MAIN", "MainActivity created (Gen5W FITCAMX v0.5.3)");
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
                    currentSsid = ssid;
                    currentDiagDetails = diagDetails;
                    cameraClient.setCameraIp(cameraIp);
                    cameraClient.setSocType(socType, httpPort);

                    String msg = "Connected: " + ssid + " [" + cameraIp + "]";
                    addDiagLog(msg + " - " + socType.displayName);

                    if (tvWifiStatus != null) {
                        tvWifiStatus.setText(msg);
                        tvWifiStatus.setTextColor(getResources().getColor(R.color.accent_green));
                    }
                    if (tvDiagInfo != null) {
                        tvDiagInfo.setText("Camera: " + socType.displayName + " (" + cameraIp + ") | " + diagDetails);
                    }

                    // Auto-sync car clock on connection
                    cameraClient.syncCarTime(null);
                    syncCameraSettings();

                    // Start Live stream with priority candidate ladder
                    startLiveStream();
                }

                @Override
                public void onDisconnected() {
                    isConnected = false;
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
        TextView tvAppVersion = (TextView) findViewById(R.id.tv_app_version);
        if (tvAppVersion != null) {
            try {
                String vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvAppVersion.setText("v" + vName);
            } catch (Exception e) {
                tvAppVersion.setText("v0.5.3");
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
                    if (layoutLiveMode.getVisibility() == View.VISIBLE && isConnected) {
                        startLiveStream();
                    }
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    liveSurface = null;
                    isStartingLive = false;
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
        lvClips = (ListView) findViewById(R.id.lv_clips);
        tvClipCount = (TextView) findViewById(R.id.tv_clip_count);
        tvEmptyGallery = (TextView) findViewById(R.id.tv_empty_gallery);
        btnRefreshGallery = (Button) findViewById(R.id.btn_refresh_gallery);
        btnFilterAll = (Button) findViewById(R.id.btn_filter_all);
        btnFilterFront = (Button) findViewById(R.id.btn_filter_front);
        btnFilterRear = (Button) findViewById(R.id.btn_filter_rear);
        btnFilterLocked = (Button) findViewById(R.id.btn_filter_locked);
        tvSelectedClipName = (TextView) findViewById(R.id.tv_selected_clip_name);
        pbGalleryLoading = (ProgressBar) findViewById(R.id.pb_gallery_loading);
        tvClipDetails = (TextView) findViewById(R.id.tv_clip_details);
        tvUsbStatus = (TextView) findViewById(R.id.tv_usb_status);
        pbDownloadProgress = (ProgressBar) findViewById(R.id.pb_download_progress);
        tvDownloadStats = (TextView) findViewById(R.id.tv_download_stats);
        btnPlayClip = (Button) findViewById(R.id.btn_play_clip);
        btnDownloadToUsb = (Button) findViewById(R.id.btn_download_to_usb);
        layoutGalleryListColumn = findViewById(R.id.layout_gallery_list_column);
        btnToggleGalleryList = (Button) findViewById(R.id.btn_toggle_gallery_list);

        clipAdapter = new ClipAdapter();
        lvClips.setAdapter(clipAdapter);
    }

    private void setupListeners() {
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
                isRecording = !isRecording;
                tvRecBadge.setVisibility(isRecording ? View.VISIBLE : View.GONE);
                btnToggleRecord.setText(isRecording ? "Stop Recording" : "Start Recording");
                cameraClient.setRecording(isRecording, null);
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
                    btnCameraRes.setText("Setting " + modeName + "...");
                    cameraClient.setVideoResolution(targetIndex, new CameraClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            is1080pMode = target1080p;
                            btnCameraRes.setText("Rec: " + (is1080pMode ? "1080P" : "4K UHD"));
                            addDiagLog("Dashcam resolution set to " + modeName);
                            Toast.makeText(MainActivity.this, "Camera resolution set to " + modeName, Toast.LENGTH_LONG).show();
                        }
                        @Override
                        public void onError(Exception error) {
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
                loadGalleryClips();
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

        // Gallery Item Selection
        lvClips.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < displayedClips.size()) {
                    selectedClip = displayedClips.get(position);
                    tvSelectedClipName.setText(selectedClip.filename);
                    tvClipDetails.setText("Size: " + selectedClip.getFormattedSize() + " | Time: " + selectedClip.timeStr);
                    btnDownloadToUsb.setEnabled(true);
                    playGalleryPreview(selectedClip);
                }
            }
        });

        // Play / Pause Selected Clip
        btnPlayClip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedClip != null) {
                    if (vlcPlayerManager != null && vlcPlayerManager.isGalleryPlaying()) {
                        vlcPlayerManager.pauseGalleryClip();
                        btnPlayClip.setText("Resume");
                    } else if (vlcPlayerManager != null && vlcPlayerManager.isInitialized()) {
                        btnPlayClip.setText("Pause");
                        vlcPlayerManager.resumeGalleryClip();
                    } else {
                        playGalleryPreview(selectedClip);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Select a clip first", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Download to USB Drive
        btnDownloadToUsb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedClip == null) {
                    Toast.makeText(MainActivity.this, "Please select a clip to save", Toast.LENGTH_SHORT).show();
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

    private void setGalleryFilter(int filter) {
        currentFilter = filter;
        if (btnFilterAll != null) btnFilterAll.setBackgroundColor(getResources().getColor(filter == 0 ? R.color.accent_blue : R.color.bg_button));
        if (btnFilterFront != null) btnFilterFront.setBackgroundColor(getResources().getColor(filter == 1 ? R.color.accent_blue : R.color.bg_button));
        if (btnFilterRear != null) btnFilterRear.setBackgroundColor(getResources().getColor(filter == 2 ? R.color.accent_blue : R.color.bg_button));
        if (btnFilterLocked != null) btnFilterLocked.setBackgroundColor(getResources().getColor(filter == 3 ? R.color.accent_blue : R.color.bg_button));
        applyFilter();
    }

    private void applyFilter() {
        displayedClips.clear();
        for (ClipItem c : allClips) {
            if (currentFilter == 1) {
                if (c.isFront) displayedClips.add(c);
            } else if (currentFilter == 2) {
                if (!c.isFront) displayedClips.add(c);
            } else if (currentFilter == 3) {
                if (c.isLocked) displayedClips.add(c);
            } else {
                displayedClips.add(c);
            }
        }
        clipAdapter.notifyDataSetChanged();
        tvClipCount.setText("Clips (" + displayedClips.size() + "/" + allClips.size() + ")");
        tvEmptyGallery.setVisibility(displayedClips.isEmpty() ? View.VISIBLE : View.GONE);
        lvClips.setVisibility(displayedClips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void cycleViewMode() {
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
            btnTabLive.setBackgroundColor(getResources().getColor(R.color.bg_button));
            btnTabGallery.setBackgroundColor(getResources().getColor(R.color.accent_blue));
            layoutLiveMode.setVisibility(View.GONE);
            layoutGalleryMode.setVisibility(View.VISIBLE);

            // Explicitly hide live surface and enable gallery surface to eliminate video bleeding
            if (surfaceLive != null) {
                surfaceLive.setVisibility(View.GONE);
            }
            if (surfaceGallery != null) {
                surfaceGallery.setVisibility(View.VISIBLE);
            }

            if (vlcPlayerManager != null) vlcPlayerManager.stopLiveStream();
            loadGalleryClips();
            checkUsbStatus();
        }
    }

    private void startLiveStream() {
        if (!isConnected) return;
        if (layoutLiveMode != null && layoutLiveMode.getVisibility() != View.VISIBLE) {
            return;
        }
        if (isStartingLive) {
            DebugLogger.log("MAIN", "startLiveStream queued target mode: " + getViewModeLabel(targetViewMode));
            updateViewModeUI();
            return;
        }

        final int modeToStart = targetViewMode;
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

                        if (vlcPlayerManager != null && vlcPlayerManager.isInitialized()) {
                            tryPlayVlcCandidate(candidates, 0, modeToStart);
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
                        isStartingLive = false;
                        Log.w(TAG, "prepareAndStartLiveView warning: " + error.getMessage());

                        if (targetViewMode != modeToStart) {
                            startLiveStream();
                            return;
                        }

                        List<String> fallbacks = cameraClient.getCandidateStreamUrls(modeToStart);
                        if (vlcPlayerManager != null && vlcPlayerManager.isInitialized()) {
                            tryPlayVlcCandidate(fallbacks, 0, modeToStart);
                        }
                    }
                });
            }
        });
    }

    private void tryPlayVlcCandidate(final List<String> urls, final int index, final int streamMode) {
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
                        addDiagLog("VLC Stream failed [" + currentUrl + "]: " + message);
                        DebugLogger.log("PLAYER_ERR", "Candidate " + (index + 1) + " failed: " + message);
                        tryPlayVlcCandidate(urls, index + 1, streamMode);
                    }
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbLiveLoading.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private void loadGalleryClips() {
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
                applyFilter();

                String resMsg = "SD card loaded: " + allClips.size() + " clips";
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

    private void playGalleryPreview(ClipItem clip) {
        pbGalleryLoading.setVisibility(View.VISIBLE);
        if (surfaceGallery != null) {
            surfaceGallery.setVisibility(View.VISIBLE);
        }
        if (vlcPlayerManager != null) vlcPlayerManager.stopGalleryClip();

        // Check if clip is already downloaded on USB drive
        File usbFolder = UsbDownloader.getUsbDestinationFolder();
        File localFile = (usbFolder != null) ? new File(usbFolder, clip.filename) : null;
        String playUri;

        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            playUri = localFile.getAbsolutePath();
            addDiagLog("Previewing from local USB (" + (clip.isFront ? "FRONT" : "REAR") + "): " + localFile.getName() + " (" + (localFile.length() / (1024 * 1024)) + "MB)");
        } else {
            boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
            playUri = clip.getHttpUrl(cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort());
            addDiagLog("Previewing clip via camera Wi-Fi (" + (clip.isFront ? "FRONT" : "REAR") + "): " + playUri);
        }

        if (vlcPlayerManager != null && vlcPlayerManager.isInitialized()) {
            final boolean isFront = (clip != null) ? clip.isFront : true;
            vlcPlayerManager.playGalleryClip(surfaceGallery, playUri, isFront, new VlcPlayerManager.GalleryListener() {
                @Override
                public void onPlaying() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pbGalleryLoading.setVisibility(View.GONE);
                            btnPlayClip.setText("Pause");
                            addDiagLog("VLC playing " + (isFront ? "front 4K slideshow (0.33x)" : "rear 1080p") + " preview");
                        }
                    });
                }

                @Override
                public void onBuffering(final float percent) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (percent < 100.0f) {
                                pbGalleryLoading.setVisibility(View.VISIBLE);
                            } else {
                                pbGalleryLoading.setVisibility(View.GONE);
                            }
                        }
                    });
                }

                @Override
                public void onTimeChanged(final long currentTimeMs, final long totalLengthMs) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (selectedClip != null) {
                                long curSec = currentTimeMs / 1000;
                                if (totalLengthMs > 0) {
                                    long totSec = totalLengthMs / 1000;
                                    tvClipDetails.setText(String.format("Size: %s | %02d:%02d / %02d:%02d",
                                            selectedClip.getFormattedSize(),
                                            curSec / 60, curSec % 60,
                                            totSec / 60, totSec % 60));
                                } else {
                                    tvClipDetails.setText(String.format("Size: %s | %02d:%02d",
                                            selectedClip.getFormattedSize(),
                                            curSec / 60, curSec % 60));
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
                            pbGalleryLoading.setVisibility(View.GONE);
                            btnPlayClip.setText("Play Preview");
                            String err = "VLC playback error: " + message;
                            addDiagLog(err);
                            DebugLogger.log("GALLERY_ERR", err);
                            Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } else {
            pbGalleryLoading.setVisibility(View.GONE);
            Toast.makeText(MainActivity.this, "VLC Player engine not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkUsbStatus() {
        File usbDir = UsbDownloader.getUsbDestinationFolder();
        if (usbDir != null) {
            tvUsbStatus.setText("USB Drive: Ready (" + usbDir.getAbsolutePath() + ")");
            tvUsbStatus.setTextColor(getResources().getColor(R.color.accent_green));
            btnDownloadToUsb.setEnabled(true);
        } else {
            tvUsbStatus.setText("USB Drive: Not detected (insert drive in car USB port)");
            tvUsbStatus.setTextColor(getResources().getColor(R.color.accent_gold));
            btnDownloadToUsb.setEnabled(false);
        }
    }

    private void startUsbDownload(final ClipItem clip) {
        pbDownloadProgress.setVisibility(View.VISIBLE);
        tvDownloadStats.setVisibility(View.VISIBLE);
        btnDownloadToUsb.setEnabled(false);

        boolean isJieLi = (cameraClient.getSocType() == CameraClient.SocType.JIELI);
        usbDownloader.downloadClip(clip, cameraClient.getCameraIp(), isJieLi, cameraClient.getHttpPort(), new UsbDownloader.DownloadCallback() {
            @Override
            public void onProgress(final int percent, final double speedMbPerSec, final long downloadedBytes, final long totalBytes) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pbDownloadProgress.setProgress(percent);
                        tvDownloadStats.setText(String.format("%d%% - %.2f MB/s", percent, speedMbPerSec));
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
                        btnDownloadToUsb.setEnabled(true);
                        addDiagLog("Saved to USB: " + destinationFile.getName());
                        Toast.makeText(MainActivity.this, "Clip saved to USB:\n" + destinationFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
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
                        btnDownloadToUsb.setEnabled(true);
                        addDiagLog("USB download error: " + error.getMessage());
                        Toast.makeText(MainActivity.this, "USB Download failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
        TextView tv = new TextView(this);
        tv.setText(DebugLogger.dumpSystemDiagnostics(this));
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

    private void exitWithReboot() {
        DebugLogger.log("EXIT", "User selected Exit & Reboot");
        Toast.makeText(this, "Restoring Wi-Fi and rebooting head unit...", Toast.LENGTH_SHORT).show();
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.cancelDownload();
        if (cameraClient != null) cameraClient.setRecording(true, null);

        if (wifiBridge != null) {
            wifiBridge.restoreAndExit();
        }

        // Brief delay to allow network teardown broadcast and flash flush, then reboot
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                performHeadUnitReboot();
                finish();
            }
        }, 750);
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
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.cancelDownload();
        if (cameraClient != null) cameraClient.setRecording(true, null);

        Toast.makeText(this, "Restoring previous Wi-Fi state...", Toast.LENGTH_SHORT).show();
        if (wifiBridge != null) {
            wifiBridge.restoreAndExit();
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        showExitDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vlcPlayerManager != null) vlcPlayerManager.release();
        if (usbDownloader != null) usbDownloader.cancelDownload();
        if (cameraClient != null) cameraClient.setRecording(true, null);
        if (wifiBridge != null) {
            wifiBridge.restoreAndExit();
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
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_clip, parent, false);
                holder = new ViewHolder();
                holder.tvFilename = (TextView) convertView.findViewById(R.id.tv_clip_filename);
                holder.tvTime = (TextView) convertView.findViewById(R.id.tv_clip_time);
                holder.tvCamBadge = (TextView) convertView.findViewById(R.id.tv_cam_badge);
                holder.tvLockBadge = (TextView) convertView.findViewById(R.id.tv_lock_badge);
                holder.tvSize = (TextView) convertView.findViewById(R.id.tv_clip_size);
                holder.btnItemDownload = (Button) convertView.findViewById(R.id.btn_item_download);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final ClipItem clip = displayedClips.get(position);

            holder.tvFilename.setText(clip.filename);
            holder.tvTime.setText(clip.timeStr != null && !clip.timeStr.isEmpty() ? clip.timeStr : "-");
            holder.tvSize.setText(clip.getFormattedSize());

            if (clip.isFront) {
                holder.tvCamBadge.setText("FRONT");
                holder.tvCamBadge.setBackgroundColor(getResources().getColor(R.color.accent_blue));
            } else {
                holder.tvCamBadge.setText("REAR");
                holder.tvCamBadge.setBackgroundColor(getResources().getColor(R.color.accent_gold));
            }

            if (clip.isLocked) {
                holder.tvLockBadge.setVisibility(View.VISIBLE);
                holder.tvLockBadge.setText("LOCKED");
                holder.tvLockBadge.setBackgroundColor(getResources().getColor(R.color.accent_red));
            } else {
                holder.tvLockBadge.setVisibility(View.GONE);
            }

            if (holder.btnItemDownload != null) {
                holder.btnItemDownload.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectedClip = clip;
                        tvSelectedClipName.setText(clip.filename);
                        tvClipDetails.setText("Size: " + clip.getFormattedSize() + " | Time: " + clip.timeStr);
                        startUsbDownload(clip);
                    }
                });
            }

            return convertView;
        }
    }
}
