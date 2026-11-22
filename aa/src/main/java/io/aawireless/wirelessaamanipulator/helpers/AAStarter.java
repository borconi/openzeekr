package io.aawireless.wirelessaamanipulator.helpers;



import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AAStarter {



    public static void startself(Context context) {

        Intent i = new Intent("com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START");
        i.setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
        );
        i.putExtra("ip_address", "127.0.0.1");
        i.putExtra("projection_port", 6288);
        Log.d("AAStarter","Sending broadcast to AA with start intent");
        context.sendBroadcast(i);


    }
}
