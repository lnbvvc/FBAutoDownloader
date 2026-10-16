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

                    Toast.makeText(this, "🚀 جاري معالجة فيديو فيسبوك...", Toast.LENGTH_SHORT).show();
                    resolveAndDownload(cleanUrl, quality);
                }
            }
        }
        finish();
    }

    private String extractFirstUrl(String text) {
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void resolveAndDownload(String inputUrl, String quality) {
        Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                // تتبع إعادة التوجيه للرابط (خصوصاً روابط fb.watch أو الروابط القصيرة)
                String currentUrl = inputUrl;
                for (int i = 0; i < 5; i++) {
                    HttpURLConnection redirectionConn = (HttpURLConnection) new URL(currentUrl).openConnection();
                    redirectionConn.setInstanceFollowRedirects(false);
                    redirectionConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    int status = redirectionConn.getResponseCode();
                    if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                        String newLoc = redirectionConn.getHeaderField("Location");
                        if (newLoc != null) {
                            currentUrl = newLoc;
                            continue;
                        }
                    }
                    break;
                }

                // جلب صفحة الفيسبوك واستخراج روابط الفيديو المباشرة
                HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line);
                }
                reader.close();

                String htmlContent = html.toString();
                String hdLink = extractRegex(htmlContent, "\"(?:playable_url_quality_hd|hd_src|browser_native_hd_url)\"\\s*:\\s*\"([^\"]+)\"");
                String sdLink = extractRegex(htmlContent, "\"(?:playable_url|sd_src|browser_native_sd_url)\"\\s*:\\s*\"([^\"]+)\"");

                // إذا ما لقاش الرابط بصفة عادية، نجرب صيغة أخرى بديلة لـ og:video
                if (hdLink == null && sdLink == null) {
                    hdLink = extractRegex(htmlContent, "property=\"og:video\"\\s+content=\"([^\"]+)\"");
                }

                String targetUrl = "HD".equals(quality) ? (hdLink != null ? hdLink : sdLink) : (sdLink != null ? sdLink : hdLink);

                if (targetUrl != null) {
                    triggerDownloadManager(ctx, targetUrl, quality);
                } else {
                    // حل احتياطي: في حال تعذر الاستخراج المباشر، إرسال الرابط كملف تحميل عام
                    triggerDownloadManager(ctx, currentUrl, quality);
                }

            } catch (Exception e) {
                // في حال أي خطأ شبكي، يتم الاعتماد على الرابط الأصلي مباشرة
                triggerDownloadManager(ctx, inputUrl, quality);
            }
        }).start();
    }

    private String extractRegex(String content, String regex) {
        Matcher m = Pattern.compile(regex).matcher(content);
        if (m.find()) {
            return m.group(1)
                    .replace("\\/", "/")
                    .replace("\\u0025", "%")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&");
        }
        return null;
    }

    private void triggerDownloadManager(Context ctx, String directUrl, String quality) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(directUrl));
            String fileName = "FB_Video_" + quality + "_" + System.currentTimeMillis() + ".mp4";
            
            req.setTitle("تحميل فيديو فيسبوك (" + quality + ")");
            req.setDescription("جاري تنزيل الفيديو في الخلفية...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            req.allowScanningByMediaScanner();

            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
            }
        } catch (Exception ignored) {}
    }
}
