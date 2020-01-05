package hagego.phonefinder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttPingSender;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import java.util.Calendar;

import static android.content.Context.ALARM_SERVICE;

/**
 * Implementation of the pahi MqttPingSender interface for Android, using AlarmManager iso Java Timer
 * (which are not reliable at all in an Android service)
 */
public class MqttAndroidPingSender implements MqttPingSender {

    private static final String TAG                  = MqttAndroidPingSender.class.getSimpleName();   // logging tag
    private static final String ACTION_WAKEUP_PHONE  = "hagego.phonefinder.wakeup_phone";             // action to wake up phone in time for MQTT ping messages

    private ClientComms comms;
    private Context context;
    private AlarmManager alarmManager;
    private PendingIntent pendingIntent;

    MqttAndroidPingSender(Context context) {
        Log.d(TAG,"MqttAndroidPingSender constructor called");

        this.context = context;
        alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        context.registerReceiver(new MqttPingWakeupReceiver(),new IntentFilter(ACTION_WAKEUP_PHONE));
    }

    public void init(ClientComms comms) {
        Log.d(TAG,"init called");

        if (comms == null) {
            throw new IllegalArgumentException("ClientComms cannot be null.");
        }
        this.comms = comms;
    }

    public void start() {
        Log.d(TAG,"start called");

        pendingIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_WAKEUP_PHONE),
                PendingIntent.FLAG_UPDATE_CURRENT);

        // schedule first ping
        int delayInMilliseconds = Math.toIntExact(comms.getKeepAlive());
        if(delayInMilliseconds<60000) {
            delayInMilliseconds = 60000;
        }

        Log.d(TAG, "scheduling next ping in " + delayInMilliseconds+ " ms");
        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.MILLISECOND, Math.toIntExact(delayInMilliseconds));

        // schedule next pin in a window that allows a max. of 40% extra time, based on MQTT standard/recommendation
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP,wakeUpTime.getTimeInMillis(), (long) (delayInMilliseconds*0.4),pendingIntent);
    }

    public void stop() {
        Log.d(TAG,"stop called");

        if(pendingIntent!=null) {
            Log.d(TAG,"cancelling alarm");

            alarmManager.cancel(pendingIntent);
            pendingIntent = null;
        }
    }

    public void schedule(long delayInMilliseconds) {
        Log.d(TAG,"schedule called, delay in seconds="+delayInMilliseconds/1000);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_WAKEUP_PHONE),
                PendingIntent.FLAG_UPDATE_CURRENT);

        if(delayInMilliseconds<60000) {
            delayInMilliseconds = 60000;
        }

        Log.d(TAG, "scheduling next ping in " + delayInMilliseconds+ " ms");
        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.MILLISECOND, Math.toIntExact(delayInMilliseconds));

        // schedule next pin in a window that allows a max. of 40% extra time, based on MQTT standard/recommendation
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP,wakeUpTime.getTimeInMillis(), (long) (delayInMilliseconds*0.4),pendingIntent);
    }

    private class MqttPingWakeupReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"MqttPingWakeupReceiver triggered. Sending ping.");
            pendingIntent = null;

            comms.checkForActivity();
        }
    }
}


