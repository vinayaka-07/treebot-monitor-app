package com.example.treebotmonitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 200;
    private static final int PERMISSION_REQUEST_BLUETOOTH = 201;

    private BluetoothHelper bluetoothHelper;
    private TextView tvBluetoothStatus;
    private TextView tvSpeedStatus;
    private Button btnConnect;
    private Button btnVoiceCommand;
    private Button btnSpeedUp;
    private Button btnSpeedDown;
    private Button btnHarvesting;
    private BluetoothAdapter bluetoothAdapter;
    private String selectedDeviceAddress; // Stores the selected device's MAC address

    // Voice command components
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private Map<String, String> voiceCommandMap;

    // Speed control
    private int currentSpeed = 1; // 1 = Low, 2 = Medium, 3 = High
    private final String[] speedLabels = {"Low Speed", "Medium Speed", "High Speed"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        initializeBluetoothComponents();
        setupVoiceCommandComponents();
        setupControlButtons();
        updateSpeedDisplay();
    }

    private void initializeViews() {
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus);
        tvSpeedStatus = findViewById(R.id.tvSpeedStatus);
        btnConnect = findViewById(R.id.btnConnect);
        btnVoiceCommand = findViewById(R.id.btnVoiceCommand);
        btnSpeedUp = findViewById(R.id.btnSpeedUp);
        btnSpeedDown = findViewById(R.id.btnSpeedDown);
        btnHarvesting = findViewById(R.id.btnHarvesting);
    }

    private void initializeBluetoothComponents() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Updated to use singleton pattern
        bluetoothHelper = BluetoothHelper.getInstance(this, new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                tvBluetoothStatus.setText((String) msg.obj);
                return true;
            }
        }));

        btnConnect.setOnClickListener(v -> {
            if (checkBluetoothPermissions()) {
                showAvailableDevices();
            } else {
                requestBluetoothPermissions();
            }
        });
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED;
        }
        return true; // For older Android versions
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST_BLUETOOTH);
        }
    }

    private void setupVoiceCommandComponents() {
        // Initialize voice command mapping (updated to include speed commands)
        voiceCommandMap = new HashMap<>();
        voiceCommandMap.put("forward", "FORWARD*");
        voiceCommandMap.put("go forward", "FORWARD*");
        voiceCommandMap.put("move forward", "FORWARD*");
        voiceCommandMap.put("climb up", "FORWARD*");
        voiceCommandMap.put("reverse", "REVERSE*");
        voiceCommandMap.put("go back", "REVERSE*");
        voiceCommandMap.put("move back", "REVERSE*");
        voiceCommandMap.put("backward", "REVERSE*");
        voiceCommandMap.put("go backward", "REVERSE*");
        voiceCommandMap.put("climb down", "REVERSE*");
        voiceCommandMap.put("riverse", "REVERSE*");
        voiceCommandMap.put("reveres", "REVERSE*");
        voiceCommandMap.put("stop", "STOP*");
        voiceCommandMap.put("Stop", "STOP*");
        voiceCommandMap.put("top", "STOP*");
        voiceCommandMap.put("staff", "STOP*");
        voiceCommandMap.put("STOP", "STOP*");

        // Speed control voice commands
        voiceCommandMap.put("speed up", "SPEED_UP");
        voiceCommandMap.put("increase speed", "SPEED_UP");
        voiceCommandMap.put("faster", "SPEED_UP");
        voiceCommandMap.put("speed down", "SPEED_DOWN");
        voiceCommandMap.put("decrease speed", "SPEED_DOWN");
        voiceCommandMap.put("slower", "SPEED_DOWN");
        voiceCommandMap.put("low speed", "SPEED_LOW");
        voiceCommandMap.put("high speed", "SPEED_HIGH");

        // Initialize speech recognizer
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new VoiceRecognitionListener());
        } else {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show();
            btnVoiceCommand.setEnabled(false);
        }

        // Set up voice command button
        btnVoiceCommand.setOnClickListener(v -> {
            if (checkVoicePermission()) {
                toggleVoiceRecognition();
            } else {
                requestVoicePermission();
            }
        });
    }

    private boolean checkVoicePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }

    private void requestVoicePermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_RECORD_AUDIO);
    }

    private void toggleVoiceRecognition() {
        if (isListening) {
            // Stop listening
            speechRecognizer.stopListening();
            btnVoiceCommand.setText("🎤 Voice Command");
            isListening = false;
        } else {
            // Start listening
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            try {
                speechRecognizer.startListening(intent);
                btnVoiceCommand.setText("🔴 Listening...");
                isListening = true;
            } catch (Exception e) {
                Log.e(TAG, "Error starting speech recognition: " + e.getMessage());
                Toast.makeText(this, "Error starting voice recognition", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class VoiceRecognitionListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            // Ready to receive speech
        }

        @Override
        public void onBeginningOfSpeech() {
            // User started speaking
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Sound level changed
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // More sound has been received
        }

        @Override
        public void onEndOfSpeech() {
            // User stopped speaking
            btnVoiceCommand.setText("🎤 Voice Command");
            isListening = false;
        }

        @Override
        public void onError(int error) {
            // Handle errors
            String errorMessage;
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO:
                    errorMessage = "Audio recording error";
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    errorMessage = "Client side error";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    errorMessage = "Insufficient permissions";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                    errorMessage = "Network error";
                    break;
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    errorMessage = "Network timeout";
                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                    errorMessage = "No match found";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    errorMessage = "Recognition service busy";
                    break;
                case SpeechRecognizer.ERROR_SERVER:
                    errorMessage = "Server error";
                    break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorMessage = "No speech input";
                    break;
                default:
                    errorMessage = "Unknown error";
                    break;
            }
            Log.e(TAG, "Speech recognition error: " + errorMessage);
            Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            btnVoiceCommand.setText("🎤 Voice Command");
            isListening = false;
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String command = matches.get(0).toLowerCase();
                processVoiceCommand(command);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            // Partial recognition results
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // Other events
        }
    }

    private void processVoiceCommand(String command) {
        Log.d(TAG, "Processing voice command: " + command);

        // Display the recognized command to the user
        Toast.makeText(this, "Command: " + command, Toast.LENGTH_SHORT).show();

        // Try direct match first
        if (voiceCommandMap.containsKey(command)) {
            String mappedCommand = voiceCommandMap.get(command);

            // Handle speed commands locally
            if (mappedCommand.equals("SPEED_UP")) {
                increaseSpeed();
                return;
            } else if (mappedCommand.equals("SPEED_DOWN")) {
                decreaseSpeed();
                return;
            } else if (mappedCommand.equals("SPEED_LOW")) {
                setSpeed(1);
                return;
            } else if (mappedCommand.equals("SPEED_HIGH")) {
                setSpeed(3);
                return;
            }

            sendCommand(mappedCommand);
            return;
        }

        // If no direct match, try to find a partial match
        for (Map.Entry<String, String> entry : voiceCommandMap.entrySet()) {
            if (command.contains(entry.getKey())) {
                String mappedCommand = entry.getValue();

                // Handle speed commands locally
                if (mappedCommand.equals("SPEED_UP")) {
                    increaseSpeed();
                    return;
                } else if (mappedCommand.equals("SPEED_DOWN")) {
                    decreaseSpeed();
                    return;
                } else if (mappedCommand.equals("SPEED_LOW")) {
                    setSpeed(1);
                    return;
                } else if (mappedCommand.equals("SPEED_HIGH")) {
                    setSpeed(3);
                    return;
                }

                sendCommand(mappedCommand);
                return;
            }
        }

        // If no match found
        Toast.makeText(this, "Command not recognized: " + command, Toast.LENGTH_SHORT).show();
    }

    private void showAvailableDevices() {
        if (bluetoothAdapter == null) {
            showToast("Bluetooth not supported on this device.");
            return;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                showToast("Please enable Bluetooth first.");
                return;
            }

            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices.isEmpty()) {
                showToast("No paired devices found. Pair your device first.");
                return;
            }

            ArrayList<String> deviceNames = new ArrayList<>();
            ArrayList<String> deviceAddresses = new ArrayList<>();

            for (BluetoothDevice device : pairedDevices) {
                deviceNames.add(device.getName() + "\n" + device.getAddress());
                deviceAddresses.add(device.getAddress());
            }

            // Show a dialog to let the user select a device
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select a Device to Connect");
            builder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            selectedDeviceAddress = deviceAddresses.get(which);
                            connectToSelectedDevice();
                        }
                    });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.show();

        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission denied: " + e.getMessage());
            showToast("Bluetooth permission denied");
            requestBluetoothPermissions();
        }
    }

    private void connectToSelectedDevice() {
        if (selectedDeviceAddress == null) {
            showToast("No device selected.");
            return;
        }

        boolean isConnected = bluetoothHelper.connectToDevice(selectedDeviceAddress);
        if (isConnected) {
            showToast("Attempting to connect...");
        } else {
            showToast("Failed to initiate connection.");
        }
    }

    private void setupControlButtons() {
        // Climbing controls
        findViewById(R.id.btnClimbForward).setOnClickListener(v -> sendCommand("FORWARD*"));
        findViewById(R.id.btnClimbReverse).setOnClickListener(v -> sendCommand("REVERSE*"));
        findViewById(R.id.btnClimbStop).setOnClickListener(v -> sendCommand("STOP*"));

        // Speed controls
        btnSpeedUp.setOnClickListener(v -> increaseSpeed());
        btnSpeedDown.setOnClickListener(v -> decreaseSpeed());

        // Harvesting button - navigate to harvesting activity
        btnHarvesting.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HarvestingArm.class);
            // Pass bluetooth helper reference if needed
            startActivity(intent);
        });
    }

    private void increaseSpeed() {
        if (currentSpeed < 3) {
            currentSpeed++;
            updateSpeedDisplay();
            sendSpeedCommand();
        }
    }

    private void decreaseSpeed() {
        if (currentSpeed > 1) {
            currentSpeed--;
            updateSpeedDisplay();
            sendSpeedCommand();
        }
    }

    private void setSpeed(int speed) {
        if (speed >= 1 && speed <= 3) {
            currentSpeed = speed;
            updateSpeedDisplay();
            sendSpeedCommand();
        }
    }

    private void updateSpeedDisplay() {
        tvSpeedStatus.setText("Speed: " + speedLabels[currentSpeed - 1]);
    }

    private void sendSpeedCommand() {
        String speedCommand = "SPEED_" + currentSpeed + "*";
        sendCommand(speedCommand);
    }

    private void sendCommand(String command) {
        if (bluetoothHelper != null && bluetoothHelper.isConnected()) {
            try {
                bluetoothHelper.sendCommand(command);
                Log.d(TAG, "Command sent: " + command);
            } catch (Exception e) {
                Log.e(TAG, "Error sending command: " + e.getMessage());
                showToast("Error sending command");
            }
        } else {
            showToast("Not connected to device");
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceRecognition();
            } else {
                Toast.makeText(this, "Voice command requires microphone permission", Toast.LENGTH_SHORT).show();
            }
        }
        else if (requestCode == PERMISSION_REQUEST_BLUETOOTH) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showAvailableDevices();
            } else {
                Toast.makeText(this, "Bluetooth features require Bluetooth permissions", Toast.LENGTH_SHORT).show();
                tvBluetoothStatus.setText("Bluetooth: Permission denied");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechRecognizer != null && isListening) {
            speechRecognizer.stopListening();
            isListening = false;
            btnVoiceCommand.setText("🎤 Voice Command");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothHelper != null) {
            bluetoothHelper.disconnect();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }
}