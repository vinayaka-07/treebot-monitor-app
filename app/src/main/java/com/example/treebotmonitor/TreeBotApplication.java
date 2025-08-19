package com.example.treebotmonitor;

import android.app.Application;
import android.util.Log;

public class TreeBotApplication extends Application {
    private static final String TAG = "TreeBotApplication";
    private HarvestingBluetoothHelper bluetoothHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TreeBot Application created");
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.d(TAG, "TreeBot Application terminating");

        // Clean up Bluetooth resources when app is completely destroyed
        if (bluetoothHelper != null) {
            bluetoothHelper.cleanup();
            bluetoothHelper = null;
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "TreeBot Application low on memory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "TreeBot Application trimming memory, level: " + level);

        // Only cleanup if the app is likely to be killed
        if (level >= TRIM_MEMORY_COMPLETE) {
            if (bluetoothHelper != null) {
                // Disable auto-reconnect temporarily to save resources
                bluetoothHelper.enableAutoReconnect(false);
            }
        }
    }
}