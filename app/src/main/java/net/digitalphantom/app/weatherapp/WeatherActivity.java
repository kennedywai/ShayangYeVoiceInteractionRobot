
package net.digitalphantom.app.weatherapp;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import net.digitalphantom.app.weatherapp.data.Channel;
import net.digitalphantom.app.weatherapp.data.Condition;
import net.digitalphantom.app.weatherapp.data.LocationResult;
import net.digitalphantom.app.weatherapp.listener.GeocodingServiceListener;
import net.digitalphantom.app.weatherapp.listener.WeatherServiceListener;
import net.digitalphantom.app.weatherapp.service.WeatherCacheService;
import net.digitalphantom.app.weatherapp.service.GoogleMapsGeocodingService;
import net.digitalphantom.app.weatherapp.service.YahooWeatherService;

public class WeatherActivity extends Activity implements OnInitListener, WeatherServiceListener, GeocodingServiceListener, LocationListener {

    //weather

    private TextView temperatureTextView;
    //private TextView conditionTextView;
    private TextView locationTextView;
    private YahooWeatherService weatherService;
    private GoogleMapsGeocodingService geocodingService;
    private WeatherCacheService cacheService;
    private ProgressDialog dialog;

    //weather service fail flag
    private boolean weatherServicesHasFailed = false;
    private SharedPreferences preferences = null;

    //Voice Recognition
    private static final int VOICE_RECOGNITION_REQUEST_CODE = 1234;
    private static final int BLUETOOTH_REQUEST_CODE = 3333;
    private final int MY_DATA_CHECK_CODE = 0;
    String BestMatch;

    //Bluetooth socket connection parameters
    private TextView receiver,temp,condDescr;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    volatile boolean stopWorker;

    //Text to speech
    private TextToSpeech myTTS;
    DateFormat timeInstance = SimpleDateFormat.getTimeInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //requestWindowFeature(Window.FEATURE_NO_TITLE);
        //getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_weather);
        initWidget();

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        weatherService = new YahooWeatherService(this);
        weatherService.setTemperatureUnit(preferences.getString(getString(R.string.pref_temperature_unit), null));

        geocodingService = new GoogleMapsGeocodingService(this);
        cacheService = new WeatherCacheService(this);

        if (preferences.getBoolean(getString(R.string.pref_needs_setup), true)) {
            startSettingsActivity();
        } else {

            dialog = new ProgressDialog(this);
            dialog.setMessage(getString(R.string.loading));
            dialog.setCancelable(false);
            dialog.show();

            String location = null;

            if (preferences.getBoolean(getString(R.string.pref_geolocation_enabled), true)) {
                String locationCache = preferences.getString(getString(R.string.pref_cached_location), null);

                if (locationCache == null) {
                    getWeatherFromCurrentLocation();
                } else {
                    location = locationCache;
                }
            } else {
                location = preferences.getString(getString(R.string.pref_manual_location), null);
            }

            if(location != null) {
                weatherService.refreshWeather(location);
            }
        }
        //Google TTS
        Intent checkTTSIntent = new Intent();
        checkTTSIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
        startActivityForResult(checkTTSIntent, MY_DATA_CHECK_CODE);
        condDescr.setText(timeInstance.format(Calendar.getInstance().getTime()));
        //Open BT

        try {
            findBT();
            openBT();
        } catch (IOException ex) {
            Log.i("BTconnect","BTconnectError");
        }

    }

    private void getWeatherFromCurrentLocation() {
        // system's LocationManager
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // medium accuracy for weather, good for 100 - 500 meters
        Criteria locationCriteria = new Criteria();
        locationCriteria.setAccuracy(Criteria.ACCURACY_MEDIUM);

        String provider = locationManager.getBestProvider(locationCriteria, true);

        // single location update
        locationManager.requestSingleUpdate(provider, this, null);
    }


    private void startSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.currentLocation:
                dialog.show();
                getWeatherFromCurrentLocation();
                return true;
            case R.id.settings:
                startSettingsActivity();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void serviceSuccess(Channel channel) {
        dialog.hide();
        Condition condition = channel.getItem().getCondition();
        String temperatureLabel = getString(R.string.temperature_output, condition.getTemperature(), channel.getUnits().getTemperature());
        temperatureTextView.setText(temperatureLabel);
        //conditionTextView.setText(condition.getDescription());
        locationTextView.setText(channel.getLocation());
    }

    @Override
    public void serviceFailure(Exception exception) {
        // display error if this is the second failure
        if (weatherServicesHasFailed) {
            dialog.hide();
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        } else {
            // error doing reverse geocoding, load weather data from cache
            weatherServicesHasFailed = true;
            // OPTIONAL: let the user know an error has occurred then fallback to the cached data
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();

            cacheService.load(this);
        }
    }

    @Override
    public void geocodeSuccess(LocationResult location) {
        // completed geocoding successfully
        weatherService.refreshWeather(location.getAddress());

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(getString(R.string.pref_cached_location), location.getAddress());
        editor.apply();
    }

    @Override
    public void geocodeFailure(Exception exception) {
        // GeoCoding failed, try loading weather data from the cache
        cacheService.load(this);
    }

    @Override
    public void onLocationChanged(Location location) {
        geocodingService.refreshLocation(location);
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
        // OPTIONAL: implement your custom logic here
    }

    @Override
    public void onProviderEnabled(String s) {
        // OPTIONAL: implement your custom logic here
    }

    @Override
    public void onProviderDisabled(String s) {
        // OPTIONAL: implement your custom logic here
    }


    @Override
    public void onBackPressed() {
        // super.onBackPressed(); // Comment this super call to avoid calling finish()
    }

    @Override
    public void onDestroy() {
        // Shutting down tts!
        if (myTTS != null) {
            myTTS.stop();
            myTTS.shutdown();
        }
        super.onDestroy();
    }

    void findBT() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null) {
            //myLabel.setText("No bluetooth adapter available");
            Log.i("BTconnect","mBluetoothAdapter is null");
        }

        if(!mBluetoothAdapter.isEnabled()) {
            Log.i("BTconnect","mBluetoothAdapter is not enabled");
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, BLUETOOTH_REQUEST_CODE);
            Log.i("BTconnect","mBluetoothAdapter is enabled now");
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size() > 0) {
            for(BluetoothDevice device : pairedDevices) {
                //bluetooth device name "Hotlife"
                if(device.getName().equals("Hotlife")) {

                    mmDevice = device;
                    break;
                }
            }
        }
        //myLabel.setText("Bluetooth Device Found");
    }

    void openBT() throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
        mmSocket.connect();
        mmOutputStream = mmSocket.getOutputStream();
        mmInputStream = mmSocket.getInputStream();
        beginListenForData();
        //myLabel.setText("Bluetooth Opened");
    }

    void beginListenForData() {
        final Handler handler = new Handler();
        final byte delimiter = 'N'; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        //Log.d("BT-RECEIVER 00","BT-RECEIVER 00");
                        int bytesAvailable = mmInputStream.available();
                        if((bytesAvailable > 0))
                        {
                            //Log.d("BT-RECEIVER 01","BT-RECEIVER 01");
                            byte[] packetBytes = new byte[bytesAvailable];
                            mmInputStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            receiver.setText(data);
                                            if(receiver.getText().toString().equals("voice")) {
                                                Log.d("BTlistening", "voice");
                                                startVoiceRecognitionActivity();
                                                receiver.setText(receiver.getText().toString());
                                                Log.d("BTlistening","ButtonPressed");
                                            }
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }

                            }
                            //counter++;
                        }
                        //isVoiceButtonPressed = false;
                        //counter = 0;
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }


    void sendData(String data) throws IOException {
        //String msg = myTextbox.getText().toString();
        //String msg02 = "1";
        String msg = data;
        msg += "\n";
        mmOutputStream.write(msg.getBytes());
        //myLabel.setText("Data Sent");
    }

    void closeBT() throws IOException {
        stopWorker = true;
        mmOutputStream.close();
        mmInputStream.close();
        mmSocket.close();
        //myLabel.setText("Bluetooth Closed");
    }

    private void initWidget() {
        receiver = (TextView)findViewById(R.id.label02);
        temp = (TextView)findViewById(R.id.temperature);
        condDescr = (TextView) findViewById(R.id.condDescr);
        temperatureTextView = (TextView) findViewById(R.id.temperatureTextView);
        //conditionTextView = (TextView) findViewById(R.id.conditionTextView);
        locationTextView = (TextView) findViewById(R.id.locationTextView);
    }

    public void startVoiceRecognitionActivity() {
        //Log.i("Voice", "startVoiceRecognitionActivity Called");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt));
        try {
            startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE);
            Log.i("Voice", "startActivityForResult Called");
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(),
                    getString(R.string.speech_not_supported),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i("Voice","onActivityResult Called");
        switch(requestCode) {
            case VOICE_RECOGNITION_REQUEST_CODE: {
                if (resultCode == RESULT_OK && null != data) {
                    ArrayList<String> result = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    BestMatch = result.get(0);
                    receiver.setText(BestMatch);
                    Log.i("Voice", "VOICE_RECOGNITION_REQUEST_CODE");
                    //Search online
                    //Weather
                    if(BestMatch.equals("今天天氣如何") || BestMatch.equals("weather") || BestMatch.equals("天氣如何") || BestMatch.equals("天氣")|| BestMatch.equals("天氣好嗎")) {
                        try {
                            sendData("1");
                            closeBT();
                        }catch (IOException e){
                            Log.i("BTconnect","Close Error");
                        }
                        String temp = temperatureTextView.getText().toString();
                        speakWords("現在的天氣是"+temp+"左右");
                        Intent LaunchIntent02 = getPackageManager().getLaunchIntentForPackage("com.yahoo.mobile.client.android.weather");
                        startService(new Intent(this, MyLongerService.class));
                        startActivity(LaunchIntent02);
                    }
                    //Location
                    if(BestMatch.equals("我在哪裡") || BestMatch.equals("location") || BestMatch.equals("位置") || BestMatch.equals("我的位置") || BestMatch.equals("我們在哪裡") || BestMatch.equals("現在的時間")) {
                        try {
                            sendData("1");
                            closeBT();
                        }catch (IOException e){
                            Log.i("BTconnect","Close Error");
                        }
                        speakWords("您在和我開玩笑嗎？我們在祥儀機器人夢工廠呢！");
                        Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.co.jp/maps/place/%E6%A9%9F%E5%99%A8%E4%BA%BA%E5%A4%A2%E5%B7%A5%E5%A0%B4/@24.9749585,121.3239001,18z/data=!4m2!3m1!1s0x0000000000000000:0x46c621fe05cdcc95"));
                        startService(new Intent(this, MyLongerService.class));
                        startActivity(intent);
                    }
                    //Restaurants nearby
                    if(BestMatch.equals("附近好吃的") || BestMatch.equals("附近有什麼好吃的") || BestMatch.equals("附近有什麼好吃") || BestMatch.equals("附近有什麼好吃的餐廳呢") || BestMatch.equals("餐廳") || BestMatch.equals("附近餐廳") || BestMatch.equals("附近有什麼好吃的餐廳")) {;
                        try {
                            sendData("1");
                            closeBT();
                        }catch (IOException e){
                            Log.i("BTconnect","Close Error");
                        }
                        speakWords("我也不曉得耶，看看google怎麼說吧！");
                        Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com.tw/maps/search/restaurants/@24.9752902,121.3229107,16z?hl=en"));
                        startService(new Intent(this, MyLongerService.class));
                        startActivity(intent);
                    }
                    //Attractions nearby
                    if(BestMatch.equals("附近好玩的") || BestMatch.equals("附近有什麼好玩的") || BestMatch.equals("附近有什麼玩") || BestMatch.equals("附近有什麼好玩的地方") || BestMatch.equals("附近有什麼好玩的景點呢") || BestMatch.equals("附近景點")|| BestMatch.equals("景點")) {
                        try {
                            sendData("1");
                            closeBT();
                        }catch (IOException e){
                            Log.i("BTconnect","Close Error");
                        }
                        speakWords("這個嘛..怎麼不問問google大神呢？");
                        Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.co.jp/maps/search/attractions/@24.9760674,121.3085372,14z/data=!3m1!4b1"));
                        startService(new Intent(this, MyLongerService.class));
                        startActivity(intent);
                    }
                    //Time
                    if(BestMatch.equals("時間") || BestMatch.equals("time") || BestMatch.equals("現在幾點") || BestMatch.equals("現在幾點呢") || BestMatch.equals("現在的時間")) {
                        try {
                            sendData("1");
                            closeBT();
                        }catch (IOException e){
                            Log.i("BTconnect","Close Error");
                        }
                        speakWords("現在的時間是");
                        PackageManager pm = getPackageManager();
                        String packageName = "TellMeTheTime.App";
                        Intent launchIntent =  pm.getLaunchIntentForPackage(packageName);
                        startService(new Intent(this, MyLongerService.class));
                        startActivity(launchIntent);
                    }

                    //search offline
                    if(BestMatch.equals("你好")) {
                        try {
                            sendData("1");
                            speakWords("您好，很高興認識您！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("你來自哪裡")) {
                        try {
                            sendData("2");
                            speakWords("我來自台灣的祥儀機器人夢工廠！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("你喜歡什麼")) {
                        try {
                            sendData("3");
                            speakWords("我最喜歡發呆，所以大家都叫我阿呆，哈哈哈！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("有辣妹耶")) {
                        try {
                            sendData("4");
                            speakWords("真的嗎？在哪裡？在哪裡？");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("你覺得我帥嗎")) {
                        try {
                            sendData("5");
                            speakWords("還好啦，還差我一點點！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("好棒")) {
                        try {
                            sendData("6");
                            speakWords("那是當然，我還會吹泡泡呢！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("口吐白沫")) {
                        try {
                            sendData("7");
                            speakWords("救我啊！！");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }

                    if(BestMatch.equals("聖誕節")) {
                        try {
                            sendData("8");
                            speakWords("媽媽，今天來機器人夢工廠，玩的還開心嗎？");
                        } catch (IOException ex) {
                            Log.i("sendData","sendError");
                        }
                    }
                }
                break;
            }

            case BLUETOOTH_REQUEST_CODE:
                Log.i("BTconnect","Request Code");
                break;

            case MY_DATA_CHECK_CODE:{
                {
                    if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                        myTTS = new TextToSpeech(this, this);
                    } else {
                        Intent installTTSIntent = new Intent();
                        installTTSIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                        startActivity(installTTSIntent);
                    }
                }
                break;
            }
        }
    }

    //speak the user text
    public void speakWords(String speech) {
        //speak straight away
        myTTS.speak(speech, TextToSpeech.QUEUE_FLUSH, null);
    }

    //setup TTS
    public void onInit(int initStatus) {

        //check for successful instantiation
        if (initStatus == TextToSpeech.SUCCESS) {
            if(myTTS.isLanguageAvailable(Locale.TAIWAN)== TextToSpeech.LANG_AVAILABLE)
                myTTS.setLanguage(Locale.TAIWAN);
        }
        else if (initStatus == TextToSpeech.ERROR) {
            Toast.makeText(this, "Sorry! Text To Speech failed...", Toast.LENGTH_LONG).show();
        }
    }
}
