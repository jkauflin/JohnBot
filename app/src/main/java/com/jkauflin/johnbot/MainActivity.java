/*==============================================================================
 * (C) Copyright 2016,2017 John J Kauflin, All rights reserved.
 *----------------------------------------------------------------------------
 * DESCRIPTION:  Main android activity for JohnBot robot controller.
 *----------------------------------------------------------------------------
 * Modification History
 * 2016-12-25 JJK 	Initial version to test Android functionality
 * 2016-12-26 JJK   Got bluetooth wireless working
 * 2016-12-27 JJK   Got the "I am Spartacus" EYES command working.  Changing
 *                  the command arrays to continual processing buffers.
 * 2016-12-31 JJK   Figured out the HC-05 needed the CMODE set from 0 to 1 to
 *                  accept connections from any address.  Got the serial
 *                  communications from Android to Arduino working
 * 2017-01-02 JJK   Got 1st version of the JohnBot hardware done
 * 2017-01-06 JJK   Abstracted all wireless communications work to a separate
 *                  class - BluetoothServices
 * 2017-01-09 JJK   Figured out buttons and listeners
 * 2017-01-15 JJK   Got the text-to-speech working
 * 2017-01-21 JJK   First voice activited command to robot test
 *                  (checking stop, eyes, head, and joke)
 * 2017-01-29 JJK   Worked on muting beeps when starting speech recognition,
 *                  recognizing when the text to speech was done with an
 *                  utterance, and waiting to restart the speedch recognition
 *                  on the main thread until the utterance was done.
 *                  Also, cleaned up the bluetooth connection error handling.
 * 2017-02-08 JJK   Got textview working and added re-connect check before
 *                  write
 * 2017-02-11 JJK   Added Toast messages and SQLite database.  Also added
 *                  set of speech volume and rate.
 * 2017-02-18 JJK   Got to the bottom of the continuous SpeechRecognizer
 *                  problems - basically bugs in Google speech service that
 *                  report onError NO MATCH too quickly, causing restart too
 *                  quickly.  Got rid of the complex extended class and just
 *                  implemented handling in this main class, and added a
 *                  health check handler to restart when needed.
 *                  Added commands to turn around.
 * 2017-02-19 JJK   Adjusted some object lifecycle and listening restart
 *                  to improve stability and interaction.
 *                  Working on database.
 * 2017-02-25 JJK   Implemented volley library to handle http interaction.
 *                  Implemented web service requests to IFTTT (to turn
 *                  WEMO switch on and off).
 *                  Implemented load of internal SQLite database from JSON
 *                  data requested from a website url.
 * 2017-03-04 JJK   Working on hello logic and user recognition
 * 2017-03-05 JJK   Moved the hello logic into a handler to give a little
 *                  more delay after intitialization tasks (and avoid using
 *                  a wait sleep on the main thread)
 * 2017-03-22 JJK   Changed errors in the external data load to non-Fatal
 * 2017-05-07 JJK   Modifying to work without a network or cellular connection
 * 2017-05-10 JJK   Working on localization and sensors (to track robot
 *                  position)
 * 2017-05-20 JJK   Modified to work in disconnected mode if bluetooth was
 *                  not available, and work if no network is available
 * 2017-07-07 JJK   Worked on error handling and did a DB pre-load
 *                  Moved the joke list load to the hello start
 * 2017-07-08 JJK   Working on position sensor (to keep robot in a circle)
 *                  Realized this is too big and complex a question to take on.
 *                  Backing off Android based speed and distance (and other
 *                  localization concepts) for now - some sites to look at
 *                  in the future:
 * http://maephv.blogspot.com/2011/10/android-computing-speed-and-distance.html
 * https://stackoverflow.com/questions/12926459/calculating-distance-using-linear-acceleration-android
 * 2017-07-09 JJK   Added "walk around" to execute multiple feet commands
 *                  Limited the walk and run to 3000ms
 *                  Removed auto-reconnect - if it can't connect at the start,
 *                  don't try, count on reconnect command
 * 2017-07-27 JJK   Working on playing music on a bluetooth device
 * 2017-09-05 JJK   Final edits before moving away from Android/smartphone as
 *                  controller (moving to Raspberry Pi)
 *============================================================================*/
package com.jkauflin.johnbot;

import android.content.ComponentName;
import android.content.Context;
/*
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
*/
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.media.AudioManager;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

// Volley library classes to handle HTTP interaction
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class MainActivity extends Activity implements RecognitionListener,TextToSpeech.OnInitListener {
    private static final String TAG = "johnbot";
    // Website from which to get configuration data
    //private static final String DATA_URL = "http://<web site>/getData.php";
    private static final String DATA_URL = "http://johnkauflin.com/getJohnBotDataProxy.php";
    // IFTTT web service request commands
    private static final String IFTTT_JJKWEMO_ON_URL = "https://maker.ifttt.com/trigger/<user key>";
    private static final String IFTTT_JJKWEMO_OFF_URL = "https://maker.ifttt.com/trigger/<user key>";

    private static final float SPEECH_RATE_SLOW = 0.7f;
    private static final float SPEECH_RATE_NORMAL = 1.0f;
    private static final float SPEECH_RATE_FAST = 1.1f;
    private static final int WAIT_CNT_MAX = 20;
    private static final String HEAD_CENTER = ",20,85;";
    private static final String ARM_CENTER = ",20,75;";

    private static final int LEFT_FOOT = 0;
    private static final int RIGHT_FOOT = 1;
    private static final int BOTH_FEET = 2;

    private static final int BACKWARD = 0;
    private static final int FORWARD = 1;
    private static final int LEFT_TURN = 2;
    private static final int RIGHT_TURN = 3;
    private static final int FEET_SPEED_SLOW = 50;
    private static final int FEET_SPEED_NORMAL = 80;
    private static final int FEET_SPEED_FAST = 120;
    private static final String TURN_AROUND = ","+BOTH_FEET+","+RIGHT_TURN+","+FEET_SPEED_SLOW+",2000";
    private static final String WALK_FORWARD = ","+BOTH_FEET+","+FORWARD+","+FEET_SPEED_SLOW+",2000";
    private static final String WALK_BACKWARD = ","+BOTH_FEET+","+BACKWARD+","+FEET_SPEED_SLOW+",2000";

    private static int foot = BOTH_FEET;
    private static int feetDirection = FORWARD;
    private static int feetSpeed = FEET_SPEED_SLOW;
    private static int feetDuration = 0;

    private Button stopButton;
    private Button armButton;
    private Button eyesButton;
    private TextView tv;
    /*
    private TextView tvX;
    private TextView tvY;
    private TextView tvZ;
    */

    public static AudioManager audioManager = null;
    public static int originalVolume = 0;

    private static SpeechRecognizer speech = null;
    private static Intent recognizerIntent;
    private static boolean isSpeechRecognizerAlive = false;
    private static boolean isPaused = false;

    private static final Handler helloStartHandler = new Handler();
    private static final Handler healthCheckHandler = new Handler();
    private static final int HEALTH_CHECK_INTERVAL_MS = 4000;  // Every 4 seconds

    private static TextToSpeech tts;
    private static BluetoothServices btServices = null;
    private DatabaseHandler db = null;
    private static JsonObjectRequest jsonObjectReq = null;
    private static int databaseVersion = 0;

    private MediaPlayerService player;
    boolean serviceBound = false;

    private static boolean textToSpeech = false;
    private static boolean repeatSpeech = false;
    private static boolean jokeStarted = false;
    private static boolean sleeping = false;
    private static boolean silent = false;
    private static boolean userIdentification = false;
    private static String userName = "";

    /*
    private static SensorManager sensorManager = null;
    private static float homeX;
    private static float homeY;
    private static float homeZ;
    private static boolean homeSet = false;
    private final float[] mAccelerometerReading = new float[3];
    private final float[] mMagnetometerReading = new float[3];
    private final float[] mRotationMatrix = new float[9];
    private final float[] mOrientationAngles = new float[3];

    Sensor sensorAccelerometer;
    //Sensor sensorMagnetic;
    */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG,"onCreate - MainActivity");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get the text view on the layout to post log messages
        this.tv = (TextView) this.findViewById(R.id.textViewWithScroll);
        tv.setMovementMethod(new ScrollingMovementMethod());
        //initializing a blank textview so that we can just append a text later
        tv.setText("");
        /*
        this.tvX = (TextView) this.findViewById(R.id.textViewX);
        this.tvY = (TextView) this.findViewById(R.id.textViewY);
        this.tvZ = (TextView) this.findViewById(R.id.textViewZ);
        */

        // Add listeners for the buttons
        addListenerOnButton();

        // Creat an Intent to tell the SpeechRecognizer what to do
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // Not sure if these do anything, but trying to make sure the SpeechRecognizer uses the
        // default offline language on the phone (with only the first match result)
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        // Create sensor objects
        /*
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        //TYPE_GYROSCOPE, TYPE_LINEAR_ACCELERATION, or TYPE_GRAVITY.
        Log.d(TAG,"sensorManager = "+sensorManager);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null){
            Log.d(TAG,"Success! There's a MAGNETOMETER");
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null){
            Log.d(TAG,"Success! There's a ACCELEROMETER");
        }
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Log.d(TAG,"sensorAccelerometer created");
        */

        // Get data from a website and load into a local datatbase
        // (start the load one time in the create and give it time to load while the Inits run
        loadData(DATA_URL);

        try {
            // Instantiate the bluetood services object
            btServices = new BluetoothServices(mHandler);
        } catch (Exception e) {
            //errorExit("Error in Bluetooth services",e.getMessage());
            Log.e(TAG,"Error in Bluetooth services",e);
            btServices = null;
        }

//        playAudio("https://upload.wikimedia.org/wikipedia/commons/6/6c/Grieg_Lyric_Pieces_Kobold.ogg");

    } // protected void onCreate(Bundle savedInstanceState) {

    @Override
    public void onResume() {
        Log.d(TAG, "...onResume - MainActivity");
        super.onResume();
        isPaused = false;

        //====================================================================================
        Log.d(TAG,"onResume start TTS (and other initializations)");
        // TTS plus other initializations
        restartTTS();
        //====================================================================================

    } // public void onResume() {

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "...In onPause()...");
        isPaused = true;
        try {
            if (speech != null) {
                speech.destroy();
                speech = null;
            }
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
            if (btServices != null) {
                btServices.close();
            }

            // Don't receive any more updates from either sensor.
            //sensorManager.unregisterListener(sensorEventListener);
            //gyroManager.unregisterListener(gyroListener);
            //accManager.unregisterListener(accListener);

        } catch (Exception e2) {
            //errorExit("Fatal Error", "In onPause() and failed to close socket." + e2.getMessage() + ".");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speech != null) {
            speech.destroy();
            speech = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (btServices != null) {
            btServices.close();
            btServices = null;
        }
        if (db != null) {
            db.close();
            db = null;
        }

        // Don't receive any more updates from either sensor.
        //sensorManager.unregisterListener(sensorEventListener);
    }

    private void errorExit(String title, String message){
        Log.e(TAG,title + " - " + message);
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }
    private void errorLog(String title, String message){
        Log.e(TAG,title + " - " + message);
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
    }

    // This runnable will schedule itself to run at 1 second intervals
    // if mShouldRun is set true.
    private final Runnable healthCheck = new Runnable() {
        public void run() {
            if (!isPaused) {
                Log.d(TAG,"HEALTH CHECK - isSpeechRecognizerAlive = "+isSpeechRecognizerAlive);
                if (!isSpeechRecognizerAlive) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            // Restart the speech listening on the main thread
                            restartListening();
                        }
                    });
                }

                // Is there some other condition or timing I could check ???

            } // if (!isPaused) {
            healthCheckHandler.postDelayed(healthCheck,HEALTH_CHECK_INTERVAL_MS);
        }
    };

    public void loadData(String url){
        String REQUEST_TAG = "com.jkauflin.johnbot.volleyJsonObjectRequest";

        // Create database objects for existing database
        Log.d(TAG,"Loading data (PRE-LOAD)");
        db = new DatabaseHandler(getApplicationContext(),1,null);

        jsonObjectReq = new JsonObjectRequest(url, null,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject jsonData) {
                        if (jsonData == null) {
                            errorLog("JsonObjectRequest", "Returned NULL");
                        } else {
                            try {
                                // Instantiate the object to handle the database interaction
                                String versionStr = jsonData.getString("version");
                                if (versionStr == null || versionStr.isEmpty()) {
                                    errorLog("Get database version","DB version is not set");
                                } else {
                                    tv.append("*** Loading data ***\n");
                                    Log.d(TAG,"Loading data");
                                    databaseVersion = Integer.parseInt(versionStr);
                                    db = new DatabaseHandler(getApplicationContext(),databaseVersion,jsonData);
                                    // Load the joke list array now
                                    db.loadJokeIdList();
                                }
                            } catch (final Exception e) {
                                errorLog("Json parsing error",e.getMessage());
                            }
                        }

                    } // public void onResponse(JSONObject response) {
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError e) {
                errorLog("Error in Volley HttpRequest for loadData",e.getMessage());
            }
        });
        // Adding JsonObject request to request queue
        AppSingleton.getInstance(this).addToRequestQueue(jsonObjectReq,REQUEST_TAG);
    }

    // Restart the Text-To-Speech
    public void restartTTS() {
        //TextToSpeech(Context context, TextToSpeech.OnInitListener listener)
        tts = new TextToSpeech(this, this);
        //tts.setPitch(0.6);
        tts.setSpeechRate(SPEECH_RATE_FAST);
        // Listen for when it is done with an utterance
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Speaking started.
                //Log.d(TAG,"onStart, utteranceId = "+utteranceId);

                // Should I check the audio level before speaking starts to make sure it's on???
                // Make sure the audio is at a good volume for Text-To-Speech
                /*
                audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                // use to set back to original when app is destroyed???
                originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                Log.d(TAG,">>> onStart Utterance originalVolume = "+originalVolume);
                // Make sure volume is good
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)-3,0);
                */
            }
            @Override
            public void onDone(String utteranceId) {
                // Speaking stopped.
                Log.d(TAG,"onDone, utteranceId = "+utteranceId);
                runOnUiThread(new Runnable() {
                    public void run() {
                        // Restart the speech listening on the main thread
                        restartListening();
                    }
                });
            }
            @Override
            public void onError(String utteranceId) {
                Log.i(TAG,"Error in TTS");
            }
        });
    } // public void restartTTS() {

    //==============================================================================================
    // Method to check the TTS initialization - and start other initializations
    // (wait to do other initializations until the TTS is good)
    //==============================================================================================
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG,"TextToSpeed onInit SUCCESS");
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                errorExit("TTS", "This Language is not supported");
            }

            // Show we have TTS capabilities
            tv.append("*** Speaking ***\n");

            // Make sure the audio is at a good volume for Text-To-Speech
            audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
            // use to set back to original when app is destroyed???
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            Log.d(TAG,"originalVolume = "+originalVolume+", MAX = "+audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            // Make sure volume is good
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)-3,0);

            // check mute?

            // Connect to robot over bluetooth (if bluetooth services are available)
            try {
                if (btServices != null) {
                    Log.d(TAG, "onResume - connect Bluetooth");
                    // Only if the state is STATE_NONE, do we know that we haven't started already
                    if (btServices.getState() != BluetoothServices.STATE_CONNECTED) {
                        // Start the Bluetooth chat services
                        tv.append("*** Connecting ***\n");
                        btServices.connect();
                        // Trying to connect is done in the btService thread, don't assume it is
                        // connected here yet.  Check it when trying to send a command.
                    }
                }
            } catch (Exception e) {
                Log.e(TAG,"Error connecting ",e);
                Toast.makeText(getBaseContext(), "Error connecting, "+e.getMessage(), Toast.LENGTH_LONG).show();
                try {
                    if (btServices != null) {
                        btServices.close();
                    }
                } catch (Exception e2) {
                    //errorExit("Fatal Error", "In onPause() and failed to close socket." + e2.getMessage() + ".");
                }
            }

            // Run the HELLO logic after a short delay (to give initialization a chance to finish, and make sure it is connected)
            helloStartHandler.postDelayed(helloStart,HEALTH_CHECK_INTERVAL_MS);

            // *** Other initializations ***
            /*
            sensorManager.registerListener(sensorEventListener,sensorAccelerometer,SensorManager.SENSOR_DELAY_NORMAL,SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG,"sensorManager REGISTERED");
            */

        } else {
            errorExit("Error in Text-to-Speech","Initilization Failed!");
        }
    } // public void onInit(int status) {

    /*
    public SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {

            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            if (!homeSet) {
                homeSet = true;
                homeX = x;
                homeY = y;
                homeZ = z;
            }

            //String s = String.format("%.2f", 1.2975118);
            // m/s2
            tvX.setText("X Acc: " + String.format("%.2f", homeX - x));
            tvY.setText("Y Acc: " + String.format("%.2f", homeY - y));
            tvZ.setText("Z Acc: " + String.format("%.2f", homeZ - z));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };
    */

    private final Runnable helloStart = new Runnable() {
        public void run() {
            // Speak hello message and go into user identification mode
            Log.i(TAG,"Saying HELLO");
            // Count on the restart listening at the end of the HELLO utterance
            speak("Hello, I am the john bot.  What is your name?");
            userIdentification = true;

            // Start the health check handler
            //healthCheckHandler.postDelayed(healthCheck,HEALTH_CHECK_INTERVAL_MS);
            healthCheckHandler.postDelayed(healthCheck,3000);
        }
    };



    //=============================================================================================
    // Methods needed to implement RecognitionListener (for SpeechRecognizer)
    //=============================================================================================
    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech");
        isSpeechRecognizerAlive = true;
    }
    @Override
    public void onBufferReceived(byte[] buffer) {
        //Log.i(TAG, "onBufferReceived: " + buffer);
    }
    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech");
    }
    @Override
    public void onEvent(int arg0, Bundle arg1) {
        Log.d(TAG, "onEvent");
    }
    @Override
    public void onPartialResults(Bundle arg0) {
        Log.d(TAG, "onPartialResults");
    }
    @Override
    public void onReadyForSpeech(Bundle arg0) {
        Log.d(TAG, "onReadyForSpeech");
    }
    @Override
    public void onRmsChanged(float rmsdB) {
        //Log.i(TAG, "onRmsChanged: " + rmsdB);
        //progressBar.setProgress((int) rmsdB);
    }
    @Override
    public void onError(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }

        Log.d(TAG, "SpeechRecognizer onError - " + message);

        // *** don't do anything on certain "failures" because they happen too quick (google speech bugs)
        // The ERROR_NO_MATCH happens almost immediately - so don't tie a restart to it
        //FAILED RecognitionService busy

        if (errorCode != SpeechRecognizer.ERROR_NO_MATCH &&
                errorCode != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            Log.d(TAG, "ERROR RESTART HERE (if needed) ???");
            //restartListening();
        }

        // 2017-02-19 - Found out in some cases the error is valid (No Match if it can't understand
        //              the command), so let's just tell the handler to restart on its cycle
        //              *** I think this might be a key to good stability ***
        isSpeechRecognizerAlive = false;
    }

    public void restartListening() {
        Log.d(TAG,"***** restartListening *****");
        if (speech != null) {
            speech.destroy();
        }
        // Instantiate the SpeechRecognizer and set a listener in this same class to
        // handle all the notification methods
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(this);

        // Mute the beeps when the speech recognizer starts
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);
        //audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);

        speech.startListening(recognizerIntent);
        // Reset the flag until you know it's really alive (onBeginningOfSpeech)
        isSpeechRecognizerAlive = false;
    }

    public void playMedia(Uri file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(file);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    //=============================================================================================
    //
    // Respond to results from the SpeechRecognizer listener
    //
    //=============================================================================================
    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String command = "";
        if (matches != null) {
            command = matches.get(0).toLowerCase();
        }

        // Boolean to track if text-to-speech is being used (to delay restart of speech listener)
        textToSpeech = false;

        Log.i(TAG,"Command: "+command);
        tv.append("Command: "+command+"\n");


        // get from the command string
        // to identification of a known command
        // match parts of the command string with defined commands
        //

        String response;

        if (command.equals("stop")) {
            sendCommand("S;");
            repeatSpeech = false;
            jokeStarted = false;
            userIdentification = false;

        } else if (command.contains("music") && command.contains("play")) {
            Log.d(TAG, "playing music");
            //playAudio("https://upload.wikimedia.org/wikipedia/commons/6/6c/Grieg_Lyric_Pieces_Kobold.ogg");
            playMedia(Uri.parse("https://upload.wikimedia.org/wikipedia/commons/6/6c/Grieg_Lyric_Pieces_Kobold.ogg"));

        } else if (command.equals("disconnect")) {
            if (btServices != null) {
                btServices.close();
            }
            speak("I am now disconnected.");
        } else if (command.equals("connect") || command.equals("reconnect")) {
            if (btServices != null) {
                Log.d(TAG, "reconnect Bluetooth");
                // Only if the state is STATE_NONE, do we know that we haven't started already
                if (btServices.getState() != BluetoothServices.STATE_CONNECTED) {
                    // Start the Bluetooth chat services
                    btServices.connect();
                    tv.append("*** Connecting ***\n");
                    speak("I am connecting.");
                }
            } else {
                speak("I cannot connect at this time.");
            }

        } else if (command.contains("be quiet") || command.contains("silent mode on")) {
            silent = true;
        } else if (command.contains("speak") || command.contains("silent mode off")) {
            silent = false;
            speak("Thank you. I appreciate the ability to communicate.");

        } else if (command.contains("to sleep") ||command.contains("turn off")) {
            speak("Goodbye.");
            sleeping = true;
            silent = true;
        } else if (command.equals("wake up") || command.contains("wakey") || command.contains("hey john")) {
            speak("Yes.");
            sleeping = false;
            silent = false;
        } else if (command.contains("that's funny") ||command.contains("that is funny")) {
            speak("I know.");

        } else if (userIdentification) {
            userName = command;
            speak("Hello "+command+". It is nice to meet you.");
            userIdentification = false;

        } else if (command.contains("repeat")) {
            speak("okay go");
            repeatSpeech = true;

        } else if (repeatSpeech) {
            speak(command);

        } else if (jokeStarted) {
            if (db != null) {
                speak(db.getJokeAnswer());
            }
            jokeStarted = false;

        } else if (command.contains("light on") || command.contains("lights on")) {
            volleyStringRequest(IFTTT_JJKWEMO_ON_URL);
            speak("Let there be light.");
        } else if (command.contains("light off") || command.contains("lights off")) {
            volleyStringRequest(IFTTT_JJKWEMO_OFF_URL);
            speak("Plunging into darkness.");
        } else if (command.contains("joke")) {
            jokeStarted = true;
            if (db != null) {
                speak(db.getJokeQuestion());
            }
        } else if (command.contains("arm")) {

            if (command.contains("down")) {
                sendCommand("A,40;");
            } else if (command.contains("up")) {
                sendCommand("A,120;");
            } else if (command.contains("center")) {
                sendCommand("A,75;");
            }

        } else if (command.contains("eyes")) {

            if (command.contains("flash")) {
                sendCommand("E,500,40,500,40,500;");
            } else if (command.contains("spartacus")) {
                sendCommand("E,1100,100,1100,100,600,40,400,40,900;");
            }

        } else if (command.contains("head")) {

            if (command.contains("left")) {
                sendCommand("H,20;");
            } else if (command.contains("right")) {
                sendCommand("H,140;");
            } else if (command.contains("center")) {
                sendCommand("H,78;");
            }

        } else if (command.contains("move") || command.contains("walk") || command.contains("run") || command.contains("turn")) {

// 4 parmeters for feet command
// 1 - foot (0 - Left, 1 - Right, 2 - Both)
// 2 - direction (0 - Backward, 1 - Forward, 2 - Left turn, 3 - Right turn)
// 3 - speed (0 - stopped to 255 - full speed)
// 4 - duration milliseconds (optional at end)

            foot = BOTH_FEET;
            feetDirection = FORWARD;
            feetSpeed = FEET_SPEED_NORMAL;
            feetDuration = 3000;

            if (command.contains("walk") && command.contains("around")) {
                String tempCommand = "F";
                for (int i = 0; i < 5; i++) {
                    tempCommand += WALK_FORWARD+TURN_AROUND;
                }
                tempCommand += ";";
                sendCommand(tempCommand);
            } else {
                if (command.contains("fast") || command.contains("run")) {
                    feetSpeed = FEET_SPEED_FAST;
                }
                if (command.contains("backward")) {
                    feetDirection = BACKWARD;
                }
                if (command.contains("turn")) {
                    feetDirection = RIGHT_TURN;
                    if (command.contains("left")) {
                        feetDirection = LEFT_TURN;
                    }
                    feetSpeed = FEET_SPEED_SLOW;
                    feetDuration = 1000;
                    if (command.contains("around")) {
                        feetDuration = 2000;
                    }
                }

                sendCommand("F,"+foot+","+feetDirection+","+feetSpeed+","+feetDuration+";");
            }

                            /*
                                sendCommand("F"+
                            ",2,1,50,2000"+
                            ",2,0,0,2000"+
                            ",1,1,50,2000,1,0,0,1000"+
                            ",0,1,50,2000,0,0,0,1000"+
                            ",1,0,50,2000,1,0,0,1000"+
                            ",0,0,50,2000,0,0,0,1000;");
                 */
            // Rotation turns - 1 second at 50 speed is a perfect 90 degrees

        } else {
            if (db != null) {
                response = db.getResponse(command);
                // null
            /*
07-08 20:58:34.587 27526-27526/com.jkauflin.johnbot I/johnbot: Command: i
07-08 20:58:34.588 27526-27526/com.jkauflin.johnbot D/AndroidRuntime: Shutting down VM
07-08 20:58:34.596 27526-27526/com.jkauflin.johnbot E/AndroidRuntime: FATAL EXCEPTION: main
                                                                      Process: com.jkauflin.johnbot, PID: 27526
java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.String
com.jkauflin.johnbot.DatabaseHandler.getResponse(java.lang.String)' on a null object reference
                                               at com.jkauflin.johnbot.MainActivity.onResults(MainActivity.java:798)


            07-05 23:12:55.959 22365-22365/? E/AndroidRuntime: FATAL EXCEPTION: main
                                                   Process: com.jkauflin.johnbot, PID: 22365
                                                   java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.String com.jkauflin.johnbot.DatabaseHandler.getResponse(java.lang.String)' on a null object reference
                                                       at com.jkauflin.johnbot.MainActivity.onResults(MainActivity.java:719)

             */
                if (response.isEmpty()) {
                    response = "I don't understand that.";
                }
                speak(response);
            }
        }

        // If NOT executing any text to speech, restart the listener immediately
        // else wait until the utterance is done (so the mute to avoid beeps doesn't mute the utterance)
        if (!textToSpeech) {
            restartListening();
        }
    } // public void onResults(Bundle results) {


    public void addListenerOnButton() {
        eyesButton = (Button) findViewById(R.id.eyesButton);
        eyesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                Log.d(TAG, "...Click EYES...");
                sendCommand("E,500,40,500,40,500;");
            }
        });

        armButton = (Button) findViewById(R.id.armButton);
        armButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                Log.d(TAG, "...Click ARM...");
                sendCommand("A,110,1000,30,1000,75;");
            }
        });

        stopButton = (Button) findViewById(R.id.stopButton);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                Log.d(TAG, "...Click STOP...");
                sendCommand("S;");
            }
        });
    }

    //=============================================================================================
    // Method to execute and animated speech (text-to-speech and robotic motions)
    //=============================================================================================
    private static void speak(String messageToSpeak) {
        String eyesStr = "";
        String headStr = "";
        String armStr = "";
        int cnt = 0;
        boolean up = true;
        String[] wordList = messageToSpeak.split(" ");

        int maxMoveCnt = wordList.length-1;
        if (maxMoveCnt < 2) {
            maxMoveCnt = 2;
        } else if (maxMoveCnt > 4) {
            maxMoveCnt = 4;
        }

        int maxEyesCnt = wordList.length;
        if (maxEyesCnt < 2) {
            maxEyesCnt = 2;
        } else if (maxEyesCnt > 9) {
            maxEyesCnt = 9;
        }

        for (String word: wordList) {
            cnt++;
            //Log.i(TAG,""+word);
            if (cnt < maxEyesCnt) {
                if (cnt == 1) {
                    eyesStr = "E";
                    headStr = "H";
                    armStr = "A";
                } else {
                    eyesStr += ",40";
                }
                eyesStr += ",260";
            }

            // Don't do a movement entry for the final word
            // (because servos are slower than LED's
            if (cnt < maxMoveCnt) {
                if (cnt > 1) {
                    headStr += ",20";
                    armStr += ",20";
                }
                if (up) {
                    headStr += ",110";
                    armStr += ",100";
                } else {
                    headStr += ",50";
                    armStr += ",50";
                }
                up = !up;
            }
        }
        eyesStr += ";";
        headStr += HEAD_CENTER;
        armStr += ARM_CENTER;

        Log.i(TAG,messageToSpeak+", word cnt = "+wordList.length);
        //Log.i(TAG,"Message = "+eyesStr+headStr+armStr);
        if (!silent) {
            textToSpeech = true;
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,AudioManager.FLAG_VIBRATE);

            //audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
            //    audioManager.setStreamVolume()
//        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            //              AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            //          .adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0);

            //audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)-3,0);

            if (tts != null) {
                tts.speak(messageToSpeak, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId");
            }
            /*
07-07 18:30:35.855 28409-28409/com.jkauflin.johnbot E/AndroidRuntime: FATAL EXCEPTION: main
        Process: com.jkauflin.johnbot, PID: 28409
        java.lang.NullPointerException: Attempt to invoke virtual method 'int android.speech.tts.TextToSpeech.speak(java.lang.CharSequence, int, android.os.Bundle, java.lang.String)' on a null object reference
        at com.jkauflin.johnbot.MainActivity.speak(MainActivity.java:837)
             */
            sendCommand(eyesStr+headStr+armStr);
        /*
        sendCommand("E,600,100,600,40,400,40,900,1000,600,40,400,40,600,40,600;");
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
        tts.speak("What did they do to the cannibal that was late for dinner?", TextToSpeech.QUEUE_ADD, null, null);
        tts.playSilentUtterance(1000,TextToSpeech.QUEUE_ADD,null);
        tts.speak("They gave him the cold shoulder.", TextToSpeech.QUEUE_ADD, null, "UtteranceId");
        */
        }
    } // private void speak(String messageToSpeak) {


    //==============================================================================================
    // Send a command message to the arduino robot controller (through the bluetooth services)
    //==============================================================================================
    private static void sendCommand(String message) {
        try {
            if (message.length() <= 0) {
                Log.d(TAG,"Message to send is zero length");
                return;
            }

            // If not connected, try to re-connect
            /*
            if (btServices.getState() != BluetoothServices.STATE_CONNECTED) {
                Log.d(TAG,"*** sendCommand NOT CONNECTED - Try re-connect ***");
                btServices.connect();
                // Logs show it takes about 2 seconds to connect
                // *** maybe find some other points in the loop to check and try re-connect???
                // Maybe have btservices call the message handler when it gets a dis-connect error?
                // Sleep for 1 second
                SystemClock.sleep(1000);
            }

            // Sleep for up to an additional 2 seconds, checking to see when it gets connected
            int waitCnt = 0;
            while (btServices.getState() != BluetoothServices.STATE_CONNECTED && waitCnt < WAIT_CNT_MAX) {
                //Log.d(TAG,"btServices.getState() = "+btServices.getState()+", waitCnt = "+waitCnt);
                SystemClock.sleep(100);
                waitCnt++;
            }
            */

            if (btServices.getState() == BluetoothServices.STATE_CONNECTED) {
                // If actually connected, send the message to the robot (over bluetooth services)
                Log.i(TAG, "message: "+message);
                btServices.write(message);
            } else {
                Log.d(TAG,"*** sendCommand NOT CONNECTED - FAIL, message = "+message);
                //Toast.makeText(getBaseContext(), "Message Fail - NOT CONNECTED, message = "+message, Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.d(TAG,"Error in sendCommand, e = "+e);
        }
    }

    //==============================================================================================
    // The handler that responds to messages sent by the arduino robot controller (over bluetooth)
    //==============================================================================================
    // https://stackoverflow.com/questions/37188519/this-handler-class-should-be-static-or-leaks-might-occurasyncqueryhandler
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String msgStr = (String)msg.obj;
            //Log.d(TAG,"InMessage = "+msgStr);
            //tv.append(msgStr+"\n");
            if (msgStr.contains("proximity")) {
                Log.d(TAG,"InMessage = "+msgStr);
                tv.append(msgStr+"\n");

                speak("Hey, I'm walking here!");
                sendCommand("S;");
            }

                //String message = (String) msg.obj; //Extract the string from the Message
                //textView.setText(message);

            /*
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            mConversationArrayAdapter.clear();
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    mConversationArrayAdapter.add("Me:  " + writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        */
        }
    };

    public void volleyStringRequest(String url){
        String  REQUEST_TAG = "com.jkauflin.johnbot.volleyStringRequest";
        StringRequest strReq = new StringRequest(url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                Log.d(TAG, response);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError e) {
                Log.e(TAG,"Error in Volley HttpRequest, e = "+e.getMessage());
            }
        });
        // Adding String request to request queue
        AppSingleton.getInstance(getApplicationContext()).addToRequestQueue(strReq, REQUEST_TAG);
    }

    //Binding this Client to the AudioPlayer Service
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            MediaPlayerService.LocalBinder binder = (MediaPlayerService.LocalBinder) service;
            player = binder.getService();
            serviceBound = true;

            //Toast.makeText(MainActivity.this, "Service Bound", Toast.LENGTH_SHORT).show();
            tv.append("*** MediaPlayer service bound ***\n");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private void playAudio(String media) {
        //Check is service is active
        if (!serviceBound) {
            Intent playerIntent = new Intent(this, MediaPlayerService.class);
            playerIntent.putExtra("media", media);
            startService(playerIntent);
            bindService(playerIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            //Service is active
            //Send media with BroadcastReceiver
        }
    }

} // public class MainActivity extends Activity implements RecognitionListener,TextToSpeech.OnInitListener {

