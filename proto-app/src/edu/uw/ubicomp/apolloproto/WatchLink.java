package edu.uw.ubicomp.apolloproto;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bluetooth LE link to the lab's WILD watch ("flashwatch", ZygPulse firmware). No pairing: the watch is
 * an open GATT sensor, so the app connects directly and subscribes to one characteristic.
 *
 * Protocol (firmware/wild_zephyr/src/main.c): 200 samples/s, each sent as two 20-byte notifications.
 * Even count = IMU packet [count u16][watch uptime ms u32][accel xyz, gyro xyz s16], odd count = PPG
 * packet [count u16][red, ir, green u24 for sensor 0, then sensor 1], all big-endian.
 *
 * Clock: each sample keeps the watch's own time. Phone time = watch time + the smallest (arrival - watch)
 * difference seen on this connection, i.e. the radio's minimum delivery delay, so the jitter of
 * Bluetooth delivery (hundreds of ms on a Mac) doesn't move the samples.
 */
final class WatchLink {
    static final UUID SERVICE = UUID.fromString("0000bc9a-0000-1000-8000-00805f9b34fb");
    static final UUID CHARACTERISTIC = UUID.fromString("0000be9a-0000-1000-8000-00805f9b34fb");
    static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int CAPACITY = 200 * 240;   // 4 minutes of samples

    enum State { IDLE, SCANNING, CONNECTING, STREAMING, BLUETOOTH_OFF }

    static final class Found {
        final BluetoothDevice device;
        final String name;
        int rssi;

        Found(BluetoothDevice device, String name, int rssi) {
            this.device = device;
            this.name = name;
            this.rssi = rssi;
        }
    }

    interface Listener {
        void onWatchChanged();
    }

    private static WatchLink instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Found> found = new LinkedHashMap<>();
    private volatile State state = State.IDLE;
    private volatile String message = "Not connected";
    private volatile String deviceName;
    private BluetoothGatt gatt;
    private boolean userDisconnect;
    private int retries;
    private final Runnable scanTimeout = this::stopScan;
    private final Runnable retry = () -> {
        state = State.IDLE;   // let connect() run again
        connectLast();
    };

    private final WatchDecoder buf = new WatchDecoder();

    private WatchLink(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized WatchLink get(Context c) {
        if (instance == null) instance = new WatchLink(c);
        return instance;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    State state() {
        return state;
    }

    String message() {
        return message;
    }

    String deviceName() {
        return deviceName;
    }

    boolean streaming() {
        return state == State.STREAMING;
    }

    synchronized List<Found> found() {
        return new java.util.ArrayList<>(found.values());
    }

    private void set(State s, String msg) {
        state = s;
        message = msg;
        main.post(() -> {
            for (Listener l : listeners) l.onWatchChanged();
        });
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    // ---- Scanning -----------------------------------------------------------------------------

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            synchronized (WatchLink.this) {
                Found f = found.get(r.getDevice().getAddress());
                if (f == null) found.put(r.getDevice().getAddress(), new Found(r.getDevice(), name != null ? name : "WILD watch", r.getRssi()));
                else f.rssi = r.getRssi();
            }
            set(State.SCANNING, "Searching… found " + found.size());
        }

        @Override
        public void onScanFailed(int errorCode) {
            set(State.IDLE, "Bluetooth scan failed (error " + errorCode + "). Try again");
        }
    };

    @SuppressWarnings("MissingPermission")
    void scan() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            set(State.BLUETOOTH_OFF, "Bluetooth is off. Turn it on in quick settings");
            return;
        }
        if (state == State.STREAMING || state == State.CONNECTING) return;
        synchronized (this) {
            found.clear();
        }
        BluetoothLeScanner scanner = a.getBluetoothLeScanner();
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(filters, settings, scanCallback);
        set(State.SCANNING, "Searching for the watch…");
        main.postDelayed(scanTimeout, 10_000);
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        BluetoothAdapter a = adapter();
        if (a != null && a.isEnabled() && a.getBluetoothLeScanner() != null) a.getBluetoothLeScanner().stopScan(scanCallback);
        main.removeCallbacks(scanTimeout);
        if (state == State.SCANNING) {
            set(State.IDLE, found.isEmpty() ? "No watch found. Is it on and not connected to another app or computer?"
                    : "Tap a watch to connect");
        }
    }

    // ---- Connecting -----------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    void connect(Found f) {
        stopScan();
        connect(f.device, f.name);
    }

    /** Reconnect to the last watch used, if any (no scan needed). */
    @SuppressWarnings("MissingPermission")
    boolean connectLast() {
        String addr = Store.prefs(app).getString("watch_address", null);
        BluetoothAdapter a = adapter();
        if (addr == null || a == null || !a.isEnabled()) return false;
        connect(a.getRemoteDevice(addr), Store.prefs(app).getString("watch_name", "watch"));
        return true;
    }

    @SuppressWarnings("MissingPermission")
    private void connect(BluetoothDevice device, String name) {
        if (state == State.STREAMING || state == State.CONNECTING) return;
        userDisconnect = false;
        deviceName = name;
        Store.prefs(app).edit().putString("watch_address", device.getAddress()).putString("watch_name", name)
                .putBoolean("watch_auto", true).apply();   // reconnect on app start until the user disconnects
        buf.newConnection();
        set(State.CONNECTING, "Connecting to " + name + "…");
        gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("MissingPermission")
    void disconnect() {
        userDisconnect = true;
        Store.prefs(app).edit().putBoolean("watch_auto", false).apply();
        main.removeCallbacks(retry);
        if (gatt != null) gatt.disconnect();
        set(State.IDLE, "Disconnected");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                retries = 0;
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);   // 200 Hz needs a short interval
                // 400 notifications/s: the 2M radio mode fits about twice as many per connection event.
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                set(State.CONNECTING, "Connected, setting up the data stream…");
                main.postDelayed(g::discoverServices, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close();
                if (g == gatt) gatt = null;
                if (!userDisconnect && retries < 3) {
                    retries++;
                    set(State.CONNECTING, "Connection dropped (" + status + "). Retrying " + retries + " of 3…");
                    main.postDelayed(retry, 1500);
                } else if (!userDisconnect) {
                    set(State.IDLE, "Couldn't stay connected (" + status + "). Is another app or computer connected to the watch?");
                }
            }
        }

        @Override
        @SuppressWarnings({"MissingPermission", "deprecation"})
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(CHARACTERISTIC);
            if (c == null) {
                set(State.IDLE, "This device doesn't have the watch's data service");
                g.disconnect();
                return;
            }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCC);
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) set(State.STREAMING, "Streaming from " + deviceName);
            else set(State.IDLE, "The watch refused the data stream (" + status + ")");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            buf.onPacket(value, System.currentTimeMillis());
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            buf.onPacket(c.getValue(), System.currentTimeMillis());   // Android 12 and older
        }
    };

    // ---- Data (decoding lives in WatchDecoder, plain Java so it's testable on the Mac) ---------------

    double dropFraction() {
        return buf.dropFraction();
    }

    double[][] between(long fromPhoneMs, long toPhoneMs) {
        return buf.between(fromPhoneMs, toPhoneMs);
    }

    /** The last `seconds` of samples ({phoneMs, red, ir, green}). */
    double[][] last(double seconds) {
        long now = System.currentTimeMillis();
        return buf.between(now - (long) (seconds * 1000), now + 1000);
    }

    void writeCsv(File f, long fromPhoneMs, long toPhoneMs) throws IOException {
        buf.writeCsv(f, fromPhoneMs, toPhoneMs);
    }
}
