package com.example.fbdownloader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_SEND.equals(incoming.getAction())) {
            String sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                String cleanUrl = extractFirstUrl(sharedText);
                if (cleanUrl != null) {
                    SharedPreferences prefs = getSharedPreferences("AutoDLPrefs", MODE_PRIVATE);
                    String quality = prefs.getString("pref_quality", "HD");

                    Toast.makeText(this, "⚡ جاري التحميل (" + quality + ") - انظر شريط الإشعارات", Toast.LENGTH_SHORT).show();

                    Intent svc = new Intent(this, DownloadService.class);
                    svc.putExtra("url", cleanUrl);
                    svc.putExtra("quality", quality);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                }
            }
        }
        finish();
    }

    private String extractFirstUrl(String text) {
        Matcher m = Pattern.compile("(https?://[^\\s\"<>]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
