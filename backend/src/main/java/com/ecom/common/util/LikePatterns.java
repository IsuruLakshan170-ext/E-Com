package com.ecom.common.util;

import java.util.Locale;

/** Builds a safe, lower-cased "contains" LIKE pattern; use with escape char '\\'. */
public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private LikePatterns() {
    }

    public static String contains(String query) {
        String escaped = query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
