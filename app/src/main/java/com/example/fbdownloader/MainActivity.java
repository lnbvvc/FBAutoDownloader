package com.example.fbdownloader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("AutoDLPrefs", MODE_PRIVATE);
        String savedQuality = prefs.getString("pref_quality", "HD");

        // الخلفية العامة بتصميم حديث (Gradient متناسق)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(50, 80, 50, 50);
        root.setBackgroundColor(Color.parseColor("#0F172A")); // لون داكن فخم حديث (Slate 900)

        // العنوان الرئيسي
        TextView title = new TextView(this);
        title.setText("⚡ FB Auto Downloader");
        title.setTextColor(Color.parseColor("#38BDF8")); // أزرق ناصع
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        // بطاقة خفيفة للإعدادات (Card container)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(40, 50, 40, 50);
        
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E293B")); // لون البطاقة أفتح قليلاً
        cardBg.setCornerRadius(30f);
        card.setBackground(cardBg);

        TextView info = new TextView(this);
        info.setText("إعدادات التحميل التلقائي:\n\nبمجرد اختيار الجودة أسفله، أي فيديو تشاركه (Share) من تطبيق فيسبوك مع هذا التطبيق، سيبدأ التحميل فوراً في الخلفية مع إشعار رسمي.");
        info.setTextColor(Color.parseColor("#94A3B8"));
        info.setTextSize(14f);
        card.addView(info);

        // خيارات الجودة
        RadioGroup group = new RadioGroup(this);
        group.setPadding(0, 40, 0, 30);

        RadioButton hdBtn = new RadioButton(this);
        hdBtn.setId(101);
        hdBtn.setText(" جودة عالية جداً (HD)");
        hdBtn.setTextColor(Color.WHITE);
        hdBtn.setTextSize(16f);
        hdBtn.setPadding(0, 20, 0, 20);
        group.addView(hdBtn);

        RadioButton sdBtn = new RadioButton(this);
        sdBtn.setId(102);
        sdBtn.setText(" جودة اقتصادية سريعة (SD)");
        sdBtn.setTextColor(Color.WHITE);
        sdBtn.setTextSize(16f);
        sdBtn.setPadding(0, 20, 0, 20);
        group.addView(sdBtn);

        if ("HD".equals(savedQuality)) {
            hdBtn.setChecked(true);
        } else {
            sdBtn.setChecked(true);
        }
        card.addView(group);

        // زر الحفظ بتصميم عصري
        Button saveBtn = new Button(this);
        saveBtn.setText("حفظ الإعدادات التلقائية");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(16f);
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#2563EB")); // أزرق عصري
        btnBg.setCornerRadius(20f);
        saveBtn.setBackground(btnBg);
        saveBtn.setPadding(20, 30, 20, 30);

        saveBtn.setOnClickListener(v -> {
            String choice = (group.getCheckedRadioButtonId() == 101) ? "HD" : "SD";
            prefs.edit().putString("pref_quality", choice).apply();
            
            Toast toast = Toast.makeText(this, "✅ تم الحفظ بنجاح: " + choice, Toast.LENGTH_SHORT);
            toast.show();
        });
        card.addView(saveBtn);

        root.addView(card);
        setContentView(root);
    }
}
