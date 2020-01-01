package hagego.phonefinder;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import static android.app.Notification.EXTRA_NOTIFICATION_ID;

public class PhoneFinderService extends Service implements MqttCallbackExtended, IMqttMessageListener {
    public PhoneFinderService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG,"onBind() called");

        sendStatusBroadcast();

        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG,"onUnbind() called");

        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG,"onRebind() called");

        sendStatusBroadcast();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG,"onDestroy() called");

        // disconnect MQTT
        disconnect();
        mqttClient = null;
    }


    /**
     * creates a new MQTT client object
     * @return new MqttAsyncClient object
     */
    MqttAsyncClient createMqttClient() {
        Log.d(TAG,"createMqttClient() called");

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyServer  = getString(R.string.preference_key_mqtt_uri);
        String keyPhoneID = getString(R.string.preference_key_phone_id);
        if (preferences.contains(keyServer) && preferences.contains(keyPhoneID)) {
            Log.d(TAG, "preferences found in createMqttClient()");
            String server = preferences.getString(keyServer, null);
            phoneId = preferences.getString(keyPhoneID, null);
            Log.d(TAG, "MQTT server: " + server + " Phone ID:" + phoneId);

            if (server != null && phoneId != null) {
                // create client object
                String uri = "tcp://"+server;
                String clientID = "PhoneFinder"+phoneId;

                try {
                    return new MqttAsyncClient(uri, clientID, new MemoryPersistence());
                } catch (MqttException e) {
                    Log.e(TAG,"Exception during creation of Mqtt client: ",e);
                }
            } else {
                Log.w(TAG, "invalid preferences in createMqttClient(). MQTT server=" + server + " Phone ID=" + phoneId);
            }
        } else {
            Log.w(TAG, "no preferences found in createMqttClient()");
        }

        return null;
    }

    /**
     * connects to the MQTT server
     */
    void connect() {
        Log.d(TAG,"connect() called");
        if(mqttClient == null) {
            Log.d(TAG, "MQTT client object is null in connect()");
            mqttClient = createMqttClient();
        }
        if(mqttClient!=null) {
            if(mqttClient.isConnected()) {
                Log.d(TAG,"already connected");
            }
            else {
                if (phoneId!=null) {
                    String clientID = "PhoneFinder"+phoneId;
                    Log.d(TAG, "connecting to MQTT server, client ID=" + clientID+" keepalive="+MQTT_KEEPALIVE);
                    MqttConnectOptions connectOptions = new MqttConnectOptions();
                    connectOptions.setAutomaticReconnect(true);
                    connectOptions.setCleanSession(true);
                    connectOptions.setKeepAliveInterval(MQTT_KEEPALIVE);

                    mqttClient.setCallback(this);
                    try {
                        mqttClient.connect(connectOptions);
                        Log.i(TAG, "connection request to MQTT server was sent successfully");
                    } catch (MqttException e) {
                        Log.e(TAG, "connection request to MQTT server failed, client ID="+clientID+": ",e);
                    } finally {
                        sendStatusBroadcast();
                    }
                }
            }
        }
    }

    /**
     * disconnects from the MQTT server
     */
    void disconnect() {
        Log.d(TAG,"disconnect() called");

        try {
            if(mqttClient!=null) {
                if(mqttClient.isConnected()) {
                    mqttClient.disconnect();
                    Log.d(TAG,"MQTT client got disconnected");
                }
                else {
                    Log.d(TAG,"MQTT client is already disconnected");
                }
            }
        } catch (MqttException e) {
            Log.e(TAG,"MQTT Server disconnect failed: ",e);
        }
        finally {
            sendStatusBroadcast();
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called");
        super.onCreate();

        // turn on logging of PAHO client library
        // AndroidLoggingHandler.reset(new AndroidLoggingHandler());
        // java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3").setLevel(Level.FINEST);

        // create an Notification channel for the mandatory foreground service notification
        NotificationChannel channelRunning = new NotificationChannel(NOTIFICATION_CHANNEL_RUNNING,
                getString(R.string.notification_channel_running_name),
                NotificationManager.IMPORTANCE_NONE);
        channelRunning.setDescription(getString(R.string.notification_channel_running_description));

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channelRunning);

        // create an Notification channel for the real notification when someone searches the phone
        NotificationChannel channelActive = new NotificationChannel(NOTIFICATION_CHANNEL_ACTIVE,
                getString(R.string.notification_channel_active_name),
                NotificationManager.IMPORTANCE_HIGH);
        channelActive.setDescription(getString(R.string.notification_channel_active_description));

        notificationManager.createNotificationChannel(channelActive);

        // create receiver for dummy wake-up messages
        registerReceiver(new MqttPingWakeupReceiver(),new IntentFilter(ACTION_WAKEUP_PHONE));

        // create intent to start main activity by the notification
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        // now create the mandatory notification for this foreground service
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_RUNNING)
                .setSmallIcon(R.mipmap.icons8_search_icon)
                .setContentTitle(getString(R.string.notification_running_title))
                .setContentText(getString(R.string.notification_running_text))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        startForeground(1,builder.build());
    }


    @Override
    public int onStartCommand(Intent intent,int flags,int startId) {
        Log.d(TAG, "onStartCommand() called. Action="+intent.getAction());

        if(intent.getAction()!=null && intent.getAction().equals(ACTION_STOP_RINGING)) {
            Log.i(TAG, "onStartCommand(): received stop ringing command");

            stopRinging();

            try {
                mqttClient.publish(MQTT_TOPIC_BASE+phoneId,MQTT_TOPIC_VALUE_FOUND.getBytes(),0,false);
            } catch (MqttException e) {
                Log.e(TAG,"Unable to publish MQTT message "+MQTT_TOPIC_VALUE_FOUND);
            }
        }

        if(intent.getAction()!=null && intent.getAction().equals(Intent.ACTION_RUN)) {
            Log.i(TAG, "onStartCommand(): received RUN command");
            connect();

            // register a receiver for WIFI changes
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            PhoneFinderReceiver receiver = new PhoneFinderReceiver();
            receiver.setService(this);
            registerReceiver(receiver, intentFilter);

            // get Home WLAN SSID from preferences
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            String keyWlanSSID  = getString(R.string.preference_key_wlan_ssid);
            if (preferences.contains(keyWlanSSID) ) {
                String wlanSSID = preferences.getString(keyWlanSSID, null);
                Log.d(TAG,"found WLAN SSID in preferences, value="+wlanSSID);
                receiver.setWlanSSID(wlanSSID);
            }
            else {
                Log.d(TAG,"No WLAN SSID found in preferences");
            }
        }

        return START_STICKY;
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.d(TAG,"MQTT connection lost: "+cause.getMessage(),cause);

        sendStatusBroadcast();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String data = message.toString();
        Log.d(TAG,"MQTT message arrived, topic="+topic+" content="+data);

        if(data.equals(MQTT_TOPIC_VALUE_TRIGGER)) {
            Log.d(TAG,"received MQTT command: trigger");

            Intent stopIntent = new Intent(this, PhoneFinderService.class);
            stopIntent.setAction(ACTION_STOP_RINGING);
            stopIntent.putExtra(EXTRA_NOTIFICATION_ID, 0);
            PendingIntent foundPendingIntent =
                    PendingIntent.getForegroundService(this, 0, stopIntent, 0);

            // create notification
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ACTIVE)
                    .setSmallIcon(R.mipmap.icons8_search_icon)
                    .setContentTitle(getString(R.string.notification_active_title))
                    .setContentText(getString(R.string.notification_active_text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    //.addAction(R.drawable.ic_stat_new_message, getString(R.string.notification_active_action_stop),foundPendingIntent)
                    .setContentIntent(foundPendingIntent)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setAutoCancel(true);

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.notify(2, builder.build());

            startRinging();

            // schedule a timer to stop ringing in case no-one stops manually
            Timer timerObj = new Timer();
            TimerTask timerTaskObj = new TimerTask() {
                public void run() {
                    Log.d(TAG,"timer to stop ringing expired");
                    stopRinging();
                }
            };
            timerObj.schedule(timerTaskObj, STOP_RINGING_TIMER_DEFFAULT*1000);
        }

        sendStatusBroadcast();
    }


    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        Log.d(TAG,"MQTT connection succeeded, reconnect="+reconnect);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyPhoneID = getString(R.string.preference_key_phone_id);
        if (preferences.contains(keyPhoneID)) {
            String id = preferences.getString(keyPhoneID, null);
            if(id!=null) {
                String topic = MQTT_TOPIC_BASE+id;
                Log.d(TAG,"subscribing for topic "+topic);
                try {
                    mqttClient.subscribe(topic, 0, this);
                    Log.d(TAG,"subscription of topic "+topic+ " successful.");
                } catch (MqttException e) {
                    Log.e(TAG, "MQTT subscribe failed: ", e);
                }
            }
        }

        sendStatusBroadcast();

        // trigger wakeup of the phone synchronized with the MQTT keepalive pings
        Log.d(TAG,"scheduling next phone wake-up");
        scheduleNextWakeup();
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    /**
     * starts the ringtone
     */
    private synchronized void startRinging() {
        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }


        // Get the audio manager instance
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        // Get the ringer maximum volume
        int max_volume_level = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);

        // Set the ringer volume
        audioManager.setStreamVolume(
                AudioManager.STREAM_RING,
                max_volume_level,
                AudioManager.FLAG_SHOW_UI
        );

        ringtone = RingtoneManager.getRingtone(this, alarmUri);
        ringtone.setVolume(1.0f);
        ringtone.setLooping(true);
        ringtone.play();

        try {
            mqttClient.publish(MQTT_TOPIC_BASE+phoneId,MQTT_TOPIC_VALUE_RINGING.getBytes(),0,false);
        } catch (MqttException e) {
            Log.e(TAG,"Unable to publish MQTT message "+MQTT_TOPIC_VALUE_RINGING);
        }
    }

    /**
     * stops the ringtone again
     */
    private synchronized void stopRinging() {
        if(ringtone!=null) {
            ringtone.stop();
            ringtone = null;
        }
    }

    /**
     * sends a broadcast message with status details
     */
    private synchronized void sendStatusBroadcast() {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_STATUS);
        intent.putExtra(STATUS_MQTT_CONNECTED,mqttClient!=null && mqttClient.isConnected());

        sendBroadcast(intent);
    }

    /**
     * schedules a wake-up of the phone by sending a dummy action to the MqttPingWakeupReceiver
     * in the same interval as the MQTT keepalive
     */
    private void scheduleNextWakeup()
    {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_WAKEUP_PHONE),
                PendingIntent.FLAG_UPDATE_CURRENT);

        // schedule the next wakeup in the same interval that is used for the MQTT ping
        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.SECOND, MQTT_KEEPALIVE);

        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP,wakeUpTime.getTimeInMillis(),pendingIntent);
    }

    /**
     * This class is used to implement a receiver that is triggered periodically in the same intervals
     * like the MQTT keepalive messages to wake up the device
     */
    public class MqttPingWakeupReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"MqttPingWakeupReceiver triggered");
            // do nothing more but schedule next wake-up if we are connected

            if(mqttClient!=null && mqttClient.isConnected()) {
                Log.d(TAG,"MQTT client is connected, scheduling next wakeup");
                scheduleNextWakeup();
            }
            else {
                Log.d(TAG,"MQTT client is not connected - no wakeup scheduled");
            }
        }
    }

    //
    // member data
    //
    private static final String TAG = PhoneFinderService.class.getSimpleName();   // logging tag

    // MQTT topics
    private static final String MQTT_TOPIC_BASE          = "phonefinder/";       // base MQTT topic name, phone ID gets added
    private static final String MQTT_TOPIC_VALUE_TRIGGER = "trigger";            // sent by other client, when received, phone starts ringing
    private static final String MQTT_TOPIC_VALUE_RINGING = "ringing";            // sent by this app, confirms phone is ringing
    private static final String MQTT_TOPIC_VALUE_FOUND   = "found";              // sent by this app, confirmed phone was found

    private static final String NOTIFICATION_CHANNEL_RUNNING = "RUNNING";         // notification channel ID for required notification as foreground service
    private static final String NOTIFICATION_CHANNEL_ACTIVE  = "ACTIVE";          // notification channel ID for real notification

    // actions used in Intents
    static final String ACTION_STOP_RINGING         = "hagego.phonefinder.stop_ringing";   // used in notification, stops ringing
    static final String ACTION_UPDATE_STATUS        = "hagego.phonefinder.update_status";  // broadcast of status
    static final String ACTION_WAKEUP_PHONE         = "hagego.phonefinder.wakeup_phone";   // dummy action to wake up phone in time for MQTT ping messages

    static final int    STOP_RINGING_TIMER_DEFFAULT = 60;                                 // default timeout in seconds to stop ringing
    static final int    MQTT_KEEPALIVE              = 600;                                // MQTT timeout interval

    static final String STATUS_MQTT_CONNECTED       = "hagego.phonefinder.mqtt_connected";

    private MqttAsyncClient     mqttClient  = null;
    private Ringtone            ringtone    = null;
    private String              phoneId     = null;
}
