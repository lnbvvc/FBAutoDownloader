package com.example.fbdownloader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private String selectedQuality = "HD";
    private LinearLayout hdCard, sdCard;
    private TextView hdBadge, sdBadge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // طلب صلاحية الإشعارات فـ أندرويد 13 فما فوق باش يبان شريط التحميل
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }

        SharedPreferences prefs = getSharedPreferences("AutoDLPrefs", MODE_PRIVATE);
        selectedQuality = prefs.getString("pref_quality", "HD");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#0B0F19"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(45, 70, 45, 60);

        // شارة الحالة الفوقانية
        TextView statusPill = new TextView(this);
        statusPill.setText("● نظام التحميل التلقائي مفعّل");
        statusPill.setTextColor(Color.parseColor("#10B981"));
        statusPill.setTextSize(12f);
        statusPill.setPadding(30, 14, 30, 14);
        statusPill.setBackground(makeRounded(Color.parseColor("#064E3B"), 50f, Color.parseColor("#059669"), 2));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillParams.gravity = Gravity.CENTER_HORIZONTAL;
        statusPill.setLayoutParams(pillParams);
        root.addView(statusPill);

        // العنوان الرئيسي
        TextView title = new TextView(this);
        title.setText("FB Auto Downloader Pro");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 30, 0, 10);
        root.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("شارك أي فيديو أو Reel من فيسبوك وسيبدأ التنزيل فوراً بشريط إشعارات يعرض النسبة والوقت المتبقي");
        subTitle.setTextColor(Color.parseColor("#94A3B8"));
        subTitle.setTextSize(13.5f);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(15, 0, 15, 50);
        root.addView(subTitle);

        // قسم اختيار الجودة التلقائية
        TextView secTitle = new TextView(this);
        secTitle.setText("اختر الجودة التلقائية المفضلة:");
        secTitle.setTextColor(Color.parseColor("#E2E8F0"));
        secTitle.setTextSize(16f);
        secTitle.setTypeface(Typeface.DEFAULT_BOLD);
        secTitle.setPadding(0, 0, 0, 25);
        root.addView(secTitle);

        // بطاقة HD
        hdCard = createQualityCard(
                "جودة فائقة الوضوح (Full HD)",
                "تحميل الفيديو بأعلى دقة متوفرة (1080p / 720p)",
                true
        );
        hdCard.setOnClickListener(v -> selectQuality("HD", prefs));
        root.addView(hdCard);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 25));
        root.addView(spacer);

        // بطاقة SD
        sdCard = createQualityCard(
                "جودة سريعة واقتصادية (SD)",
                "حجم ملف أصغر وسرعة تنزيل مضاعفة للبيانات المحدودة",
                false
        );
        sdCard.setOnClickListener(v -> selectQuality("SD", prefs));
        root.addView(sdCard);

        updateCardStyles();

        // قسم تجربة رابط مباشر من داخل التطبيق
        LinearLayout testBox = new LinearLayout(this);
        testBox.setOrientation(LinearLayout.VERTICAL);
        testBox.setPadding(40, 40, 40, 40);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(0, 55, 0, 0);
        testBox.setLayoutParams(boxParams);
        testBox.setBackground(makeRounded(Color.parseColor("#131C2E"), 32f, Color.parseColor("#1E293B"), 3));

        TextView testTitle = new TextView(this);
        testTitle.setText("🔗 تجربة وتنزيل رابط مباشر");
        testTitle.setTextColor(Color.WHITE);
        testTitle.setTextSize(16f);
        testTitle.setTypeface(Typeface.DEFAULT_BOLD);
        testBox.addView(testTitle);

        EditText urlInput = new EditText(this);
        urlInput.setHint("انسخ رابط فيديو فيسبوك هنا للتجربة...");
        urlInput.setHintTextColor(Color.parseColor("#64748B"));
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(14f);
        urlInput.setPadding(30, 28, 30, 28);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 25, 0, 25);
        urlInput.setLayoutParams(inputParams);
        urlInput.setBackground(makeRounded(Color.parseColor("#0B0F19"), 20f, Color.parseColor("#334155"), 2));
        testBox.addView(urlInput);

        Button dlNowBtn = new Button(this);
        dlNowBtn.setText("⬇️ بدء التنزيل الآن (" + selectedQuality + ")");
        dlNowBtn.setTextColor(Color.WHITE);
        dlNowBtn.setTextSize(15f);
        dlNowBtn.setTypeface(Typeface.DEFAULT_BOLD);
        dlNowBtn.setPadding(20, 28, 20, 28);
        dlNowBtn.setBackground(makeRounded(Color.parseColor("#2563EB"), 22f, Color.parseColor("#3B82F6"), 0));
        dlNowBtn.setOnClickListener(v -> {
            String link = urlInput.getText().toString().trim();
            if (link.isEmpty()) {
                Toast.makeText(this, "حط رابط فيديو فيسبوك أولاً!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent svc = new Intent(this, DownloadService.class);
            svc.putExtra("url", link);
            svc.putExtra("quality", selectedQuality);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "🚀 بدأ التحميل! شوف شريط الإشعارات الفوق", Toast.LENGTH_LONG).show();
            urlInput.setText("");
        });
        testBox.addView(dlNowBtn);

        root.addView(testBox);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout createQualityCard(String titleTxt, String descTxt, boolean isHd) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(40, 38, 40, 38);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleTxt);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        topRow.addView(t);

        TextView badge = new TextView(this);
        badge.setTextSize(12f);
        badge.setPadding(24, 8, 24, 8);
        topRow.addView(badge);

        if (isHd) hdBadge = badge; else sdBadge = badge;

        TextView d = new TextView(this);
        d.setText(descTxt);
        d.setTextColor(Color.parseColor("#94A3B8"));
        d.setTextSize(13f);
        d.setPadding(0, 12, 0, 0);

        card.addView(topRow);
        card.addView(d);
        return card;
    }

    private void selectQuality(String q, SharedPreferences prefs) {
        selectedQuality = q;
        prefs.edit().putString("pref_quality", q).apply();
        updateCardStyles();
        Toast.makeText(this, "✅ تم ضبط التحميل التلقائي على: " + q, Toast.LENGTH_SHORT).show();
    }

    private void updateCardStyles() {
        boolean isHd = "HD".equals(selectedQuality);
        hdCard.setBackground(makeRounded(
                Color.parseColor(isHd ? "#172554" : "#131C2E"),
                28f,
                Color.parseColor(isHd ? "#3B82F6" : "#1E293B"),
                isHd ? 4 : 2
        ));
        hdBadge.setText(isHd ? "✓ مفعّل" : "اختيار");
        hdBadge.setTextColor(Color.WHITE);
        hdBadge.setBackground(makeRounded(Color.parseColor(isHd ? "#2563EB" : "#334155"), 30f, 0, 0));

        sdCard.setBackground(makeRounded(
                Color.parseColor(!isHd ? "#172554" : "#131C2E"),
                28f,
                Color.parseColor(!isHd ? "#3B82F6" : "#1E293B"),
                !isHd ? 4 : 2
        ));
        sdBadge.setText(!isHd ? "✓ مفعّل" : "اختيار");
        sdBadge.setTextColor(Color.WHITE);
        sdBadge.setBackground(makeRounded(Color.parseColor(!isHd ? "#2563EB" : "#334155"), 30f, 0, 0));
    }

    private GradientDrawable makeRounded(int bgColor, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radius);
        if (strokeWidth > 0) {
            gd.setStroke(strokeWidth, strokeColor);
        }
        return gd;
    }
}
