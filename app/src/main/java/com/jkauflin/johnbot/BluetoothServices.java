/*==============================================================================
 * (C) Copyright 2016,2017 John J Kauflin, All rights reserved.
 *----------------------------------------------------------------------------
 * DESCRIPTION:  Class to handle all wireless communication with the Arduino
 *               robot.
 *----------------------------------------------------------------------------
 * Modification History
 * 2017-01-01 JJK   Initial version
 * 2017-01-06 JJK   Implemented ConnectThread and Connected Thread concepts
 *                  from the BluetoothChat example and got back and forth
 *                  command line communications working
 * 2017-02-11 JJK   Removed the context parameter on create becuase this
 *                  services class should not do any UI work
 *============================================================================*/
package com.jkauflin.johnbot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;


public class BluetoothServices {
    private static final String TAG = "btServices";

    // SPP UUID service  UUID for RFCOMM/SPP
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // MAC-address of my Bluetooth module (Arduino HC-05 Name:H-C-2010-06-01)
    private static final String address = "98:D3:31:FD:1C:58";

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    private BluetoothAdapter btAdapter = null;
    private BluetoothDevice btDevice = null;
    private Handler mHandler = null;  // handler that gets info from Bluetooth service
    private ConnectThread connectThread = null;
    private ConnectedThread connectedThread = null;
    private int mState;

    //---------------------------------------------------------------------------------------------
    // Constructor for BluetoothServices
    // @param handler A Handler to send messages back to the UI Activity
    //---------------------------------------------------------------------------------------------
    public BluetoothServices(Handler handler) {
        try {
            btAdapter = BluetoothAdapter.getDefaultAdapter();
            mState = STATE_NONE;
            mHandler = handler;

            // Check for Bluetooth support and then check to make sure it is turned on
            // Emulator doesn't support Bluetooth and will return null
            if (btAdapter == null) {
                throw new RuntimeException("Bluetooth Adapter is NULL");
            } else {
                if (btAdapter.isEnabled()) {
                    //Log.d(TAG, "Bluetooth ON");
                } else {
                    //Prompt user to turn on Bluetooth
                /*
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                */
                    throw new RuntimeException("Bluetooth Adapter is not enabled");
                }
            }

            // Set up a pointer to the remote node using it's address.
            btDevice = btAdapter.getRemoteDevice(address);

        } catch (Exception e) {
            Log.e(TAG,"Error getting device from adpater: ",e);
            throw new RuntimeException("Error in BluetoothServices");
        }

    } // public BluetoothServices()


    private synchronized void setState(int state) {
        mState = state;
    }
    public synchronized int getState() {
        return mState;
    }


    public synchronized void connect() {
        // Cancel any thread attempting to make a connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        connectThread = new ConnectThread();
        connectThread.start();
        setState(STATE_CONNECTING);

    } // public void connect() {

    public synchronized void close() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        setState(STATE_NONE);
    }


    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;

        public ConnectThread() {
            // Use a temporary object that is later assigned to mmSocket
            // because mmSocket is final.
            BluetoothSocket tmpSocket = null;

            try {
                // Get a BluetoothSocket to connect with the given BluetoothDevice.
                // MY_UUID is the app's UUID string, also used in the server code.
                tmpSocket = btDevice.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
            }
            mmSocket = tmpSocket;
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            btAdapter.cancelDiscovery();

            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
                //Log.d(TAG, "Socket connected");
            } catch (Exception e) {
                // Unable to connect; close the socket and return.
                Log.e(TAG,"*** Unable to connect ***");
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothServices.this) {
                connectThread = null;
            }

            // Start the connected thread
            connected(mmSocket);
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "CONNECT T - Could not close the client socket", e);
            }
        }
    } // private class ConnectThread extends Thread

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     */
    public synchronized void connected(BluetoothSocket socket) {

        // Cancel the thread that completed the connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // The connection attempt succeeded. Perform work associated with
        // the connection in a separate thread.
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        setState(STATE_CONNECTED);
        Log.i(TAG, "connected");
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     */
    public void write(String message) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = connectedThread;
        }
        // Perform the write unsynchronized
        // *** check for errors ***
        try {
            r.write(message);
        } catch (Exception e) {
            Log.e(TAG,"Error on write",e);
        }
    }


    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream inSerial3;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer; // mmBuffer store for the stream

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams; using temp objects because
            // member streams are final.
            try {
                tmpIn = socket.getInputStream();
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = socket.getOutputStream();
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            inSerial3 = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            mmBuffer = new byte[1024];
            int numBytes; // bytes returned from read()

            int inByte;
            String inStr = "";
            String command = "";

            // Keep listening to the InputStream until an exception occurs.
            while (mState == STATE_CONNECTED) {

                try {
                    if (inSerial3.available() > 0) {
                        inByte = inSerial3.read();

                        switch (inByte) {
                            /*
                            case 36: // Dollar sign (command start)
                                //commandStart = true;
                                //commandStop = false;
                                command = "";
                                inStr = "";
                                break;
                            */
                            case 59: // Semi-colon (command stop)
                                /*
                                if (commandStart) {
                                    command = inStr;
                                    //Serial.println("Command = "+inStr);
                                } else {
                                    //Serial.println("param = "+inStr);
                                    addCommandParam(command,inStr);
                                }
                                commandStart = false;
                                commandStop = true;
                                */
                                //Log.d(TAG,"inStr = "+inStr);

                                // Send the obtained bytes to the UI Activity

                                Message msg = Message.obtain(); // Creates an new Message instance
                                msg.obj = inStr; // Put the string into Message, into "obj" field.
                                msg.setTarget(mHandler); // Set the Handler
                                msg.sendToTarget(); //Send the message
                                /*
                                mHandler.obtainMessage(1, bytes, -1, buffer)
                                        .sendToTarget();

                                Message readMsg = mHandler.obtainMessage(
                                        MessageConstants.MESSAGE_READ, numBytes, -1,
                                        mmBuffer);
                                readMsg.sendToTarget();


                                final Message msg = new Message();
                                final Bundle b = new Bundle();
                                b.putInt("KEY", value);
                                msg.setData(b);
                                handler.sendMessage(msg);
                                */

                                command = "";
                                inStr = "";
                                break;
                            /*
                            case 44: // Comma (separator)
                                if (commandStart) {
                                    command = inStr;
                                    //Serial.println("Command = "+inStr);
                                    commandStart = false;
                                } else {
                                    //Serial.println("param = "+inStr);
                                    addCommandParam(command,inStr);
                                }
                                inStr = "";
                                break;
                             */
                            default:
                                inStr += Character.toString((char)inByte);

                        } // End of switch (inByte)

                    } // End of while (Serial.available())

                    /*
                    if (inSerial3.available() > 0) {
                        // Read from the InputStream.
                        numBytes = inSerial3.read(mmBuffer);

                        //String decodedData = new String(mmBuffer);  // Create new String Object and assign byte[] to it
                        //Log.d(TAG,"Text Decryted : " + decodedData);
                        decodedDataUsingUTF8 = new String(mmBuffer, "UTF-8");  // Best way to decode using "UTF-8"
                        Log.d(TAG,"Text Decryted using UTF-8 : " + decodedDataUsingUTF8);
                    }
                    */

                    // Send the obtained bytes to the UI activity.
                    /*
                    Message readMsg = mHandler.obtainMessage(
                            MessageConstants.MESSAGE_READ, numBytes, -1,
                            mmBuffer);
                    readMsg.sendToTarget();
                    */


                } catch (Exception e) {
                    //Log.e(TAG, "*** Input stream was disconnected ***");
                    //connectionLost();  *** send a message if needed ***

                    setState(STATE_NONE);
                    break;
                }


            } // while (true)
        } // public void run() {

        // Call this from the main activity to send data to the remote device.
        //public void write(byte[] bytes) {
        public void write(String command) {
            try {
                byte[] msgBuffer = command.getBytes();
                Log.d(TAG, "send Command: " + command);
                mmOutStream.write(msgBuffer);

            } catch (IOException e) {
                Log.e(TAG, "*** Error occurred when sending data ***");
                //connectionLost();  *** send a message if needed ***
                // Start the service over to restart listening mode

                setState(STATE_NONE);

                Log.e(TAG, "Re-connecting bluetooth services");
                BluetoothServices.this.connect();
            }
        } // public void write(String message)

        // Call this method from the main activity to shut down the connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "CONNECTED THREAD Could not close the connect socket ", e);
            }
        }
    } // private class ConnectedThread extends Thread

} // public class BluetoothServices
