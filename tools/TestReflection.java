import java.lang.reflect.Method;

public class TestReflection {
    public static void main(String[] args) {
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getService = atmClass.getMethod("getService");
            Object service = getService.invoke(null);
            System.out.println("Service: " + service);
            for (Method m : service.getClass().getMethods()) {
                if (m.getName().equals("startActivity")) {
                    System.out.println("Found startActivity: " + m);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}