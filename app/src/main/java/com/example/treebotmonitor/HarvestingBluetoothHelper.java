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
import java.util.concurrent.atomic.AtomicBoolean;

public class HarvestingBluetoothHelper {
    private static final String TAG = "HarvestingBluetoothHelper";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long RECONNECT_DELAY = 5000; // 5 seconds

    // Singleton instance for harvesting
    private static HarvestingBluetoothHelper instance;

    private Context context;
    private Handler handler;
    private BluetoothAdapter bluetoothAdapter;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldStopReconnecting = new AtomicBoolean(false);
    private String connectedDeviceName = "";
    private String lastConnectedDeviceAddress = "";

    // Auto-reconnection
    private Handler reconnectHandler;
    private boolean autoReconnectEnabled = true;

    // Private constructor for singleton
    private HarvestingBluetoothHelper(Context context, Handler handler) {
        this.context = context.getApplicationContext(); // Use application context to prevent memory leaks
        this.handler = handler;
        this.reconnectHandler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Log.d(TAG, "HarvestingBluetoothHelper instance created");
    }

    // Get singleton instance
    public static synchronized HarvestingBluetoothHelper getInstance(Context context, Handler handler) {
        if (instance == null) {
            instance = new HarvestingBluetoothHelper(context, handler);
        }
        // Always update handler for current activity
        instance.updateHandler(handler);
        return instance;
    }

    // Update handler for different activities (if needed)
    public void updateHandler(Handler newHandler) {
        this.handler = newHandler;
        Log.d(TAG, "Handler updated for harvesting bluetooth");
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
        Log.d(TAG, "Attempting to connect to harvesting device: " + address);

        // Check permissions first
        if (!checkBluetoothPermissions()) {
            sendStatusUpdate("Harvesting Bluetooth: Permission denied");
            return false;
        }

        if (bluetoothAdapter == null) {
            sendStatusUpdate("Harvesting Bluetooth: Adapter not available");
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            sendStatusUpdate("Harvesting Bluetooth: Please enable Bluetooth");
            return false;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            lastConnectedDeviceAddress = address; // Store for auto-reconnection
            shouldStopReconnecting.set(false);
            disconnectInternal(); // Close any existing connections first

            // Start connection attempt
            connectThread = new ConnectThread(device);
            connectThread.start();
            sendStatusUpdate("Connecting to harvesting device...");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception: " + e.getMessage());
            sendStatusUpdate("Harvesting Bluetooth: Permission denied");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to harvesting device: " + e.getMessage());
            sendStatusUpdate("Harvesting Bluetooth: Error connecting");
            return false;
        }
    }

    public void disconnect() {
        Log.d(TAG, "User initiated disconnect from harvesting device");
        shouldStopReconnecting.set(true);
        autoReconnectEnabled = false;
        lastConnectedDeviceAddress = "";
        disconnectInternal();
    }

    private void disconnectInternal() {
        Log.d(TAG, "Disconnecting from harvesting device internally");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        isConnected.set(false);
        connectedDeviceName = "";

        // Cancel any pending reconnection attempts
        if (reconnectHandler != null) {
            reconnectHandler.removeCallbacksAndMessages(null);
        }

        sendStatusUpdate("Harvesting Bluetooth: Disconnected");
    }

    public void sendCommand(String command) {
        if (connectedThread != null && isConnected.get()) {
            Log.d(TAG, "Sending harvesting command: " + command);
            connectedThread.write(command.getBytes());
        } else {
            Log.w(TAG, "Cannot send command - not connected to harvesting device");
            sendStatusUpdate("Harvesting Bluetooth: Not connected");

            // Try to reconnect if auto-reconnect is enabled
            if (autoReconnectEnabled && !lastConnectedDeviceAddress.isEmpty()) {
                startReconnectionAttempt();
            }
        }
    }

    public boolean isConnected() {
        return isConnected.get() && connectedThread != null;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    public void enableAutoReconnect(boolean enable) {
        autoReconnectEnabled = enable;
        if (!enable) {
            shouldStopReconnecting.set(true);
            if (reconnectHandler != null) {
                reconnectHandler.removeCallbacksAndMessages(null);
            }
        }
    }

    private void startReconnectionAttempt() {
        if (shouldStopReconnecting.get() || !autoReconnectEnabled || lastConnectedDeviceAddress.isEmpty()) {
            return;
        }

        Log.d(TAG, "Starting auto-reconnection attempt in " + (RECONNECT_DELAY / 1000) + " seconds");

        if (reconnectHandler != null) {
            reconnectHandler.postDelayed(() -> {
                if (!isConnected.get() && !shouldStopReconnecting.get() && autoReconnectEnabled) {
                    Log.d(TAG, "Attempting auto-reconnection to: " + lastConnectedDeviceAddress);
                    connectToDevice(lastConnectedDeviceAddress);
                }
            }, RECONNECT_DELAY);
        }
    }

    private void sendStatusUpdate(String status) {
        Log.d(TAG, "Status update: " + status);
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
                Log.d(TAG, "Socket created for harvesting device");
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for harvesting socket creation", e);
                sendStatusUpdate("Harvesting Bluetooth: Permission denied");
            } catch (IOException e) {
                Log.e(TAG, "Harvesting socket creation failed", e);
                sendStatusUpdate("Harvesting Bluetooth: Socket creation failed");
            }
            socket = tmp;
        }

        public void run() {
            if (socket == null) {
                sendStatusUpdate("Harvesting Bluetooth: Socket creation failed");
                if (autoReconnectEnabled && !shouldStopReconnecting.get()) {
                    startReconnectionAttempt();
                }
                return;
            }

            try {
                if (!checkBluetoothPermissions()) {
                    throw new SecurityException("Bluetooth connect permission not granted");
                }

                // Cancel discovery as it slows down the connection
                bluetoothAdapter.cancelDiscovery();

                Log.d(TAG, "Attempting to connect harvesting socket...");
                socket.connect();

                String deviceName;
                try {
                    deviceName = device.getName();
                    if (deviceName == null || deviceName.isEmpty()) {
                        deviceName = "Harvesting Device";
                    }
                } catch (SecurityException e) {
                    deviceName = "Harvesting Device";
                }

                connectedDeviceName = deviceName;
                sendStatusUpdate("Harvesting Bluetooth: Connected to " + deviceName);
                Log.d(TAG, "Successfully connected to harvesting device: " + deviceName);

                // Start the connected thread
                connectedThread = new ConnectedThread(socket);
                connectedThread.start();
                isConnected.set(true);

                // Send initial handshake or ready signal
                sendStatusUpdate("Harvesting System: Ready for commands");

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied during harvesting connection", e);
                sendStatusUpdate("Harvesting Bluetooth: Permission denied");
                closeSocket(socket);
                isConnected.set(false);
            } catch (IOException connectException) {
                Log.e(TAG, "Harvesting connection failed", connectException);
                closeSocket(socket);
                sendStatusUpdate("Harvesting Bluetooth: Connection failed");
                isConnected.set(false);

                // Try to reconnect if enabled
                if (autoReconnectEnabled && !shouldStopReconnecting.get()) {
                    startReconnectionAttempt();
                }
            }
        }

        private void closeSocket(BluetoothSocket socket) {
            try {
                if (socket != null) {
                    socket.close();
                    Log.d(TAG, "Harvesting socket closed");
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the harvesting client socket", e);
            }
        }

        public void cancel() {
            closeSocket(socket);
            Log.d(TAG, "Harvesting connect thread cancelled");
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
                Log.d(TAG, "Harvesting streams created successfully");
            } catch (IOException e) {
                Log.e(TAG, "Error creating harvesting streams", e);
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied when creating harvesting streams", e);
                sendStatusUpdate("Harvesting Bluetooth: Permission denied");
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            if (inStream == null) {
                Log.e(TAG, "Harvesting input stream is null, cannot read data");
                isConnected.set(false);
                sendStatusUpdate("Harvesting Bluetooth: Stream error");

                if (autoReconnectEnabled && !shouldStopReconnecting.get()) {
                    startReconnectionAttempt();
                }
                return;
            }

            buffer = new byte[1024];
            int numBytes;
            StringBuilder messageBuffer = new StringBuilder();

            Log.d(TAG, "Starting to listen for harvesting messages...");

            while (isConnected.get() && !shouldStopReconnecting.get()) {
                try {
                    numBytes = inStream.read(buffer);
                    if (numBytes > 0) {
                        String received = new String(buffer, 0, numBytes);
                        messageBuffer.append(received);

                        // Process complete messages (ending with * or newline)
                        String fullMessage = messageBuffer.toString();
                        if (fullMessage.contains("*") || fullMessage.contains("\n")) {
                            // Split by message delimiters and process each complete message
                            String[] messages = fullMessage.split("[*\n]");
                            for (int i = 0; i < messages.length - 1; i++) {
                                if (!messages[i].trim().isEmpty()) {
                                    processHarvestingMessage(messages[i].trim());
                                }
                            }

                            // Keep the last incomplete message in buffer
                            if (messages.length > 0 && !fullMessage.endsWith("*") && !fullMessage.endsWith("\n")) {
                                messageBuffer = new StringBuilder(messages[messages.length - 1]);
                            } else {
                                messageBuffer = new StringBuilder();
                            }
                        }

                        Log.d(TAG, "Harvesting received: " + received);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Harvesting connection lost during read", e);
                    isConnected.set(false);
                    sendStatusUpdate("Harvesting Bluetooth: Connection lost");

                    // Try to reconnect if enabled
                    if (autoReconnectEnabled && !shouldStopReconnecting.get()) {
                        startReconnectionAttempt();
                    }
                    break;
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied when reading harvesting data", e);
                    isConnected.set(false);
                    sendStatusUpdate("Harvesting Bluetooth: Permission denied");
                    break;
                }
            }
        }

        private void processHarvestingMessage(String message) {
            Log.d(TAG, "Processing harvesting message: " + message);

            // Send the processed message to the handler
            if (handler != null) {
                Message msg = handler.obtainMessage();
                msg.obj = message;
                handler.sendMessage(msg);
            }
        }

        public void write(byte[] bytes) {
            try {
                if (outStream == null) {
                    sendStatusUpdate("Harvesting Bluetooth: Output stream error");
                    return;
                }
                outStream.write(bytes);
                outStream.flush(); // Ensure data is sent immediately
                Log.d(TAG, "Harvesting data sent: " + new String(bytes));
            } catch (IOException e) {
                Log.e(TAG, "Error sending harvesting data", e);
                sendStatusUpdate("Harvesting Bluetooth: Error sending command");
                isConnected.set(false);

                // Try to reconnect if enabled
                if (autoReconnectEnabled && !shouldStopReconnecting.get()) {
                    startReconnectionAttempt();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied when writing harvesting data", e);
                sendStatusUpdate("Harvesting Bluetooth: Permission denied");
            }
        }

        public void cancel() {
            isConnected.set(false);
            try {
                if (socket != null) {
                    socket.close();
                    Log.d(TAG, "Harvesting connected thread cancelled");
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the harvesting connected socket", e);
            }
        }
    }

    // Utility method to get available Bluetooth devices
    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    // Method to check if Bluetooth is enabled
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    // Clean up resources when app is completely destroyed
    public void cleanup() {
        Log.d(TAG, "Cleaning up HarvestingBluetoothHelper resources");
        shouldStopReconnecting.set(true);
        autoReconnectEnabled = false;

        if (reconnectHandler != null) {
            reconnectHandler.removeCallbacksAndMessages(null);
        }

        disconnectInternal();
        instance = null;
    }
}