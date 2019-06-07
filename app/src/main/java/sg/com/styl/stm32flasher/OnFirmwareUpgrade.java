package sg.com.styl.stm32flasher;

public interface OnFirmwareUpgrade {
    void onFirmwareUpgradeLog(String logText);
    void onUpdateProgressBar(int value);
}
