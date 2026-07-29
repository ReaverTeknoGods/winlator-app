package com.winlator;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal system-permission host used by TPUI's managed setup. It never shows
 * Winlator's container UI and returns to TPUI as soon as Android records the
 * user's shared-game-folder decision.
 */
public final class TeknoParrotProvisioningActivity extends AppCompatActivity {
    private static final int STORAGE_PERMISSION_REQUEST = 0x5450;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("TeknoParrot game access");
        if (hasStoragePermission()) {
            finishWithPermissionResult();
            return;
        }
        requestPermissions(new String[]{
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, STORAGE_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST)
            finishWithPermissionResult();
    }

    private void finishWithPermissionResult() {
        setResult(hasStoragePermission() ? RESULT_OK : RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean hasStoragePermission() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                   PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                   PackageManager.PERMISSION_GRANTED;
    }
}
