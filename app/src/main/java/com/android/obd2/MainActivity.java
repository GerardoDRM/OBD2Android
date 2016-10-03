package com.android.obd2;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;

import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.commands.temperature.AmbientAirTemperatureCommand;
import com.github.pires.obd.enums.ObdProtocols;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    DatabaseReference myRef;
    BluetoothAdapter mBluetoothAdapter;
    private static final int REQUEST_CODE_LOCATION = 2;
    private static final int REQUEST_ENABLE_BT = 1;
    private String selectedDevice;
    private ArrayList<String> devicesName = new ArrayList<>();
    private ArrayList<BluetoothDevice> devices = new ArrayList<>();

    // Create a BroadcastReceiver for ACTION_FOUND
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // Add the name and address to an array adapter to show in a ListView
                devicesName.add(device.getName() + "\n" + device.getAddress());
                devices.add(device);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Write a message to the database
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        myRef = database.getReference("coord");

        // Acquire a reference to the system Location Manager
        final LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // Define a listener that responds to location updates
        final LocationListener locationListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                // Called when a new location is found by the network location provider.
               makeUseOfNewLocation(location);
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            public void onProviderEnabled(String provider) {
            }

            public void onProviderDisabled(String provider) {
            }
        };

        // Android M permissions
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Display UI and wait for user interaction
                Log.d("Permission", "Needs permission");
            } else {
                // Request missing location permission.
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_CODE_LOCATION);
            }

        } else {
            // Location permission has been granted, continue as usual.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
        }


        // Check compatibility
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Turn on bluetooth
            Log.d("Bluetooth", "No Bluetooth on this device");
        }

        // Enable Bluetooth
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }



    }

    private void makeUseOfNewLocation(final Location location) {
        myRef.child("lon").setValue(location.getLongitude());
        myRef.child("lat").setValue(location.getLatitude());
    }

    public void connect(View view) {
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0) {
            // Loop through paired devices
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                devicesName.add(device.getName() + "\n" + device.getAddress());
                devices.add(device);
            }
            checkDevicesList();
        } else {
            // Register the BroadcastReceiver
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter); // Don't forget to unregister during onDestroy
        }

    }


    private void checkDevicesList () {

        ArrayAdapter adapter = new ArrayAdapter(this, android.R.layout.select_dialog_singlechoice,
                devicesName.toArray(new String[devicesName.size()]));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setSingleChoiceItems(adapter, -1, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
                int position = ((AlertDialog) dialogInterface).getListView().getCheckedItemPosition();
                selectedDevice = devices.get(position).getAddress();
                new ConnectThread().run();

            }
        });
        // Create the AlertDialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread() {
            // Use a temporary object that is later assigned to mmSocket,
            // because mmSocket is final
            BluetoothSocket tmp = null;
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            mmDevice = btAdapter.getRemoteDevice(selectedDevice);
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try {
                // MY_UUID is the app's UUID string, also used by the server code
                tmp = mmDevice.createRfcommSocketToServiceRecord(uuid);
            } catch (IOException e) { }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it will slow down the connection
            mBluetoothAdapter.cancelDiscovery();

            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception
                mmSocket.connect();
                new ConnectedThread(mmSocket).run();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and get out
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    return;
                }
            }
        }

        /** Will cancel an in-progress connection, and close the socket */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;

        private ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
        }

        public void run() {
            try {
                new EchoOffCommand().run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                new LineFeedOffCommand().run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                new TimeoutCommand(125).run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                new SelectProtocolCommand(ObdProtocols.AUTO).run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                new AmbientAirTemperatureCommand().run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                RPMCommand engineRpmCommand = new RPMCommand();
                engineRpmCommand.run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                Log.d("RPM", "RPM: " + engineRpmCommand.getFormattedResult());
                SpeedCommand speedCommand = new SpeedCommand();
                speedCommand.run(mmSocket.getInputStream(), mmSocket.getOutputStream());
                Log.d("Speed", "SPEED: " + speedCommand.getFormattedResult());

            }
            catch (Exception e) {
                // handle errors
            }
        }

    }

}
