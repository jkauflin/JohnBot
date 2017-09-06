# JohnBot
JohnBot is my 1st hobby robotics project using an Arduino based robot with an Android smartphone
functioning as the brain or controller.  The goal was to mimic human interaction:  autonomous movement, 
speech, and reacting to voice commands.  Another goal was to keep it inexpensive and use standard Android 
capabilities.  I was trying to learn basic robotic control and what could be done with a smartphone controller 
(to learn Android development).

For this learning exercise, I am not using any of the popular robotics control frameworks, just my 
own concepts for handling serial communication to the Arduino, the processing loop, and translating 
commands to physical robotic components.  I am using standard Android capabilities for speech 
recognition, text to speech (TTS), HTTP requests, and bluetooth serial communication.

Here is a link to a YouTube video showing what I got working:

[![IMAGE ALT TEXT HERE](http://img.youtube.com/vi/YOUTUBE_VIDEO_ID_HERE/0.jpg)](http://www.youtube.com/watch?v=YOUTUBE_VIDEO_ID_HERE)

## Hardware
Here is a list of the hardware I am used for this project:
* Droid Turbo 1 Android smartphone (BRAIN)
* Arduino Mega
* [Adafruit mini robot chassis kit - 2WD wtih DC motors](https://www.adafruit.com/products/3216) (FEET)
* [Adafruit motor shield v2](https://learn.adafruit.com/adafruit-motor-shield-v2-for-arduino/overview)
* Bluetooth module (HC-05)
* Ultrasonic distance sensor
* Servo for HEAD
* Servo for ARM
* LED's for EYES
* 4AA battery pack for DC motors and arduino power
* 4AA battery pack for servo motors

Beyond learning Arduino in general, the two biggest hardware issues I had when first learning robotics 
were power and the bluetooth controller.  Components like DC motors and servos need good power - strong 
battery amp power.  They can't be run from the USB power, and I couldn't get the servos to work correctly
until I gave them their own 4AA battery pack (separate from the one I was using for the DC motors).  Until
I got the power correct, they would do strange things when given commands.

The HC-06 Bluetooth module was a challenge until I figured out how to execute "AT" commands on it, and
get the default command mode changed from 0 (only paired addresses), to 1 (accept all addresses).  That was
the only default I had to change to get it talking to the Android Bluetooth libraries.  Here are links for 
HC-06 module documentation and getting to the AT command mode to execute AT commands:

* [HC-05 Bluetooth module documentation](https://www.itead.cc/wiki/Serial_Port_Bluetooth_Module_(Master/Slave)_:_HC-05)
* [AT command mode for HC-06](http://www.instructables.com/id/AT-command-mode-of-HC-05-Bluetooth-module/)

I used the following AT command to set the CMODE parameter:
* AT+CMODE=1
   
Other useful AT commands:
* AT : Ceck the connection
* AT+NAME : See default name
* AT+ADDR : see default address
* AT+VERSION : See version
* AT+UART : See baudrate
* AT+ROLE: See role of bt module(1=master/0=slave)

The total for the robotics hardware was around $100, and my daughter Amy created the ping pong ball head, 
the arm, and the front parts out of popsicle sticks (gotta love hot glue guns).

## Arduino software

I used the standard [Arduino IDE](https://www.arduino.cc/en/Main/Software) to work on the code and upload to
the Mega.  You can find the code in jjkBot.ino under **arduinoCode** folder.  I included libraries for the
Adafruit_MotorShield to control the DC motors and servos, and the ping library for ultrasonic distance sensor.

The main thing I learned about Arduino programming was not to block the main loop.  You can't do too much 
work in the subroutines because it will starve other functions and the robot will appear unresponsive.  
You must break down the work into small chunks that don't block the main cycle too much - sort of like 
the **nodejs** concept of asynchronous methods and events.  For example, when checking for characters from
the Bluetooth serial communication, don't do a loop that keeps going while there are characters - that will
get "stuck" there.  Just check if a character is available and add it to an array during a loop cycle.  Then
check for the end or termination character to know when to process the command. (see the **serialEvent**
subroutine)
```
void serialEvent() {
  // Don't loop here, it will throw off the main loop timing and incremental execution
  //while (Serial3.available()) {
  if (Serial3.available()) {
    inByte = Serial3.read();
```

Then main loop can then just call subroutines to check for small bits of work to do for the serial
communication to get the commands, and small bits of work to execute robotic functions for those commands
until they are done.  That way nothing gets stuck trying to do too much work or take too much time.
```
void loop() {
  // Get the current milliseconds count
  currMs = millis();

  // Read characters from the Serial input (for commands and parameters)
  serialEvent();

  // Execute command execution functions
  moveHead();
  moveArm();
  moveFeet();
  flashEyes();
```

The robot Arduino uses serial communication over bluetooth to collect characters into array buffers and 
sets flags to tell the subroutines when to start processing a buffer.  The "commands" are in the form of:
```
<entity><parameters><terminator>
E,600,100,600,40,400,40,900,1000,600,40,400,40,600,40,600;
```
This command is for the eyes (E), with parameters for millisecond duration (on and off), and a semi-colon
for the terminator.

## Android
I used the standard [Android Studio IDE](https://developer.android.com/studio/index.html) to write the 
Android code with a Droid Turbo 1 smartphone plugged into the USB to run it.  The java classes can be found
under **app/src/main/java/com.jkauflin.johnbot**.
 
* **MainActivity** - main controller and logic class for Text to Speech (TTS) and speech recognizer
* **BluetoothServices** - class to connect to and communicate to and from the robot bluetooth module
* **DatabaseHandler** - handle the load and queries to the internal SQLite database
* **AppSingleton** - singleton class to execute volley HTTP requests
* **MediaPlayerService** - class to play media files (when I was testing playing MP3's)

I didn't do much with the Android UI, just some buttons to test functions, a scrolling log to show voice
commands, and a Toast popup to display messages and errors.  Trying to get the robot to speak, understand 
voice commands, and move around got me into some of the major areas of robotics:
* **Text to Speech (TTS)** - language voice synthesis
* **Natural Language Processing (NLP)** - capturing and interpreting language sentences
* **Localization** - understand the physical world around the robot (position, navigation, dead reconing, etc.)

Most of the things in these areas are well beyond me, but it was fun to explore, learn, and get some 
functions working.  I played with the Android TTS parameters to get a flat, inflectionless voice that 
sounded like what I thought a robot would sound like (instead of a more human voice).  The speech recognition 
was extremely difficult especially when trying to avoid the startup beep.  I went through many web articles 
and examples till I found a way to turn off the volume before starting, checking the health with a background 
process, and re-starting the speech recognition when needed.  It "kind of" works but it's certainly no Siri 
or Alexa.  Lastly, I thought I would use built-in Andoid smartphone sensors to help the robot move around, 
but this opened a can of **Localization** worms that quickly made me realize I was in over my head and would
not be able to do things this way - when they started giving me double integrals for interpreting Android
sensor position data, I knew I was lost, so I just put some hard limits on how far the robot could walk.

Here is a summary of the methods in **MainActivity** and the order of startup execution:
``` java
onCreate
    create a speech recognizer intent object
    start the data load (loadData)
        call web service to get JSON object
        pass to db handler to update internal SQLite db
onResume
    restartTTS
    --> onInit (called after TTS is done initializing)
        use audioManager (to set volume)
        bluetooth services (to connect to arduino)
        helloStartHandler (hello message and user identification)
onResults
    process command string from speech recognizer
sendCommand
    send command string to Arduino over bluetooth serial communication
speak
    send text to TTS to say something
    send command string to flash eyes, and move head and arm along with speech
volleyStringRequest
    send HTTP request    
```

I hope this project was interesting and helped to convey things I learned about robotic technology.
