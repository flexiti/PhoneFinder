package hagego.phonefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;

import java.util.Objects;

/**
 * helper class to register the service for automatic start
 */
public class PhoneFinderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG,"received message: "+intent.getAction());
        if (Objects.requireNonNull(intent.getAction()).equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG,"received boot completed message");

            // start the service
            Intent intentStartService = new Intent(context, PhoneFinderService.class);
            intentStartService.setAction(Intent.ACTION_RUN);
            context.startForegroundService(intentStartService);
        }

        if(intent.getAction().equals("android.net.wifi.STATE_CHANGE")) {
            Parcelable p = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

            if(p.getClass()==NetworkInfo.class) {
                NetworkInfo networkInfo=(NetworkInfo)p;
                if(networkInfo.getType()==ConnectivityManager.TYPE_WIFI) {
                    NetworkInfo.DetailedState detailedState = networkInfo.getDetailedState();
                    Log.d(TAG,"received WIFI status changed message, detailed state="+detailedState);

                    if (detailedState == NetworkInfo.DetailedState.DISCONNECTED) {
                        Log.d(TAG, "WIFI is disconnected");
                        if (phoneFinderService != null) {
                            phoneFinderService.disconnect();
                        }
                    }

                    if(detailedState==NetworkInfo.DetailedState.CONNECTED) {
                        Log.d(TAG,"WIFI is connected");

                        WifiManager wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        Log.d(TAG,"SSID="+wifiInfo.getSSID()+" HOME WLAN SSID="+wlanSSID);

                        // getSSID returns SSID enclosed in quotes...???
                        if(wlanSSID!=null && wifiInfo.getSSID().contains(wlanSSID)) {
                            Log.d(TAG,"HOME WLAN detected. Calling service connect function");
                            if (phoneFinderService != null) {
                                phoneFinderService.connect();
                            }
                        }
                    }
                }
            }
        }
    }

    void setService(PhoneFinderService service) {
        phoneFinderService = service;
    }

    void setWlanSSID(String ssid) {
        wlanSSID = ssid;
    }

    //
    private static final String TAG = PhoneFinderReceiver.class.getSimpleName();   // logging tag

    private PhoneFinderService phoneFinderService = null;
    private String             wlanSSID           = null;
}
