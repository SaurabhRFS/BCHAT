package com.example.bchat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button btnConnect;
    private Button btnSos;

    private ConnectionsClient connectionsClient;
    private FusedLocationProviderClient fusedLocationClient;

    private static final String SERVICE_ID = "com.example.bchat.mesh";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private final String myEndpointName = "Node_" + (int)(Math.random() * 10000);
    private static final String TAG = "BCHAT_MESH";

    // Keep track of who we are connected to so we can send them data
    private final List<String> connectedEndpoints = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        btnConnect = findViewById(R.id.btnConnect);
        btnSos = findViewById(R.id.btnSos);

        connectionsClient = Nearby.getConnectionsClient(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        btnConnect.setOnClickListener(v -> startMeshNetwork());
        btnSos.setOnClickListener(v -> fetchLocationAndPreparePayload());

        requestPermissions();
    }

    // --- MESH NETWORK LOGIC ---
    private void startMeshNetwork() {
        statusText.setText("Flushing old sockets...");

        connectionsClient.stopAllEndpoints();
        connectedEndpoints.clear();

        // Give the physical hardware 1.5 seconds to fully close
        // the old connections before starting new ones.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            statusText.setText("Initializing Mesh Engine...");
            startAdvertising();
            startDiscovery();
        }, 1500);
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        connectionsClient.startAdvertising(myEndpointName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                .addOnFailureListener(e -> Log.e(TAG, "Advertising failed: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
                .addOnFailureListener(e -> Log.e(TAG, "Discovery failed: " + e.getMessage()));
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            statusText.setText("Found: " + info.getEndpointName());
            connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback);
        }
        @Override
        public void onEndpointLost(@NonNull String endpointId) {}
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connectedEndpoints.add(endpointId);
                statusText.setText("Connected to Mesh!\nNodes attached: " + connectedEndpoints.size());
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedEndpoints.remove(endpointId);
            statusText.setText("Disconnected. Nodes attached: " + connectedEndpoints.size());
        }
    };

    // --- GPS AND DATA TRANSMISSION LOGIC ---
    @SuppressLint("MissingPermission")
    private void fetchLocationAndPreparePayload() {
        if (connectedEndpoints.isEmpty()) {
            statusText.setText("Error: Connect to mesh first.");
            return;
        }

        statusText.setText("Acquiring GPS...");
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        double lat = location.getLatitude();
                        double lng = location.getLongitude();

                        // Create the string and convert to Byte Array
                        String payloadString = "SOS|" + myEndpointName + "|" + lat + "|" + lng;
                        Payload bytesPayload = Payload.fromBytes(payloadString.getBytes(StandardCharsets.UTF_8));

                        // Send to all connected nodes
                        connectionsClient.sendPayload(connectedEndpoints, bytesPayload);
                        statusText.setText("SOS Sent!\nLat: " + lat + "\nLng: " + lng);
                    } else {
                        statusText.setText("Error: Could not get GPS fix.");
                    }
                });
    }

    // --- RECEIVING DATA LOGIC ---
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] receivedBytes = payload.asBytes();
                if (receivedBytes != null) {
                    String receivedMessage = new String(receivedBytes, StandardCharsets.UTF_8);

                    // Display the received SOS on the screen
                    statusText.setText("RECEIVED URGENT DATA:\n" + receivedMessage);
                }
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    // --- PERMISSIONS LOGIC ---
    private void requestPermissions() {
        List<String> requiredPermissions = new ArrayList<>();
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
        } else {
            statusText.setText("Permissions Granted.");
        }
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    statusText.setText("Permissions Granted.");
                } else {
                    Toast.makeText(this, "Permissions denied. App cannot function.", Toast.LENGTH_LONG).show();
                }
            });
}