import android.content.Context;
import android.media.AudioManager;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * READ-ONLY compatibility probe for porting BYD Assistant NG to another BYD model (e.g. Song PLUS).
 *
 * Input file, one line per id:   <deviceType> <featureId> <label>
 * For each id it prints (tab separated):
 *   ID  <label>  <deviceType>  <featureId>  <names this car's BYDAutoFeatureIds gives that number, or ->  <value | ->
 * The value is only read for names that are states (never *_SET / hal_only / RCS / RSE). It never calls set().
 *
 * It also prints ENV lines (Android version, model, ABI), CLASS lines (which bydauto device classes exist),
 * SERVICE lines (the A/C and dashcam Binder services the app talks to), STREAM lines (voice/navi stream ranges)
 * and NAME lines for a few names the app checks by name (exterior speaker, drive mode).
 *
 *   args: <idsFile>
 */
public class Compat {
    static final Map<Integer, String> DEVICE_CLASS = new LinkedHashMap<>();
    static {
        DEVICE_CLASS.put(1000, "android.hardware.bydauto.ac.BYDAutoAcDevice");
        DEVICE_CLASS.put(1001, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
        DEVICE_CLASS.put(1002, "android.hardware.bydauto.audio.BYDAutoAudioDevice");
        DEVICE_CLASS.put(1004, "android.hardware.bydauto.light.BYDAutoLightDevice");
        DEVICE_CLASS.put(1007, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
        DEVICE_CLASS.put(1016, "android.hardware.bydauto.tyre.BYDAutoTyreDevice");
        DEVICE_CLASS.put(1023, "android.hardware.bydauto.setting.BYDAutoSettingDevice");
        DEVICE_CLASS.put(1042, "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice");
        // 1010 (Security) is deliberately not read.
    }
    static final String[] EXTRA_CLASSES = {
        "android.hardware.bydauto.BYDAutoFeatureIds",
        "android.hardware.bydauto.AbsBYDAutoDevice",
        "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
        "android.hardware.bydauto.adas.BYDAutoAdasDevice",
        "com.byd.ac.BydAcManager",
    };
    static final String[] SERVICES = {"byd_airconditioning", "IBYDCDRService"};
    static final String[][] NAMED = {
        {"Adas", "ADAS_UE_BROADCAST_POSITION_SET", "-1442839934"},        // exterior speaker switch
        {"Setting", "SETTING_PRESELECTED_DRIVING_MODE_SET", "1276260400"}, // drive-mode request (0x4C123030)
        {"Setting", "SETTING_TARGET_DRIVING_MODE", ""},
        {"Instrument", "INSTRUMENT_NAVI_TYPE_SET", ""},                    // cluster projection
    };
    static final String[] SKIP_READ = {"_SET", "hal_only", "RCS_", "_RSE_"};

    public static void main(String[] args) {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            env();
            classes();
            services();
            streams(ctx);

            Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            Map<Integer, List<String>> byValue = new HashMap<>();
            List<Class<?>> holders = new ArrayList<>(Arrays.asList(ids.getDeclaredClasses()));
            holders.add(0, ids);
            int total = 0;
            for (Class<?> h : holders) {
                String group = h == ids ? "(top)" : h.getSimpleName();
                for (Field f : h.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    if (v == 0) continue;
                    byValue.computeIfAbsent(v, k -> new ArrayList<>()).add(group + "." + f.getName());
                    total++;
                }
            }
            System.out.println("ENV\tfeatureIdCount\t" + total);

            for (String[] n : NAMED) {
                Integer id = idOf(ids, n[0], n[1]);
                String expected = n[2].isEmpty() ? "" : ("\texpected " + n[2] + (id != null && n[2].equals(String.valueOf(id)) ? " MATCH" : " DIFFERENT"));
                System.out.println("NAME\t" + n[0] + "." + n[1] + "\t" + (id == null ? "-" : id.toString()) + expected);
            }

            Method get = Class.forName("android.hardware.bydauto.AbsBYDAutoDevice").getDeclaredMethod("get", int.class, int.class);
            get.setAccessible(true);
            Map<Integer, Object> devices = new HashMap<>();

            try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split("\\s+", 3);
                    int type = Integer.parseInt(p[0]);
                    int id = Integer.parseInt(p[1]);
                    String label = p.length > 2 ? p[2] : "";
                    List<String> names = byValue.get(id);
                    String value = "-";
                    if (names != null && isState(names) && DEVICE_CLASS.containsKey(type)) {
                        try {
                            Object dev = devices.get(type);
                            if (dev == null) {
                                dev = Class.forName(DEVICE_CLASS.get(type)).getMethod("getInstance", Context.class).invoke(null, ctx);
                                devices.put(type, dev);
                            }
                            value = String.valueOf(get.invoke(dev, type, id));
                        } catch (Throwable t) {
                            Throwable c = t.getCause() != null ? t.getCause() : t;
                            value = "ERR:" + c.getClass().getSimpleName();
                        }
                    }
                    System.out.println("ID\t" + label + "\t" + type + "\t" + id + "\t"
                        + (names == null ? "-" : String.join(",", names)) + "\t" + value);
                }
            }
        } catch (Throwable t) {
            System.out.println("FAILED\t" + (t.getCause() != null ? t.getCause() : t));
        }
        System.out.flush();
        System.exit(0);
    }

    static boolean isState(List<String> names) {
        for (String n : names) for (String s : SKIP_READ) if (n.contains(s)) return false;
        return true;
    }

    static Integer idOf(Class<?> ids, String group, String name) {
        try {
            for (Class<?> h : ids.getDeclaredClasses()) {
                if (!h.getSimpleName().equals(group)) continue;
                Field f = h.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(null);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static void env() {
        System.out.println("ENV\tsdk\t" + android.os.Build.VERSION.SDK_INT);
        System.out.println("ENV\trelease\t" + android.os.Build.VERSION.RELEASE);
        System.out.println("ENV\tmodel\t" + android.os.Build.MODEL);
        System.out.println("ENV\tdevice\t" + android.os.Build.DEVICE);
        System.out.println("ENV\tdisplay\t" + android.os.Build.DISPLAY);
        System.out.println("ENV\tincremental\t" + android.os.Build.VERSION.INCREMENTAL);
        System.out.println("ENV\tabis\t" + String.join(",", android.os.Build.SUPPORTED_ABIS));
    }

    static void classes() {
        for (String c : DEVICE_CLASS.values()) System.out.println("CLASS\t" + c + "\t" + exists(c));
        for (String c : EXTRA_CLASSES) System.out.println("CLASS\t" + c + "\t" + exists(c));
    }

    static String exists(String c) {
        try { Class.forName(c); return "yes"; } catch (Throwable t) { return "no"; }
    }

    static void services() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method check = sm.getMethod("checkService", String.class);
            for (String s : SERVICES) System.out.println("SERVICE\t" + s + "\t" + (check.invoke(null, s) != null ? "yes" : "no"));
        } catch (Throwable t) {
            System.out.println("SERVICE\t?\tERR:" + t.getClass().getSimpleName());
        }
    }

    static void streams(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            for (int s : new int[]{3, 14, 15, 17}) {
                String min = "?";
                try { min = String.valueOf(am.getStreamMinVolume(s)); } catch (Throwable ignored) { }
                System.out.println("STREAM\t" + s + "\tmin=" + min + "\tmax=" + am.getStreamMaxVolume(s));
            }
        } catch (Throwable t) {
            System.out.println("STREAM\t?\tERR:" + t.getClass().getSimpleName());
        }
    }
}
