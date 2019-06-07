package sg.com.styl.stm32flasher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.FormatException;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

public class DeviceFirmwareUpgrade {

    private static final String TAG = "DFU: ";
    private int deviceVid;
    private int devicePid;
    private DfuFile dfuFile = null;
    private Context context;

    private OnFirmwareUpgrade onFirmwareUpgrade;

    private STM32F042UsbManager usb;
    private int deviceVersion;  //STM bootloader version

    private static final int READ_STORAGE_PERMISSION_REQUEST_CODE = 0x03;

    private final static int USB_DIR_OUT = 0;
    private final static int USB_DIR_IN = 128;       //0x80
    private final static int DFU_RequestType = 0x21;  // '2' => Class request ; '1' => to interface

    private final static int STATE_IDLE = 0x00;
    private final static int STATE_DETACH = 0x01;
    private final static int STATE_DFU_IDLE = 0x02;
    private final static int STATE_DFU_DOWNLOAD_SYNC = 0x03;
    private final static int STATE_DFU_DOWNLOAD_BUSY = 0x04;
    private final static int STATE_DFU_DOWNLOAD_IDLE = 0x05;
    private final static int STATE_DFU_MANIFEST_SYNC = 0x06;
    private final static int STATE_DFU_MANIFEST = 0x07;
    private final static int STATE_DFU_MANIFEST_WAIT_RESET = 0x08;
    private final static int STATE_DFU_UPLOAD_IDLE = 0x09;
    private final static int STATE_DFU_ERROR = 0x0A;
    private final static int STATE_DFU_UPLOAD_SYNC = 0x91;
    private final static int STATE_DFU_UPLOAD_BUSY = 0x92;

    // DFU Commands, request ID code when using controlTransfers
    private final static int DFU_DETACH = 0x00;
    private final static int DFU_DNLOAD = 0x01;
    private final static int DFU_UPLOAD = 0x02;
    private final static int DFU_GETSTATUS = 0x03;
    private final static int DFU_CLRSTATUS = 0x04;
    private final static int DFU_GETSTATE = 0x05;
    private final static int DFU_ABORT = 0x06;

    public final static int ELEMENT1_OFFSET = 293;  // constant offset in file array where image data starts
    public final static int TARGET_NAME_START = 22;
    public final static int TARGET_NAME_MAX_END = 276;
    public final static int TARGET_SIZE = 277;
    public final static int TARGET_NUM_ELEMENTS = 281;


    // Device specific parameters
    public static final String mInternalFlashString = "@Internal Flash  /0x08000000/032*0001Kg";
    public static final int mInternalFlashSize = 32768;
    public static final int mInternalFlashStartAddress = 0x08000000;
    public static final int mOptionByteStartAddress = 0x1FFFF800;
    private static final int OPT_BOR_1 = 0x08;
    private static final int OPT_BOR_2 = 0x04;
    private static final int OPT_BOR_3 = 0x00;
    private static final int OPT_BOR_OFF = 0x0C;
    private static final int OPT_WDG_SW = 0x20;
    private static final int OPT_nRST_STOP = 0x40;
    private static final int OPT_nRST_STDBY = 0x80;
    private static final int OPT_RDP_OFF = 0xAA00;
    private static final int OPT_RDP_1 = 0x3300;

    public void setOnFirmwareUpgrade(OnFirmwareUpgrade onFirmwareUpgrade) {
        this.onFirmwareUpgrade = onFirmwareUpgrade;
    }

    public void setUsb(STM32F042UsbManager usb) {
        this.usb = usb;
    }

    public int getDeviceVersion() {
        return deviceVersion;
    }

    public DfuFile getDfuFile() {
        return dfuFile;
    }

    public void setDfuFile(DfuFile dfuFile) {
        this.dfuFile = dfuFile;
    }

    public DeviceFirmwareUpgrade(Context context, int deviceVid, int devicePid) {
        this.deviceVid = deviceVid;
        this.devicePid = devicePid;
        this.context = context;
    }

    public DeviceFirmwareUpgrade(Context context, int deviceVid, int devicePid, DfuFile dfuFile) {
        this.deviceVid = deviceVid;
        this.devicePid = devicePid;
        this.context = context;
        this.dfuFile = dfuFile;
    }
    private boolean isUsbConnected() {
        if (usb != null && usb.isConnected()) {
            return true;
        }
        Log.d(TAG, "isUsbConnected: " + "No device connected");
        return false;
    }

    private void clearStatus() throws Exception {
        int length = usb.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0);
        if (length < 0) {
            throw new Exception("USB Failed during clearStatus");
        }
    }

    private void getUsbStatus(DfuStatus status) throws Exception {
        byte[] buffer = new byte[6];
        int length, retry = 5;
        do {
            length = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_GETSTATUS, 0, 0, buffer, 6, 500);
        } while (length < 0 && retry-- > 0);
        if (length < 0) {
            throw new Exception("USB Failed during getUsbStatus");
        }
        status.bStatus = buffer[0]; // state during request
        status.bState = buffer[4]; // state after request
        status.bwPollTimeout = (buffer[3] & 0xFF) << 16;
        status.bwPollTimeout |= (buffer[2] & 0xFF) << 8;
        status.bwPollTimeout |= (buffer[1] & 0xFF);
    }

    // use for commands
    private void download(byte[] data) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, data.length, 50);
        if (len < 0) {
            throw new Exception("USB Failed during command download");
        }
    }

    // use for firmware download
    private void download(byte[] data, int nBlock) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, data.length, 0);
        if (len < 0) {
            throw new Exception("USB failed during firmware download");
        }
    }

    private void setAddressPointer(int Address) throws Exception {
        byte[] buffer = new byte[5];
        buffer[0] = 0x21;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer);
    }

    private boolean isDeviceProtected() throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        boolean isProtected = false;

        do {
            clearStatus();
            getUsbStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(mInternalFlashStartAddress);
        getUsbStatus(dfuStatus); // to execute
        getUsbStatus(dfuStatus);   // to verify

        if (dfuStatus.bState == STATE_DFU_ERROR) {
            isProtected = true;
        }
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getUsbStatus(dfuStatus);
        }
        return isProtected;
    }

    private void unProtectCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) 0x92;
        download(buffer);
    }

    private void removeReadProtection() throws Exception {
        DfuStatus dfuStatus = new DfuStatus();
        unProtectCommand();
        getUsbStatus(dfuStatus);
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("Failed to execute unprotect command");
        }
        usb.release();     // XXX device will self-reset
        Log.i(TAG, "USB was released");
    }

    private void massEraseCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = 0x41;
        download(buffer);
    }


    public void massErase() {
        AsyncTaskErase asyncTaskErase = new AsyncTaskErase();
        asyncTaskErase.execute();

    }

//    public void massErase() {
//
//        onFirmwareUpgrade.onFirmwareUpgradeLog("---------------------------------------------");
//        if (!isUsbConnected()){
//            onFirmwareUpgrade.onFirmwareUpgradeLog("STM32F042C4 is not connected or not in DFU mode");
//            return;
//        }
//        DfuStatus dfuStatus = new DfuStatus();
//        long startTime = System.currentTimeMillis();  // note current time
//
//        try {
//            onFirmwareUpgrade.onFirmwareUpgradeLog("Getting Status of STM32 devices....");
//            do {
//                clearStatus();
//                getUsbStatus(dfuStatus);
//            } while (dfuStatus.bState != STATE_DFU_IDLE);
//            onFirmwareUpgrade.onFirmwareUpgradeLog("STM32 devices is idle");
//            if (isDeviceProtected()) {
//                removeReadProtection();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("massErase: Read Protection removed. Device resets...Wait until it   re-enumerates ");
//                return;
//            }
//            onFirmwareUpgrade.onFirmwareUpgradeLog("Sending mass erase command");
//            massEraseCommand();                 // sent erase command request
//            getUsbStatus(dfuStatus);                // initiate erase command, returns 'download busy' even if invalid address or ROP
//            onFirmwareUpgrade.onFirmwareUpgradeLog("Getting Status of STM32 devices....");
//            int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
//            do {
//                /* wait specified time before next getUsbStatus call */
//                Thread.sleep(pollingTime);
//                clearStatus();
//                getUsbStatus(dfuStatus);
//            } while (dfuStatus.bState != STATE_DFU_IDLE);
//
//           onFirmwareUpgrade.onFirmwareUpgradeLog("massErase: Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        } catch (Exception e) {
//            onFirmwareUpgrade.onFirmwareUpgradeLog("massErase: " + e.toString());
//        }
//    }

    private void openFile() throws Exception {

        File extDownload;
        String myFilePath = null;
        String myFileName = null;
        FileInputStream fileInputStream;
        File myFile;

        if (dfuFile == null) {
            if (Environment.getExternalStorageState() != null)  // todo not sure if this works
            {
                extDownload = new File(Environment.getExternalStorageDirectory() + "/Download");

                if (extDownload.exists()) {
                    String[] files = extDownload.list();
                    // todo support multiple dfu files in dir
                    if (files.length > 0) {   // will select first dfu file found in dir
                        for (String file : files) {
                            if (file.endsWith(".dfu")) {
                                myFilePath = extDownload.toString();
                                myFileName = file;
                                break;
                            }
                        }
                    }
                }
            }
            if (myFileName == null) throw new Exception("No .dfu file found in Download Folder");

            myFile = new File(myFilePath + "/" + myFileName);

            dfuFile.filePath = myFile.toString();
            dfuFile.file = new byte[(int) myFile.length()];
        } else {
            myFile = new File(dfuFile.filePath);
            dfuFile.file = new byte[(int) myFile.length()];
        }
        //convert file into byte array
        fileInputStream = new FileInputStream(myFile);
        fileInputStream.read(dfuFile.file);
        fileInputStream.close();
    }

    private void verifyFile() throws Exception {

        // todo for now i expect the file to be not corrupted

        int length = dfuFile.file.length;

        int crcIndex = length - 4;
        int crc = 0;
        crc |= dfuFile.file[crcIndex++] & 0xFF;
        crc |= (dfuFile.file[crcIndex++] & 0xFF) << 8;
        crc |= (dfuFile.file[crcIndex++] & 0xFF) << 16;
        crc |= (dfuFile.file[crcIndex] & 0xFF) << 24;
        // do crc check
        if (crc != CRC8Utils.calculateCRC(dfuFile.file)) {
            throw new FormatException("CRC Failed");
        }

        // Check the prefix
        String prefix = new String(dfuFile.file, 0, 5);
        if (prefix.compareTo("DfuSe") != 0) {
            throw new FormatException("File signature error");
        }

        // check dfuSe Version
        if (dfuFile.file[5] != 1) {
            throw new FormatException("DFU file version must be 1");
        }

        // Check the suffix
        String suffix = new String(dfuFile.file, length - 8, 3);
        if (suffix.compareTo("UFD") != 0) {
            throw new FormatException("File suffix error");
        }
        if ((dfuFile.file[length - 5] != 16) || (dfuFile.file[length - 10] != 0x1A) || (dfuFile.file[length - 9] != 0x01)) {
            throw new FormatException("File number error");
        }

        // Now check the target prefix, we assume there is only one target in the file
        String target = new String(dfuFile.file, 11, 6);
        if (target.compareTo("Target") != 0) {
            throw new FormatException("Target signature error");
        }

        if (0 != dfuFile.file[TARGET_NAME_START]) {
            String tempName = new String(dfuFile.file, TARGET_NAME_START, TARGET_NAME_MAX_END);
            int foundNullAt = tempName.indexOf(0);
            dfuFile.TargetName = tempName.substring(0, foundNullAt);
        } else {
            throw new FormatException("No Target Name Exist in File");
        }
        Log.i(TAG, "Firmware Target Name: " + dfuFile.TargetName);

        dfuFile.TargetSize = dfuFile.file[TARGET_SIZE] & 0xFF;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 1] & 0xFF) << 8;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 2] & 0xFF) << 16;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 3] & 0xFF) << 24;

        Log.i(TAG, "Firmware Target Size: " + dfuFile.TargetSize);

        dfuFile.NumElements = dfuFile.file[TARGET_NUM_ELEMENTS] & 0xFF;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 1] & 0xFF) << 8;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 2] & 0xFF) << 16;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 3] & 0xFF) << 24;

        Log.i(TAG, "Firmware Num of Elements: " + dfuFile.NumElements);

        if (dfuFile.NumElements > 1) {
            throw new FormatException("Do not support multiple Elements inside Image");
            /*  If you get this error, that means that the C-compiler IDE is treating the Reset Vector ISR
                and the data ( your code) as two separate elements.
                This problem has been observed with The Atollic TrueStudio V5.5.2
                The version of Atollic that works with this is v5.3.0
                The version of DfuSe FileManager is v3.0.3
                Refer to ST document UM0391 for more details on DfuSe format
             */
        }

        // Get Element Flash start address and size
        dfuFile.elementStartAddress = dfuFile.file[285] & 0xFF;
        dfuFile.elementStartAddress |= (dfuFile.file[286] & 0xFF) << 8;
        dfuFile.elementStartAddress |= (dfuFile.file[287] & 0xFF) << 16;
        dfuFile.elementStartAddress |= (dfuFile.file[288] & 0xFF) << 24;

        dfuFile.elementLength = dfuFile.file[289] & 0xFF;
        dfuFile.elementLength |= (dfuFile.file[290] & 0xFF) << 8;
        dfuFile.elementLength |= (dfuFile.file[291] & 0xFF) << 16;
        dfuFile.elementLength |= (dfuFile.file[292] & 0xFF) << 24;

        if (dfuFile.elementLength < 512) {
            throw new FormatException("Element Size is too small");
        }

        // Get VID, PID and version number
        dfuFile.VID = (dfuFile.file[length - 11] & 0xFF) << 8;
        dfuFile.VID |= (dfuFile.file[length - 12] & 0xFF);
        dfuFile.PID = (dfuFile.file[length - 13] & 0xFF) << 8;
        dfuFile.PID |= (dfuFile.file[length - 14] & 0xFF);
        dfuFile.BootVersion = (dfuFile.file[length - 15] & 0xFF) << 8;
        dfuFile.BootVersion |= (dfuFile.file[length - 16] & 0xFF);
    }

    private int deviceSizeLimit() {   // retrieves and compares the Internal Flash Memory Size  and compares to constant string

        int bmRequest = 0x80;       // IN, standard request to usb device
        byte bRequest = (byte) 0x06; // USB_REQ_GET_DESCRIPTOR
        byte wLength = (byte) 127;   // max string size
        byte[] descriptor = new byte[wLength];

        /* This method can be used to retrieve any memory location size by incrementing the wValue in the defined range.
            ie. Size of: Internal Flash,  Option Bytes, OTP Size, and Feature location
         */
        int wValue = 0x0304;        // possible strings range from 0x304-0x307

        int len = usb.controlTransfer(bmRequest, bRequest, wValue, 0, descriptor, wLength, 500);
        if (len < 0) {
            return -1;
        }
        String decoded = new String(descriptor, Charset.forName("UTF-16LE"));
        if (decoded.contains(mInternalFlashString)) {
            return mInternalFlashSize; // size of stm32f405RG
        } else {
            return -1;
        }

    }

    private void checkCompatibility() throws Exception {

        if ((devicePid != dfuFile.PID) || (deviceVid != dfuFile.VID)) {
            throw new FormatException("PID/VID Miss match");
        }

        deviceVersion = usb.getDeviceVersion();

        // give warning and continue on
        if (deviceVersion != dfuFile.BootVersion) {
            Log.d(TAG, "checkCompatibility: Warning: Device BootVersion: " + Integer.toHexString(deviceVersion) +
                    "\tFile BootVersion: " + Integer.toHexString(dfuFile.BootVersion) + "\n");
        }

        if (dfuFile.elementStartAddress != mInternalFlashStartAddress) { // todo: this will fail with images for other memory sections, other than Internal Flash
            throw new FormatException("Firmware does not start at beginning of internal flash");
        }

        if (deviceSizeLimit() < 0) {
            throw new Exception("Error: Could Not Retrieve Internal Flash String");
        }

        if ((dfuFile.elementStartAddress + dfuFile.elementLength) >=
                (mInternalFlashStartAddress + mInternalFlashSize)) {
            throw new FormatException("Firmware image too large for target");
        }

        switch (deviceVersion) {
            case 0x011A:
            case 0x0200:
                dfuFile.maxBlockSize = 1024;
                break;
            case 0x2100:
            case 0x2200:
                dfuFile.maxBlockSize = 2048;
                break;
            default:
                throw new Exception("Error: Unsupported bootloader version");
        }
        Log.i(TAG, "Firmware ok and compatible");

    }

    private void writeBlock(int address, byte[] block, int blockNumber) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        do {
            clearStatus();
            getUsbStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        if (0 == blockNumber) {
            setAddressPointer(address);
            getUsbStatus(dfuStatus);
            getUsbStatus(dfuStatus);
            if (dfuStatus.bState == STATE_DFU_ERROR) {
                throw new Exception("Start address not supported");
            }
        }

        do {
            clearStatus();
            getUsbStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        download(block, (blockNumber + 2));
        getUsbStatus(dfuStatus);   // to execute
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("error when downloading, was not busy ");
        }
        getUsbStatus(dfuStatus);   // to verify action
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("error when downloading, did not perform action");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getUsbStatus(dfuStatus);
        }
    }

    private void writeImage() throws Exception {
        int address = dfuFile.elementStartAddress;  // flash start address
        int fileOffset = ELEMENT1_OFFSET;   // index offset of file
        int blockSize = dfuFile.maxBlockSize;   // max block size
        byte[] Block = new byte[blockSize];
        int NumOfBlocks = dfuFile.elementLength / blockSize;
        int blockNum;
        for (blockNum = 0; blockNum < NumOfBlocks; blockNum++) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, blockSize);

            writeBlock(address, Block, blockNum);
        }
        int remainder = dfuFile.elementLength - (blockNum * blockSize);
        if (remainder > 0) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, remainder);
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < Block.length) {
                Block[remainder++] = (byte) 0xFF;
            }
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
    }

    public void program() {
        AsyncTaskProgram asyncTaskProgram = new AsyncTaskProgram();
        asyncTaskProgram.execute();
    }

    private class AsyncTaskProgram extends AsyncTask<Void, Bundle, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            final String statusFlag = "status";
            final String progressFlag = "progress";

            Bundle bundle = new Bundle();
            bundle.putString(statusFlag, "---------------------------------------------");
            bundle.putInt(progressFlag, 0);
            publishProgress(bundle);
            try {
                ResetUtils.enterDfuMode();
                Thread.sleep(1000);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            bundle.putString(statusFlag, "Resetting Device to DFU Mode");
            publishProgress(bundle);
            if (!isUsbConnected()) {
                bundle.putString(statusFlag, "STM32F042C4 is not connected or not in DFU mode");
                publishProgress(bundle);
                bundle.putString(statusFlag,"Try mass erase before programing");
                publishProgress(bundle);
                return null;
            }

            try {
                if (isDeviceProtected()) {
                    bundle.putString(statusFlag,"program: Device is Read-Protected...First Mass Erase");
                    publishProgress(bundle);
                    return null;
                }

                if (!checkPermissionForReadExtertalStorage()) {
                    bundle.putString(statusFlag,"Requesting Storage permission");
                    publishProgress(bundle);
                    requestPermissionForReadExtertalStorage();
                }
                if (checkPermissionForReadExtertalStorage()) {
                    bundle.putString(statusFlag,"Reading firmware...");
                    publishProgress(bundle);
                    openFile();
                    bundle.putString(statusFlag,"Verifying firmware...");
                    publishProgress(bundle);
                    verifyFile();
                    bundle.putString(statusFlag,"Checking compatibility...");
                    publishProgress(bundle);
                    checkCompatibility();
                    bundle.putString(statusFlag,"program: File Path: " + dfuFile.filePath);
                    publishProgress(bundle);
                    bundle.putString(statusFlag,"program: File Size: " + dfuFile.file.length + " Bytes \n");
                    publishProgress(bundle);
                    bundle.putString(statusFlag,"program: ElementAddress: 0x" + Integer.toHexString(dfuFile.elementStartAddress));
                    publishProgress(bundle);
                    bundle.putString(statusFlag,"program: ElementSize: " + dfuFile.elementLength + " Bytes\n");
                    publishProgress(bundle);
                    bundle.putString(statusFlag,"program: Start writing file in blocks of " + dfuFile.maxBlockSize + " Bytes \n");
                    publishProgress(bundle);
                    long startTime = System.currentTimeMillis();
                    bundle.putString(statusFlag,"Writing firmware Image...");
                    publishProgress(bundle);

                    int address = dfuFile.elementStartAddress;  // flash start address
                    int fileOffset = ELEMENT1_OFFSET;   // index offset of file
                    int blockSize = dfuFile.maxBlockSize;   // max block size
                    byte[] Block = new byte[blockSize];
                    int NumOfBlocks = dfuFile.elementLength / blockSize;
                    int blockNum;
                    bundle.putString(statusFlag,"Blocks to be written: " + NumOfBlocks);
                    publishProgress(bundle);
                    for (blockNum = 0; blockNum < NumOfBlocks; blockNum++) {
                        System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, blockSize);
                        bundle.putString(statusFlag,"Writing block " + (blockNum + 1));
                        bundle.putInt(progressFlag, (blockNum + 1)*100/NumOfBlocks);
                        publishProgress(bundle);
                        writeBlock(address, Block, blockNum);
                    }
                    int remainder = dfuFile.elementLength - (blockNum * blockSize);
                    if (remainder > 0) {
                        System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, remainder);
                        // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
                        while (remainder < Block.length) {
                            Block[remainder++] = (byte) 0xFF;
                        }
                        // send out the block to device
                        writeBlock(address, Block, blockNum);
                        bundle.putString(statusFlag,"Writing final block");
                        publishProgress(bundle);
                    }

                    bundle.putString(statusFlag,"program: Programming completed in " + (System.currentTimeMillis() - startTime) + " ms\n");
                    publishProgress(bundle);
                    bundle.putString(statusFlag,"Resetting Device to normal mode");
                    publishProgress(bundle);
                    ResetUtils.enterNormalMode();
                } else {
                    bundle.putString(statusFlag,"Storage permission was not granted");
                    publishProgress(bundle);
                }
            } catch (Exception e) {
                e.printStackTrace();
                bundle.putString(statusFlag,"program: " + e.toString());
                publishProgress(bundle);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Bundle... values) {
            super.onProgressUpdate(values);
            final String statusFlag = "status";
            final String progressFlag = "progress";
            Bundle bundle = values[0];
            String statusLog = bundle.getString(statusFlag);
            int progress = bundle.getInt(progressFlag);
            onFirmwareUpgrade.onFirmwareUpgradeLog(statusLog);
            onFirmwareUpgrade.onUpdateProgressBar(progress);
        }
    }


    private class AsyncTaskErase extends AsyncTask<Void, String, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            publishProgress("---------------------------------------------");

            try {
                ResetUtils.enterDfuMode();
                Thread.sleep(1000);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (!isUsbConnected()){
                publishProgress("STM32F042C4 is not connected or not in DFU mode");
                return null;
            }
            DfuStatus dfuStatus = new DfuStatus();
            long startTime = System.currentTimeMillis();  // note current time

            try {
                publishProgress("Getting Status of STM32 devices....");
                do {
                    clearStatus();
                    getUsbStatus(dfuStatus);
                } while (dfuStatus.bState != STATE_DFU_IDLE);
                publishProgress("STM32 devices is idle");
                if (isDeviceProtected()) {
                    removeReadProtection();
                    publishProgress("massErase: Read Protection removed. Device resets...Wait until it   re-enumerates ");
                    return null;
                }
                publishProgress("Sending mass erase command");
                massEraseCommand();                 // sent erase command request
                getUsbStatus(dfuStatus);                // initiate erase command, returns 'download busy' even if invalid address or ROP
                publishProgress("Getting Status of STM32 devices....");
                int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
                do {
                    /* wait specified time before next getUsbStatus call */
                    Thread.sleep(pollingTime);
                    clearStatus();
                    getUsbStatus(dfuStatus);
                } while (dfuStatus.bState != STATE_DFU_IDLE);

               publishProgress("massErase: Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");

            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                publishProgress("massErase: " + e.toString());
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            onFirmwareUpgrade.onFirmwareUpgradeLog(values[0]);
        }
    }


//    public void program() {
//
//        onFirmwareUpgrade.onFirmwareUpgradeLog("---------------------------------------------");
//
//        if (!isUsbConnected()) {
//            onFirmwareUpgrade.onFirmwareUpgradeProgress(0, 1);
//            onFirmwareUpgrade.onFirmwareUpgradeLog("STM32F042C4 is not connected or not in DFU mode");
//            onFirmwareUpgrade.onFirmwareUpgradeLog("Try mass erase before programing");
//            return;
//        }
//
//        try {
//            if (isDeviceProtected()) {
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: Device is Read-Protected...First Mass Erase");
//                return;
//            }
//
//            if (!checkPermissionForReadExtertalStorage()) {
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Requesting Storage permission");
//                requestPermissionForReadExtertalStorage();
//            }
//            if (checkPermissionForReadExtertalStorage()) {
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Reading firmware...");
//                openFile();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Verifying firmware...");
//                verifyFile();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Checking compatibility...");
//                checkCompatibility();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: File Path: " + dfuFile.filePath + "\n");
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: File Size: " + dfuFile.file.length + " Bytes \n");
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: ElementAddress: 0x" + Integer.toHexString(dfuFile.elementStartAddress));
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: ElementSize: " + dfuFile.elementLength + " Bytes\n");
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: Start writing file in blocks of " + dfuFile.maxBlockSize + " Bytes \n");
//
//                long startTime = System.currentTimeMillis();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Writing firmware Image...");
//                writeImage();
//                onFirmwareUpgrade.onFirmwareUpgradeLog("program: Programming completed in " + (System.currentTimeMillis() - startTime) + " ms\n");
//            } else {
//                onFirmwareUpgrade.onFirmwareUpgradeLog("Storage permission was not granted");
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            onFirmwareUpgrade.onFirmwareUpgradeLog("program: " + e.toString());
//        }
//    }

    public boolean checkPermissionForReadExtertalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }


    public void requestPermissionForReadExtertalStorage() throws Exception {
        try {
            ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    READ_STORAGE_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public BroadcastReceiver getExternalStoragereceiver() {
        return externalStoragereceiver;
    }

    private final BroadcastReceiver externalStoragereceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive: " + action);
        }
    };
}
