import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

public class SendCorpusHelper {
    public static void main(String[] args) {
        try {
            File dir = new File("/sdcard/Download/hard017-corpus");
            File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
            if (files == null || files.length == 0) {
                System.err.println("No .bin files found in " + dir);
                System.exit(1);
            }
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            System.out.println("Found " + files.length + " corpus files.");

            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                uris.add(Uri.parse("content://dev.glide.app.debug.fileprovider/shares/" + f.getName()));
            }

            ClipData clipData = new ClipData("files", new String[]{"application/octet-stream"}, new ClipData.Item(uris.get(0)));
            for (int i = 1; i < uris.size(); i++) {
                clipData.addItem(new ClipData.Item(uris.get(i)));
            }

            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setComponent(new ComponentName("dev.glide.app.debug", "dev.glide.app.MainActivity"));
            intent.setType("application/octet-stream");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.setClipData(clipData);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getService = atmClass.getMethod("getService");
            Object service = getService.invoke(null);

            Method startActivity = null;
            for (Method m : service.getClass().getMethods()) {
                if (m.getName().equals("startActivity") && m.getParameterCount() == 11) {
                    startActivity = m;
                    break;
                }
            }

            if (startActivity == null) {
                System.err.println("startActivity method not found");
                System.exit(1);
            }

            int result = (int) startActivity.invoke(
                service,
                null, // caller
                "com.android.shell", // callingPackage
                null, // callingFeatureId
                intent,
                "application/octet-stream", // resolvedType
                null, // resultTo
                null, // resultWho
                0, // requestCode
                0, // flags
                null, // profilerInfo
                null // bOptions
            );

            System.out.println("startActivity returned: " + result);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}