package com.example.fbdownloader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
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

        // جلب الإعدادات المحفوظة مسبقاً
        SharedPreferences prefs = getSharedPreferences("AutoDLPrefs", MODE_PRIVATE);
        String savedQuality = prefs.getString("pref_quality", "HD");

        // بناء واجهة بسيطة ومرتبة بالكود مباشرة
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(60, 80, 60, 60);

        TextView title = new TextView(this);
        title.setText("إعدادات التحميل التلقائي من فيسبوك");
        title.setTextSize(20f);
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("\nاختار الجودة لي بغيتي، ومن بعد غير دير مشاركة (Share) لأي فيديو من فيسبوك مع هاد التطبيق وغادي يبدا التحميل بوحدو فـ الخلفية:\n");
        box.addView(info);

        RadioGroup group = new RadioGroup(this);

        RadioButton hdBtn = new RadioButton(this);
        hdBtn.setId(101);
        hdBtn.setText("جودة عالية تلقائياً (HD)");
        group.addView(hdBtn);

        RadioButton sdBtn = new RadioButton(this);
        sdBtn.setId(102);
        sdBtn.setText("جودة متوسطة واقتصادية (SD)");
        group.addView(sdBtn);

        if ("HD".equals(savedQuality)) {
            hdBtn.setChecked(true);
        } else {
            sdBtn.setChecked(true);
        }
        box.addView(group);

        Button saveBtn = new Button(this);
        saveBtn.setText("حفظ الإعدادات التلقائية");
        saveBtn.setOnClickListener(v -> {
            String choice = (group.getCheckedRadioButtonId() == 101) ? "HD" : "SD";
            prefs.edit().putString("pref_quality", choice).apply();
            Toast.makeText(this, "تم الحفظ: التحميل التلقائي مضبوط على " + choice, Toast.LENGTH_LONG).show();
        });
        box.addView(saveBtn);

        setContentView(box);
    }
}
