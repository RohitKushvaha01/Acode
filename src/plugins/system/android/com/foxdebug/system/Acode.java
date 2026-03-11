package com.foxdebug.system;

import android.app.Application;
import android.util.Log;

public class Acode extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("Acode", "App started");

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler(
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable ex) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    String stackTrace = sw.toString();

                    String errorMsg = String.format(
                        "Uncaught Exception: %s\nStack trace: %s",
                        ex.getMessage(),
                        stackTrace
                    );

                    sendLogToJavaScript("error", errorMsg);

                    // rethrow to the default handler
                    Thread.getDefaultUncaughtExceptionHandler()
                        .uncaughtException(thread, ex);
                }
            }
        );
    }
}