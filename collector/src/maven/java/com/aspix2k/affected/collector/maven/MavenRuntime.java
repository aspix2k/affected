package com.aspix2k.affected.collector.maven;

/**
 * Classifies a Maven runtime version from its public Implementation-Version string.
 * Maven 4 GA has no proven exact-selection range yet; previews stay on the full goal.
 */
public final class MavenRuntime {

    public enum Line {
        MAVEN_39_EXACT,
        MAVEN_4_PREVIEW,
        UNSUPPORTED
    }

    private MavenRuntime() {
    }

    public static boolean exactEligible(String version) {
        return classify(version) == Line.MAVEN_39_EXACT;
    }

    public static Line classify(String version) {
        Parsed parsed = parse(version);
        if (parsed == null) {
            return Line.UNSUPPORTED;
        }
        if (parsed.major == 3 && parsed.minor == 9 && parsed.qualifier == null) {
            return Line.MAVEN_39_EXACT;
        }
        if (parsed.major == 4 && parsed.qualifier != null) {
            return Line.MAVEN_4_PREVIEW;
        }
        return Line.UNSUPPORTED;
    }

    private static Parsed parse(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        int qualifierAt = qualifierIndex(version);
        String numeric = qualifierAt < 0 ? version : version.substring(0, qualifierAt);
        String qualifier = qualifierAt < 0 ? null : version.substring(qualifierAt + 1);
        if (qualifier != null && qualifier.isEmpty()) {
            return null;
        }
        String[] parts = numeric.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        Integer major = parseSegment(parts[0]);
        Integer minor = parseSegment(parts[1]);
        Integer patch = parseSegment(parts[2]);
        if (major == null || minor == null || patch == null) {
            return null;
        }
        return new Parsed(major.intValue(), minor.intValue(), patch.intValue(), qualifier);
    }

    private static int qualifierIndex(String version) {
        int dash = version.indexOf('-');
        int plus = version.indexOf('+');
        if (dash < 0) {
            return plus;
        }
        if (plus < 0) {
            return dash;
        }
        return Math.min(dash, plus);
    }

    private static Integer parseSegment(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int parsed = 0;
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') {
                return null;
            }
            parsed = parsed * 10 + (digit - '0');
        }
        return Integer.valueOf(parsed);
    }

    private static final class Parsed {
        final int major;
        final int minor;
        final int patch;
        final String qualifier;

        Parsed(int major, int minor, int patch, String qualifier) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.qualifier = qualifier;
        }
    }
}
