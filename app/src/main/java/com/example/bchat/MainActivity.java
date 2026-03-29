package com.example.bchat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
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
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button btnConnect;
    private Button btnSosRed, btnSosOrange, btnSosYellow;

    private ConnectionsClient connectionsClient;
    private FusedLocationProviderClient fusedLocationClient;

    private FirebaseDatabase database;
    private DatabaseReference sosRef;

    private static final String SERVICE_ID = "com.example.bchat.mesh";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private final String myEndpointName = "Node_" + UUID.randomUUID().toString().substring(0, 6);
    private static final String TAG = "BCHAT_MESH";

    private final List<String> connectedEndpoints = new ArrayList<>();

    // Tracks messages to prevent infinite loops
    private final Set<String> processedMessageIds = new HashSet<>();
    private boolean isMeshActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        btnConnect = findViewById(R.id.btnConnect);
        btnSosRed = findViewById(R.id.btnSosRed);
        btnSosOrange = findViewById(R.id.btnSosOrange);
        btnSosYellow = findViewById(R.id.btnSosYellow);

        connectionsClient = Nearby.getConnectionsClient(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        database = FirebaseDatabase.getInstance();
        sosRef = database.getReference("sos_alerts");

        btnConnect.setOnClickListener(v -> toggleMeshNetwork());

        btnSosRed.setOnClickListener(v -> fetchLocationAndPreparePayload("RED"));
        btnSosOrange.setOnClickListener(v -> fetchLocationAndPreparePayload("ORANGE"));
        btnSosYellow.setOnClickListener(v -> fetchLocationAndPreparePayload("YELLOW"));

        requestPermissions();
    }

    private void toggleMeshNetwork() {
        if (isMeshActive) {
            connectionsClient.stopAllEndpoints();
            connectionsClient.stopAdvertising();
            connectionsClient.stopDiscovery();
            connectedEndpoints.clear();
            isMeshActive = false;
            btnConnect.setText("Enable Mesh");
            statusText.setText("Mesh Offline");
        } else {
            isMeshActive = true;
            btnConnect.setText("Disable Mesh");
            statusText.setText("Mesh Engine Active. Scanning...");
            startAdvertising();
            startDiscovery();
        }
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        connectionsClient.startAdvertising(myEndpointName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                .addOnSuccessListener(unused -> Log.d(TAG, "Advertising started"))
                .addOnFailureListener(e -> Log.e(TAG, "Advertising failed: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
                .addOnSuccessListener(unused -> Log.d(TAG, "Discovery started"))
                .addOnFailureListener(e -> Log.e(TAG, "Discovery failed: " + e.getMessage()));
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            // FIX: Tie-breaker to prevent the 6-second collision drop
            if (myEndpointName.compareTo(endpointId) > 0) {
                connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback)
                        .addOnFailureListener(e -> Log.e(TAG, "Request connection failed: " + e.getMessage()));
            }
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
                if (!connectedEndpoints.contains(endpointId)) {
                    connectedEndpoints.add(endpointId);
                }
                updateStatusText();
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedEndpoints.remove(endpointId);
            updateStatusText();
        }
    };

    private void updateStatusText() {
        runOnUiThread(() -> statusText.setText("Connected Nodes: " + connectedEndpoints.size()));
    }

    @SuppressLint("MissingPermission")
    private void fetchLocationAndPreparePayload(String severity) {
        if (connectedEndpoints.isEmpty()) {
            statusText.setText("Error: No nodes connected to forward data.");
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    double lat = (location != null) ? location.getLatitude() : 0.0;
                    double lng = (location != null) ? location.getLongitude() : 0.0;

                    String messageId = UUID.randomUUID().toString();
                    processedMessageIds.add(messageId);

                    String payloadString = messageId + "SOS|" + severity + "|" + myEndpointName + "|" + lat + "|" + lng + "|" + System.currentTimeMillis();

                    // Passing null because we are the origin, we want to broadcast to everyone
                    broadcastPayload(payloadString, null);
                    statusText.setText(severity + " SOS Broadcasted!");
                });
    }

    // FIX: Added excludeEndpointId to prevent sending data back to the node that just sent it to us
    private void broadcastPayload(String payloadString, String excludeEndpointId) {
        Payload bytesPayload = Payload.fromBytes(payloadString.getBytes(StandardCharsets.UTF_8));

        List<String> targetEndpoints = new ArrayList<>();
        for (String endpoint : connectedEndpoints) {
            if (!endpoint.equals(excludeEndpointId)) {
                targetEndpoints.add(endpoint);
            }
        }

        if (!targetEndpoints.isEmpty()) {
            connectionsClient.sendPayload(targetEndpoints, bytesPayload);
        }
    }

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] receivedBytes = payload.asBytes();
                if (receivedBytes != null) {
                    String receivedMessage = new String(receivedBytes, StandardCharsets.UTF_8);
                    // Pass the sender's endpointId so we don't echo it back to them
                    handleIncomingMeshMessage(receivedMessage, endpointId);
                }
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    private void handleIncomingMeshMessage(String message, String senderEndpointId) {
        String[] parts = message.split("\\|");
        if (parts.length < 2) return;

        String messageId = parts[0];

        if (processedMessageIds.contains(messageId)) {
            return; // Drop duplicate
        }

        processedMessageIds.add(messageId);

        // Forward to all connected nodes EXCEPT the sender
        broadcastPayload(message, senderEndpointId);

        runOnUiThread(() -> statusText.setText("RELAYING DATA:\n" + message));
        uploadToFirebase(message);
    }

    private void uploadToFirebase(String message) {
        String pushId = sosRef.push().getKey();
        if (pushId != null) {
            sosRef.child(pushId).setValue(message)
                    .addOnFailureListener(e -> Log.e(TAG, "Cloud upload failed"));
        }
    }

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
            statusText.setText("Ready.");
        }
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (!allGranted) {
                    Toast.makeText(this, "Permissions denied. App cannot function.", Toast.LENGTH_LONG).show();
                }
            });
}















//package com.example.bchat;
//
//import androidx.activity.result.ActivityResultLauncher;
//import androidx.activity.result.contract.ActivityResultContracts;
//import androidx.annotation.NonNull;
//import androidx.appcompat.app.AppCompatActivity;
//import androidx.core.content.ContextCompat;
//
//import android.Manifest;
//import android.annotation.SuppressLint;
//import android.content.pm.PackageManager;
//import android.location.Location;
//import android.os.Build;
//import android.os.Bundle;
//import android.os.Handler;
//import android.os.Looper;
//import android.util.Log;
//import android.widget.Button;
//import android.widget.TextView;
//import android.widget.Toast;
//
//import com.google.android.gms.location.FusedLocationProviderClient;
//import com.google.android.gms.location.LocationServices;
//import com.google.android.gms.location.Priority;
//import com.google.android.gms.nearby.Nearby;
//import com.google.android.gms.nearby.connection.AdvertisingOptions;
//import com.google.android.gms.nearby.connection.ConnectionInfo;
//import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
//import com.google.android.gms.nearby.connection.ConnectionResolution;
//import com.google.android.gms.nearby.connection.ConnectionsClient;
//import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
//import com.google.android.gms.nearby.connection.DiscoveryOptions;
//import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
//import com.google.android.gms.nearby.connection.Payload;
//import com.google.android.gms.nearby.connection.PayloadCallback;
//import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
//import com.google.android.gms.nearby.connection.Strategy;
//import com.google.firebase.database.DatabaseReference;
//import com.google.firebase.database.FirebaseDatabase;
//
//import java.nio.charset.StandardCharsets;
//import java.util.ArrayList;
//import java.util.List;
//
//public class MainActivity extends AppCompatActivity {
//
//    private TextView statusText;
//    private Button btnConnect;
//    private Button btnSosRed, btnSosOrange, btnSosYellow; // Updated
//
//    private ConnectionsClient connectionsClient;
//    private FusedLocationProviderClient fusedLocationClient;
//
//    // Firebase
//    private FirebaseDatabase database;
//    private DatabaseReference sosRef;
//
//    private static final String SERVICE_ID = "com.example.bchat.mesh";
//    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
//    private final String myEndpointName = "Node_" + (int)(Math.random() * 10000);
//    private static final String TAG = "BCHAT_MESH";
//
//    private final List<String> connectedEndpoints = new ArrayList<>();
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
//
//        statusText = findViewById(R.id.statusText);
//        btnConnect = findViewById(R.id.btnConnect);
//        btnSosRed = findViewById(R.id.btnSosRed);
//        btnSosOrange = findViewById(R.id.btnSosOrange);
//        btnSosYellow = findViewById(R.id.btnSosYellow);
//
//        connectionsClient = Nearby.getConnectionsClient(this);
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
//
//        // Initialize Firebase
//        database = FirebaseDatabase.getInstance();
//        sosRef = database.getReference("sos_alerts");
//
//        btnConnect.setOnClickListener(v -> startMeshNetwork());
//
//        // Updated click listeners with severity
//        btnSosRed.setOnClickListener(v -> fetchLocationAndPreparePayload("RED"));
//        btnSosOrange.setOnClickListener(v -> fetchLocationAndPreparePayload("ORANGE"));
//        btnSosYellow.setOnClickListener(v -> fetchLocationAndPreparePayload("YELLOW"));
//
//        requestPermissions();
//    }
//
//    private void startMeshNetwork() {
//        statusText.setText("Flushing old sockets...");
//
//        connectionsClient.stopAllEndpoints();
//        connectedEndpoints.clear();
//
//        new Handler(Looper.getMainLooper()).postDelayed(() -> {
//            statusText.setText("Initializing Mesh Engine...");
//            startAdvertising();
//            startDiscovery();
//        }, 1500);
//    }
//
//    private void startAdvertising() {
//        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
//        connectionsClient.startAdvertising(myEndpointName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
//                .addOnFailureListener(e -> Log.e(TAG, "Advertising failed: " + e.getMessage()));
//    }
//
//    private void startDiscovery() {
//        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
//        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
//                .addOnFailureListener(e -> Log.e(TAG, "Discovery failed: " + e.getMessage()));
//    }
//
//    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
//        @Override
//        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
//            statusText.setText("Found: " + info.getEndpointName());
//            connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback);
//        }
//        @Override
//        public void onEndpointLost(@NonNull String endpointId) {}
//    };
//
//    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
//        @Override
//        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
//            connectionsClient.acceptConnection(endpointId, payloadCallback);
//        }
//
//        @Override
//        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
//            if (result.getStatus().isSuccess()) {
//                connectedEndpoints.add(endpointId);
//                statusText.setText("Connected to Mesh!\nNodes attached: " + connectedEndpoints.size());
//            }
//        }
//
//        @Override
//        public void onDisconnected(@NonNull String endpointId) {
//            connectedEndpoints.remove(endpointId);
//            statusText.setText("Disconnected. Nodes attached: " + connectedEndpoints.size());
//        }
//    };
//
//    @SuppressLint("MissingPermission")
//    private void fetchLocationAndPreparePayload(String severity) { // Updated
//        if (connectedEndpoints.isEmpty()) {
//            statusText.setText("Error: Connect to mesh first.");
//            return;
//        }
//
//        statusText.setText("Acquiring GPS...");
//        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
//                .addOnSuccessListener(this, location -> {
//                    if (location != null) {
//                        double lat = location.getLatitude();
//                        double lng = location.getLongitude();
//
//                        // Updated payload with severity
//                        String payloadString = "SOS|" + severity + "|" + myEndpointName + "|" + lat + "|" + lng + "|" + System.currentTimeMillis();
//                        Payload bytesPayload = Payload.fromBytes(payloadString.getBytes(StandardCharsets.UTF_8));
//
//                        connectionsClient.sendPayload(connectedEndpoints, bytesPayload);
//                        statusText.setText(severity + " SOS Sent!\nLat: " + lat + "\nLng: " + lng);
//                    } else {
//                        statusText.setText("Error: Could not get GPS fix.");
//                    }
//                });
//    }
//
//    private final PayloadCallback payloadCallback = new PayloadCallback() {
//        @Override
//        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
//            if (payload.getType() == Payload.Type.BYTES) {
//                byte[] receivedBytes = payload.asBytes();
//                if (receivedBytes != null) {
//                    String receivedMessage = new String(receivedBytes, StandardCharsets.UTF_8);
//
//                    statusText.setText("RECEIVED URGENT DATA:\n" + receivedMessage);
//
//                    String pushId = sosRef.push().getKey();
//                    if (pushId != null) {
//                        sosRef.child(pushId).setValue(receivedMessage)
//                                .addOnSuccessListener(aVoid -> {
//                                    Log.d(TAG, "Uploaded to Firebase!");
//                                    statusText.append("\n\n[UPLOADED TO CLOUD DASHBOARD]");
//                                })
//                                .addOnFailureListener(e -> {
//                                    Log.e(TAG, "Upload Failed: " + e.getMessage());
//                                    statusText.append("\n\n[NO INTERNET: Saved locally]");
//                                });
//                    }
//                }
//            }
//        }
//        @Override
//        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
//    };
//
//    private void requestPermissions() {
//        List<String> requiredPermissions = new ArrayList<>();
//        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
//        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
//            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
//            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
//        }
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
//        }
//
//        List<String> missingPermissions = new ArrayList<>();
//        for (String permission : requiredPermissions) {
//            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
//                missingPermissions.add(permission);
//            }
//        }
//
//        if (!missingPermissions.isEmpty()) {
//            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
//        } else {
//            statusText.setText("Permissions Granted.");
//        }
//    }
//
//    private final ActivityResultLauncher<String[]> permissionLauncher =
//            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
//                boolean allGranted = true;
//                for (Boolean granted : result.values()) {
//                    if (!granted) { allGranted = false; break; }
//                }
//                if (allGranted) {
//                    statusText.setText("Permissions Granted.");
//                } else {
//                    Toast.makeText(this, "Permissions denied. App cannot function.", Toast.LENGTH_LONG).show();
//                }
//            });
//}