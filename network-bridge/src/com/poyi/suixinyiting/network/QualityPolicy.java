package com.poyi.suixinyiting.network;

public final class QualityPolicy {
    public static final String[] LOSSLESS_FIRST = {"lossless", "exhigh", "higher", "standard"};
    public static final String[] HIRES_FIRST = {"hires", "lossless", "exhigh", "higher", "standard"};
    private QualityPolicy() {}

    public static String[] levels(String preferred) {
        if ("hires".equals(preferred)) return HIRES_FIRST;
        if ("exhigh".equals(preferred)) return new String[]{"exhigh", "higher", "standard"};
        if ("higher".equals(preferred)) return new String[]{"higher", "standard"};
        if ("standard".equals(preferred)) return new String[]{"standard"};
        return LOSSLESS_FIRST;
    }

    public static String label(String level) {
        if ("hires".equalsIgnoreCase(level)) return "Hi-Res";
        if ("lossless".equalsIgnoreCase(level)) return "无损";
        if ("exhigh".equalsIgnoreCase(level)) return "极高";
        if ("higher".equalsIgnoreCase(level)) return "较高";
        if ("standard".equalsIgnoreCase(level)) return "标准";
        return level == null || level.isEmpty() ? "未知音质" : level;
    }
}
