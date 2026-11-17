package ui;

import org.eclipse.swt.graphics.RGB;

import java.time.LocalTime;
import java.util.Locale;

/** Exact palette data from native/ui/ThemePalette.swift, ported to SWT. */
public final class SwiftPalette {

public enum Variant { NIGHT, DAWN, DAY, DUSK;

        static Variant parse(String raw) {
            if (raw == null) return fromHour(LocalTime.now().getHour());
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "night" -> NIGHT;
                case "dawn" -> DAWN;
                case "day" -> DAY;
                case "dusk" -> DUSK;
                default -> fromHour(LocalTime.now().getHour());
            };
        }

        static Variant fromHour(int hour) {
            if (hour >= 5 && hour < 8) return DAWN;
            if (hour >= 8 && hour < 17) return DAY;
            if (hour >= 17 && hour < 20) return DUSK;
            return NIGHT;
        }
    }

    // scene order: skyTop, skyHorizon, ridgeFar, ridgeMid, ridgeNear,
    //             lakeDeep, lakeGlint, ground, foliage, shadow
    // chrome order: chromeDeep, chromeRaised, cardFill, cardFill2,
    //               separator, textPrimary, textMuted, textFaint
    private final RGB accent, accentPair, ink;
    private final RGB[] nightScene, dayScene, dawnScene, duskScene;
    private final RGB[] nightChrome, dayChrome, dawnChrome, duskChrome;
    final String key;

    private SwiftPalette(String key, String accent, String accentPair, String ink,
                         String[] night, String[] day,
                         String[] dawn, String[] dusk,
                         String[] nightChrome, String[] dayChrome,
                         String[] dawnChrome, String[] duskChrome) {
        this.key = key;
        this.accent = rgb(accent);
        this.accentPair = rgb(accentPair);
        this.ink = rgb(ink);
        this.nightScene = parse(night);
        this.dayScene = parse(day);
        this.dawnScene = dawn == null ? deriveDawn(nightScene, dayScene) : parse(dawn);
        this.duskScene = dusk == null ? deriveDusk(nightScene, dayScene) : parse(dusk);
        this.nightChrome = parse(nightChrome);
        this.dayChrome = parse(dayChrome);
        this.dawnChrome = dawnChrome == null ? this.nightChrome : parse(dawnChrome);
        this.duskChrome = duskChrome == null ? this.nightChrome : parse(duskChrome);
    }

    static RGB[] parse(String[] hexes) {
        RGB[] out = new RGB[hexes.length];
        for (int i = 0; i < hexes.length; i++) out[i] = rgb(hexes[i]);
        return out;
    }

    static RGB rgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new RGB(Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16));
    }

    private static RGB[] deriveDawn(RGB[] n, RGB[] d) {
        RGB[] out = d.clone();
        out[0] = mix(n[0], d[0], 0.30);
        return out;
    }

    private static RGB[] deriveDusk(RGB[] n, RGB[] d) {
        RGB[] out = n.clone();
        out[1] = mix(n[1], d[1], 0.5);
        out[2] = mix(n[2], d[2], 0.30);
        out[6] = d[6];
        return out;
    }

    static RGB mix(RGB a, RGB b, double f) {
        return new RGB((int) Math.round(a.red + (b.red - a.red) * f),
                (int) Math.round(a.green + (b.green - a.green) * f),
                (int) Math.round(a.blue + (b.blue - a.blue) * f));
    }

    static RGB scale(RGB c, double f) {
        if (f <= 1) return new RGB((int) (c.red * f), (int) (c.green * f), (int) (c.blue * f));
        double lift = f - 1;
        return new RGB((int) (c.red + (255 - c.red) * lift),
                (int) (c.green + (255 - c.green) * lift),
                (int) (c.blue + (255 - c.blue) * lift));
    }

    RGB[] scene(Variant v) {
        return switch (v) {
            case NIGHT -> nightScene;
            case DAWN -> dawnScene;
            case DAY -> dayScene;
            case DUSK -> duskScene;
        };
    }

    RGB[] chrome(Variant v) {
        return switch (v) {
            case NIGHT -> nightChrome;
            case DAWN -> dawnChrome;
            case DAY -> dayChrome;
            case DUSK -> duskChrome;
        };
    }

    RGB accent() { return accent; }
    RGB accentPair() { return accentPair; }
    RGB ink() { return ink; }

    private static final String[] PINE_NIGHT = {"#07120D", "#23402F", "#2E5240", "#1B352A", "#0F2018", "#0A1C16", "#BFE6C9", "#0A150F", "#143424", "#060B08"};
    private static final String[] PINE_DAWN = {"#1A1520", "#E8A05E", "#4A6050", "#2E4438", "#1A2E20", "#1A3028", "#FFE4B8", "#1A2A1E", "#2A4A34", "#0E1810"};
    private static final String[] PINE_DAY = {"#5E93B8", "#E4D9B4", "#6E8F77", "#476B52", "#2C4A34", "#3E6E7E", "#FFF3D0", "#24402C", "#356043", "#1B2E20"};
    private static final String[] PINE_DUSK = {"#2A1A30", "#E8734C", "#5A5048", "#3A3430", "#222018", "#2A2420", "#FFB890", "#2A2018", "#3A3028", "#1A140E"};

    private static final String[] PINE_CHROME_NIGHT = {"#0A0D0C", "#121615", "#15251E", "#1C2E26", "#333B38", "#F0EDE2", "#98A098", "#636B64"};
    private static final String[] PINE_CHROME_DAWN = {"#1A1520", "#2A2430", "#2A2030", "#352A3C", "#4A4050", "#FFE8D0", "#C0A890", "#8A7868"};
    private static final String[] PINE_CHROME_DAY = {"#2A231C", "#3A3128", "#31291F", "#3A3126", "#4A4438", "#FFF8EA", "#C9BCA3", "#948874"};
    private static final String[] PINE_CHROME_DUSK = {"#1A1218", "#282028", "#2A2028", "#342830", "#484048", "#F8E8DC", "#B8A098", "#807068"};

    private static final String[] TAIGA_NIGHT = {"#0A1118", "#2A4258", "#3A5A78", "#28405A", "#182838", "#142830", "#B0D8E8", "#1A2830", "#223C48", "#0A1218"};
    private static final String[] TAIGA_DAY = {"#88B4D8", "#E0ECE8", "#88A0B4", "#607C94", "#405870", "#507890", "#F0F8FC", "#384C58", "#486474", "#283848"};
    private static final String[] TAIGA_CHROME_NIGHT = {"#0A1014", "#141C22", "#182430", "#20303C", "#384850", "#E8F0F4", "#90A4B0", "#5A6E7A"};
    private static final String[] TAIGA_CHROME_DAY = {"#1E2A34", "#2A3844", "#243440", "#2C3C48", "#445462", "#F0F4F8", "#B0C0CC", "#80909C"};

    private static final String[] SUMMIT_NIGHT = {"#0C1318", "#2A4157", "#2F4E6B", "#20364B", "#132030", "#0F1E2B", "#CBE4F0", "#0C1620", "#17324A", "#070D12"};
    private static final String[] SUMMIT_DAY = {"#6FA3D8", "#F2D8A8", "#7C93A8", "#57708A", "#394F63", "#4E7E96", "#FFF6DB", "#2E4250", "#42607A", "#22313C"};

    private static final String[] DUSK_NIGHT = {"#120E1A", "#33204A", "#3D2A57", "#291E3D", "#1A1326", "#171023", "#E4D2F2", "#120C1C", "#241836", "#090609"};
    private static final String[] DUSK_DAY = {"#8E7FC8", "#F0CDB4", "#8A7FB0", "#645A8E", "#453D66", "#6A5E92", "#FFF0DC", "#352D4E", "#4D4272", "#282236"};
    private static final String[] DUSK_CHROME_NIGHT = {"#0E0A15", "#161020", "#1C1528", "#241B33", "#3A3045", "#F3EFFA", "#A39AB0", "#6A6078"};

    private static final String[] GLACIER_NIGHT = {"#0A141C", "#1E3550", "#29445F", "#1A2E42", "#101E2B", "#0D1B27", "#D4ECF4", "#0A141C", "#153045", "#060D12"};
    private static final String[] GLACIER_DAY = {"#7FB4DC", "#EDEBD8", "#8AA4BC", "#5F7A96", "#3D556B", "#54849C", "#FFFCE8", "#30465A", "#47687F", "#223242"};
    private static final String[] GLACIER_CHROME_NIGHT = {"#0A0F14", "#111820", "#14202B", "#1A2835", "#32404C", "#EDF3F8", "#98A8B4", "#5E6E7A"};

    private static final String[] EMBER_NIGHT = {"#161010", "#3B1F18", "#4A2A1F", "#2E1D16", "#1C1310", "#170F0C", "#F2D4BC", "#140D0A", "#241714", "#0A0605"};
    private static final String[] EMBER_DAY = {"#B87F5E", "#F2D3A8", "#A87F66", "#7A5644", "#563C30", "#8A6A56", "#FFF3DC", "#443126", "#5E463A", "#30221C"};
    private static final String[] EMBER_CHROME_NIGHT = {"#110D0B", "#1A1512", "#1F1713", "#281E18", "#453A31", "#F8F0E6", "#AD9E8E", "#71645A"};

public static SwiftPalette get(String key) {
        if (key == null) key = "pine";
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "taiga" -> new SwiftPalette("taiga", "#A8C8DC", "#D8E8F0", "#F5F8FA",
                    TAIGA_NIGHT, TAIGA_DAY, null, null,
                    TAIGA_CHROME_NIGHT, TAIGA_CHROME_DAY, null, null);
            case "summit" -> new SwiftPalette("summit", "#E8A25E", "#E8C07D", "#F5F3E7",
                    SUMMIT_NIGHT, SUMMIT_DAY, null, null,
                    PINE_CHROME_NIGHT, PINE_CHROME_DAY, null, null);
            case "dusk" -> new SwiftPalette("dusk", "#B583E8", "#E8A2C0", "#F5F3E7",
                    DUSK_NIGHT, DUSK_DAY, null, null,
                    DUSK_CHROME_NIGHT, PINE_CHROME_DAY, null, null);
            case "glacier" -> new SwiftPalette("glacier", "#7FD1E8", "#BFA2E8", "#F5F3E7",
                    GLACIER_NIGHT, GLACIER_DAY, null, null,
                    GLACIER_CHROME_NIGHT, PINE_CHROME_DAY, null, null);
            case "ember" -> new SwiftPalette("ember", "#E8734C", "#E8B25E", "#F5F3E7",
                    EMBER_NIGHT, EMBER_DAY, null, null,
                    EMBER_CHROME_NIGHT, PINE_CHROME_DAY, null, null);
            default -> new SwiftPalette("pine", "#8FBC6F", "#C9E3A8", "#F5F3E7",
                    PINE_NIGHT, PINE_DAY, PINE_DAWN, PINE_DUSK,
                    PINE_CHROME_NIGHT, PINE_CHROME_DAY, PINE_CHROME_DAWN, PINE_CHROME_DUSK);
        };
    }
}
