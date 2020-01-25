package hagego.phonefinder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;

import org.eclipse.paho.client.mqttv3.MqttPingSender;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import static android.content.Context.ALARM_SERVICE;

/**
 * Implementation of the pahi MqttPingSender interface for Android, using AlarmManager iso Java Timer
 * (which are not reliable at all in an Android service)
 */
public class MqttAndroidPingSender implements MqttPingSender {

    private static final String TAG                      = MqttAndroidPingSender.class.getSimpleName();   // logging tag
    private static final String ACTION_WAKEUP_PHONE      = "hagego.phonefinder.wakeup_phone";             // action to wake up phone in time for MQTT ping messages
    private static final double PING_REQUEST_WINDOW_SIZE = 0.3;                                           // relative time window size for ping request

    private ClientComms comms;
    private Context context;
    private AlarmManager alarmManager;
    private PendingIntent pendingIntent;
    private Timer timer;

    MqttAndroidPingSender(Context context) {
        HyperLog.d(TAG,"MqttAndroidPingSender constructor called");

        this.context = context;
        alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        context.registerReceiver(new MqttPingWakeupReceiver(),new IntentFilter(ACTION_WAKEUP_PHONE));
    }

    public void init(ClientComms comms) {
        HyperLog.d(TAG,"init called");

        if (comms == null) {
            throw new IllegalArgumentException("ClientComms cannot be null.");
        }
        this.comms = comms;
    }

    public void start() {
        HyperLog.d(TAG,"start called");

        pendingIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_WAKEUP_PHONE),
                PendingIntent.FLAG_UPDATE_CURRENT);
        timer = new Timer();

        // schedule first ping
        int delayInMilliseconds = Math.toIntExact(comms.getKeepAlive());

        if(delayInMilliseconds<10000) {
            HyperLog.d(TAG, "scheduling next ping over Timer in " + delayInMilliseconds + " ms");
            timer = new Timer("MQTT TimerPing");
            //Check ping after first keep alive interval.
            timer.schedule(new TimerPingTask(), comms.getKeepAlive());
        }
        else {
            HyperLog.d(TAG, "scheduling next ping over Calendar in " + delayInMilliseconds + " ms");
            Calendar wakeUpTime = Calendar.getInstance();
            wakeUpTime.add(Calendar.MILLISECOND, Math.toIntExact(delayInMilliseconds));

            // schedule next pin in a window that allows a max. of 40% extra time, based on MQTT standard/recommendation
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(), (long) (delayInMilliseconds * PING_REQUEST_WINDOW_SIZE), pendingIntent);
        }
    }

    public void stop() {
        HyperLog.d(TAG,"stop called");

        if(pendingIntent!=null) {
            HyperLog.d(TAG,"cancelling calendar task");

            alarmManager.cancel(pendingIntent);
            pendingIntent = null;
        }

        if(timer != null){
            HyperLog.d(TAG,"cancelling Timer task");

            timer.cancel();
            timer = null;
        }
    }

    public void schedule(long delayInMilliseconds) {
        HyperLog.d(TAG,"schedule called, delay in ms="+delayInMilliseconds);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_WAKEUP_PHONE),
                PendingIntent.FLAG_UPDATE_CURRENT);

        // in case of small delays < 10s use Java timer, else use Android Calendar
        if(delayInMilliseconds<10000) {
            HyperLog.d(TAG, "scheduling next ping over Timer in " + delayInMilliseconds + " ms");

            timer.schedule(new TimerPingTask(), delayInMilliseconds);
        }
        else {
            HyperLog.d(TAG, "scheduling next ping over Calendar in " + delayInMilliseconds + " ms");
            Calendar wakeUpTime = Calendar.getInstance();
            wakeUpTime.add(Calendar.MILLISECOND, Math.toIntExact(delayInMilliseconds));

            // schedule next pin in a window that allows a max. of 40% extra time, based on MQTT standard/recommendation
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(), (long) (delayInMilliseconds * PING_REQUEST_WINDOW_SIZE), pendingIntent);
        }
    }

    /**
     * private class used to schedule next ping using Android Calendar
     */
    private class MqttPingWakeupReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            HyperLog.d(TAG,"MqttPingWakeupReceiver triggered. Sending ping.");
            pendingIntent = null;

            comms.checkForActivity();
        }
    }

    /**
     * private class used to schedule next ping using Java Timer
     */
    private class TimerPingTask extends TimerTask {

        @Override
        public void run() {
            HyperLog.d(TAG,"TimerPingTask triggered. Sending ping.");
            comms.checkForActivity();
        }
    }
}


