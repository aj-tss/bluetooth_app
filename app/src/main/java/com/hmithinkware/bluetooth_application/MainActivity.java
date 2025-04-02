package com.hmithinkware.bluetooth_application;

import android.os.Build;
import android.os.Bundle;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private final String TAG = MainActivity.class.getSimpleName();
    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public final static int MESSAGE_READ = 2;
    private final static int CONNECTING_STATUS = 3;

    // GUI Components
    private TextView mBluetoothStatus;
    private TextView mReadBuffer;
    private Button mScanBtn;
    // private Button mOffBtn;
    private Button mListPairedDevicesBtn;
    private Button mDiscoverBtn;
    private ListView mDevicesListView;
    private CheckBox mLED1;
    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;
    private Handler mHandler;
    private ConnectedThread mConnectedThread;
    private BluetoothSocket mBTSocket = null;

    // Activity result launcher for Bluetooth enable request
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;

    // Activity result launcher for permissions
    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components first
        initializeUI();

        // Initialize result launchers
        setupResultLaunchers();

        // Check and request necessary permissions
        checkAndRequestPermissions();

        // Get Bluetooth adapter using BluetoothManager
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            mBTAdapter = bluetoothManager.getAdapter();
        }

        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        setupMessageHandler();

        if (mBTAdapter == null) {
            // Device does not support Bluetooth
            mBluetoothStatus.setText(getString(R.string.sBTstaNF));
            Toast.makeText(getApplicationContext(), getString(R.string.sBTdevNF), Toast.LENGTH_SHORT).show();
        } else {
            setupButtonListeners();
        }
    }

    private void initializeUI() {
        mBluetoothStatus = findViewById(R.id.bluetooth_status);
        mReadBuffer = findViewById(R.id.read_buffer);
        mScanBtn = findViewById(R.id.scan);
        //mOffBtn = findViewById(R.id.off);
        mDiscoverBtn = findViewById(R.id.discover);
        mListPairedDevicesBtn = findViewById(R.id.paired_btn);
        mLED1 = findViewById(R.id.checkbox_led_1);
        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mDevicesListView = findViewById(R.id.devices_list_view);
    }

    private void setupResultLaunchers() {
        // For enabling Bluetooth
        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        mBluetoothStatus.setText(getString(R.string.sEnabled));
                    } else {
                        mBluetoothStatus.setText(getString(R.string.sDisabled));
                    }
                }
        );

        // For requesting permissions
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean isGranted : permissions.values()) {
                        if (!isGranted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (allGranted) {
                        Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
                        // Refresh UI or functionality that depends on these permissions
                        if (mBTAdapter != null && mBTAdapter.isEnabled()) {
                            listPairedDevices();
                        }
                    } else {
                        Toast.makeText(this, "Bluetooth functionality will be limited without required permissions", Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void setupMessageHandler() {
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.what == MESSAGE_READ) {
                    String readMessage = new String((byte[]) msg.obj, StandardCharsets.UTF_8);
                    mReadBuffer.setText(readMessage);
                }

                if (msg.what == CONNECTING_STATUS) {
                    if (msg.arg1 == 1)
                        mBluetoothStatus.setText(getString(R.string.BTConnected) + msg.obj);
                    else
                        mBluetoothStatus.setText(getString(R.string.BTconnFail));
                }
            }
        };
    }

    private void setupButtonListeners() {
        mLED1.setOnClickListener(v -> {
            if (mConnectedThread != null) //First check to make sure thread created
                mConnectedThread.write("1");
        });

        mScanBtn.setOnClickListener(v -> bluetoothOn());
        //mOffBtn.setOnClickListener(v -> bluetoothOff());
        mListPairedDevicesBtn.setOnClickListener(v -> listPairedDevices());
        mDiscoverBtn.setOnClickListener(v -> discover());
    }

    private void checkAndRequestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31+)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // Location permission is needed for Bluetooth scanning on all Android versions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Request all needed permissions at once
        if (!permissionsToRequest.isEmpty()) {
            String[] permissions = permissionsToRequest.toArray(new String[0]);
            requestPermissionLauncher.launch(permissions);
        }
    }

    private void bluetoothOn() {
        if (mBTAdapter == null) {
            Toast.makeText(getApplicationContext(), getString(R.string.sBTdevNF), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mBTAdapter.isEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
                    return;
                }
            }
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
            mBluetoothStatus.setText(getString(R.string.BTEnable));
            Toast.makeText(getApplicationContext(), getString(R.string.sBTturON), Toast.LENGTH_SHORT).show();
        } else {
            mBTAdapter.disable(); // turn off
            mBluetoothStatus.setText(getString(R.string.sBTdisabl));
            Toast.makeText(getApplicationContext(), "Bluetooth turned Off", Toast.LENGTH_SHORT).show();
        }
    }

    /*
    private void bluetoothOff() {
        if (mBTAdapter == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
                return;
            }
        }
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText(getString(R.string.sBTdisabl));
        Toast.makeText(getApplicationContext(), "Bluetooth turned Off", Toast.LENGTH_SHORT).show();

    }
    */
    private void discover() {
        if (mBTAdapter == null) {
            return;
        }

        // Check if we have required permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION
                });
                return;
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }

        // Check if the device is already discovering
        if (mBTAdapter.isDiscovering()) {
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(), getString(R.string.DisStop), Toast.LENGTH_SHORT).show();

            // Unregister the receiver to avoid duplicate registrations
            try {
                unregisterReceiver(blReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered
            }
        } else {
            if (mBTAdapter.isEnabled()) {
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), getString(R.string.DisStart), Toast.LENGTH_SHORT).show();

                // Register for broadcasts when a device is discovered
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                int receiverFlag = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    receiverFlag = Context.RECEIVER_EXPORTED;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    registerReceiver(blReceiver, filter, receiverFlag);
                }
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
            }
        }
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }

                // Add the name to the list
                if (device != null) {
                    String deviceName = "Unknown";
                    String deviceAddress = device.getAddress();

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            deviceName = device.getName() != null ? device.getName() : "Unknown";
                        }
                    } else {
                        deviceName = device.getName() != null ? device.getName() : "Unknown";
                    }

                    mBTArrayAdapter.add(deviceName + "\n" + deviceAddress);
                    mBTArrayAdapter.notifyDataSetChanged();
                }
            }
        }
    };

    private void listPairedDevices() {
        if (mBTAdapter == null) {
            return;
        }

        mBTArrayAdapter.clear();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
                return;
            }
        }

        if (mBTAdapter.isEnabled()) {
            mPairedDevices = mBTAdapter.getBondedDevices();

            if (mPairedDevices.size() > 0) {
                // Add paired devices to the adapter
                for (BluetoothDevice device : mPairedDevices) {
                    mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                }

                Toast.makeText(getApplicationContext(), getString(R.string.show_paired_devices), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getApplicationContext(), "No paired devices found", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
        }
    }

    private final AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (mBTAdapter == null || !mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText(getString(R.string.cConnet));
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0, info.length() - 17);

            // Spawn a new thread to avoid blocking the GUI one
            new Thread() {
                @Override
                public void run() {
                    boolean fail = false;

                    BluetoothDevice device = mBTAdapter.getRemoteDevice(address);

                    try {
                        mBTSocket = createBluetoothSocket(device);
                    } catch (IOException e) {
                        fail = true;
                        runOnUiThread(() -> Toast.makeText(getBaseContext(), "Socket creation failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }

                    // Establish the Bluetooth socket connection.
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                runOnUiThread(() -> {
                                    requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
                                    Toast.makeText(getBaseContext(), "Bluetooth connect permission required", Toast.LENGTH_SHORT).show();
                                });
                                return;
                            }
                        }

                        mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            mHandler.obtainMessage(CONNECTING_STATUS, -1, -1).sendToTarget();
                            runOnUiThread(() -> Toast.makeText(getBaseContext(), "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        } catch (IOException e2) {
                            runOnUiThread(() -> Toast.makeText(getBaseContext(), "Socket close failed: " + e2.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }

                    if (!fail) {
                        mConnectedThread = new ConnectedThread(mBTSocket);
                        mConnectedThread.start();
                        mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name).sendToTarget();
                    }
                }
            }.start();
        }
    };

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("Bluetooth connect permission not granted");
            }
        }

        // Use the standard method instead of reflection
        return device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
    }

    // Thread class for handling Bluetooth communication
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error creating I/O streams", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = mmInStream.read(buffer);
                    if (bytes > 0) {
                        mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Connection lost", e);
                    break;
                }
            }
        }

        public void write(String input) {
            byte[] msgBuffer = input.getBytes();
            try {
                mmOutStream.write(msgBuffer);
            } catch (IOException e) {
                Log.e(TAG, "Error sending data", e);
                runOnUiThread(() -> Toast.makeText(getBaseContext(), "Connection error - failed to send data", Toast.LENGTH_SHORT).show());
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Make sure we unregister the receiver to avoid memory leaks
        try {
            unregisterReceiver(blReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }

        // Cancel discovery if it's in progress
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        if (mBTAdapter != null && mBTAdapter.isDiscovering()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    mBTAdapter.cancelDiscovery();
                }
            } else {
                mBTAdapter.cancelDiscovery();
            }
        }

        // Close connection if still open
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mBTSocket != null) {
            try {
                mBTSocket.close();
                mBTSocket = null;
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
    }
}