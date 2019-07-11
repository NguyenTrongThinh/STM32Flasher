package sg.com.styl.stm32flasher;


import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.nfc_programmer.GetPath;

public class MainActivity extends AppCompatActivity implements OnUsbChangeListener, OnFirmwareUpgrade{

    private String TAG = "MainActivity: ";
    Button btnMassErase, btnProgram, btnSelectFW;
    ProgressBar upgradeProgressbar;
    TextView txtLog;
    ScrollView scrollLog;
    final static int FILE_REQUEST = 7;
    private STM32F042UsbManager m_Stm32F042UsbManager;
    private DeviceFirmwareUpgrade deviceFirmwareUpgrade;
    DfuFile dfuFile;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewInXml();
        dfuFile = new DfuFile();
        deviceFirmwareUpgrade = new DeviceFirmwareUpgrade(this, STM32F042UsbManager.getStm32f042UsbVid(), STM32F042UsbManager.getStm32f042UsbPid());
        deviceFirmwareUpgrade.setOnFirmwareUpgrade(this);
        btnMassErase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                deviceFirmwareUpgrade.massErase();

            }
        });
        btnProgram.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!dfuFile.filePath.isEmpty()) {
                    deviceFirmwareUpgrade.setDfuFile(dfuFile);
                    deviceFirmwareUpgrade.program();
                }

            }
        });
        btnSelectFW.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setType("*/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(intent, FILE_REQUEST);
            }
        });

    }

    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode){
            case FILE_REQUEST:
                if (resultCode == RESULT_OK){
                    String Path = GetPath.getPath(this, data.getData());
                    onFirmwareUpgradeLog(Path);
                    dfuFile.filePath = Path;
                }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void findViewInXml() {
        btnSelectFW = findViewById(R.id.btnSelectF);
        btnMassErase = findViewById(R.id.btnMassErase);
        btnProgram = findViewById(R.id.btnProgram);
        txtLog = findViewById(R.id.txtLog);
        upgradeProgressbar = findViewById(R.id.upgradeProgress);
        scrollLog = findViewById(R.id.scrollLog);
        upgradeProgressbar.setProgress(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        m_Stm32F042UsbManager = new STM32F042UsbManager(this);
        m_Stm32F042UsbManager.setUsbManager((UsbManager) getSystemService(Context.USB_SERVICE));
        m_Stm32F042UsbManager.setOnUsbChangeListener(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(STM32F042UsbManager.ACTION_USB_PERMISSION);
        registerReceiver(m_Stm32F042UsbManager.getUsbBroadcastReceiver(), filter);
        m_Stm32F042UsbManager.requestPermission(this, STM32F042UsbManager.getStm32f042UsbVid(), STM32F042UsbManager.getStm32f042UsbPid());
    }

    @Override
    protected void onStop() {
        super.onStop();
        deviceFirmwareUpgrade.setUsb(null);
        m_Stm32F042UsbManager.release();
        try {
            unregisterReceiver(m_Stm32F042UsbManager.getUsbBroadcastReceiver());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "onStop: Already unregister");
        }
    }

    @Override
    public void onUsbConnected() {
        Toast.makeText(getApplicationContext(), "STM32F042 Device Connected", Toast.LENGTH_SHORT).show();
        final String deviceInfo = m_Stm32F042UsbManager.getDeviceInfo(m_Stm32F042UsbManager.getUsbDevice());
        deviceFirmwareUpgrade.setUsb(m_Stm32F042UsbManager);
        txtLog.append(deviceInfo);
    }


    @Override
    public void onFirmwareUpgradeLog(String logText) {
        txtLog.append(logText + "\n");
        scrollLog.post(new Runnable() {
            @Override
            public void run() {
                scrollLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    public void onUpdateProgressBar(int value) {
        upgradeProgressbar.setProgress(value);
    }
}
