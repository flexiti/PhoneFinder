package hagego.phonefinder;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG,"onCreate() called");

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        valuePhoneId              = findViewById(R.id.valuePhoneId);
        valueMqttServerConnection = findViewById(R.id.valueMqttServerConnection);

        // start service if not done already
        Intent intent = new Intent(this, PhoneFinderService.class);
        intent.setAction(Intent.ACTION_RUN);
        startService(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            startActivity(new Intent(this,SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();

        Log.d(TAG,"onStart() called");

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG,"receiver onReceive() called with action "+intent.getAction());
                Log.d(TAG,"value of "+PhoneFinderService.STATUS_MQTT_CONNECTED+":"+intent.getBooleanExtra(PhoneFinderService.STATUS_MQTT_CONNECTED,false));

                int mqttServerConnectionId = intent.getBooleanExtra(PhoneFinderService.STATUS_MQTT_CONNECTED,false) ? R.string.ui_value_yes : R.string.ui_value_no;
                valueMqttServerConnection.setText(getString(mqttServerConnectionId));
            }
        };
        IntentFilter intentFilter = new IntentFilter(PhoneFinderService.ACTION_UPDATE_STATUS);
        registerReceiver(receiver,intentFilter);

        Intent intent = new Intent(this,PhoneFinderService.class);
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "onServiceConnected() called");
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "onServiceDisconnected() called");
            }
        };

        bindService(intent,serviceConnection,0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG,"onStop() called");

        unregisterReceiver(receiver);
        unbindService(serviceConnection);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG,"onResume() called");

        // check permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG,"permission ACCESS_FINE_LOCATION not granted yet");
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);
        }

        // UI updates
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String keyPhoneID = getString(R.string.preference_key_phone_id);
        if (preferences.contains(keyPhoneID)) {
            String id = preferences.getString(keyPhoneID, null);
            if (id != null) {
                valuePhoneId.setText(id);
            }
        }
    }

    //
    // member data
    //
    private static final String TAG = MainActivity.class.getSimpleName();   // logging tag

    private BroadcastReceiver receiver;
    private ServiceConnection serviceConnection;

    // UI Elements
    TextView valuePhoneId;
    TextView valueMqttServerConnection;
}
