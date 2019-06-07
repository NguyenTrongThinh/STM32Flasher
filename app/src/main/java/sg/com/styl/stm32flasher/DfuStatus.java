package sg.com.styl.stm32flasher;

public class DfuStatus {
        byte bStatus;       // state during request
        int bwPollTimeout;  // minimum time in ms before next getStatus call should be made
        byte bState;        // state after request
    }