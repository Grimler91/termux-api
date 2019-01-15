package com.termux.api;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.util.Log;

import com.termux.api.util.ResultReturner;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class JobSchedulerAPI {

    private static final String LOG_TAG = "JobSchedulerAPI";


    static void onReceive(final Context context, final JSONObject opts) {

        final String scriptPath = opts.optString("script");

        final int periodicMillis = opts.optInt("period_ms", 0);
        final int jobId = opts.optInt("job_id", 0);
        final String networkType = opts.optString("network");
        final boolean batteryNotLow = opts.optBoolean("battery_not_low", true);
        final boolean charging = opts.optBoolean("charging", false);
        final boolean idle = opts.optBoolean("idle", false);
        final boolean storageNotLow = opts.optBoolean("storage_not_low", false);

        int networkTypeCode = JobInfo.NETWORK_TYPE_NONE;
        if (networkType != null) {
            switch (networkType) {
                case "any":
                    networkTypeCode = JobInfo.NETWORK_TYPE_ANY;
                    break;
                case "unmetered":
                    networkTypeCode = JobInfo.NETWORK_TYPE_UNMETERED;
                    break;
                case "cellular":
                    networkTypeCode = JobInfo.NETWORK_TYPE_CELLULAR;
                    break;
                case "not_roaming":
                    networkTypeCode = JobInfo.NETWORK_TYPE_NOT_ROAMING;
                    break;
                default:
                case "none":
                    networkTypeCode = JobInfo.NETWORK_TYPE_NONE;
                    break;
            }
        }
        if (scriptPath == null) {
            ResultReturner.returnData(context, out -> out.println("No script path given"));
            return;
        }
        final File file = new File(scriptPath);
        final String fileCheckMsg;
        if (!file.isFile()) {
            fileCheckMsg = "No such file: %s";
        } else if (!file.canRead()) {
            fileCheckMsg = "Cannot read file: %s";
        } else if (!file.canExecute()) {
            fileCheckMsg = "Cannot execute file: %s";
        } else {
            fileCheckMsg = "";
        }

        if (!fileCheckMsg.isEmpty()) {
            ResultReturner.returnData(context, out -> out.println(String.format(fileCheckMsg, scriptPath)));
            return;
        }

        PersistableBundle extras = new PersistableBundle();
        extras.putString(SchedulerJobService.SCRIPT_FILE_PATH, file.getAbsolutePath());


        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        // Display pending jobs
        for (JobInfo job : jobScheduler.getAllPendingJobs()) {
            final JobInfo j = job;
            ResultReturner.returnData(context, out -> out.println(String.format(Locale.ENGLISH, "Pending job %d %s", j.getId(), j.toString())));
        }

        ComponentName serviceComponent = new ComponentName(context, SchedulerJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(jobId, serviceComponent)
                .setExtras(extras)
                .setRequiredNetworkType(networkTypeCode)
                .setRequiresCharging(charging)
                .setRequiresDeviceIdle(idle);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = builder.setRequiresBatteryNotLow(batteryNotLow);
            builder = builder.setRequiresStorageNotLow(storageNotLow);
        }

        if (periodicMillis > 0) {
            builder = builder.setPeriodic(periodicMillis);
        }

        JobInfo job = builder.build();

        final int scheduleResponse = jobScheduler.schedule(job);

        Log.i(LOG_TAG, String.format("Scheduled job %d to call %s every %d ms - response %d",
                jobId, scriptPath, periodicMillis, scheduleResponse));
        ResultReturner.returnData(context, out -> out.println(String.format(Locale.ENGLISH,"Scheduled job %d to call %s every %d ms - response %d",
                jobId, scriptPath, periodicMillis, scheduleResponse)));
    }

}
