package com.example.fbdownloader;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService extends Service {

    private static final String CHANNEL_ID = "fb_dl_channel_v3";
    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(2001);
    private NotificationManager notifManager;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        createChannel();
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final String rawUrl = intent.getStringExtra("url");
        final String quality = intent.getStringExtra("quality") != null ? intent.getStringExtra("quality") : "HD";
        final int notifId = NOTIF_COUNTER.getAndIncrement();

        Notification initialNotif = buildProgressNotification(
                "⏳ جاري تحليل رابط فيسبوك (" + quality + ")...",
                "يتم فك الرابط واستخراج الفيديو المباشر...",
                0, true
        );

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notifId, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(notifId, initialNotif);
        }

        new Thread(() -> executeDownloadFlow(rawUrl, quality, notifId, startId)).start();

        return START_NOT_STICKY;
    }

    private void executeDownloadFlow(String shareUrl, String quality, int notifId, int startId) {
        try {
            // 1. فك الروابط المختصرة (share/v و share/r و fb.watch)
            String resolvedUrl = resolveRedirects(shareUrl);

            // 2. استخراج الرابط المباشر عبر 4 مراحل متتالية
            String directMp4 = extractVideoMultiStage(resolvedUrl, shareUrl, quality, notifId);

            if (directMp4 == null || directMp4.isEmpty()) {
                showToast("❌ تعذر استخراج الفيديو: تأكد أن الفيديو عام (Public)");
                showFinalNotification(notifId, "❌ تعذر استخراج الفيديو", "الفيديو خاص أو يتطلب تسجيل دخول.");
                return;
            }

            // 3. بدء التنزيل الفعلي مع تحديث الإشعار (النسبة، الحجم، السرعة، الوقت المتبقي)
            downloadStreamWithProgress(directMp4, quality, notifId);

        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : "خطأ في الاتصال بالشبكة";
            showToast("❌ خطأ: " + err);
            showFinalNotification(notifId, "❌ فشل التنزيل", err);
        } finally {
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private String resolveRedirects(String startUrl) {
        String current = startUrl;
        for (int i = 0; i < 10; i++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(current);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        if (loc.startsWith("intent://") || loc.startsWith("fb://")) break;
                        String nextUrl = new URL(u, loc).toString();
                        if (nextUrl.contains("/login")) break;
                        current = nextUrl;
                        continue;
                    }
                }
                break;
            } catch (Exception e) {
                break;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return current;
    }

    private String extractVideoMultiStage(String resolvedUrl, String originalUrl, String preferredQuality, int notifId) {
        // المرحلة 1: استخراج من صفحة الكمبيوتر المباشرة
        String desktopHtml = fetchPageHtml(resolvedUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        String picked = pickQualityFromHtml(desktopHtml, preferredQuality);
        if (picked != null) return picked;

        // البحث عن رقم الفيديو (Video ID) لاستعماله في المشغل المدمج
        String videoId = extractVideoId(resolvedUrl, desktopHtml);
        String canonicalWatchUrl = (videoId != null)
                ? "https://www.facebook.com/watch/?v=" + videoId
                : resolvedUrl;

        // المرحلة 2: استخراج عبر المشغل الرسمي المدمج (plugins/video.php) الذي يتجاوز شاشة الدخول
        updateNotification(notifId, "⏳ جاري فحص المشغل المباشر...", "المرحلة 2 من استخراج الفيديو...", 0, true);
        try {
            String embedUrl = "https://www.facebook.com/plugins/video.php?href="
                    + URLEncoder.encode(canonicalWatchUrl, "UTF-8") + "&show_text=false";
            String embedHtml = fetchPageHtml(embedUrl,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            picked = pickQualityFromHtml(embedHtml, preferredQuality);
            if (picked != null) return picked;
        } catch (Exception ignored) {}

        // المرحلة 3: استخراج عبر واجهة mbasic وبوت المعاينة
        updateNotification(notifId, "⏳ جاري الاستخراج العميق...", "المرحلة 3 من استخراج الفيديو...", 0, true);
        String mbasicUrl = canonicalWatchUrl.replace("www.facebook.com", "mbasic.facebook.com")
                                            .replace("m.facebook.com", "mbasic.facebook.com");
        String mbasicHtml = fetchPageHtml(mbasicUrl,
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        Matcher redir = Pattern.compile("/video_redirect/\\?src=([^\"&]+)").matcher(mbasicHtml);
        if (redir.find()) {
            try {
                return URLDecoder.decode(redir.group(1), "UTF-8");
            } catch (Exception ignored) {}
        }

        String botHtml = fetchPageHtml(canonicalWatchUrl, "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)");
        picked = findInHtml(botHtml, new String[]{
                "property=\"og:video:secure_url\"\\s+content=\"(https?:[^\"]+)\"",
                "property=\"og:video\"\\s+content=\"(https?:[^\"]+)\"",
                "content=\"(https?:[^\"]+)\"\\s+property=\"og:video\""
        });
        if (picked != null) return picked;

        // المرحلة 4: المزود الاحتياطي الخارجي (FDown) في حال كان الرابط محمياً
        updateNotification(notifId, "⏳ جاري الاستخراج عبر المزود البديل...", "المرحلة 4...", 0, true);
        picked = extractViaFdownFallback(originalUrl, preferredQuality);
        if (picked == null && !originalUrl.equals(resolvedUrl)) {
            picked = extractViaFdownFallback(resolvedUrl, preferredQuality);
        }

        return picked;
    }

    private String pickQualityFromHtml(String html, String preferredQuality) {
        if (html == null || html.isEmpty()) return null;

        String hd = findInHtml(html, new String[]{
                "\"browser_native_hd_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"playable_url_quality_hd\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"hd_src_no_ratelimit\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"hd_src\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"[^{}]*\"quality\"\\s*:\\s*\"HD\"",
                "\"quality\"\\s*:\\s*\"HD\"[^{}]*\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\""
        });

        String sd = findInHtml(html, new String[]{
                "\"browser_native_sd_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"playable_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"sd_src_no_ratelimit\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"sd_src\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"[^{}]*\"quality\"\\s*:\\s*\"SD\"",
                "\"quality\"\\s*:\\s*\"SD\"[^{}]*\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\""
        });

        if ("HD".equalsIgnoreCase(preferredQuality)) {
            return hd != null ? hd : sd;
        } else {
            return sd != null ? sd : hd;
        }
    }

    private String extractVideoId(String url, String html) {
        String[] urlPats = new String[]{
                "/videos/(?:[^/]+/)?(\\d{8,})",
                "/reel/(\\d{8,})",
                "[?&]v=(\\d{8,})"
        };
        for (String p : urlPats) {
            Matcher m = Pattern.compile(p).matcher(url);
            if (m.find()) return m.group(1);
        }
        if (html != null) {
            String[] htmlPats = new String[]{
                    "\"video_id\"\\s*:\\s*\"(\\d{8,})\"",
                    "\"videoID\"\\s*:\\s*\"(\\d{8,})\"",
                    "fb://video/(\\d{8,})"
            };
            for (String p : htmlPats) {
                Matcher m = Pattern.compile(p).matcher(html);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private String extractViaFdownFallback(String fbUrl, String preferredQuality) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://fdown.net/download.php");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Origin", "https://fdown.net");
            conn.setRequestProperty("Referer", "https://fdown.net/");

            String body = "URLz=" + URLEncoder.encode(fbUrl, "UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            String resp = sb.toString();

            String hd = findInHtml(resp, new String[]{
                    "id=\"hdlink\"\\s+href=\"(https?:[^\"]+)\"",
                    "href=\"(https?:[^\"]+)\"[^>]*id=\"hdlink\""
            });
            String sd = findInHtml(resp, new String[]{
                    "id=\"sdlink\"\\s+href=\"(https?:[^\"]+)\"",
                    "href=\"(https?:[^\"]+)\"[^>]*id=\"sdlink\""
            });

            if ("HD".equalsIgnoreCase(preferredQuality)) {
                return hd != null ? hd : sd;
            } else {
                return sd != null ? sd : hd;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void downloadStreamWithProgress(String videoUrl, String quality, int notifId) throws Exception {
        URL u = new URL(videoUrl);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.connect();

        long totalBytes = conn.getContentLengthLong();
        File dlFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dlFolder.exists()) dlFolder.mkdirs();

        String fileName = "FB_" + quality + "_" + System.currentTimeMillis() + ".mp4";
        File outFile = new File(dlFolder, fileName);

        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(outFile);

        byte[] buffer = new byte[65536];
        int bytesRead;
        long downloadedBytes = 0;
        long startTime = System.currentTimeMillis();
        long lastNotifUpdate = 0;

        showToast("⬇️ بدأ تنزيل الفيديو (" + quality + ")...");

        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            downloadedBytes += bytesRead;

            long now = System.currentTimeMillis();
            if (now - lastNotifUpdate >= 500) {
                lastNotifUpdate = now;
                long elapsedMs = Math.max(200, now - startTime);
                long speedBytesPerSec = (downloadedBytes * 1000L) / elapsedMs;

                int percent = (totalBytes > 0) ? (int) ((downloadedBytes * 100L) / totalBytes) : 0;
                String sizeInfo = formatMB(downloadedBytes) + (totalBytes > 0 ? " / " + formatMB(totalBytes) : "");
                String speedInfo = formatMB(speedBytesPerSec) + "/s";
                String etaInfo = "جاري الحساب...";

                if (totalBytes > 0 && speedBytesPerSec > 0) {
                    long remainingBytes = Math.max(0, totalBytes - downloadedBytes);
                    long etaSec = remainingBytes / speedBytesPerSec;
                    etaInfo = formatEta(etaSec);
                }

                String title = (totalBytes > 0)
                        ? String.format(Locale.US, "⬇️ جاري التنزيل (%s) - %d%%", quality, percent)
                        : String.format(Locale.US, "⬇️ جاري التنزيل (%s) - %s", quality, formatMB(downloadedBytes));
                String details = sizeInfo + " • " + speedInfo + " • المتبقي: " + etaInfo;

                updateNotification(notifId, title, details, percent, totalBytes <= 0);
            }
        }

        out.flush();
        out.close();
        in.close();
        conn.disconnect();

        MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{outFile.getAbsolutePath()},
                new String[]{"video/mp4"},
                null
        );

        showToast("✅ اكتمل التنزيل: " + fileName);
        showCompletedNotification(notifId, outFile, downloadedBytes, quality);
    }

    private String fetchPageHtml(String urlStr, String ua) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "none");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String findInHtml(String html, String[] patterns) {
        if (html == null || html.isEmpty()) return null;
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(html);
            if (m.find()) {
                return cleanJsonUrl(m.group(1));
            }
        }
        return null;
    }

    private String cleanJsonUrl(String raw) {
        if (raw == null) return null;
        return raw.replace("\\/", "/")
                  .replace("\\u0025", "%")
                  .replace("\\u0026", "&")
                  .replace("\\u003C", "<")
                  .replace("\\u003E", ">")
                  .replace("\\u003D", "=")
                  .replace("&amp;", "&");
    }

    private String formatMB(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private String formatEta(long sec) {
        if (sec < 60) return sec + " ثانية";
        long mins = sec / 60;
        long remSec = sec % 60;
        return mins + " د و " + remSec + " ث";
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "إشعارات تنزيل فيسبوك",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            ch.setDescription("عرض نسبة التنزيل والحجم والوقت المتبقي");
            ch.setSound(null, null);
            ch.enableVibration(false);
            notifManager.createNotificationChannel(ch);
        }
    }

    private Notification buildProgressNotification(String title, String content, int progress, boolean indeterminate) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(title)
         .setContentText(content)
         .setSmallIcon(android.R.drawable.stat_sys_download)
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setProgress(100, progress, indeterminate);

        return b.build();
    }

    private void updateNotification(int id, String title, String content, int progress, boolean indeterminate) {
        notifManager.notify(id, buildProgressNotification(title, content, progress, indeterminate));
    }

    private void showFinalNotification(int id, String title, String message) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(title)
         .setContentText(message)
         .setSmallIcon(android.R.drawable.stat_notify_error)
         .setAutoCancel(true);

        notifManager.notify(id, b.build());
    }

    private void showCompletedNotification(int id, File file, long totalBytes, String quality) {
        Intent openIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(
                this, id, openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("✅ اكتمل تنزيل الفيديو (" + quality + ")")
         .setContentText("الحجم: " + formatMB(totalBytes) + " • محفوظ في التنزيلات والمعرض")
         .setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setContentIntent(pi)
         .setAutoCancel(true);

        notifManager.notify(id, b.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
