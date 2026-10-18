package com.example.fbdownloader;

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
import android.os.IBinder;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService extends Service {

    private static final String CHANNEL_ID = "fb_dl_channel_v2";
    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(1001);
    private NotificationManager notifManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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
                "يتم استخراج رابط الفيديو المباشر...",
                0, true
        );

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notifId, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(notifId, initialNotif);
        }

        new Thread(() -> {
            executeDownloadFlow(rawUrl, quality, notifId, startId);
        }).start();

        return START_NOT_STICKY;
    }

    private void executeDownloadFlow(String shareUrl, String quality, int notifId, int startId) {
        try {
            // المرحلة 1: فك الروابط المختصرة (/share/v/ أو /share/r/ أو fb.watch)
            String resolvedUrl = resolveRedirects(shareUrl);

            // المرحلة 2: استخراج الرابط المباشر عبر محرك البحث السريع في JSON الصفحة
            String directMp4 = extractDirectVideoUrl(resolvedUrl, quality);

            // المرحلة 3: إذا كان الرابط محمياً، استخدام محرك yt-dlp المدمج داخل التطبيق
            if (directMp4 == null) {
                updateNotification(notifId, "⚙️ جاري الاستخراج العميق عبر محرك yt-dlp...", "يرجى الانتظار ثواني قليلة...", 0, true);
                directMp4 = extractViaYtDlp(resolvedUrl, quality);
                if (directMp4 == null && !resolvedUrl.equals(shareUrl)) {
                    directMp4 = extractViaYtDlp(shareUrl, quality);
                }
            }

            if (directMp4 == null) {
                showFinalNotification(notifId, "❌ تعذر استخراج الفيديو", "تأكد أن الفيديو عام (Public) وليس داخل مجموعة خاصة.");
                stopSelf(startId);
                return;
            }

            // المرحلة 4: التنزيل المباشر مع حساب الحجم والسرعة والوقت المتبقي والنسبة المئوية
            downloadStreamWithProgress(directMp4, quality, notifId);

        } catch (Exception e) {
            showFinalNotification(notifId, "❌ حدث خطأ أثناء التحميل", e.getMessage() != null ? e.getMessage() : "خطأ في الاتصال");
        } finally {
            stopForeground( false );
            stopSelf(startId);
        }
    }

    private String resolveRedirects(String startUrl) {
        String current = startUrl;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(current);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        if (loc.startsWith("intent://")) break;
                        current = new URL(u, loc).toString();
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

    private String extractDirectVideoUrl(String pageUrl, String preferredQuality) {
        // المحاولة 1: عبر نسخة الكمبيوتر الكاملة لاستخراج Relay JSON (HD + SD)
        String html = fetchPageHtml(pageUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");

        String hd = findInHtml(html, new String[]{
                "\"browser_native_hd_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"hd_src_no_ratelimit\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"playable_url_quality_hd\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"hd_src\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"[^{}]*\"quality\"\\s*:\\s*\"HD\""
        });

        String sd = findInHtml(html, new String[]{
                "\"browser_native_sd_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"sd_src_no_ratelimit\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"playable_url\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"sd_src\"\\s*:\\s*\"(https?:[^\"]+)\"",
                "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"[^{}]*\"quality\"\\s*:\\s*\"SD\""
        });

        if (hd == null && sd == null) {
            // المحاولة 2: عبر mbasic.facebook.com التي تعطي رابط video_redirect مباشر
            String mbasicUrl = pageUrl.replace("www.facebook.com", "mbasic.facebook.com")
                                      .replace("m.facebook.com", "mbasic.facebook.com");
            String mbasicHtml = fetchPageHtml(mbasicUrl,
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            Matcher redir = Pattern.compile("/video_redirect/\\?src=([^\"&]+)").matcher(mbasicHtml);
            if (redir.find()) {
                try {
                    sd = URLDecoder.decode(redir.group(1), "UTF-8");
                } catch (Exception ignored) {}
            }
            if (sd == null) {
                sd = findInHtml(mbasicHtml, new String[]{
                        "property=\"og:video\"\\s+content=\"(https?:[^\"]+)\"",
                        "\"(https?:\\\\/\\\\/video[^\"]+\\.mp4[^\"]*)\""
                });
            }
        }

        if ("HD".equalsIgnoreCase(preferredQuality)) {
            return hd != null ? hd : sd;
        } else {
            return sd != null ? sd : hd;
        }
    }

    private String extractViaYtDlp(String url, String quality) {
        try {
            YoutubeDL.getInstance().init(getApplicationContext());
            YoutubeDLRequest req = new YoutubeDLRequest(url);
            if ("HD".equalsIgnoreCase(quality)) {
                req.addOption("-f", "best[ext=mp4]/best");
            } else {
                req.addOption("-f", "worst[ext=mp4]/worst");
            }
            VideoInfo info = YoutubeDL.getInstance().getInfo(req);
            if (info != null && info.getUrl() != null && !info.getUrl().isEmpty()) {
                return info.getUrl();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void downloadStreamWithProgress(String videoUrl, String quality, int notifId) throws Exception {
        URL u = new URL(videoUrl);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
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

        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            downloadedBytes += bytesRead;

            long now = System.currentTimeMillis();
            if (now - lastNotifUpdate >= 500) {
                lastNotifUpdate = now;
                long elapsedSec = Math.max(1, (now - startTime) / 1000);
                long speedBytesPerSec = downloadedBytes / elapsedSec;

                int percent = (totalBytes > 0) ? (int) ((downloadedBytes * 100L) / totalBytes) : 0;
                String sizeInfo = formatMB(downloadedBytes) + (totalBytes > 0 ? " / " + formatMB(totalBytes) : "");
                String speedInfo = formatMB(speedBytesPerSec) + "/s";
                String etaInfo = "غير معروف";

                if (totalBytes > 0 && speedBytesPerSec > 0) {
                    long remainingBytes = Math.max(0, totalBytes - downloadedBytes);
                    long etaSec = remainingBytes / speedBytesPerSec;
                    etaInfo = formatEta(etaSec);
                }

                String title = String.format(Locale.US, "⬇️ جاري تنزيل الفيديو (%s) - %d%%", quality, percent);
                String details = sizeInfo + " • " + speedInfo + " • المتبقي: " + etaInfo;

                updateNotification(notifId, title, details, percent, totalBytes <= 0);
            }
        }

        out.flush();
        out.close();
        in.close();
        conn.disconnect();

        // إظهار الفيديو فوراً في معرض الصور (Gallery)
        MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{outFile.getAbsolutePath()},
                new String[]{"video/mp4"},
                null
        );

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

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "تنزيلات فيسبوك التلقائية",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("إشعارات تتبع نسبة وحجم التنزيل والوقت المتبقي");
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
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath()), "resource/folder");
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
