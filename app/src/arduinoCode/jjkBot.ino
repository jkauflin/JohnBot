 /*==============================================================================
 * (C) Copyright 2016,2017 John J Kauflin, All rights reserved. 
 *----------------------------------------------------------------------------
 * DESCRIPTION: Sketch for robot using Arduino Mega, receiving commands over
 *              bluetooth from an android smartphone
 *----------------------------------------------------------------------------
 * Modification History
 * 2016-11-02 JJK  Initial version
 * 2016-11-02 JJK  Got serial working over USB at 57600
 * 2016-11-26 JJK  Working on basic work loop with checking serial in, set
 *                 global parameters for work, executing work elements and
 *                 state, and then serialPrint status info back out the serial
 *                 (working on a servo for example)
 * 2016-12-26 JJK  Got bluetooth wireless, 2 DC drive motors, 2 servos, 
 *                 ultrasonic distance sensor, and 2 LED's working.  
 *                 (Working on loop logic for LED's today)
 * 2016-12-27 JJK  Got the "I am Spartacus" EYES command working.  Changing
 *                 the command arrays to continual processing buffers.
 * 2016-12-31 JJK  Figured out the HC-05 needed the CMODE set from 0 to 1 to
 *                 accept connections from any address.  Got the serial
 *                 communications from Android to Arduino working
 * 2017-01-02 JJK  Got 1st version of the JohnBot hardware done, working with
 *                 Amy to get an outside cover, head and arm.  Programmed to
 *                 flash the eyes and move servos when started.
 * 2017-01-09 JJK  Figured out continuous buffered parameters would not work.
 *                 It needs to execute one command string at a time.  If a
 *                 new one comes in, stop the current, reset, and start the new.
 * 2017-01-10 JJK  Finished flashEyes with a 15ms delay when a command starts.
 *                 Implemented STOP.  Working on servos.
 * 2017-01-15 JJK  Testing flashEyes and moveArm, realized that new commands
 *                 were interfering with running command - restructure command
 *                 input into generic param array, then copy over on execute
 * 2017-01-25 JJK  Added serialPrint method to print to Serial3
 * 2017-01-31 JJK  Read servo positions at setup (to solve positioning problems)
 * 2017-02-01 JJK  Implemented an array to execute multiple parameters for
 *                 arm and head movements
 * 2017-02-04 JJK  Added a second pack of 4 AA batteries as a separate power
 *                 supply for the two servos (trying to run these from the
 *                 arduino power does not work, and messes up the bluetooth
 *                 communicaton)
 * 2017-02-14 JJK  Re-implemented the send of a status message every second
 *                 to try to keep the bluetooth connection constant.
 *                 Added feet parameter array and handling (for DC motors)
 * 2017-02-18 JJK  Added duration and separate Left and Right feet commands
 * 2017-02-19 JJK  Reset the checkpoints on STOP
 *============================================================================*/

#include <Servo.h>
#include <Wire.h>
#include <Adafruit_MotorShield.h>
#include "utility/Adafruit_MS_PWMServoDriver.h"
#include <NewPing.h>

#define SERVO_1_PIN 10
#define SERVO_2_PIN 9
#define SERVO_DELAY 10
// mega 44,45,46 are PWM that can be used for analogWrite (for LED fades)
#define LEFT_EYE 44
#define RIGHT_EYE 45
#define LIGHT_ON 255
#define LIGHT_OFF 0
#define BUF_MAX 30

#define MOVE_BACKWARD 0
#define MOVE_FORWARD 1
#define TURN_LEFT 2
#define TURN_RIGHT 3
#define LEFT_FOOT 0
#define RIGHT_FOOT 1
#define BOTH_FEET 2

// NewPing setup of pins and maximum distance.
#define TRIGGER_PIN 11
#define ECHO_PIN 12
#define MAX_DISTANCE 200
NewPing sonar(TRIGGER_PIN, ECHO_PIN, MAX_DISTANCE); 
unsigned long currSonarMs;
unsigned long prevSonarMs = 0;
long sonarInterval = 50;
unsigned int sonarCm;

Servo armServo;
unsigned int armPos = 0;
unsigned int armTargetPos = 0;
unsigned long armCheckpoint = 0;
const int armParamMax = 50;
unsigned long armParam[armParamMax];
int armParamsToDo = 0;
int currArmParam = -1;

Servo headServo;
unsigned int headPos = 0;
unsigned int headTargetPos = 0;
unsigned long headCheckpoint = 0;
const int headParamMax = 50;
unsigned long headParam[headParamMax];
int headParamsToDo = 0;
int currHeadParam = -1;


static char statusStr[100];
unsigned long currMs;
unsigned long prevMs = 0;
// the follow variables is a long because the time, measured in miliseconds,
// will quickly become a bigger number than can be stored in an int.
// serialPrint status every second
//long statusInterval = 1000;
long statusInterval = 5000;

// Create the motor shield object with the default I2C address
Adafruit_MotorShield AFMS = Adafruit_MotorShield(); 
// Feet - wheels
// Select which 'port' M1, M2, M3 or M4. In this case, M1
Adafruit_DCMotor *leftFoot = AFMS.getMotor(1);
// You can also make another motor on port M2
Adafruit_DCMotor *rightFoot = AFMS.getMotor(2);
// Variables for the DC drive motors
unsigned int feetPos = 0;
unsigned int feetSpeed = 0;
unsigned long feetCheckpoint = 0;
const int feetParamMax = 50;
unsigned long feetParam[feetParamMax];
int feetParamsToDo = 0;
int currFeetParam = -1;
int feetDirection = MOVE_FORWARD;
int foot = BOTH_FEET;
boolean moving = false;
boolean turning = false;

// Variables for the LED "eyes"
boolean eyesOn = false;
unsigned long execEyesCmdCheckpoint = 0;
unsigned long eyesStart = 0;
int brightness = 0;    // how bright the LED is
int fadeAmount = 5;    // how many points to fade the LED by
const int eyesParamMax = 50;
unsigned long eyesParam[eyesParamMax];
int eyesParamsToDo = 0;
int currEyesParam = -1;

// Variables for the serial communication (commands and parameters)
int inByte;
char charBuffer[BUF_MAX];
int bufPos = -1;
char command;
boolean firstParam = true;
int paramsCnt = -1;
const int paramMax = 50;
unsigned long paramsList[paramMax];

uint8_t i;


void setup() {
  // Open serial communications and wait for port to open:
  //Serial.begin(9600);
  //while (!Serial);

  // Serial3 used for bluetooth communication to android smartphone
  Serial3.begin(38400);
  while (!Serial3);

  serialPrintln(0,"jjkBot running on the Mega");
  serialPrintln(0,"Serial3 running at 38400");

  // Smaller one - use for Head
  headServo.attach(SERVO_2_PIN);  // attaches the servo on pin 9 to the servo object
  // Bigger one - use for Arm
  armServo.attach(SERVO_1_PIN);  // attaches the servo on pin 10 to the servo object

  // Get the current servo positions
  armPos = armServo.read();
  armTargetPos = armPos;
  headPos = headServo.read();
  headTargetPos = headPos;

  AFMS.begin();  // create with the default frequency 1.6KHz
  //AFMS.begin(1000);  // OR with a different frequency, say 1KHz 
}

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

  // Get a sonar distance value every 50ms
  if (currMs - prevSonarMs > sonarInterval) {
    prevSonarMs = currMs;
    sonarCm = sonar.ping_cm();
    //ignore 0 and > 100?

    // Send a proximity warning and stop moving if too close to something
    if (sonarCm > 0 && sonarCm < 20) {
      if (moving && !turning) {
        leftFoot->run(RELEASE);
        rightFoot->run(RELEASE);
        serialPrintln(0,"proximity");
        feetParamsToDo = 0;
        moving = false;
        turning = false;
        feetCheckpoint = 0;
      }
    }
  }

  // Post data when the interval has been exceeded
  if (currMs - prevMs > statusInterval) {
    prevMs = currMs;
    // long unsigned %lu
    sprintf(statusStr,"Status currMs = %lu, sonarCm = %u;",currMs,sonarCm);
    Serial3.print(statusStr);
  }

} // End of loop


void moveHead() {
  if (currMs > headCheckpoint) {
    if (headPos == headTargetPos) {
      //moving = false;
    } else {
      //moving = true;
      if (headPos < headTargetPos) {
        headPos++;
      } else if (headPos > headTargetPos) {
        headPos--;
      }

      headServo.write(headPos);
      if (headPos == headTargetPos) {
        //serialPrintln2(0,"REACHED headTargetPos = ",headTargetPos);
        if (headParamsToDo > 0) {
          headCheckpoint = currMs + headParam[++currHeadParam];
          //serialPrint2(0,"currHeadParam = ",currHeadParam);
          //serialPrintln2(0,",checkpoint headParam[currHeadParam] = ",headParam[currHeadParam]);
          headParamsToDo--;
        }
        if (headParamsToDo > 0) {
          headTargetPos = headParam[++currHeadParam];
          //serialPrint2(0,"currHeadParam = ",currHeadParam);
          //serialPrintln2(0,",headTargetPos headParam[currHeadParam] = ",headParam[currHeadParam]);
          headParamsToDo--;
        }
      } else {
        headCheckpoint = currMs + SERVO_DELAY;                       
      }
    }
  }
} // void moveHead() {

void moveArm() {
  if (currMs > armCheckpoint) {
    if (armPos == armTargetPos) {
      //moving = false;
    } else {
      //moving = true;
      if (armPos < armTargetPos) {
        armPos++;
      } else if (armPos > armTargetPos) {
        armPos--;
      }

      armServo.write(armPos);
      if (armPos == armTargetPos) {
        //serialPrintln2(0,"REACHED armTargetPos = ",armTargetPos);
        if (armParamsToDo > 0) {
          armCheckpoint = currMs + armParam[++currArmParam];
          //serialPrint2(0,"currArmParam = ",currArmParam);
          //serialPrintln2(0,",checkpoint armParam[currArmParam] = ",armParam[currArmParam]);
          armParamsToDo--;
        }
        if (armParamsToDo > 0) {
          armTargetPos = armParam[++currArmParam];
          //serialPrint2(0,"currArmParam = ",currArmParam);
          //serialPrintln2(0,",armTargetPos armParam[currArmParam] = ",armParam[currArmParam]);
          armParamsToDo--;
        }
      } else {
        armCheckpoint = currMs + SERVO_DELAY;
      }               
    }
  }
} // void moveArm() {

void moveFeet() {
  if (currMs > feetCheckpoint) {
    if (moving) {
      leftFoot->run(RELEASE);
      rightFoot->run(RELEASE);
      moving = false;
      turning = false;
    }

// 4 parmeters for feet command
// 1 - foot (0 - Left, 1 - Right, 2 - Both)
// 2 - direction (0 - Backward, 1 - Forward, 2 - Left turn, 3 - Right turn)
// 3 - speed (0 - stopped to 255 - full speed)
// 4 - duration milliseconds (optional at end)

    if (feetParamsToDo > 0) {
      foot = feetParam[++currFeetParam];
      //serialPrint2(0,"foot = ",foot);
      feetParamsToDo--;
      
      feetDirection = feetParam[++currFeetParam];
      //serialPrint2(0,", direction = ",feetDirection);
      feetParamsToDo--;

      feetSpeed = feetParam[++currFeetParam];
      //serialPrintln2(0,", speed = ",feetSpeed);
      feetParamsToDo--;

      if (foot == BOTH_FEET) {
        leftFoot->setSpeed(feetSpeed);          
        rightFoot->setSpeed(feetSpeed);          
      } else if (foot == LEFT_FOOT) {
        leftFoot->setSpeed(feetSpeed);          
      } else if (foot == RIGHT_FOOT) {
        rightFoot->setSpeed(feetSpeed);  
      }

      if (feetDirection == MOVE_BACKWARD) {
        if (foot == BOTH_FEET) {
          //serialPrintln(0,"BOTH_FEET BACKWARD");
          leftFoot->run(BACKWARD);
          rightFoot->run(BACKWARD);        
        } else if (foot == LEFT_FOOT) {
          leftFoot->run(BACKWARD);
        } else if (foot == RIGHT_FOOT) {
          rightFoot->run(BACKWARD);        
        }
      } else if (feetDirection == MOVE_FORWARD) {
        if (foot == BOTH_FEET) {
          //serialPrintln(0,"BOTH_FEET FORWARD");
          leftFoot->run(FORWARD);
          rightFoot->run(FORWARD);                          
        } else if (foot == LEFT_FOOT) {
          leftFoot->run(FORWARD);
        } else if (foot == RIGHT_FOOT) {
          rightFoot->run(FORWARD);                
        }
      } else if (feetDirection == TURN_LEFT) {
          //serialPrintln(0,"TURN_LEFT");
          turning = true;
          leftFoot->run(FORWARD);
          rightFoot->run(BACKWARD);                          
      } else if (feetDirection == TURN_RIGHT) {
          //serialPrintln(0,"TURN RIGHT");
          turning = true;
          leftFoot->run(BACKWARD);
          rightFoot->run(FORWARD);                          
      }
      moving = true;

      // Optional parameter for duration (else it just keeps moving)
      if (feetParamsToDo > 0) {
          feetCheckpoint = currMs + feetParam[++currFeetParam];
          feetParamsToDo--;
      }
      
    } // if (feetParamsToDo > 0) {    
  } //   if (currMs > feetCheckpoint) {
} // void moveFeet() {


// Turn LED eyes on and off according to the timing parameters in the $EYES command array
void flashEyes() {

  if (execEyesCmdCheckpoint > 0) {
    // check when to stop
    if (currMs > execEyesCmdCheckpoint) {      
      eyesParamsToDo--;      

      execEyesCmdCheckpoint = 0;
      // If no more params to execute, turn the eyes off
      if (eyesParamsToDo < 1) {
        resetEyes();
      }
    }
  } else {
    if (currMs > eyesStart) {
      if (eyesParamsToDo > 0) {
        currEyesParam++;
        execEyesCmdCheckpoint = currMs + eyesParam[currEyesParam];
  
        if (eyesOn) {
          brightness = LIGHT_OFF;
          eyesOn = false;
        } else {
          brightness = LIGHT_ON;
          eyesOn = true;
        }
        analogWrite(LEFT_EYE, brightness);
        analogWrite(RIGHT_EYE, brightness);

      } else {
        // If no params to do and no checkpoints, make sure the eyes are off
        if (eyesOn) {
          resetEyes();
        }
      }
      
    } // if (currMs > eyesStart) {
    
  }

} // End of void flashEyes()

void resetEyes() {
  //Serial.println("===== Reset EYES =====");
  analogWrite(LEFT_EYE, LIGHT_OFF);
  analogWrite(RIGHT_EYE, LIGHT_OFF);
  eyesOn = false;  
  execEyesCmdCheckpoint = 0;
}


// Read characters from the Serial input, parse into commands and parameters
// Command lines are in the following pattern:  $command,param1,param2,param3;
// 10 = Newline
// 13 = Carriage return
// 13 10 = Carriage return and Newline
// 58 = :
// 59 = ;
// 35 = #
// 36 = $
// 44 = ,
void serialEvent() {
  // Don't loop here, it will throw off the main loop timing and incremental execution
  //while (Serial3.available()) {
  if (Serial3.available()) {
    inByte = Serial3.read();

    switch (inByte) {
      case 59: // Semi-colon (command stop)
        if (firstParam) {
          command = charBuffer[0];
        } else {
          bufPos++;
          charBuffer[bufPos] = '\0';
          paramsCnt++;
          paramsList[paramsCnt] = atoi(charBuffer);
        }
        // Execute the command
        executeCommand(command);
        break;
        
      case 44: // Comma (separator) 
        if (firstParam) {
          command = charBuffer[0];
          firstParam = false;
        } else {
          bufPos++;
          charBuffer[bufPos] = '\0';
          paramsCnt++;
          paramsList[paramsCnt] = atoi(charBuffer);
        }
        bufPos = -1;
        break;

      default:
        bufPos++;
        charBuffer[bufPos] = (char)inByte;
    } // End of switch (inByte)
    
  } // End of while (Serial.available())
} // End of serialEvent

// Copy the parameters and execute the command
void executeCommand(char cmd) {

      serialPrint(0,"Execute cmd = ");
      serialPrint(0,String(cmd));
      serialPrintln2(0,", paramsCnt = ",(paramsCnt+1));
      /*
      serialPrint(0,", currMs = ");
      serialPrintln(0,String(currMs));
      */
      
  // Eyes
  if (cmd == 'E') {
    // If start a new command when one is executing, delay before starting ???
    if (eyesOn) {
      eyesStart = currMs + 15;
    }
    
    // First call reset to stop the execution of the current command
    resetEyes();
    // Copy the parameters into the ToDo array
    for (i = 0; i <= paramsCnt; i++) {
      eyesParam[i] = paramsList[i];
    }
    // Set the ToDo count
    currEyesParam = -1;
    eyesParamsToDo = paramsCnt+1;
    
  } else if (cmd == 'H') {
    // Copy the parameters into the ToDo array
    for (i = 0; i <= paramsCnt; i++) {
      headParam[i] = paramsList[i];
    }
    // Set the ToDo count
    currHeadParam = -1;
    headParamsToDo = paramsCnt+1;

    // Set the 1st target
    if (headParamsToDo > 0) {
      headTargetPos = headParam[++currHeadParam];
      headParamsToDo--;
    }
    
  } else if (cmd == 'A') {
    // Copy the parameters into the ToDo array
    for (i = 0; i <= paramsCnt; i++) {
      armParam[i] = paramsList[i];
    }
    // Set the ToDo count
    currArmParam = -1;
    armParamsToDo = paramsCnt+1;

    // Set the 1st target
    if (armParamsToDo > 0) {
      armTargetPos = armParam[++currArmParam];
      armParamsToDo--;
    }

  } else if (cmd == 'F') {
    // Copy the parameters into the ToDo array
    for (i = 0; i <= paramsCnt; i++) {
      feetParam[i] = paramsList[i];
    }
    // Set the ToDo count
    currFeetParam = -1;
    feetParamsToDo = paramsCnt+1;

  } else {
    // If command not recognized, assume STOP
    feetParamsToDo = 0;
    leftFoot->run(RELEASE);
    rightFoot->run(RELEASE);
    moving = false;
    turning = false;
    feetCheckpoint = 0;

    eyesParamsToDo = 0;
    resetEyes();

    headTargetPos = headPos;
    headCheckpoint = 0;
    headParamsToDo = 0;
    headCheckpoint = 0;

    armTargetPos = armPos;
    armCheckpoint = 0;
    armParamsToDo = 0;
    armCheckpoint = 0;
  }

  // Reset for next command
  firstParam = true;
  command = ' ';
  bufPos = -1;
  paramsCnt = -1;

} // void executeCommand(char cmd) {

// Figure out a more generic print using sprintf
// long unsigned %lu
//sprintf(statusStr,"Status currMs = %lu, sonarCm = %u;",currMs,sonarCm);

void serialPrint(int level, String outStr) {
  Serial3.print(outStr);
}
void serialPrintln(int level, String outStr) {
  Serial3.print(outStr+";");
}

void serialPrint2(int level, String outStr, int outInt) {
  Serial3.print(outStr+String(outInt));
}
void serialPrintln2(int level, String outStr, int outInt) {
  Serial3.print(outStr+String(outInt)+";");
}

