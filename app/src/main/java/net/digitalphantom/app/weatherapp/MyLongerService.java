package net.digitalphantom.app.weatherapp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class MyLongerService extends Service {
    public MyLongerService() {
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Runnable r = new Runnable() {
            @Override
            public void run() {
                long futureTime = System.currentTimeMillis() + 10000;//miliseconds delayed
                while(System.currentTimeMillis() < futureTime){
                    synchronized (this){
                        try{
                            wait(futureTime- System.currentTimeMillis());
                            Intent i=new Intent(getApplicationContext(),WeatherActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            stopSelf();
                            startActivity(i);
                        }catch(Exception e){
                        }
                    }
                }
            }
        };
        Thread mThread = new Thread(r);
        mThread.start();
        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        //throw new UnsupportedOperationException("Not yet implemented");
        return null;
    }
}
