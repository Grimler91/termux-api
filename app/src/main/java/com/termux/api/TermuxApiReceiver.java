package com.termux.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.termux.api.util.TermuxApiLogger;

public class TermuxApiReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            doWork(context, intent);
        } catch (Exception e) {
            // Make sure never to throw exception from BroadCastReceiver to avoid "process is bad"
            // behaviour from the Android system.
            TermuxApiLogger.error("Error in TermuxApiReceiver", e);
        }
    }

    private void doWork(Context context, Intent intent) {
        String apiMethod = intent.getStringExtra("api_method");
        if (apiMethod == null) {
            TermuxApiLogger.error("Missing 'api_method' extra");
            return;
        }

        Intent myService = new Intent(context, TermuxApiService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(myService);
        } else {
            context.startService(myService);
        }
    }

}
