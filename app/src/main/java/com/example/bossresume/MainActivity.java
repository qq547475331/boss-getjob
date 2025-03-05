package com.example.bossresume;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_SERVICE_STATUS_CHANGED = "com.example.bossresume.ACTION_SERVICE_STATUS_CHANGED";

    private TextView tvStatus;
    private TextView tvLog;
    private Button btnStart;
    private Button btnStop;
    private Button btnAccessibilitySettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 显示版本信息
        TextView versionInfoText = findViewById(R.id.versionInfoText);
        String versionInfo = String.format(
            getString(R.string.version_info), 
            getString(R.string.app_version)
        );
        versionInfoText.setText(versionInfo);
        
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnAccessibilitySettings = findViewById(R.id.btn_accessibility_settings);

        btnAccessibilitySettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnStart.setOnClickListener(v -> {
            if (isAccessibilityServiceEnabled()) {
                startService();
                updateLog("开始自动投递任务");
            } else {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            }
        });

        btnStop.setOnClickListener(v -> {
            Intent intent = new Intent(this, BossResumeService.class);
            intent.setAction(BossResumeService.ACTION_STOP);
            startService(intent);
            updateLog("停止自动投递任务");
        });

        registerBroadcastReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        if (isAccessibilityServiceEnabled()) {
            tvStatus.setText("服务状态：已启用");
        } else {
            tvStatus.setText("服务状态：未启用");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC);
        
        for (AccessibilityServiceInfo service : enabledServices) {
            if (service.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    public void updateLog(final String message) {
        runOnUiThread(() -> {
            tvLog.append(message + "\n");
        });
    }

    public static void appendLog(Context context, String message) {
        if (context instanceof MainActivity) {
            ((MainActivity) context).updateLog(message);
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_SERVICE_STATUS_CHANGED);
        registerReceiver(serviceStatusReceiver, filter);
    }

    private BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SERVICE_STATUS_CHANGED.equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra("running", false);
                int count = intent.getIntExtra("count", 0);
                
                updateUI(isRunning, count);
            }
        }
    };

    private void updateUI(boolean isRunning, int count) {
        btnStart.setEnabled(!isRunning);
        btnStop.setEnabled(isRunning);
        
        tvStatus.setText("服务状态：" + (isRunning ? "运行中" : "未运行"));
        tvLog.append("已投递: " + count + "\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(serviceStatusReceiver);
    }

    private void startService() {
        Intent intent = new Intent(this, BossResumeService.class);
        intent.setAction(BossResumeService.ACTION_START);
        startService(intent);
        updateUI(true, 0);
        
        // 启动BOSS直聘APP
        Handler handler = new Handler();
        handler.postDelayed(() -> {
            BossResumeService.launchBossApp(this);
        }, 500);
    }
} 