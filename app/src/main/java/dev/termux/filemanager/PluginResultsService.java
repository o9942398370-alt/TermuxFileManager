package dev.termux.filemanager;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

/** Receives the result bundle sent back by Termux's documented RUN_COMMAND API. */
public class PluginResultsService extends IntentService {
    public static final String ACTION_RESULT = "dev.termux.filemanager.ACTION_TERMUX_RESULT";
    public static final String EXTRA_EXECUTION_ID = "execution_id";
    public static final String EXTRA_STDOUT = "result_stdout";
    public static final String EXTRA_STDERR = "result_stderr";
    public static final String EXTRA_EXIT_CODE = "result_exit_code";
    public static final String EXTRA_ERRMSG = "result_errmsg";

    private static int nextId = 1000;

    public PluginResultsService() {
        super("TermuxFileManagerResults");
    }

    public static synchronized int getNextExecutionId() {
        return nextId++;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        Bundle bundle = intent.getBundleExtra("result");
        if (bundle == null) {
            Intent result = new Intent(ACTION_RESULT);
            result.setPackage(getPackageName());
            result.putExtra(EXTRA_EXECUTION_ID, intent.getIntExtra(EXTRA_EXECUTION_ID, 0));
            result.putExtra(EXTRA_STDERR, "Termux returned no result bundle. Check that Termux is up to date and allow-external-apps=true is enabled.");
            result.putExtra(EXTRA_EXIT_CODE, -1);
            sendBroadcast(result);
            return;
        }

        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_EXECUTION_ID, intent.getIntExtra(EXTRA_EXECUTION_ID, 0));
        result.putExtra(EXTRA_STDOUT, bundle.getString("stdout", ""));
        result.putExtra(EXTRA_STDERR, bundle.getString("stderr", ""));
        result.putExtra(EXTRA_EXIT_CODE, bundle.getInt("exitCode", -1));
        result.putExtra(EXTRA_ERRMSG, bundle.getString("errmsg", ""));
        sendBroadcast(result);
    }
}
