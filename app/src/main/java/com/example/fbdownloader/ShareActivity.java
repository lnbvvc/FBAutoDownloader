package com.example.fbdownloader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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

                    Toast.makeText(this, "جاري بدء التحميل التلقائي (" + quality + ")...", Toast.LENGTH_SHORT).show();
                    processInBackground(cleanUrl, quality);
                }
            }
        }
        // إغلاق الشاشة الشفافة فوراً باش تبقى مرتاح فـ تصفح فيسبوك
        finish();
    }

    private String extractFirstUrl(String text) {
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void processInBackground(String pageUrl, String preferredQuality) {
        Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(pageUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line);
                }
                reader.close();

                String content = html.toString();
                String hdLink = findVideoUrl(content, "(?:playable_url_quality_hd|hd_src|browser_native_hd_url)\"\\s*:\\s*\"([^\"]+)\"");
                String sdLink = findVideoUrl(content, "(?:playable_url|sd_src|browser_native_sd_url)\"\\s*:\\s*\"([^\"]+)\"");

                String finalVideoUrl = "HD".equals(preferredQuality)
                        ? (hdLink != null ? hdLink : sdLink)
                        : (sdLink != null ? sdLink : hdLink);

                if (finalVideoUrl != null) {
                    startSystemDownload(appCtx, finalVideoUrl, preferredQuality);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String findVideoUrl(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        if (m.find()) {
            return m.group(1)
                    .replace("\\/", "/")
                    .replace("\\u0025", "%")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&");
        }
        return null;
    }

    private void startSystemDownload(Context ctx, String directUrl, String quality) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(directUrl));
        String fileName = "FB_" + quality + "_" + System.currentTimeMillis() + ".mp4";
        req.setTitle("تحميل فيديو فيسبوك (" + quality + ")");
        req.setDescription("يتم التنزيل تلقائياً...");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            dm.enqueue(req);
        }
    }
}
