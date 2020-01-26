package hagego.phonefinder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import static android.app.Notification.EXTRA_NOTIFICATION_ID;
import static android.content.Intent.ACTION_RUN;

public class PhoneFinderService extends Service implements MqttCallbackExtended, IMqttMessageListener {
    public PhoneFinderService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // initialize Hyperlog remote logging
        HyperLog.initialize(this);
        HyperLog.setLogLevel(BuildConfig.DEBUG ? Log.VERBOSE : Log.ERROR);

        HyperLog.d(TAG, "onCreate called");
        HyperLog.d(TAG,"build type was: "+BuildConfig.BUILD_TYPE);

        // turn on logging of PAHO client library
        if(BuildConfig.DEBUG) {
            PahoAndroidLoggingHandler.reset(new PahoAndroidLoggingHandler());
            java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3").setLevel(Level.FINE);
        }

        // callback for WIFI connect state changes
        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                HyperLog.d(TAG, "network callback: onAvailable called");

                if(isInHomeNetwork()) {
                    HyperLog.d(TAG, "home network detected, calling connect() in 10s");
                    connectionCounter = 2;

                    // starting connect in 10 seconds
                    Timer timerObj = new Timer();
                    TimerTask timerTaskObj = new TimerTask() {
                        public void run() {
                            HyperLog.d(TAG,"calling connect() now");
                            connect();
                        }
                    };
                    timerObj.schedule(timerTaskObj, 10000);
                }
            }

            @Override
            public void onLost(Network network) {
                HyperLog.d(TAG, "network callback: onLost called. Disconnecting");

                disconnect();
            }
        };

        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        connectivityManager.registerNetworkCallback(request, networkCallback);

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

        // create intent to start main activity by the notification
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        // now create the mandatory notification for this foreground service
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_RUNNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_running_title))
                .setContentText(getString(R.string.notification_running_text))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        startForeground(1,builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        HyperLog.d(TAG,"onBind() called");

        sendStatusBroadcast();

        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        HyperLog.d(TAG,"onUnbind() called");

        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        HyperLog.d(TAG,"onRebind() called");

        sendStatusBroadcast();
    }

    @Override
    public void onDestroy() {
        HyperLog.d(TAG,"onDestroy() called");

        // disconnect MQTT
        disconnect();
        mqttClient = null;
    }


    /**
     * creates a new MQTT client object
     * @return new MqttAsyncClient object
     */
    MqttAsyncClient createMqttClient() {
        HyperLog.d(TAG,"createMqttClient() called");

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyServer  = getString(R.string.preference_key_mqtt_uri);
        String keyPhoneID = getString(R.string.preference_key_phone_id);
        if (preferences.contains(keyServer) && preferences.contains(keyPhoneID)) {
            HyperLog.d(TAG, "preferences found in createMqttClient()");
            String server = preferences.getString(keyServer, null);
            phoneId = preferences.getString(keyPhoneID, null);
            HyperLog.d(TAG, "MQTT server: " + server + " Phone ID:" + phoneId);

            if (server != null && phoneId != null) {
                // create client object
                String uri = "tcp://"+server;
                String clientID = "PhoneFinder"+phoneId;

                try {
                    return new MqttAsyncClient(uri, clientID, new MemoryPersistence(),new MqttAndroidPingSender(this));
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
        HyperLog.d(TAG,"connect() called");
        connectionCounter--;

        if(mqttClient == null) {
            HyperLog.d(TAG, "MQTT client object is null in connect()");
            mqttClient = createMqttClient();
        }
        if(mqttClient!=null) {
            if(mqttClient.isConnected()) {
                HyperLog.d(TAG,"already connected");
            }
            else {
                if (phoneId!=null) {
                    String clientID = "PhoneFinder"+phoneId;
                    HyperLog.d(TAG, "connecting to MQTT server, client ID=" + clientID+" keepalive="+MQTT_KEEPALIVE);
                    MqttConnectOptions connectOptions = new MqttConnectOptions();
                    connectOptions.setAutomaticReconnect(true);
                    connectOptions.setCleanSession(true);
                    connectOptions.setKeepAliveInterval(MQTT_KEEPALIVE);

                    mqttClient.setCallback(this);
                    try {
                        mqttClient.connect(connectOptions);
                        Log.d(TAG, "connection request to MQTT server was sent successfully");

                        Timer timerObj = new Timer();
                        TimerTask timerTaskObj = new TimerTask() {
                            public void run() {
                                HyperLog.d(TAG,"checking connection request");
                                if(!mqttClient.isConnected()) {
                                    HyperLog.d(TAG,"still not connected, counter="+connectionCounter);
                                    if(connectionCounter>0) {
                                        HyperLog.d(TAG,"retrying...");
                                        connect();
                                    }
                                }
                                else {
                                    HyperLog.d(TAG,"connection OK.");
                                }
                            }
                        };
                        timerObj.schedule(timerTaskObj, 10000);
                    } catch (MqttException e) {
                        Log.e(TAG, "connection request to MQTT server failed, client ID="+clientID+": ",e);
                        Log.d(TAG, "connection request to MQTT server failed, client ID="+clientID+": ",e);
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
        HyperLog.d(TAG,"disconnect() called");

        try {
            if(mqttClient!=null) {
                if(mqttClient.isConnected()) {
                    mqttClient.disconnect();
                    HyperLog.d(TAG,"MQTT client got disconnected");
                }
                else {
                    HyperLog.d(TAG,"MQTT client is already disconnected");
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
    public int onStartCommand(Intent intent,int flags,int startId) {
        HyperLog.d(TAG, "onStartCommand() called. Action="+intent.getAction());

        if(intent.getAction()!=null && intent.getAction().equals(ACTION_RUN)) {
            Log.i(TAG, "onStartCommand(): received RUN command");
        }

        if(intent.getAction()!=null && intent.getAction().equals(ACTION_STOP_RINGING)) {
            Log.i(TAG, "onStartCommand(): received stop ringing command");

            stopRinging();

            try {
                mqttClient.publish(MQTT_TOPIC_BASE+phoneId,MQTT_TOPIC_VALUE_FOUND.getBytes(),0,false);
            } catch (MqttException e) {
                Log.e(TAG,"Unable to publish MQTT message "+MQTT_TOPIC_VALUE_FOUND);
            }
        }

        return START_STICKY;
    }

    /**
     * triggers the search for a phone with the specified name
     * @param phoneName phone to search for
     */
    public void triggerPhoneSearch(String phoneName) {
        Log.d(TAG,"triggerPhoneSearch called for phone "+phoneName);

        if(mqttClient==null) {
            Log.w(TAG,"triggerPhoneSearch: MQTT client still null");
            return;
        }

        String topic = MQTT_TOPIC_BASE+phoneName;

        try {
            mqttClient.publish(topic, MQTT_TOPIC_VALUE_TRIGGER.getBytes(), 0, false);
            Log.d(TAG,"Successfully published MQTT topic "+topic+", data="+MQTT_TOPIC_VALUE_TRIGGER);
        } catch (MqttException e) {
            Log.e(TAG,"Exception during publishing of MQTT topic "+topic+", data="+MQTT_TOPIC_VALUE_TRIGGER,e);
        }
    }


    /*
     * overrides for MqttCallbackExtended and IMqttMessageListener
     */

    @Override
    public void connectionLost(Throwable cause) {
        HyperLog.d(TAG,"MQTT connection lost: "+cause.getMessage(),cause);

        sendStatusBroadcast();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String data = message.toString();
        HyperLog.d(TAG,"MQTT message arrived, topic="+topic+" content="+data);

        if(data.equals(MQTT_TOPIC_VALUE_TRIGGER)) {
            HyperLog.d(TAG,"received MQTT command: trigger");

            Intent stopIntent = new Intent(this, PhoneFinderService.class);
            stopIntent.setAction(ACTION_STOP_RINGING);
            stopIntent.putExtra(EXTRA_NOTIFICATION_ID, 0);
            PendingIntent foundPendingIntent =
                    PendingIntent.getForegroundService(this, 0, stopIntent, 0);

            // create notification
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ACTIVE)
                    .setSmallIcon(R.mipmap.ic_launcher)
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
                    HyperLog.d(TAG,"timer to stop ringing expired");
                    stopRinging();
                }
            };
            timerObj.schedule(timerTaskObj, STOP_RINGING_TIMER_DEFFAULT*1000);
        }

        sendStatusBroadcast();
    }


    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        HyperLog.d(TAG,"MQTT connection succeeded, reconnect="+reconnect);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyPhoneID = getString(R.string.preference_key_phone_id);
        if (preferences.contains(keyPhoneID)) {
            String id = preferences.getString(keyPhoneID, null);
            if(id!=null) {
                String topic = MQTT_TOPIC_BASE+id;
                HyperLog.d(TAG,"subscribing for topic "+topic);
                try {
                    mqttClient.subscribe(topic, 0, this);
                    HyperLog.d(TAG,"subscription of topic "+topic+ " successful.");
                } catch (MqttException e) {
                    Log.e(TAG, "MQTT subscribe failed: ", e);
                }
            }
        }

        sendStatusBroadcast();
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
     * checks if we are in our HOME WLAN network
     * @return true if we are in the registered HOME netwrok
     */
    private boolean isInHomeNetwork() {
        HyperLog.d(TAG,"isInHomeNetwork() called");

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyWlanSSID  = getString(R.string.preference_key_wlan_ssid);
        String wlanSSID = null;
        if (preferences.contains(keyWlanSSID) ) {
            wlanSSID = preferences.getString(keyWlanSSID, null);
            HyperLog.d(TAG,"found WLAN SSID in preferences, value="+wlanSSID);
        }
        else {
            HyperLog.d(TAG,"No WLAN SSID found in preferences");
        }

        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        String activeSSID = wifiManager.getConnectionInfo().getSSID();
        HyperLog.d(TAG,"active WIFI SSID="+activeSSID);

        // activeSSID has SSID enclosed in quotes
        if(wlanSSID!=null && activeSSID!=null && activeSSID.contains(wlanSSID)) {
            HyperLog.d(TAG, "HOME WLAN detected.");
            return true;
        }

        return false;
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
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    class LocalBinder extends Binder {
        PhoneFinderService getService() {
            // Return this instance of LocalService so clients can call public methods
            return PhoneFinderService.this;
        }
    }

    //
    // member data
    //
    private static final String TAG = PhoneFinderService.class.getSimpleName();   // logging tag

    // Binder given to clients
    private final IBinder binder = new LocalBinder();

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

    static final int    STOP_RINGING_TIMER_DEFFAULT = 60;                                 // default timeout in seconds to stop ringing
    static final int    MQTT_KEEPALIVE              = 900;                                // MQTT timeout interval

    static final String STATUS_MQTT_CONNECTED       = "hagego.phonefinder.mqtt_connected";

    private MqttAsyncClient     mqttClient        = null;
    private Ringtone            ringtone          = null;
    private String              phoneId           = null;
    private int                 connectionCounter = 0;           // counts connection retries
}
