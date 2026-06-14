package com.example.ai_scanner;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(getString(R.string.key_settings_prefs), MODE_PRIVATE);

        // Floating ball toggle
        SwitchMaterial swFloating = findViewById(R.id.swFloatingEnabled);
        swFloating.setChecked(prefs.getBoolean(getString(R.string.key_floating_enabled), true));
        swFloating.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(getString(R.string.key_floating_enabled), checked).apply());

        // Emergency phone
        TextInputEditText etPhone = findViewById(R.id.etEmergencyPhone);
        etPhone.setText(prefs.getString(getString(R.string.key_emergency_phone), ""));
        etPhone.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                prefs.edit().putString(getString(R.string.key_emergency_phone),
                        etPhone.getText().toString().trim()).apply();
            }
        });

        // SMS toggle
        SwitchMaterial swSms = findViewById(R.id.swSmsEnabled);
        swSms.setChecked(prefs.getBoolean(getString(R.string.key_sms_enabled), true));
        swSms.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(getString(R.string.key_sms_enabled), checked).apply());

        // TTS toggle
        SwitchMaterial swTts = findViewById(R.id.swTtsEnabled);
        swTts.setChecked(prefs.getBoolean(getString(R.string.key_tts_enabled), true));
        swTts.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(getString(R.string.key_tts_enabled), checked).apply());

        // API base URL
        TextInputEditText etApiUrl = findViewById(R.id.etApiUrl);
        String defaultUrl = getString(R.string.api_url_default);
        etApiUrl.setText(prefs.getString(getString(R.string.key_api_base_url), defaultUrl));
        etApiUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String url = etApiUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    url = defaultUrl;
                    etApiUrl.setText(url);
                }
                prefs.edit().putString(getString(R.string.key_api_base_url), url).apply();
            }
        });

        // API Key
        TextInputEditText etApiKey = findViewById(R.id.etApiKey);
        etApiKey.setText(prefs.getString(getString(R.string.key_api_key), ""));
        etApiKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                prefs.edit().putString(getString(R.string.key_api_key),
                        etApiKey.getText().toString().trim()).apply();
            }
        });

        // Mobile data toggle
        SwitchMaterial swMobileData = findViewById(R.id.swMobileDataEnabled);
        swMobileData.setChecked(prefs.getBoolean(getString(R.string.key_mobile_data_enabled), false));
        swMobileData.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(getString(R.string.key_mobile_data_enabled), checked).apply());

        // Async detection toggle
        SwitchMaterial swAsyncDetect = findViewById(R.id.swAsyncDetect);
        swAsyncDetect.setChecked(prefs.getBoolean(getString(R.string.key_async_detect), false));
        swAsyncDetect.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(getString(R.string.key_async_detect), checked).apply());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
}
