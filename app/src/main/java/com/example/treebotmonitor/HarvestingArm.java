package com.example.treebotmonitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

public class HarvestingArm extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "HarvestingArm";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_CAMERA_PERMISSION = 2;

    // Bluetooth components - now using singleton helper
    private HarvestingBluetoothHelper bluetoothHelper;
    private BluetoothAdapter btAdapter;
    private ArrayList<BluetoothDevice> deviceList;
    private ArrayAdapter<String> deviceAdapter;
    private Handler bluetoothHandler;

    // UI components
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private Button btnBluetoothConnect, btnManualHarvest, btnAutoHarvest;
    private LinearLayout manualControlsLayout;
    private TextView tvConnectionStatus, tvRobotStatus;
    private ProgressBar progressBarConnection;

    // Manual control components
    private SeekBar seekBase, seekShoulder, seekElbow, seekWristPitch, seekWristRoll, seekGripper;
    private TextView tvBase, tvShoulder, tvElbow, tvWristPitch, tvWristRoll, tvGripper;

    // Auto harvest components
    private Handler autoHarvestHandler;
    private Handler mainHandler;
    private boolean isAutoHarvesting = false;
    private int autoHarvestStep = 0;

    // Connection monitoring
    private Handler connectionHandler;
    private final long CONNECTION_CHECK_INTERVAL = 5000; // 5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_harvesting_arm);

        initializeComponents();
        setupBluetoothAdapter();
        setupCameraView();
        setupClickListeners();
        checkPermissions();
        startConnectionMonitoring();
    }

    private void initializeComponents() {
        // Main UI components
        surfaceView = findViewById(R.id.surfaceView);
        btnBluetoothConnect = findViewById(R.id.btnBluetoothConnect);
        btnManualHarvest = findViewById(R.id.btnManualHarvest);
        btnAutoHarvest = findViewById(R.id.btnAutoHarvest);
        manualControlsLayout = findViewById(R.id.manualControlsLayout);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvRobotStatus = findViewById(R.id.tvRobotStatus);
        progressBarConnection = findViewById(R.id.progressBarConnection);

        // Manual control components
        initializeManualControls();

        // Initially hide manual controls and progress bar
        manualControlsLayout.setVisibility(View.GONE);
        progressBarConnection.setVisibility(View.GONE);

        // Initialize handlers
        autoHarvestHandler = new Handler(Looper.getMainLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        connectionHandler = new Handler(Looper.getMainLooper());

        // Initialize Bluetooth handler for receiving messages
        bluetoothHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.obj != null) {
                    String receivedMessage = msg.obj.toString();
                    handleBluetoothMessage(receivedMessage);
                }
            }
        };

        // Initialize device list for Bluetooth
        deviceList = new ArrayList<>();

        // Set initial status
        updateConnectionStatus("Not Connected", false);
        updateRobotStatus("Robot Idle");
    }

    private void initializeManualControls() {
        tvBase = findViewById(R.id.tvBase);
        tvShoulder = findViewById(R.id.tvShoulder);
        tvElbow = findViewById(R.id.tvElbow);
        tvWristPitch = findViewById(R.id.tvWristPitch);
        tvWristRoll = findViewById(R.id.tvWristRoll);
        tvGripper = findViewById(R.id.tvGripper);

        seekBase = findViewById(R.id.seekBase);
        seekShoulder = findViewById(R.id.seekShoulder);
        seekElbow = findViewById(R.id.seekElbow);
        seekWristPitch = findViewById(R.id.seekWristPitch);
        seekWristRoll = findViewById(R.id.seekWristRoll);
        seekGripper = findViewById(R.id.seekGripper);

        // Set up SeekBar listeners with delay to prevent spam
        setSeekBarListener(seekBase, tvBase, "A");
        setSeekBarListener(seekShoulder, tvShoulder, "B");
        setSeekBarListener(seekElbow, tvElbow, "C");
        setSeekBarListener(seekWristPitch, tvWristPitch, "D");
        setSeekBarListener(seekWristRoll, tvWristRoll, "E");
        setSeekBarListener(seekGripper, tvGripper, "F");
    }

    private void setupBluetoothAdapter() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            showError("Bluetooth not supported on this device");
            btnBluetoothConnect.setEnabled(false);
            return;
        }

        // Initialize Bluetooth helper singleton
        bluetoothHelper = HarvestingBluetoothHelper.getInstance(this, bluetoothHandler);
    }

    private void setupCameraView() {
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    private void setupClickListeners() {
        btnBluetoothConnect.setOnClickListener(v -> {
            if (bluetoothHelper != null && bluetoothHelper.isConnected()) {
                disconnectBluetooth();
            } else {
                // Check permissions before attempting to show device list
                if (!hasBluetoothPermissions()) {
                    showError("Bluetooth permissions required");
                    checkAndRequestBluetoothPermissions();
                    return;
                }
                showBluetoothDeviceList();
            }
        });

        btnManualHarvest.setOnClickListener(v -> {
            if (bluetoothHelper == null || !bluetoothHelper.isConnected()) {
                showError("Please connect to Bluetooth device first");
                return;
            }

            if (manualControlsLayout.getVisibility() == View.VISIBLE) {
                // Hide manual controls with animation
                Animation slideUp = AnimationUtils.loadAnimation(this, android.R.anim.slide_out_right);
                manualControlsLayout.startAnimation(slideUp);
                manualControlsLayout.setVisibility(View.GONE);
                btnManualHarvest.setText("Manual Harvest");
                updateRobotStatus("Robot Idle");
            } else {
                // Show manual controls with animation
                Animation slideDown = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left);
                manualControlsLayout.startAnimation(slideDown);
                manualControlsLayout.setVisibility(View.VISIBLE);
                btnManualHarvest.setText("Hide Manual Controls");
                updateRobotStatus("Manual Control Mode");
                // Stop auto harvest if running
                if (isAutoHarvesting) {
                    stopAutoHarvest();
                }
            }
        });

        btnAutoHarvest.setOnClickListener(v -> {
            if (bluetoothHelper == null || !bluetoothHelper.isConnected()) {
                showError("Please connect to Bluetooth device first");
                return;
            }

            if (isAutoHarvesting) {
                stopAutoHarvest();
            } else {
                startAutoHarvest();
            }
        });
    }

    private void startConnectionMonitoring() {
        connectionHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bluetoothHelper != null && bluetoothHelper.isConnected()) {
                    // Send ping command to check connection
                    bluetoothHelper.sendCommand("PING");
                    updateConnectionStatus("Connected to: " + bluetoothHelper.getConnectedDeviceName(), true);
                } else {
                    updateConnectionStatus("Not Connected", false);
                }
                connectionHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
            }
        }, CONNECTION_CHECK_INTERVAL);
    }

    private void updateConnectionStatus(String status, boolean connected) {
        tvConnectionStatus.setText(status);

        if (connected) {
            tvConnectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            tvConnectionStatus.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.presence_online, 0, 0, 0);
            btnBluetoothConnect.setText("Disconnect");
            btnBluetoothConnect.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
            btnManualHarvest.setEnabled(true);
            btnAutoHarvest.setEnabled(true);
        } else {
            tvConnectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvConnectionStatus.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.presence_offline, 0, 0, 0);
            btnBluetoothConnect.setText("Connect Bluetooth");
            btnBluetoothConnect.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_dark));
            btnManualHarvest.setEnabled(false);
            btnAutoHarvest.setEnabled(false);

            // Hide manual controls if shown
            if (manualControlsLayout.getVisibility() == View.VISIBLE) {
                manualControlsLayout.setVisibility(View.GONE);
                btnManualHarvest.setText("Manual Harvest");
            }

            // Stop auto harvest if running
            if (isAutoHarvesting) {
                stopAutoHarvest();
            }
        }
    }

    private void updateRobotStatus(String status) {
        tvRobotStatus.setText(status);
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.e(TAG, message);
    }

    private void showSuccess(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Log.i(TAG, message);
    }

    private void checkPermissions() {
        // Check camera permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }

        // Check Bluetooth permissions
        checkAndRequestBluetoothPermissions();
    }

    private void checkAndRequestBluetoothPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        // For Android 12+ (API 31+), we need different permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            // For older versions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        // Location permission is still needed for device discovery
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    /**
     * Check if all required Bluetooth permissions are granted
     */
    private boolean hasBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                            == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                            == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void showBluetoothDeviceList() {
        if (btAdapter == null) {
            showError("Bluetooth not supported");
            return;
        }

        if (!btAdapter.isEnabled()) {
            showError("Please enable Bluetooth first");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            showError("Bluetooth permission required");
            return;
        }

        // Show progress
        progressBarConnection.setVisibility(View.VISIBLE);

        try {
            // Get paired devices with proper permission handling
            Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
            deviceList.clear();
            ArrayList<String> deviceNames = new ArrayList<>();

            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    deviceList.add(device);

                    // Get device name safely with permission check
                    String deviceName = "Unknown Device";
                    String deviceAddress = device.getAddress();

                    try {
                        String name = device.getName();
                        if (name != null && !name.isEmpty()) {
                            deviceName = name;
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "Permission denied when getting device name for " + deviceAddress, e);
                        deviceName = "Bluetooth Device";
                    }

                    deviceNames.add(deviceName + "\n" + deviceAddress);
                }
            } else {
                deviceNames.add("No paired devices found");
            }

            progressBarConnection.setVisibility(View.GONE);

            // Create dialog to show available devices
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select Bluetooth Device");
            builder.setIcon(android.R.drawable.stat_sys_data_bluetooth);

            ListView listView = new ListView(this);
            deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
            listView.setAdapter(deviceAdapter);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                if (position < deviceList.size()) {
                    BluetoothDevice selectedDevice = deviceList.get(position);
                    connectToDevice(selectedDevice);
                }
            });

            builder.setView(listView);
            builder.setNegativeButton("Cancel", (dialog, which) -> {
                progressBarConnection.setVisibility(View.GONE);
            });
            AlertDialog dialog = builder.create();
            dialog.show();

        } catch (SecurityException e) {
            progressBarConnection.setVisibility(View.GONE);
            Log.e(TAG, "Security exception when accessing paired devices", e);
            showError("Bluetooth permission denied");
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        progressBarConnection.setVisibility(View.VISIBLE);
        updateConnectionStatus("Connecting...", false);

        // Check Bluetooth permissions before proceeding
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            progressBarConnection.setVisibility(View.GONE);
            showError("Bluetooth permission required");
            updateConnectionStatus("Permission denied", false);
            return;
        }

        // Use the singleton Bluetooth helper to connect
        if (bluetoothHelper != null) {
            boolean success = bluetoothHelper.connectToDevice(device.getAddress());
            if (success) {
                // Get device name safely with permission check
                String deviceName = "Unknown Device";
                try {
                    String name = device.getName();
                    if (name != null && !name.isEmpty()) {
                        deviceName = name;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Permission denied when getting device name", e);
                    deviceName = "Bluetooth Device";
                }

                // Connection attempt started successfully
                showSuccess("Connecting to " + deviceName);

                // Check connection status after a delay
                mainHandler.postDelayed(() -> {
                    progressBarConnection.setVisibility(View.GONE);
                    if (bluetoothHelper.isConnected()) {
                        updateConnectionStatus("Connected to: " + bluetoothHelper.getConnectedDeviceName(), true);
                        showSuccess("Connected successfully!");
                    } else {
                        updateConnectionStatus("Connection timeout", false);
                        showError("Connection timeout - please try again");
                    }
                }, 3000);
            } else {
                progressBarConnection.setVisibility(View.GONE);
                updateConnectionStatus("Connection failed", false);
                showError("Failed to start connection");
            }
        } else {
            progressBarConnection.setVisibility(View.GONE);
            showError("Bluetooth helper not initialized");
        }
    }

    private void handleBluetoothMessage(String message) {
        Log.d(TAG, "Received: " + message);

        // Handle different types of responses
        if (message.contains("Connected to")) {
            // Connection status update
            updateConnectionStatus(message, true);
            showSuccess("Connection established");
        } else if (message.contains("Disconnected") || message.contains("Connection lost")) {
            // Disconnection
            updateConnectionStatus("Connection lost", false);
            showError("Connection lost");
        } else if (message.equals("OK")) {
            // Command acknowledged
        } else if (message.equals("PONG")) {
            // Ping response - connection is alive
        } else if (message.startsWith("STATUS:")) {
            // Robot status update
            updateRobotStatus(message.substring(7));
        } else if (message.startsWith("ERROR:")) {
            // Error from robot
            showError("Robot Error: " + message.substring(6));
        } else if (message.contains("Ready for commands")) {
            updateRobotStatus("Robot Ready");
        }
    }

    private void disconnectBluetooth() {
        if (bluetoothHelper != null) {
            bluetoothHelper.disconnect();
        }

        // Stop auto harvest
        stopAutoHarvest();

        updateConnectionStatus("Disconnected", false);
        updateRobotStatus("Robot Idle");
        showSuccess("Disconnected successfully");
    }

    private void setSeekBarListener(SeekBar seekBar, TextView textView, String commandPrefix) {
        final Handler seekBarHandler = new Handler();
        final Runnable[] pendingCommand = {null};

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    textView.setText(commandPrefix + ": " + progress + "°");

                    // Cancel pending command
                    if (pendingCommand[0] != null) {
                        seekBarHandler.removeCallbacks(pendingCommand[0]);
                    }

                    // Schedule new command with delay to prevent spam
                    pendingCommand[0] = () -> {
                        sendCommand(commandPrefix + progress);
                    };
                    seekBarHandler.postDelayed(pendingCommand[0], 100); // 100ms delay
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // Send final command immediately when user stops dragging
                if (pendingCommand[0] != null) {
                    seekBarHandler.removeCallbacks(pendingCommand[0]);
                }
                sendCommand(commandPrefix + sb.getProgress());
            }
        });
    }

    private void sendCommand(String command) {
        if (bluetoothHelper != null && bluetoothHelper.isConnected()) {
            bluetoothHelper.sendCommand(command);
            Log.d(TAG, "Sent command: " + command);
        } else {
            showError("Not connected to device");
        }
    }

    private void startAutoHarvest() {
        if (bluetoothHelper == null || !bluetoothHelper.isConnected()) {
            showError("Please connect to Bluetooth device first");
            return;
        }

        isAutoHarvesting = true;
        autoHarvestStep = 0;
        btnAutoHarvest.setText("Stop Auto Harvest");
        btnAutoHarvest.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));

        // Hide manual controls during auto harvest
        if (manualControlsLayout.getVisibility() == View.VISIBLE) {
            manualControlsLayout.setVisibility(View.GONE);
            btnManualHarvest.setText("Manual Harvest");
        }

        updateRobotStatus("Auto Harvest Mode - Starting");
        showSuccess("Auto harvest started");

        // Start the auto harvest sequence
        performAutoHarvestSequence();
    }

    private void stopAutoHarvest() {
        isAutoHarvesting = false;
        btnAutoHarvest.setText("Auto Harvest");
        btnAutoHarvest.setBackgroundColor(getResources().getColor(android.R.color.holo_orange_dark));

        // Remove any pending auto harvest callbacks
        autoHarvestHandler.removeCallbacksAndMessages(null);

        updateRobotStatus("Auto Harvest Stopped");
        showSuccess("Auto harvest stopped");
    }

    private void performAutoHarvestSequence() {
        if (!isAutoHarvesting || bluetoothHelper == null || !bluetoothHelper.isConnected()) return;

        switch (autoHarvestStep) {
            case 0:
                updateRobotStatus("Auto Harvest - Search Position");
                sendCommand("A90");   // Base center
                sendCommand("B45");   // Shoulder up
                sendCommand("C90");   // Elbow neutral
                sendCommand("D90");   // Wrist pitch neutral
                sendCommand("E90");   // Wrist roll neutral
                sendCommand("F30");   // Gripper open
                autoHarvestStep++;
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 3000);
                break;

            case 1:
                updateRobotStatus("Auto Harvest - Approaching Target");
                sendCommand("A120");  // Turn base
                sendCommand("B90");   // Lower shoulder
                sendCommand("C120");  // Extend elbow
                sendCommand("D60");   // Adjust wrist
                autoHarvestStep++;
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 3000);
                break;

            case 2:
                updateRobotStatus("Auto Harvest - Gripping");
                sendCommand("F150");  // Close gripper
                autoHarvestStep++;
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 2000);
                break;

            case 3:
                updateRobotStatus("Auto Harvest - Returning Home");
                sendCommand("A90");   // Base center
                sendCommand("B90");   // Shoulder neutral
                sendCommand("C90");   // Elbow neutral
                sendCommand("D90");   // Wrist neutral
                sendCommand("E90");   // Wrist roll neutral
                autoHarvestStep++;
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 3000);
                break;

            case 4:
                updateRobotStatus("Auto Harvest - Releasing");
                sendCommand("F30");   // Open gripper
                autoHarvestStep++;
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 2000);
                break;

            case 5:
                updateRobotStatus("Auto Harvest - Cycle Complete");
                autoHarvestStep = 0; // Reset to start again
                autoHarvestHandler.postDelayed(this::performAutoHarvestSequence, 3000);
                break;
        }
    }

    // Camera surface callbacks
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                camera = Camera.open();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error starting camera preview", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (IOException e) {
                Log.e(TAG, "Error changing camera surface", e);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (surfaceHolder != null) {
                    surfaceCreated(surfaceHolder);
                }
            } else {
                showError("Camera permission required for video feed");
            }
        }

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (deniedPermissions.length() > 0) {
                        deniedPermissions.append(", ");
                    }
                    deniedPermissions.append(permissions[i]);
                }
            }

            if (allGranted) {
                showSuccess("Bluetooth permissions granted");
                // Reinitialize Bluetooth helper with permissions granted
                if (bluetoothHelper == null && btAdapter != null) {
                    bluetoothHelper = HarvestingBluetoothHelper.getInstance(this, bluetoothHandler);
                }
            } else {
                showError("Required permissions denied: " + deniedPermissions.toString());
                Log.w(TAG, "Bluetooth permissions denied: " + deniedPermissions.toString());

                // Show explanation dialog if user denied permissions
                new AlertDialog.Builder(this)
                        .setTitle("Permissions Required")
                        .setMessage("Bluetooth permissions are required for robot control. Please grant permissions in Settings.")
                        .setPositiveButton("Settings", (dialog, which) -> {
                            // Open app settings
                            android.content.Intent intent = new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            android.net.Uri uri = android.net.Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update the Bluetooth helper's handler when resuming
        if (bluetoothHelper != null) {
            bluetoothHelper.updateHandler(bluetoothHandler);
        }

        // Resume camera if permission is granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED && camera == null && surfaceHolder != null) {
            surfaceCreated(surfaceHolder);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // DO NOT stop auto harvest or disconnect when app goes to background
        // The connection will be maintained by the singleton
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop all handlers
        connectionHandler.removeCallbacksAndMessages(null);
        stopAutoHarvest();

        // Close camera
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }

        // DO NOT disconnect Bluetooth here - let the singleton manage it
        // Only disconnect if user explicitly chooses to, or app is completely destroyed
    }
}