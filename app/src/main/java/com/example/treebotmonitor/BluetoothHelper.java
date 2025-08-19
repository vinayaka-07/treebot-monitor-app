package com.example.treebotmonitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothHelper {
    private static final String TAG = "BluetoothHelper";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Singleton instance
    private static BluetoothHelper instance;

    private Context context;
    private Handler handler;
    private BluetoothAdapter bluetoothAdapter;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private boolean isConnected = false;

    // Private constructor for singleton
    private BluetoothHelper(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // Get singleton instance
    public static synchronized BluetoothHelper getInstance(Context context, Handler handler) {
        if (instance == null) {
            instance = new BluetoothHelper(context, handler);
        }
        return instance;
    }

    // Update handler for different activities
    public void updateHandler(Handler newHandler) {
        this.handler = newHandler;
    }

    /**
     * Check if we have the necessary Bluetooth permissions
     */
    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED;
        }
        return true; // For older Android versions
    }

    public boolean connectToDevice(String address) {
        // Check permissions first
        if (!checkBluetoothPermissions()) {
            sendStatusUpdate("Bluetooth: Permission denied");
            return false;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            disconnect(); // Close any existing connections first

            // Start connection attempt
            connectThread = new ConnectThread(device);
            connectThread.start();
            sendStatusUpdate("Connecting to " + device.getName() + "...");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception: " + e.getMessage());
            sendStatusUpdate("Bluetooth: Permission denied");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to device: " + e.getMessage());
            sendStatusUpdate("Bluetooth: Error connecting");
            return false;
        }
    }

    public void disconnect() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        isConnected = false;
        sendStatusUpdate("Bluetooth: Disconnected");
    }

    public void sendCommand(String command) {
        if (connectedThread != null) {
            connectedThread.write(command.getBytes());
        } else {
            sendStatusUpdate("Bluetooth: Not connected");
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    private void sendStatusUpdate(String status) {
        if (handler != null) {
            Message msg = handler.obtainMessage();
            msg.obj = status;
            handler.sendMessage(msg);
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;

            try {
                if (!checkBluetoothPermissions()) {
                    throw new SecurityException("Bluetooth connect permission not granted");
                }
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for socket creation", e);
                sendStatusUpdate("Bluetooth: Permission denied");
            } catch (IOException e) {
                Log.e(TAG, "Socket creation failed", e);
            }
            socket = tmp;
        }

        public void run() {
            if (socket == null) {
                sendStatusUpdate("Bluetooth: Socket creation failed");
                return;
            }

            try {
                if (!checkBluetoothPermissions()) {
                    throw new SecurityException("Bluetooth connect permission not granted");
                }

                // Cancel discovery as it slows down the connection
                bluetoothAdapter.cancelDiscovery();
                socket.connect();

                String deviceName;
                try {
                    deviceName = device.getName();
                } catch (SecurityException e) {
                    deviceName = "device";
                }

                sendStatusUpdate("Bluetooth: Connected to " + deviceName);

                // Start the connected thread
                connectedThread = new ConnectedThread(socket);
                connectedThread.start();
                isConnected = true;

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied during connection", e);
                sendStatusUpdate("Bluetooth: Permission denied");
                try {
                    socket.close();
                } catch (Exception closeEx) {
                    Log.e(TAG, "Could not close the client socket", closeEx);
                }
                isConnected = false;
            } catch (IOException connectException) {
                Log.e(TAG, "Connection failed", connectException);
                try {
                    socket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                sendStatusUpdate("Bluetooth: Connection failed");
                isConnected = false;
            }
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inStream;
        private final OutputStream outStream;
        private byte[] buffer;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error creating streams", e);
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied when creating streams", e);
                sendStatusUpdate("Bluetooth: Permission denied");
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            if (inStream == null) {
                Log.e(TAG, "Input stream is null, cannot read data");
                isConnected = false;
                sendStatusUpdate("Bluetooth: Stream error");
                return;
            }

            buffer = new byte[1024];
            int numBytes;

            while (true) {
                try {
                    numBytes = inStream.read(buffer);
                    String received = new String(buffer, 0, numBytes);

                    // Process received data if needed
                    Log.d(TAG, "Received: " + received);

                } catch (IOException e) {
                    Log.e(TAG, "Connection lost", e);
                    isConnected = false;
                    sendStatusUpdate("Bluetooth: Disconnected");
                    break;
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied when reading", e);
                    isConnected = false;
                    sendStatusUpdate("Bluetooth: Permission denied");
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                if (outStream == null) {
                    sendStatusUpdate("Bluetooth: Output stream error");
                    return;
                }
                outStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Error sending data", e);
                sendStatusUpdate("Bluetooth: Error sending command");
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied when writing", e);
                sendStatusUpdate("Bluetooth: Permission denied");
            }
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connected socket", e);
            }
        }
    }
}