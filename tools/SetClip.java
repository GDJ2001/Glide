import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;

public class SetClip {
    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                System.err.println("Usage: SetClip <text>");
                System.exit(1);
            }
            String text = args[0];
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "clipboard");
            Class<?> stub = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            Object clipboard = asInterface.invoke(null, binder);

            ClipData clip = ClipData.newPlainText("text", text);
            for (Method m : clipboard.getClass().getMethods()) {
                if (m.getName().equals("setPrimaryClip")) {
                    if (m.getParameterCount() == 4) {
                        m.invoke(clipboard, clip, "com.android.shell", null, 0);
                        System.out.println("Set clipboard successfully!");
                        return;
                    } else if (m.getParameterCount() == 3) {
                        m.invoke(clipboard, clip, "com.android.shell", 0);
                        System.out.println("Set clipboard successfully!");
                        return;
                    }
                }
            }
            System.err.println("setPrimaryClip method not matched");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
