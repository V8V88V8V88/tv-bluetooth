package com.v8v88v8v88.tvbluetooth;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main extends Activity {
    static final String T = "TvBluetooth";

    static final int HEADSET = 1, A2DP = 2, HID_HOST = 4;
    static final int[] PROFILES = {A2DP, HEADSET, HID_HOST};

    static final int BG = 0xFF000000, CARD = 0xFF1C1C1E, FOCUS = 0xFFF2F2F7;
    static final int TEXT = 0xFFFFFFFF, TEXT_ON_FOCUS = 0xFF000000;
    static final int SUB = 0xFF8E8E93, SUB_ON_FOCUS = 0xFF3C3C43, GREEN = 0xFF30D158;

    final Handler ui = new Handler(Looper.getMainLooper());
    final SparseArray<BluetoothProfile> proxies = new SparseArray<>();
    final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    final Map<String, Row> rows = new LinkedHashMap<>();
    final List<String> pending = new ArrayList<>();
    static final Map<String, String> names = new HashMap<>();
    boolean showUnnamed, leScanning;
    Row unnamedRow;

    BluetoothAdapter ad;
    LinearLayout myList, otherList;
    TextView myHeader, otherHeader, status;
    ProgressBar spinner;
    Row searchRow, powerRow;
    TextView powerNote;
    boolean registered, started, restarting;
    Dialog sheet;

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ad = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        if (ad == null) { setStatus("This TV has no Bluetooth.", false); return; }
        if (!askPermissions()) begin();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(receiver);
        if (ad != null && started) {
            try { ad.cancelDiscovery(); } catch (Throwable ignored) {}
            stopLe();
            for (int i = 0; i < proxies.size(); i++) {
                try { ad.closeProfileProxy(proxies.keyAt(i), proxies.valueAt(i)); } catch (Throwable ignored) {}
            }
        }
    }

    boolean askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return false;
        String[] need = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) return false;
        requestPermissions(missing.toArray(new String[0]), 1);
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        for (int x : r) {
            if (x != PackageManager.PERMISSION_GRANTED) {
                setStatus("Bluetooth permission is needed to find devices.", false);
                return;
            }
        }
        begin();
    }

    void begin() {
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_UUID);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED");
        registerReceiver(receiver, f);
        registered = true;
        for (int p : PROFILES) {
            final int profile = p;
            try {
                ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int id, BluetoothProfile proxy) { proxies.put(profile, proxy); refreshAll(); }
                    @Override public void onServiceDisconnected(int id) { proxies.remove(profile); }
                }, profile);
            } catch (Throwable e) { Log.w(T, "proxy " + p + ": " + e); }
        }
        onAdapterState();
        String mac = getIntent().getStringExtra("mac");
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac.toUpperCase())) {
            BluetoothDevice d = ad.getRemoteDevice(mac.toUpperCase());
            add(d);
            primary(d);
        }
    }

    void onAdapterState() {
        int st = ad.getState();
        boolean on = st == BluetoothAdapter.STATE_ON;
        paintPower(st);
        int vis = on ? View.VISIBLE : View.GONE;
        searchRow.view.setVisibility(vis);
        myList.setVisibility(vis);
        otherList.setVisibility(vis);
        otherHeader.setVisibility(vis);
        if (!on) myHeader.setVisibility(View.GONE);
        if (st == BluetoothAdapter.STATE_OFF) {
            if (restarting) {
                setStatus("Turning Bluetooth back on…", true);
                ui.postDelayed(() -> setPower(true), 1200);
            } else {
                setStatus("Bluetooth is off.", false);
            }
            return;
        }
        if (!on) return;
        if (restarting) {
            restarting = false;
            setStatus("Bluetooth restarted.", false);
            ui.postDelayed(() -> { if ("Bluetooth restarted.".contentEquals(status.getText())) setStatus("", false); }, 4000);
        }
        try { for (BluetoothDevice d : ad.getBondedDevices()) add(d); } catch (Throwable ignored) {}
        refreshAll();
        scan();
    }

    void paintPower(int st) {
        String t;
        switch (st) {
            case BluetoothAdapter.STATE_ON: t = "On"; break;
            case BluetoothAdapter.STATE_TURNING_ON: t = "Turning On…"; break;
            case BluetoothAdapter.STATE_TURNING_OFF: t = "Turning Off…"; break;
            default: t = "Off";
        }
        powerRow.right.setText(t);
        powerRow.paint();
    }

    static boolean appsMayToggle() { return Build.VERSION.SDK_INT < 33; }

    boolean setPower(boolean on) {
        if (on && ad.isEnabled()) return true;
        if (appsMayToggle()) {
            boolean ok = false;
            try { ok = on ? ad.enable() : ad.disable(); } catch (Throwable e) { Log.w(T, "power: " + e); }
            Log.i(T, "power " + on + " = " + ok);
            if (ok) return true;
        }
        if (on) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return true; } catch (Throwable ignored) {}
        }
        restarting = false;
        setStatus("This TV doesn't let apps turn Bluetooth off. Opening the TV's Bluetooth settings.", false);
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Throwable ignored) {}
        return false;
    }

    void onPowerClick() {
        if (ad == null) return;
        int st = ad.getState();
        if (st == BluetoothAdapter.STATE_TURNING_ON || st == BluetoothAdapter.STATE_TURNING_OFF) return;
        if (st != BluetoothAdapter.STATE_ON) { restarting = false; setPower(true); return; }
        Dialog d = newSheet();
        LinearLayout box = sheetBox("Bluetooth",
                "Remotes, speakers and headphones connected over Bluetooth stop while it is off. "
                        + "Restart turns it back on by itself.");
        View restart = sheetButton(box, "Restart Bluetooth", false, this::restartBluetooth);
        sheetButton(box, "Turn Off", true, this::confirmOff);
        sheetButton(box, "Cancel", false, null);
        showSheetBox(d, box);
        restart.requestFocus();
    }

    void restartBluetooth() {
        restarting = true;
        setStatus("Restarting Bluetooth…", true);
        if (!setPower(false)) return;
        ui.postDelayed(() -> {
            if (restarting && ad.getState() == BluetoothAdapter.STATE_OFF) setPower(true);
        }, 10000);
        ui.postDelayed(() -> {
            if (restarting && !ad.isEnabled()) { restarting = false; setPower(true); }
        }, 20000);
    }

    void confirmOff() {
        Dialog d = newSheet();
        LinearLayout box = sheetBox("Turn Off Bluetooth?",
                "If your remote uses Bluetooth it may stop responding. Point it straight at the TV, "
                        + "or use the TV's own buttons, to turn Bluetooth back on.");
        sheetButton(box, "Turn Off", true, () -> { restarting = false; setPower(false); });
        View cancel = sheetButton(box, "Cancel", false, null);
        showSheetBox(d, box);
        cancel.requestFocus();
    }

    Dialog newSheet() {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = sheet.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setDimAmount(0.7f); }
        return sheet;
    }

    LinearLayout sheetBox(String title, String message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(36), dp(32), dp(36), dp(22));
        box.setBackground(round(0xF21C1C1E, 28));
        TextView t = text(24, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView m = text(15, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
        m.setText(message);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(8), 0, dp(24));
        box.addView(m);
        return box;
    }

    void showSheetBox(Dialog d, LinearLayout box) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(box, new FrameLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(wrap);
        d.show();
    }

    void scan() {
        if (ad == null || !ad.isEnabled()) return;
        try {
            ad.cancelDiscovery();
            if (!ad.startDiscovery()) setStatus("Could not start searching.", false);
        } catch (Throwable e) {
            setStatus("Could not start searching.", false);
        }
    }

    // ---------------------------------------------------------------- events

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                onAdapterState();
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) {
                setStatus("Searching…", true);
                startLe();
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                ui.postDelayed(() -> stopLe(), 3000);
                setStatus(namedOthers() == 0 ? "No devices found. Put yours in pairing mode and search again." : "", false);
                return;
            }
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            remember(d.getAddress(), i.getStringExtra(BluetoothDevice.EXTRA_NAME));
            add(d);
            String key = d.getAddress();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                int s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                Log.i(T, key + " bond " + s);
                if (s == BluetoothDevice.BOND_BONDED && pending.remove(key)) {
                    ui.postDelayed(() -> connect(d), 1500);
                } else if (s == BluetoothDevice.BOND_NONE && pending.remove(key)) {
                    setStatus("Couldn't pair with " + name(d) + ". Put it in pairing mode and try again.", false);
                }
            }
            refresh(d);
        }
    };

    final ScanCallback le = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { onLe(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) onLe(r); }
    };

    void onLe(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        if (d == null) return;
        ScanRecord rec = r.getScanRecord();
        String n = rec == null ? null : rec.getDeviceName();
        if (n == null || n.trim().isEmpty()) { if (!devices.containsKey(d.getAddress())) return; }
        remember(d.getAddress(), n);
        ui.post(() -> add(d));
    }

    void startLe() {
        if (leScanning || ad == null || !ad.isEnabled()) return;
        try {
            BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            if (sc == null) return;
            sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), le);
            leScanning = true;
        } catch (Throwable e) { Log.w(T, "le scan: " + e); }
    }

    void stopLe() {
        if (!leScanning) return;
        leScanning = false;
        try {
            BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            if (sc != null) sc.stopScan(le);
        } catch (Throwable ignored) {}
    }

    int namedOthers() {
        int n = 0;
        for (BluetoothDevice d : devices.values())
            if (d.getBondState() != BluetoothDevice.BOND_BONDED && rawName(d) != null) n++;
        return n;
    }

    int unnamedOthers() {
        int n = 0;
        for (BluetoothDevice d : devices.values())
            if (d.getBondState() != BluetoothDevice.BOND_BONDED && rawName(d) == null) n++;
        return n;
    }

    // ---------------------------------------------------------------- actions

    void primary(BluetoothDevice d) {
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) { showSheet(d); return; }
        if (d.getBondState() == BluetoothDevice.BOND_BONDING) return;
        try { ad.cancelDiscovery(); } catch (Throwable ignored) {}
        pending.add(d.getAddress());
        boolean ok = false;
        try { ok = d.createBond(); } catch (Throwable e) { Log.w(T, "createBond: " + e); }
        Log.i(T, "createBond " + d.getAddress() + " = " + ok);
        if (!ok) {
            pending.remove(d.getAddress());
            setStatus("Couldn't start pairing with " + name(d) + ".", false);
        } else {
            setStatus("Pairing with " + name(d) + "…", true);
        }
        refresh(d);
    }

    void connect(BluetoothDevice d) {
        List<Integer> ps = profilesFor(d);
        if (ps.isEmpty()) {
            setStatus(name(d) + " is paired.", false);
            refresh(d);
            return;
        }
        boolean any = false;
        for (int p : ps) {
            BluetoothProfile proxy = proxies.get(p);
            if (proxy == null) continue;
            try {
                Object r = proxy.getClass().getMethod("connect", BluetoothDevice.class).invoke(proxy, d);
                Log.i(T, "connect " + p + " " + d.getAddress() + " = " + r);
                if (Boolean.TRUE.equals(r)) any = true;
            } catch (Throwable e) { Log.w(T, "connect " + p + ": " + e); }
        }
        setStatus(any ? "Connecting to " + name(d) + "…"
                : name(d) + " is paired. Turn it off and on if it doesn't connect by itself.", any);
        if (any) ui.postDelayed(() -> setStatus("", false), 6000);
        refresh(d);
    }

    void disconnect(BluetoothDevice d) {
        for (int p : PROFILES) {
            BluetoothProfile proxy = proxies.get(p);
            if (proxy == null || state(proxy, d) == BluetoothProfile.STATE_DISCONNECTED) continue;
            try { proxy.getClass().getMethod("disconnect", BluetoothDevice.class).invoke(proxy, d); }
            catch (Throwable e) { Log.w(T, "disconnect " + p + ": " + e); }
        }
        setStatus("Disconnecting " + name(d) + "…", false);
        ui.postDelayed(() -> setStatus("", false), 4000);
        refresh(d);
    }

    void forget(BluetoothDevice d) {
        boolean ok = false;
        try { ok = (Boolean) d.getClass().getMethod("removeBond").invoke(d); } catch (Throwable e) { Log.w(T, "removeBond: " + e); }
        setStatus(ok ? name(d) + " was forgotten." : "This TV doesn't let apps forget devices. Use the TV's Bluetooth settings.", false);
        refresh(d);
    }

    // ---------------------------------------------------------------- device facts

    void add(BluetoothDevice d) {
        if (!devices.containsKey(d.getAddress())) devices.put(d.getAddress(), d);
        refresh(d);
    }

    static int state(BluetoothProfile proxy, BluetoothDevice d) {
        try { return proxy.getConnectionState(d); } catch (Throwable e) { return BluetoothProfile.STATE_DISCONNECTED; }
    }

    int connState(BluetoothDevice d) {
        int best = BluetoothProfile.STATE_DISCONNECTED;
        for (int p : PROFILES) {
            BluetoothProfile proxy = proxies.get(p);
            if (proxy == null) continue;
            int s = state(proxy, d);
            if (s == BluetoothProfile.STATE_CONNECTED) return s;
            if (s == BluetoothProfile.STATE_CONNECTING) best = s;
        }
        if (best == BluetoothProfile.STATE_DISCONNECTED && d.getBondState() == BluetoothDevice.BOND_BONDED) {
            try {
                if ((Boolean) d.getClass().getMethod("isConnected").invoke(d)) return BluetoothProfile.STATE_CONNECTED;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    static boolean has(BluetoothDevice d, String... shortUuids) {
        ParcelUuid[] u = null;
        try { u = d.getUuids(); } catch (Throwable ignored) {}
        if (u == null) return false;
        for (ParcelUuid p : u) {
            String s = p.toString().toLowerCase();
            for (String x : shortUuids) if (s.startsWith("0000" + x + "-")) return true;
        }
        return false;
    }

    static List<Integer> profilesFor(BluetoothDevice d) {
        List<Integer> out = new ArrayList<>();
        BluetoothClass c = d.getBluetoothClass();
        int major = c == null ? -1 : c.getMajorDeviceClass();
        boolean audioSink = has(d, "110b", "110d") || major == BluetoothClass.Device.Major.AUDIO_VIDEO;
        boolean headset = has(d, "1108", "111e");
        boolean hid = has(d, "1124", "1812") || major == BluetoothClass.Device.Major.PERIPHERAL;
        boolean phone = major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER;
        if (audioSink && !phone) out.add(A2DP);
        if (headset && !phone && audioSink) out.add(HEADSET);
        if (hid) out.add(HID_HOST);
        return out;
    }

    static String rawName(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (Throwable ignored) {}
        if (n == null || n.trim().isEmpty()) n = names.get(d.getAddress());
        return n == null || n.trim().isEmpty() ? null : n.trim();
    }

    static String name(BluetoothDevice d) {
        String n = rawName(d);
        return n == null ? "Unnamed device" : n;
    }

    static void remember(String address, String n) {
        if (address != null && n != null && !n.trim().isEmpty()) names.put(address, n.trim());
    }

    static String kind(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c != null) {
            int dc = c.getDeviceClass();
            switch (c.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.AUDIO_VIDEO:
                    if (dc == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                            || dc == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET) return "Headphones";
                    if (dc == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) return "Car Audio";
                    if (dc == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER
                            || dc == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR) return "Display";
                    return "Speaker";
                case BluetoothClass.Device.Major.PHONE: return "Phone";
                case BluetoothClass.Device.Major.COMPUTER: return "Computer";
                case BluetoothClass.Device.Major.WEARABLE: return "Wearable";
                case BluetoothClass.Device.Major.HEALTH: return "Health Device";
                case BluetoothClass.Device.Major.IMAGING: return "Printer / Camera";
                case BluetoothClass.Device.Major.PERIPHERAL:
                    int sub = dc & 0xC0;
                    int low = dc & 0x3C;
                    if (low == 0x08) return "Game Controller";
                    if (low == 0x0C) return "Remote";
                    if (sub == 0x40) return "Keyboard";
                    if (sub == 0x80) return "Mouse";
                    if (sub == 0xC0) return "Keyboard & Mouse";
                    return "Remote";
            }
        }
        if (has(d, "110b")) return "Speaker";
        if (has(d, "1812", "1124")) return "Remote";
        return "Bluetooth Device";
    }

    String statusOf(BluetoothDevice d) {
        if (pending.contains(d.getAddress()) || d.getBondState() == BluetoothDevice.BOND_BONDING) return "Pairing…";
        int s = connState(d);
        if (s == BluetoothProfile.STATE_CONNECTED) return "Connected";
        if (s == BluetoothProfile.STATE_CONNECTING) return "Connecting…";
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) return "Not Connected";
        return "";
    }

    // ---------------------------------------------------------------- list

    class Row {
        final LinearLayout view;
        final TextView title, sub, right;
        BluetoothDevice device;

        Row(String t, Runnable onClick) {
            view = new LinearLayout(Main.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(28), dp(18), dp(28), dp(18));
            view.setFocusable(true);
            view.setClickable(true);
            LinearLayout texts = new LinearLayout(Main.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = text(21, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setText(t);
            title.setSingleLine(true);
            sub = text(15, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
            sub.setSingleLine(true);
            sub.setVisibility(View.GONE);
            texts.addView(title);
            texts.addView(sub);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            right = text(17, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
            view.addView(right);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            view.setLayoutParams(lp);
            view.setOnClickListener(v -> onClick.run());
            view.setOnFocusChangeListener((v, has) -> paint());
            paint();
        }

        void paint() {
            boolean f = view.hasFocus() || view.isFocused();
            view.setBackground(round(f ? FOCUS : CARD, 16));
            title.setTextColor(f ? TEXT_ON_FOCUS : TEXT);
            sub.setTextColor(f ? SUB_ON_FOCUS : SUB);
            boolean connected = "Connected".contentEquals(right.getText());
            right.setTextColor(connected ? (f ? 0xFF248A3D : GREEN) : (f ? SUB_ON_FOCUS : SUB));
            view.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).translationZ(f ? dp(8) : 0)
                    .setDuration(160).start();
        }
    }

    void refreshAll() { for (BluetoothDevice d : devices.values()) refresh(d); paintUnnamedRow(); }

    void paintUnnamedRow() {
        if (unnamedRow == null) return;
        int n = unnamedOthers();
        boolean on = ad != null && ad.isEnabled();
        unnamedRow.view.setVisibility(on && n > 0 ? View.VISIBLE : View.GONE);
        unnamedRow.title.setText(showUnnamed ? "Hide Unnamed Devices" : "Show Unnamed Devices");
        unnamedRow.right.setText(String.valueOf(n));
        if (unnamedRow.view.getParent() != null && unnamedRow.view.getParent() == otherList
                && otherList.indexOfChild(unnamedRow.view) != otherList.getChildCount() - 1) {
            boolean f = unnamedRow.view.hasFocus();
            otherList.removeView(unnamedRow.view);
            otherList.addView(unnamedRow.view);
            if (f) unnamedRow.view.requestFocus();
        }
    }

    void refresh(BluetoothDevice d) {
        String key = d.getAddress();
        Row r = rows.get(key);
        if (r == null) {
            r = new Row(name(d), () -> primary(devices.get(key)));
            r.device = d;
            rows.put(key, r);
        }
        boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
        LinearLayout want = bonded ? myList : otherList;
        if (r.view.getParent() != want) {
            boolean refocus = r.view.hasFocus();
            if (r.view.getParent() != null) ((ViewGroup) r.view.getParent()).removeView(r.view);
            want.addView(r.view);
            if (refocus) r.view.requestFocus();
        }
        r.title.setText(name(d));
        String k = kind(d);
        boolean unnamed = rawName(d) == null;
        r.sub.setText(unnamed ? k + " · " + key : k);
        r.sub.setVisibility(View.VISIBLE);
        r.view.setVisibility(bonded || !unnamed || showUnnamed ? View.VISIBLE : View.GONE);
        paintUnnamedRow();
        r.right.setText(statusOf(d));
        r.paint();
        boolean on = ad != null && ad.isEnabled();
        myHeader.setVisibility(on && myList.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        otherHeader.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- action sheet

    void showSheet(BluetoothDevice d) {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = sheet.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(36), dp(32), dp(36), dp(22));
        box.setBackground(round(0xF21C1C1E, 28));
        TextView title = text(26, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText(name(d));
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView sub = text(15, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setText(kind(d) + " · " + statusOf(d));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(24));
        box.addView(sub);

        boolean connected = connState(d) == BluetoothProfile.STATE_CONNECTED;
        boolean canConnect = !profilesFor(d).isEmpty();
        View first = null;
        if (connected && canConnect) first = sheetButton(box, "Disconnect", false, () -> disconnect(d));
        else if (!connected && canConnect) first = sheetButton(box, "Connect", false, () -> connect(d));
        sheetButton(box, "Forget This Device", true, () -> confirmForget(d));
        View cancel = sheetButton(box, "Cancel", false, null);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(box, new FrameLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        sheet.setContentView(wrap);
        sheet.show();
        (first != null ? first : cancel).requestFocus();
    }

    void confirmForget(BluetoothDevice d) {
        String k = kind(d);
        if (k.equals("Remote") || k.startsWith("Keyboard") || k.equals("Mouse")) {
            if (sheet != null) sheet.dismiss();
            sheet = new Dialog(this);
            sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window w = sheet.getWindow();
            if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setDimAmount(0.7f); }
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(36), dp(32), dp(36), dp(22));
            box.setBackground(round(0xF21C1C1E, 28));
            TextView t = text(24, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setText("Forget " + name(d) + "?");
            t.setGravity(Gravity.CENTER);
            box.addView(t);
            TextView s = text(15, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
            s.setText("If this is the remote you are holding, it will stop working until you pair it again.");
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(8), 0, dp(24));
            box.addView(s);
            sheetButton(box, "Forget", true, () -> forget(d));
            View cancel = sheetButton(box, "Cancel", false, null);
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(box, new FrameLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            sheet.setContentView(wrap);
            sheet.show();
            cancel.requestFocus();
        } else {
            forget(d);
        }
    }

    View sheetButton(LinearLayout box, String label, boolean destructive, Runnable action) {
        TextView b = text(19, destructive ? 0xFFFF453A : TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(16), 0, dp(16));
        b.setFocusable(true);
        b.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        final int normal = destructive ? 0xFFFF453A : TEXT;
        b.setBackground(round(0xFF2C2C2E, 14));
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(round(f ? FOCUS : 0xFF2C2C2E, 14));
            ((TextView) v).setTextColor(f ? (destructive ? 0xFFD70015 : TEXT_ON_FOCUS) : normal);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(v -> {
            Dialog s = sheet;
            if (s != null) s.dismiss();
            if (action != null) action.run();
        });
        box.addView(b);
        return b;
    }

    // ---------------------------------------------------------------- layout

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(48), dp(64), 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(R_DRAWABLE_GLYPH());
        left.addView(glyph, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView title = text(40, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText("Bluetooth");
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(8));
        left.addView(title);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setMinimumHeight(dp(24));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setVisibility(View.GONE);
        statusRow.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        status = text(16, SUB, Typeface.create("sans-serif", Typeface.NORMAL));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), 0, 0, 0);
        status.setMaxWidth(dp(360));
        statusRow.addView(status);
        left.addView(statusRow);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(36), dp(24), dp(36), dp(48));
        scroll.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        powerRow = new Row("Bluetooth", this::onPowerClick);
        powerRow.right.setText("…");
        col.addView(powerRow.view);

        myHeader = header("MY DEVICES");
        myHeader.setVisibility(View.GONE);
        col.addView(myHeader);
        myList = new LinearLayout(this);
        myList.setOrientation(LinearLayout.VERTICAL);
        myList.setClipChildren(false);
        col.addView(myList);

        otherHeader = header("OTHER DEVICES");
        col.addView(otherHeader);
        searchRow = new Row("Search for Devices", this::scan);
        col.addView(searchRow.view);
        otherList = new LinearLayout(this);
        otherList.setOrientation(LinearLayout.VERTICAL);
        otherList.setClipChildren(false);
        col.addView(otherList);
        unnamedRow = new Row("Show Unnamed Devices", () -> { showUnnamed = !showUnnamed; refreshAll(); });
        unnamedRow.view.setVisibility(View.GONE);
        otherList.addView(unnamedRow.view);

        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        setContentView(root);
        powerRow.view.requestFocus();
    }

    int R_DRAWABLE_GLYPH() { return getResources().getIdentifier("glyph", "drawable", getPackageName()); }

    TextView header(String s) {
        TextView t = text(14, SUB, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(s);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(28), dp(22), 0, dp(10));
        return t;
    }

    void setStatus(String s, boolean busy) {
        status.setText(s);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    TextView text(int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) { scan(); return true; }
        return super.onKeyDown(code, e);
    }
}
