package hagego.phonefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.Objects;


/**
 * helper class to register the service for automatic start
 */
public class PhoneFinderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.d(TAG,"received message: "+intent.getAction());
        if (Objects.requireNonNull(intent.getAction()).equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG,"received boot completed message");

            // start the service
            Intent intentStartService = new Intent(context, PhoneFinderService.class);
            intentStartService.setAction(Intent.ACTION_RUN);
            context.startForegroundService(intentStartService);
        }
    }

    private static final String TAG = PhoneFinderReceiver.class.getSimpleName();   // logging tag
}
