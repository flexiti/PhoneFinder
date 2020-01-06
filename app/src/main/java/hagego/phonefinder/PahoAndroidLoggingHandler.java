package hagego.phonefinder;

import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;

import java.util.logging.*;

/**
 * Make JUL work on Android.
 */
public class PahoAndroidLoggingHandler extends Handler {

    public static void reset(Handler rootHandler) {
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }
        rootLogger.addHandler(rootHandler);
    }

    @Override
    public void close() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void publish(LogRecord record) {
        if (!super.isLoggable(record))
            return;

        String name = record.getLoggerName();
        int maxLength = 30;
        String tag = name.length() > maxLength ? name.substring(name.length() - maxLength) : name;

        try {
            int level = getAndroidLevel(record.getLevel());
            if(level==Log.ERROR) {
                HyperLog.e(tag, record.getMessage());
                if (record.getThrown() != null) {
                    HyperLog.e(tag, Log.getStackTraceString(record.getThrown()));
                }
            }
            else if(level==Log.WARN) {
                HyperLog.w(tag, record.getMessage());
                if (record.getThrown() != null) {
                    HyperLog.w(tag, Log.getStackTraceString(record.getThrown()));
                }
            }
            else if(level==Log.INFO) {
                HyperLog.i(tag, record.getMessage());
                if (record.getThrown() != null) {
                    HyperLog.i(tag, Log.getStackTraceString(record.getThrown()));
                }
            }
            else {
                HyperLog.d(tag, record.getMessage());
                if (record.getThrown() != null) {
                    HyperLog.d(tag, Log.getStackTraceString(record.getThrown()));
                }
            }

        } catch (RuntimeException e) {
            Log.e("PahoAndroidLoggingHandler", "Error logging message.", e);
        }
    }

    static int getAndroidLevel(Level level) {
        int value = level.intValue();

        if (value >= Level.SEVERE.intValue()) {
            return Log.ERROR;
        } else if (value >= Level.WARNING.intValue()) {
            return Log.WARN;
        } else if (value >= Level.INFO.intValue()) {
            return Log.INFO;
        } else {
            return Log.DEBUG;
        }
    }
}

