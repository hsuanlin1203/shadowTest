package com.example.shadowtest.anomaly;

/** Minimal line-based diff for reporting mismatched response bodies. */
public final class DiffUtil {

    private DiffUtil() {}

    public static String lineDiff(String prod, String shadow) {
        String p = prod == null ? "" : prod;
        String s = shadow == null ? "" : shadow;
        if (p.equals(s)) {
            return "";
        }
        String[] pl = p.split("\n", -1);
        String[] sl = s.split("\n", -1);
        int max = Math.max(pl.length, sl.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            String pv = i < pl.length ? pl[i] : null;
            String sv = i < sl.length ? sl[i] : null;
            if (pv != null && pv.equals(sv)) {
                continue;
            }
            if (pv != null) {
                sb.append("- ").append(pv).append('\n');
            }
            if (sv != null) {
                sb.append("+ ").append(sv).append('\n');
            }
        }
        return sb.toString();
    }
}
