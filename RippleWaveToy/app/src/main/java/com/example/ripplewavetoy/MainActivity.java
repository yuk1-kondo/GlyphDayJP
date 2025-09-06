package com.example.ripplewavetoy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * MainActivity
 * エミュレーター用のメイン画面
 * シミュレーターを起動するためのエントリーポイント
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Button simulatorButton = findViewById(R.id.simulator_button);
        Button toyInfoButton = findViewById(R.id.toy_info_button);
        Button previewButton = findViewById(R.id.weekday_preview_button);
        
        simulatorButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, com.example.ripplewavetoy.simulator.RippleWaveSimulatorActivity.class);
            startActivity(intent);
        });
        previewButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, com.example.ripplewavetoy.WeekdayPreviewActivity.class);
            startActivity(intent);
        });
        
        toyInfoButton.setOnClickListener(v -> {
            // トイの情報を表示
            showToyInfo();
        });
    }
    
    private void showToyInfo() {
        TextView infoText = findViewById(R.id.info_text);
        String info = "曜日表示 について\n\n" +
                     "• 端末の日付に応じて曜日（漢字）を表示\n" +
                     "• 長押しで反転表示の切替\n" +
                     "• 端末シェイクで崩落アニメーション\n" +
                     "• AOD時は静止表示\n\n" +
                     "※ このシミュレーターは25x25グリッドを画面に表示します";
        infoText.setText(info);
        infoText.setVisibility(View.VISIBLE);
    }
}
