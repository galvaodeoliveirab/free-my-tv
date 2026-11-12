package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * Personal: not part of the app's own process. Devices starts it through the TV's own ADB as the shell user
 * (`CLASSPATH=<this APK> app_process / io.freemytv.BtHelper ...`), like scrcpy does: since Android 13 only the system
 * (BLUETOOTH_PRIVILEGED, which the shell holds) may connect or disconnect a paired device.
 * Commands: `list` prints one line per paired device (D, address, class, connected 0/1, battery or -1, name, tab
 * separated); `connect ADDR` and `disconnect ADDR` print the status code (0 = asked).
 * Touches nothing of the app (Ui, Prefs...): it runs outside it, with no Context.
 */
public final class BtHelper {
    private BtHelper() {}

    public static void main(String[] args) throws Exception {
        try {
            // In a plain app_process, nobody has registered the Bluetooth service with the framework yet.
            Class<?> manager = Class.forName("android.os.BluetoothServiceManager");
            Class<?> init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            init.getMethod("setBluetoothServiceManager", manager).invoke(null, manager.getConstructor().newInstance());
        } catch (Throwable ignored) {   // already set up, or an older framework
        }
        AttributionSource source = new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
        BluetoothAdapter adapter = (BluetoothAdapter) BluetoothAdapter.class
                .getDeclaredMethod("createAdapter", AttributionSource.class).invoke(null, source);
        if (adapter == null) {
            System.out.println("E\tno adapter");
            return;
        }
        String command = args.length > 0 ? args[0] : "list";
        if (command.equals("list")) {
            if (!adapter.isEnabled()) {
                System.out.println("E\toff");
                return;
            }
            Method connected = BluetoothDevice.class.getMethod("isConnected");
            Method battery = BluetoothDevice.class.getMethod("getBatteryLevel");
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                BluetoothClass cls = d.getBluetoothClass();
                String name = d.getAlias();
                if (name == null) name = d.getName();
                if (name == null) name = d.getAddress();
                System.out.println("D\t" + d.getAddress() + "\t" + (cls == null ? 0 : cls.getDeviceClass()) + "\t"
                        + ((Boolean) connected.invoke(d) ? 1 : 0) + "\t" + battery.invoke(d) + "\t"
                        + name.replace('\t', ' ').replace('\n', ' '));
            }
        } else if ((command.equals("connect") || command.equals("disconnect")) && args.length > 1) {
            BluetoothDevice d = adapter.getRemoteDevice(args[1]);
            System.out.println("R\t" + BluetoothDevice.class.getMethod(command).invoke(d));
        }
    }
}
