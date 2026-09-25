import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;

public class ClipboardHelper {
    public static void main(String[] args) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "clipboard");
            Class<?> stub = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            Object clipboard = asInterface.invoke(null, binder);

            if (args.length > 0 && args[0].equals("get")) {
                for (Method m : clipboard.getClass().getMethods()) {
                    if (m.getName().equals("getPrimaryClip")) {
                        ClipData clip = null;
                        if (m.getParameterCount() == 4) {
                            clip = (ClipData) m.invoke(clipboard, "com.android.shell", null, 0, 0);
                        } else if (m.getParameterCount() == 3) {
                            clip = (ClipData) m.invoke(clipboard, "com.android.shell", null, 0);
                        } else if (m.getParameterCount() == 2) {
                            clip = (ClipData) m.invoke(clipboard, "com.android.shell", 0);
                        }
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence text = clip.getItemAt(0).getText();
                            if (text != null) {
                                System.out.println("CLIPBOARD_TEXT:" + text.toString());
                                return;
                            }
                        }
                    }
                }
                System.err.println("Failed to get clipboard");
                System.exit(1);
            } else if (args.length > 1 && args[0].equals("set")) {
                String text = args[1];
                ClipData clip = ClipData.newPlainText("text", text);
                for (Method m : clipboard.getClass().getMethods()) {
                    if (m.getName().equals("setPrimaryClip")) {
                        if (m.getParameterCount() == 4) {
                            m.invoke(clipboard, clip, "com.android.shell", null, 0);
                            System.out.println("SET_OK");
                            return;
                        } else if (m.getParameterCount() == 3) {
                            m.invoke(clipboard, clip, "com.android.shell", 0);
                            System.out.println("SET_OK");
                            return;
                        }
                    }
                }
                System.err.println("Failed to set clipboard");
                System.exit(1);
            } else {
                System.err.println("Usage: ClipboardHelper get | ClipboardHelper set <text>");
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
