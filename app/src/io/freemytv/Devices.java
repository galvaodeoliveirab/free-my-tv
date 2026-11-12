package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Personal: the TV's paired Bluetooth devices for the drawer's Devices card (in place of the quotes), and connecting or
 * disconnecting one with OK. Pairing a new one stays in the TV's settings. Everything goes through BtHelper, started
 * as the shell user over the TV's own ADB (the same key HomeMode uses): an app can't connect a device by itself since
 * Android 13. Only fixed commands; an address goes in only after it matches the MAC pattern.
 */
final class Devices {
    private Devices() {}

    static final int IDLE = 0, CONNECTING = 1, DISCONNECTING = 2;

    static final class Device {
        final String address, name, symbol;
        final boolean connected;
        final int battery;   // 0-100, or -1 when the device doesn't tell
        final int state;     // IDLE, or CONNECTING / DISCONNECTING while OK's request is under way

        Device(String address, String name, String symbol, boolean connected, int battery, int state) {
            this.address = address;
            this.name = name;
            this.symbol = symbol;
            this.connected = connected;
            this.battery = battery;
            this.state = state;
        }
    }

    interface Listener { void onDevices(); }

    private static final Pattern MAC = Pattern.compile("[0-9A-F]{2}(:[0-9A-F]{2}){5}");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** A connect or disconnect is given this long to show in the list before the card stops waiting. */
    private static final long SETTLE_MS = 12_000;

    private static volatile List<Device> list = Collections.emptyList();
    /** Bluetooth off, no ADB, or not Android 13+: the card says so instead of listing. */
    private static volatile String problem = "";
    private static final Map<String, Integer> pending = new HashMap<>();   // address -> CONNECTING / DISCONNECTING
    private static boolean refreshing;
    private static long refreshedAt;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    static List<Device> list() { return list; }
    static String problem() { return problem; }

    /** Reads the list again in the background (skipped when one is under way or was read in the last `minAgeMs`). */
    static void refresh(Context c, Listener l, long minAgeMs) {
        if (!supported()) {
            problem = Ui.tr("Needs Android 13 or newer");
            return;
        }
        Context app = c.getApplicationContext();
        synchronized (pending) {
            if (refreshing || SystemClock.elapsedRealtime() - refreshedAt < minAgeMs) return;
            refreshing = true;
        }
        WORKER.execute(() -> {
            try (Adb adb = Adb.connect(app, 3000, null)) {
                read(app, adb);
            } catch (IOException | RuntimeException e) {
                problem = Ui.tr("Turn on the TV's debugging");
                AppLog.w("devices: " + e.getMessage());
            } finally {
                synchronized (pending) {
                    refreshing = false;
                    refreshedAt = SystemClock.elapsedRealtime();
                }
                notify(l);
            }
        });
    }

    /** OK on a device: connects it if it's off, disconnects it if it's on; the card shows the step until it lands. */
    static void toggle(Context c, Device d, Listener l) {
        if (!supported() || !MAC.matcher(d.address).matches()) return;
        Context app = c.getApplicationContext();
        boolean connect = !d.connected;
        synchronized (pending) {
            if (pending.containsKey(d.address)) return;
            pending.put(d.address, connect ? CONNECTING : DISCONNECTING);
        }
        mark();
        notify(l);
        WORKER.execute(() -> {
            try (Adb adb = Adb.connect(app, 3000, null)) {
                adb.shell(helper(app) + (connect ? " connect " : " disconnect ") + d.address);
                long until = SystemClock.elapsedRealtime() + SETTLE_MS;
                while (SystemClock.elapsedRealtime() < until) {
                    SystemClock.sleep(1000);
                    read(app, adb);
                    Device now = find(d.address);
                    if (now == null || now.connected == connect) break;
                    notify(l);
                }
            } catch (IOException | RuntimeException e) {
                AppLog.w("devices: " + e.getMessage());
            } finally {
                synchronized (pending) { pending.remove(d.address); }
                mark();
                notify(l);
            }
        });
    }

    private static String helper(Context app) {
        return "CLASSPATH='" + app.getApplicationInfo().sourceDir + "' app_process / io.freemytv.BtHelper";
    }

    private static void read(Context app, Adb adb) throws IOException {
        String out = adb.shell(helper(app) + " list");
        List<Device> next = new ArrayList<>();
        String error = null;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\t", 6);
            if (f.length >= 2 && f[0].equals("E")) error = f[1];
            if (f.length < 6 || !f[0].equals("D") || !MAC.matcher(f[1]).matches()) continue;
            int cls = parse(f[2], 0);
            if (isRemote(cls, f[5])) continue;   // disconnecting the remote would leave you without one
            next.add(new Device(f[1], f[5], symbol(cls), f[3].equals("1"), parse(f[4], -1), IDLE));
        }
        // Connected first, then by name; the order stays put while you move along the card.
        Collections.sort(next, (a, b) -> a.connected != b.connected ? (a.connected ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        if ("off".equals(error)) problem = Ui.tr("Bluetooth is off");
        else if (error != null) problem = Ui.tr("Bluetooth isn't available");
        else problem = "";
        list = next;
        mark();
    }

    /** Applies the pending steps to the list (a new list: the drawing thread may be reading the old one). */
    private static void mark() {
        List<Device> marked = new ArrayList<>();
        synchronized (pending) {
            for (Device d : list) {
                Integer p = pending.get(d.address);
                int state = p == null ? IDLE : p;
                marked.add(state == d.state ? d : new Device(d.address, d.name, d.symbol, d.connected, d.battery, state));
            }
        }
        list = marked;
    }

    private static Device find(String address) {
        for (Device d : list) if (d.address.equals(address)) return d;
        return null;
    }

    private static void notify(Listener l) {
        if (l != null) MAIN.post(l::onDevices);
    }

    private static int parse(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /** The TV's own remote: class "remote control" (0x50C), or a TCL remote's name (BT_RC…, "TCL RC…"). */
    private static boolean isRemote(int cls, String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return (cls & 0x1FFC) == 0x050C || n.startsWith("BT_RC") || n.contains("REMOTE") || n.startsWith("TCL RC");
    }

    /** Symbol from the Bluetooth class of device (major and minor). */
    private static String symbol(int cls) {
        int major = cls & 0x1F00;
        if (major == 0x0400) {   // audio / video
            int minor = cls & 0xFC;
            return minor == 0x04 || minor == 0x18 || minor == 0x08 ? "headphones" : "speaker";
        }
        if (major == 0x0500) {   // peripheral
            if ((cls & 0x0C) == 0x08) return "gamepad";   // joystick, gamepad
            if ((cls & 0xC0) == 0x40) return "keyboard";
            if ((cls & 0xC0) == 0x80) return "mouse";
            return "gamepad";
        }
        return "bluetooth";
    }
}
