package sg.com.styl.stm32flasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ResetUtils {

    private static final String TAG = "ResetUtils: ";
    private static final String stm32ResetValPath = "/sys/class/gpio-boot-reset/stm32f042c4/mode";

    private static final File resetFile = new File(stm32ResetValPath);


    public static void enterDfuMode() throws IOException {
        FileOutputStream stream = new FileOutputStream(resetFile);
        try{
            stream.write("prog".getBytes());
        }  catch (IOException e) {
            e.printStackTrace();
        } finally {
            stream.close();
        }
    }
    public static void enterNormalMode() throws IOException {
        FileOutputStream stream = new FileOutputStream(resetFile);
        try{
            stream.write("normal".getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            stream.close();
        }
    }
}
