package com.hmithinkware.bluetooth_application;

import static android.content.ContentValues.TAG;
import static com.hmithinkware.bluetooth_application.MainActivity.MESSAGE_READ;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
// Implementation of the ConnectedThread class that was missing
class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mmHandler;

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mmHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        // Get the input and output streams, using temp objects because member streams are final
        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "Error creating IO streams", e);
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    public void run() {
        byte[] buffer = new byte[1024];  // buffer store for the stream
        int bytes; // bytes returned from read()

        // Keep listening to the InputStream until an exception occurs
        while (true) {
            try {
                // Read from the InputStream
                bytes = mmInStream.read(buffer);
                if (bytes > 0) {
                    // Send the obtained bytes to the UI activity
                    mmHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget();
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection lost", e);
                break;
            }
        }
    }

    // Call this from the main activity to send data to the remote device
    public void write(String message) {
        byte[] msgBuffer = message.getBytes();
        try {
            mmOutStream.write(msgBuffer);
        } catch (IOException e) {
            Log.e(TAG, "Error sending data", e);
        }
    }

    // Call this from the main activity to shutdown the connection
    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
    }
}
