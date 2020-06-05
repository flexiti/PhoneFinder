package hagego.phonefinder;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.hypertrack.hyperlog.HLCallback;
import com.hypertrack.hyperlog.HyperLog;
import com.hypertrack.hyperlog.error.HLErrorResponse;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;


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
        spinnerPhones             = findViewById(R.id.spinnerPhones);

        // start service if not done already
        Intent intent = new Intent(this, PhoneFinderService.class);
        intent.setAction(Intent.ACTION_RUN);
        startService(intent);

        // create service connection to communicate to our PhoneFinderService
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "onServiceConnected() called");
                PhoneFinderService.LocalBinder binder = (PhoneFinderService.LocalBinder) service;
                phoneFinderService = binder.getService();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "onServiceDisconnected() called");
                phoneFinderService = null;
            }
        };
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

        // add a phone to search
        if (id == R.id.action_add_phone) {
            Log.d(TAG,"add phone dialog called");
            final Context context = this;

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            final View view = getLayoutInflater().inflate(R.layout.dialog_add,null);

            builder.setTitle(R.string.dialog_add_title)
                   .setView(view);

            builder.setPositiveButton(R.string.ok, (dialog, id1) -> {
                // User clicked OK button
                TextView name = view.findViewById(R.id.dialog_add_name);
                Log.d(TAG,"new phone to be added: "+name.getText());
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                @NotNull Set<String> phoneSet = preferences.getStringSet(PREFERENCE_ID_PHONELIST,new HashSet<>() );
                phoneSet.add(name.getText().toString());

                SharedPreferences.Editor editor = preferences.edit();
                editor.remove(PREFERENCE_ID_PHONELIST);
                if( editor.commit() ) {
                    editor.putStringSet(PREFERENCE_ID_PHONELIST, phoneSet);
                    editor.apply();
                }
            });
            builder.setNegativeButton(R.string.cancel, (dialog, id12) -> {
                // User cancelled the dialog - nothing to do
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            return true;
        }

        if (id == R.id.action_delete_phone) {
            Log.d(TAG,"delete phone dialog called");
            final Context context = this;

            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            @NotNull Set<String> phoneSet = preferences.getStringSet(PREFERENCE_ID_PHONELIST, new HashSet<>() );
            for(String phone:phoneSet) {
                Log.d(TAG,"found phone: "+phone);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            final CharSequence[] items = phoneSet.stream().map(s -> (CharSequence) s).sorted().toArray(CharSequence[]::new);
            builder.setTitle(R.string.dialog_delete_title)

                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG,"deleting item index "+which+": "+items[which]);

                            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                            Set<String> phoneSet = preferences.getStringSet(PREFERENCE_ID_PHONELIST,new HashSet<>() );
                            phoneSet.remove(items[which].toString());

                            SharedPreferences.Editor editor = preferences.edit();
                            editor.remove(PREFERENCE_ID_PHONELIST);
                            if(editor.commit()) {
                                editor.putStringSet(PREFERENCE_ID_PHONELIST, phoneSet);
                                editor.apply();
                            }
                        }
                    });

            builder.setNegativeButton(R.string.cancel, (dialog, id13) -> {
                // User cancelled the dialog - nothing to do
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            return true;
        }

        if(id == R.id.action_push_logs) {
            if(BuildConfig.DEBUG) {
                Log.d(TAG, "pushing HyperLog logs to server");
                HyperLog.setURL("https://en8gn1mwhb3rw.x.pipedream.net");
                HyperLog.pushLogs(this, false, new HLCallback() {

                    @Override
                    public void onSuccess(@NonNull Object response) {
                        Log.d(TAG, "HyperLog pushLogs: success");
                    }

                    @Override
                    public void onError(@NonNull HLErrorResponse HLErrorResponse) {
                        Log.d(TAG, "HyperLog pushLogs error: " + HLErrorResponse.getErrorMessage());
                    }
                });
            }
        }

        if(id == R.id.action_delete_logs) {
            HyperLog.deleteLogs();
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

        final String dummyPhone = "-----";
        ArrayList<String> phoneList = new ArrayList<>();
        phoneList.add(dummyPhone);
        phoneList.addAll(preferences.getStringSet(PREFERENCE_ID_PHONELIST, new HashSet<>() ));

        ArrayAdapter<String> spinnerArrayAdapter = new ArrayAdapter<>
                (this, android.R.layout.simple_spinner_item,phoneList);
        spinnerArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerPhones.setAdapter(spinnerArrayAdapter);
        spinnerPhones.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String phone = phoneList.get(position);
                Log.d(TAG,"selected phone to search: "+phone);

                if(!dummyPhone.equals(phone)) {
                    if (phoneFinderService != null) {
                        phoneFinderService.triggerPhoneSearch(phone);
                    } else {
                        Log.e(TAG, "phoneFinderService is null when a phone search should be triggered");
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        spinnerPhones.setSelection(0);
    }


    //
    // member data
    //
    private static final String TAG = MainActivity.class.getSimpleName();   // logging tag
    private static final String PREFERENCE_ID_PHONELIST = "phonelist";

    private  PhoneFinderService phoneFinderService = null;                  // the phone finder service
    private BroadcastReceiver receiver;                                     // required to connect to the service
    private ServiceConnection serviceConnection;

    // UI Elements
    TextView valuePhoneId;
    TextView valueMqttServerConnection;
    Spinner  spinnerPhones;
}
