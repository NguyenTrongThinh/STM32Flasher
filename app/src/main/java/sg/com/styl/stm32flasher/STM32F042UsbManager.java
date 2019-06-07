package sg.com.styl.stm32flasher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Iterator;


public class STM32F042UsbManager {

    private Context m_Context;
    private UsbManager usbManager;
    private static final String TAG = "DFU Manager: ";


    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbInterface mInterface;
    private int mDeviceVersion;
    private OnUsbChangeListener mOnUsbChangeListener;

    public static final String ACTION_USB_PERMISSION = "sg.com.styl.stm32flasher.USB_PERMISSION";

    private final static int STM32F042_USB_VID = 0x0483;
    private final static int STM32F042_USB_PID = 0xDF11;

    public Context getM_Context() {
        return m_Context;
    }

    public static int getStm32f042UsbVid() {
        return STM32F042_USB_VID;
    }

    public static int getStm32f042UsbPid() {
        return STM32F042_USB_PID;
    }

    public void setOnUsbChangeListener(OnUsbChangeListener mOnUsbChangeListener) {
        this.mOnUsbChangeListener = mOnUsbChangeListener;
    }

    public STM32F042UsbManager(Context m_Context) {
        this.m_Context = m_Context;
    }

    public void setUsbManager(UsbManager usbManager) {
        this.usbManager = usbManager;
    }

    public void setDevice(UsbDevice mDevice) {
        this.mDevice = mDevice;
        mInterface = mDevice.getInterface(0); //This one need to be checked
        if (mDevice != null) {
            UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(mDevice);
            if (usbDeviceConnection != null && usbDeviceConnection.claimInterface(mInterface, true)) {
                Log.d(TAG, "setDevice: USB Device Opened");
                mConnection = usbDeviceConnection;
                byte[] rawDescriptor = mConnection.getRawDescriptors();
                mDeviceVersion = rawDescriptor[13] << 8;
                mDeviceVersion |= rawDescriptor[12];

            } else {
                Log.d(TAG, "setDevice: USB Device not Opened");
                mConnection = null;
            }
        }
    }

    public String getDeviceInfo(UsbDevice device) {
        if (device == null)
            return "No device found.";

        StringBuilder sb = new StringBuilder();
        sb.append("Model: " + device.getDeviceName() + "\n");
        sb.append("ID: " + device.getDeviceId() + " (0x" + Integer.toHexString(device.getDeviceId()) + ")" + "\n");
        sb.append("Class: " + device.getDeviceClass() + "\n");
        sb.append("Subclass: " + device.getDeviceSubclass() + "\n");
        sb.append("Protocol: " + device.getDeviceProtocol() + "\n");
        sb.append("Vendor ID " + device.getVendorId() + " (0x" + Integer.toHexString(device.getVendorId()) + ")" + "\n");
        sb.append("Product ID: " + device.getProductId() + " (0x" + Integer.toHexString(device.getProductId()) + ")" + "\n");
        sb.append("Device Ver: 0x" + Integer.toHexString(mDeviceVersion) + "\n");
        sb.append("Interface count: " + device.getInterfaceCount() + "\n");

        for (int i = 0; i < device.getInterfaceCount(); i++) {

            UsbInterface usbInterface = device.getInterface(i);

            sb.append("Interface: " + usbInterface.toString() + "\n");
            sb.append("Endpoint Count: " + usbInterface.getEndpointCount() + "\n");

            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {

                UsbEndpoint ep = usbInterface.getEndpoint(j);

                sb.append("Endpoint: " + ep.toString() + "\n");
            }
        }

        return sb.toString();
    }


    public UsbDevice getUsbDevice() {
        return mDevice;
    }

    public boolean release() {
        boolean isReleased = false;
        if (mConnection != null) {
            isReleased = mConnection.releaseInterface(mInterface);
            mConnection.close();
            mConnection = null;
        }
        return isReleased;
    }

    public BroadcastReceiver getUsbBroadcastReceiver() {
        return m_BroadCastReceiver;
    }
    private final BroadcastReceiver m_BroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    synchronized (this) {
                        requestPermission(m_Context, STM32F042_USB_VID, STM32F042_USB_PID);
                    }
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    synchronized (this) {

                        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (mDevice != null && usbDevice.equals(mDevice)) {
                            release();
                        }
                    }
                    break;
                case ACTION_USB_PERMISSION:
                    synchronized (this) {
                        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (usbDevice != null) {
                                setDevice(usbDevice);
                                if (mOnUsbChangeListener != null) {
                                    mOnUsbChangeListener.onUsbConnected();
                                }
                            }
                        } else {
                            Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;
            }
        }
    };
    private UsbDevice getUsbDevice(int vendorId, int productId) {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
        UsbDevice device;
        while (deviceIterator.hasNext()) {
            device = deviceIterator.next();
            if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                return device;
            }
        }
        return null;
    }

    public void requestPermission(Context context, int vendorId, int productId) {
        // Setup Pending Intent
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(this.ACTION_USB_PERMISSION), 0);
        UsbDevice device = getUsbDevice(vendorId, productId);

        if (device != null) {
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    public boolean isConnected() {
        return (mConnection != null);
    }
    public int getDeviceVersion() {
        return mDeviceVersion;
    }
    public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout) {
        synchronized (this) {
            return mConnection.controlTransfer(requestType, request, value, index, buffer, length, timeout);
        }
    }
}
