package sg.com.styl.stm32flasher;

public class DfuFile {
        String filePath;
        byte[] file;
        int PID;
        int VID;
        int BootVersion;
        int maxBlockSize = 1024;

        int elementStartAddress;
        int elementLength;

        String TargetName;
        int TargetSize;
        int NumElements;
    }