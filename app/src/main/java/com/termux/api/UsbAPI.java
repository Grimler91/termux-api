package com.termux.api;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.util.JsonWriter;
import android.util.Log;
import android.util.SparseArray;

import com.termux.api.util.ResultReturner;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.HashMap;

import androidx.annotation.NonNull;

public class UsbAPI {

    private static final String LOG_TAG = "TermuxUsbAPI";
    private static SparseArray<UsbDeviceConnection> openDevices = new SparseArray<>();

    /* Version, to be increased when printed format changes */
    private static String api_version = "usbAPI|0";
    private static char sep = '|';
    private static char row_sep = '\n';

    static void onReceive(final TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        UsbDevice device;
        String action = intent.getAction();
        if (action == null) {
            ResultReturner.returnData(apiReceiver, intent, out -> out.append("Missing action\n"));
        } else {
            switch (action) {
                case "list":
                    ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        public void writeJson(JsonWriter out) throws Exception {
                            listDevices(context, out);
                        }
                    });
                    break;
                case "open":
                    device = getDevice(apiReceiver, context, intent);
                    if (device == null) return;
                    ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithAncillaryFd() {
                        @Override
                        public void writeResult(PrintWriter out) {
                            if (getPermission(device, context, intent)) {
                                int result = open(device, context);
                                if (result < 0) {
                                    out.append("Failed to open device\n");
                                } else {
                                    this.setFd(result);
                                    out.append("@"); // has to be non-empty
                                }
                            } else out.append("No permission\n");
                        }
                    });
                    break;
                case "permission":
                    device = getDevice(apiReceiver, context, intent);
                    if (device == null) return;
                    ResultReturner.returnData(apiReceiver, intent, out -> {
                        boolean result = getPermission(device, context, intent);
                        out.append(result ? "yes\n" : "no\n");
                    });
                    break;

                /* The following cases print data that is suppose to
                 * be parsed by the termux-api userspace library */

                case "getDevices":
                    /* get info from android.hardware.usb.UsbDevice */
                    ResultReturner.returnData(context, intent, new ResultReturner.ResultWriter() {
                        @Override
                        public void writeResult(PrintWriter out) throws Exception {
                            getDevices(context, out);
                            out.flush();
                            out.close();
                        }
                    });
                    break;
                case "getDeviceInterfaces":
                    /* get info from android.hardware.usb.UsbInterface */
                    device = getDevice(apiReceiver, context, intent);
                    if (device == null) return;

                    ResultReturner.returnData(context, intent, new ResultReturner.ResultWriter() {
                        @Override
                        public void writeResult(PrintWriter out) throws Exception {
                            getDeviceInterfaces(device, context, intent, out);
                            out.flush();
                            out.close();
                        }
                    });
                    break;
                default:
                    ResultReturner.returnData(apiReceiver, intent, out -> out.append("Invalid action\n"));
            }
        }
    }

    private static void listDevices(final Context context, JsonWriter out) throws IOException {
        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        out.beginArray();
        for (UsbDevice device : deviceList.values()) {
            out.beginObject();
            out.name("device_name").value(device.getDeviceName());
            out.name("device_id").value(device.getDeviceId());
            out.name("vendor_id").value(String.format("0x%04x", device.getVendorId()));
            out.name("product_id").value(String.format("0x%04x", device.getProductId()));
            out.name("device_class").value(device.getDeviceClass()+" - "+translateDeviceClass(device.getDeviceClass()));
            out.name("device_sub_class").value(device.getDeviceSubclass());
            out.name("manufacturer_name").value(device.getManufacturerName().replace("\u0000", ""));
            out.name("device_protocol").value(device.getDeviceProtocol());
            out.name("product_name").value(device.getProductName().replace("\u0000", ""));
            out.name("serial_number").value(device.getSerialNumber());
            out.name("configurations").value(device.getConfigurationCount());
            out.name("descriptor_type").value(device.describeContents());
            out.name("access_granted").value(usbManager.hasPermission(device));
            out.endObject();
        }
        out.endArray();
    }
    private static String translateDeviceClass(int usbClass){
        switch(usbClass){
        case UsbConstants.USB_CLASS_APP_SPEC:
            return "App specific USB class";
        case UsbConstants.USB_CLASS_AUDIO:
            return "Audio device";
        case UsbConstants.USB_CLASS_CDC_DATA:
            return "CDC device (communications device class)";
        case UsbConstants.USB_CLASS_COMM:
            return "Communication device";
        case UsbConstants.USB_CLASS_CONTENT_SEC:
            return "Content security device";
        case UsbConstants.USB_CLASS_CSCID:
            return "Content smart card device";
        case UsbConstants.USB_CLASS_HID:
            return "Human interface device (for example a keyboard)";
        case UsbConstants.USB_CLASS_HUB:
            return "USB hub";
        case UsbConstants.USB_CLASS_MASS_STORAGE:
            return "Mass storage device";
        case UsbConstants.USB_CLASS_MISC:
            return "Wireless miscellaneous devices";
        case UsbConstants.USB_CLASS_PER_INTERFACE:
            return "Usb class is determined on a per-interface basis";
        case UsbConstants.USB_CLASS_PHYSICA:
            return "Physical device";
        case UsbConstants.USB_CLASS_PRINTER:
            return "Printer";
        case UsbConstants.USB_CLASS_STILL_IMAGE:
            return "Still image devices (digital cameras)";
        case UsbConstants.USB_CLASS_VENDOR_SPEC:
            return "Vendor specific USB class";
        case UsbConstants.USB_CLASS_VIDEO:
            return "Video device";
        case UsbConstants.USB_CLASS_WIRELESS_CONTROLLER:
            return "Wireless controller device";
        default: return "Unknown USB class!";
        }
    }

    private static UsbDevice getDevice(final TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        String deviceName = intent.getStringExtra("device");
        String vendorId = intent.getStringExtra("vendorId");
        String productId = intent.getStringExtra("productId");
        if (deviceName == null && (vendorId == null || productId == null)) {
            Log.e(LOG_TAG, "Missing usb device info in open()");
            ResultReturner.returnData(apiReceiver, intent, out -> out.append("Need either usbfs path or vendorId+productId\n"));
            return null;
        }

        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        UsbDevice device = null;
        if (deviceName != null) {
            device = deviceList.get(deviceName);
        } else {
            for (UsbDevice dev : deviceList.values()) {
                Log.d(LOG_TAG, "Comparing "+dev.getDeviceName()+" with given vendorId and productId");
                if (String.format("0x%04x", dev.getVendorId()).equalsIgnoreCase(vendorId) &&
                    String.format("0x%04x", dev.getProductId()).equalsIgnoreCase(productId)) {
                    device = dev;
                    break;
                }
            }
        }
        if (device == null) {
            ResultReturner.returnData(apiReceiver, intent, out -> out.append("No such device\n"));
        } else {
            Log.i(LOG_TAG, "Found matching device at "+device.getDeviceName());
        }
        return device;
    }

    private static boolean hasPermission(final @NonNull UsbDevice device, final Context context) {
        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return usbManager.hasPermission(device);
    }

    private static boolean requestPermission(final @NonNull UsbDevice device, final Context context) {
        Looper.prepare();
        Looper looper = Looper.myLooper();
        final boolean[] result = new boolean[1];

        final String ACTION_USB_PERMISSION = "com.termux.api.USB_PERMISSION";
        final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context usbContext, final Intent usbIntent) {
                String action = usbIntent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = usbIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (usbIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                result[0] = true;
                                if (looper != null) looper.quit();
                            }
                        } else {
                            result[0] = false;
                            if (looper != null) looper.quit();
                        }
                    }
                }
            }
        };

        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        context.getApplicationContext().registerReceiver(usbReceiver, filter);
        usbManager.requestPermission(device, permissionIntent);
        Looper.loop();
        return result[0];
    }

    private static boolean getPermission(final @NonNull UsbDevice device, final Context context, final Intent intent) {
        boolean request = intent.getBooleanExtra("request", false);
        if (request) {
            return requestPermission(device, context);
        } else {
            return hasPermission(device, context);
        }
    }

    private static int open(final @NonNull UsbDevice device, final Context context) {
        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null)
            return -2;
        int fd = connection.getFileDescriptor();
        if (fd == -1) {
            connection.close();
            return -1;
        }
        openDevices.put(fd, connection);
        return fd;
    }

    /* The following actions print data that is suppose to be parsed
     * by the termux-api userspace library */

    private static void getDevices(final Context context, PrintWriter out) throws IOException {
        out.append(api_version).append(row_sep);
        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice dev : deviceList.values()) {
            out.append(dev.getDeviceName()).append(sep);
            out.append(Integer.toString(dev.getConfigurationCount())).append(sep);
            out.append(Integer.toString(dev.getDeviceClass())).append(sep);
            out.append(Integer.toString(dev.getDeviceId())).append(sep);
            out.append(Integer.toString(dev.getDeviceProtocol())).append(sep);
            out.append(Integer.toString(dev.getDeviceSubclass())).append(sep);
            out.append(Integer.toString(dev.getInterfaceCount())).append(sep);
            out.append(Integer.toString(dev.getProductId())).append(sep);
            out.append(Integer.toString(dev.getVendorId())).append(sep);
            out.append(Integer.toString(dev.hashCode())).append(sep);
            out.append(dev.getManufacturerName().replace("\u0000", "")).append(sep);
            out.append(dev.getProductName().replace("\u0000", "")).append(sep);
            out.append(dev.getSerialNumber()).append(sep);
            out.append(dev.getVersion()).append(row_sep);
        }
    }

    private static void getDeviceInterfaces(final @NonNull UsbDevice device, final Context context, final Intent intent, PrintWriter out) {
        out.append(api_version).append(row_sep);
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            out.append(intf.getName()).append(sep);
            out.append(Integer.toString(intf.getAlternateSetting())).append(sep);
            out.append(Integer.toString(intf.getEndpointCount())).append(sep);
            out.append(Integer.toString(intf.getId())).append(sep);
            out.append(Integer.toString(intf.getInterfaceClass())).append(sep);
            out.append(Integer.toString(intf.getInterfaceProtocol())).append(sep);
            out.append(Integer.toString(intf.getInterfaceSubclass())).append(row_sep);
        }
    }
}
