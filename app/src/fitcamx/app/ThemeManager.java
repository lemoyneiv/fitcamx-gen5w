package fitcamx.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;

/**
 * Material Design 3 (M3) Tonal Theme Engine & API 19 Shape/State-Layer Factory.
 *
 * Implements M3 semantic color roles (tonal surface hierarchy, primary/secondary/tertiary
 * containers, outlines, harmonized status roles) and geometric shape tokens (full stadium
 * pills, 8dp filter chips, 12dp-20dp surface cards, M3 state-layer press feedback) using
 * zero external dependencies on Android 4.4.2 KitKat (API 19).
 */
public final class ThemeManager {
    private static final String PREFS_NAME = "fitcamx_theme_prefs";
    private static final String KEY_THEME_ID = "selected_theme_id";

    public static final String ID_OCEAN_BLUE = "ocean_blue";
    public static final String ID_IONIQ_TEAL = "ioniq_teal";
    public static final String ID_M3_VIOLET = "m3_violet";
    public static final String ID_KIA_CRIMSON = "kia_crimson";
    public static final String ID_GENESIS_COPPER = "genesis_copper";
    public static final String ID_DAYLIGHT = "daylight";

    // Fixed high-contrast colors for text/controls sitting atop dark smoked-glass video overlays
    public static final int OVERLAY_TEXT_PRIMARY = 0xFFF2F5F9;
    public static final int OVERLAY_TEXT_SECONDARY = 0xFFCBD5E1;
    public static final int OVERLAY_BORDER = 0x33FFFFFF;
    public static final int OVERLAY_POSITIVE = 0xFF6DD58C;
    public static final int OVERLAY_WARNING = 0xFFFFB951;

    public static final class Palette {
        public final String id;
        public final String displayName;
        public final boolean isLight;

        // M3 Tonal Surface Hierarchy
        public final int surface;
        public final int surfaceContainerLow;
        public final int surfaceContainer;
        public final int surfaceContainerHigh;
        public final int surfaceContainerHighest;

        // M3 Accent & Tonal Containers
        public final int primary;
        public final int onPrimary;
        public final int primaryContainer;
        public final int onPrimaryContainer;
        public final int secondaryContainer;
        public final int onSecondaryContainer;
        public final int tertiary;
        public final int tertiaryContainer;
        public final int onTertiaryContainer;

        // M3 Text & Outlines
        public final int onSurface;
        public final int onSurfaceVariant;
        public final int outline;
        public final int outlineVariant;

        // M3 Error & Status Roles
        public final int error;
        public final int onError;
        public final int errorContainer;
        public final int onErrorContainer;
        public final int statusPositive;
        public final int statusWarning;
        public final int scrimOverlay;

        public Palette(
                String id, String displayName, boolean isLight,
                int surface, int surfaceContainerLow, int surfaceContainer,
                int surfaceContainerHigh, int surfaceContainerHighest,
                int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                int secondaryContainer, int onSecondaryContainer,
                int tertiary, int tertiaryContainer, int onTertiaryContainer,
                int onSurface, int onSurfaceVariant, int outline, int outlineVariant,
                int error, int onError, int errorContainer, int onErrorContainer,
                int statusPositive, int statusWarning, int scrimOverlay) {
            this.id = id;
            this.displayName = displayName;
            this.isLight = isLight;
            this.surface = surface;
            this.surfaceContainerLow = surfaceContainerLow;
            this.surfaceContainer = surfaceContainer;
            this.surfaceContainerHigh = surfaceContainerHigh;
            this.surfaceContainerHighest = surfaceContainerHighest;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.secondaryContainer = secondaryContainer;
            this.onSecondaryContainer = onSecondaryContainer;
            this.tertiary = tertiary;
            this.tertiaryContainer = tertiaryContainer;
            this.onTertiaryContainer = onTertiaryContainer;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.outline = outline;
            this.outlineVariant = outlineVariant;
            this.error = error;
            this.onError = onError;
            this.errorContainer = errorContainer;
            this.onErrorContainer = onErrorContainer;
            this.statusPositive = statusPositive;
            this.statusWarning = statusWarning;
            this.scrimOverlay = scrimOverlay;
        }
    }

    private static final Palette[] PALETTES = new Palette[] {
        new Palette(
            ID_OCEAN_BLUE, "Ocean Blue", false,
            0xFF0F1419, 0xFF171C22, 0xFF1E242C, 0xFF28303A, 0xFF333C48,
            0xFF9ECAFF, 0xFF003258, 0xFF00497D, 0xFFD1E4FF,
            0xFF33475B, 0xFFD5E4F7,
            0xFFF5BC70, 0xFF563E1D, 0xFFFFDDB5,
            0xFFE1E6EE, 0xFFB8C2CE, 0xFF6B7684, 0xFF323B46,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF6DD58C, 0xFFFFB951, 0xCC10141A
        ),
        new Palette(
            ID_IONIQ_TEAL, "Ioniq Teal", false,
            0xFF0D1415, 0xFF151D1F, 0xFF1C2628, 0xFF263235, 0xFF313F42,
            0xFF7FD8E2, 0xFF00363C, 0xFF004F57, 0xFFA2F0F9,
            0xFF2F4B4F, 0xFFCCE8EC,
            0xFFB4C8FF, 0xFF354666, 0xFFDCE2FF,
            0xFFDDE4E5, 0xFFB5C4C6, 0xFF677779, 0xFF334042,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF6DD58C, 0xFFFFB951, 0xCC0E1517
        ),
        new Palette(
            ID_M3_VIOLET, "M3 Violet", false,
            0xFF141218, 0xFF1D1B22, 0xFF24212B, 0xFF2F2B38, 0xFF3A3645,
            0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF,
            0xFF4A4458, 0xFFE8DEF8,
            0xFFEFB8C8, 0xFF633B48, 0xFFFFD8E4,
            0xFFE6E0E9, 0xFFCAC4D0, 0xFF79747E, 0xFF443F4C,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF6DD58C, 0xFFFFB951, 0xCC141218
        ),
        new Palette(
            ID_KIA_CRIMSON, "Kia Crimson", false,
            0xFF171112, 0xFF201819, 0xFF281E20, 0xFF34282A, 0xFF403235,
            0xFFFFB3B4, 0xFF680016, 0xFF920023, 0xFFFFDAD9,
            0xFF5B3F41, 0xFFFFDAD9,
            0xFFEFC085, 0xFF5B4219, 0xFFFFDDB4,
            0xFFEFDFE0, 0xFFD6C2C3, 0xFF857374, 0xFF4C3C3D,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF6DD58C, 0xFFFFB951, 0xCC171112
        ),
        new Palette(
            ID_GENESIS_COPPER, "Genesis Copper", false,
            0xFF16120E, 0xFF1F1A15, 0xFF27211B, 0xFF332B24, 0xFF3E352D,
            0xFFF5BC70, 0xFF462A00, 0xFF643F00, 0xFFFFDDB5,
            0xFF534433, 0xFFFDDFA6,
            0xFFB8CEA2, 0xFF3B4D2B, 0xFFD4EABB,
            0xFFECE0D6, 0xFFD2C4B5, 0xFF827568, 0xFF4A4035,
            0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
            0xFF6DD58C, 0xFFFFB951, 0xCC16120E
        ),
        new Palette(
            ID_DAYLIGHT, "Daylight (Light)", true,
            0xFFF4FAFB, 0xFFEAF2F3, 0xFFDFE9EB, 0xFFD4E1E3, 0xFFC8D7DA,
            0xFF006870, 0xFFFFFFFF, 0xFF9CF0F8, 0xFF001F23,
            0xFFC4E7EB, 0xFF072023,
            0xFF4B5C7E, 0xFFD3E3FF, 0xFF051937,
            0xFF171D1E, 0xFF3F484A, 0xFF6F797B, 0xFFB8C5C7,
            0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
            0xFF146C2E, 0xFF8F4E00, 0xCC111619
        )
    };

    private ThemeManager() {}

    public static Palette[] getPalettes() {
        return PALETTES.clone();
    }

    public static Palette getDefaultPalette() {
        return PALETTES[0];
    }

    public static Palette getPaletteById(String id) {
        if (id != null) {
            for (Palette p : PALETTES) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
        }
        return PALETTES[0];
    }

    public static int indexOfPalette(String id) {
        if (id != null) {
            for (int i = 0; i < PALETTES.length; i++) {
                if (PALETTES[i].id.equals(id)) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static String nextPaletteId(String currentId) {
        int nextIdx = (indexOfPalette(currentId) + 1) % PALETTES.length;
        return PALETTES[nextIdx].id;
    }

    public static Palette loadSavedPalette(Context ctx) {
        if (ctx == null) return PALETTES[0];
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedId = prefs != null ? prefs.getString(KEY_THEME_ID, ID_OCEAN_BLUE) : ID_OCEAN_BLUE;
            return getPaletteById(savedId);
        } catch (Throwable ignored) {
            return PALETTES[0];
        }
    }

    public static void savePalette(Context ctx, Palette palette) {
        if (ctx == null || palette == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs != null) {
                prefs.edit().putString(KEY_THEME_ID, palette.id).apply();
            }
        } catch (Throwable ignored) {}
    }

    // --- Pure Color & Contrast Math (Host-JVM Unit-Testable) ---

    public static int withAlpha(int argb, int alpha0To255) {
        int a = Math.max(0, Math.min(255, alpha0To255));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /**
     * Blends overlayArgb over baseArgb using ratio (0.0 = 100% base, 1.0 = 100% overlay),
     * preserving the base alpha channel (unless base is transparent, in which case ratio*255 is used).
     */
    public static int blendArgb(int baseArgb, int overlayArgb, float ratio) {
        float t = Math.max(0.0f, Math.min(1.0f, ratio));
        int baseA = (baseArgb >>> 24) & 0xFF;
        if (baseA == 0) {
            return withAlpha(overlayArgb, Math.round(t * 255.0f));
        }
        float inv = 1.0f - t;
        int r = Math.round(((baseArgb >>> 16) & 0xFF) * inv + ((overlayArgb >>> 16) & 0xFF) * t);
        int g = Math.round(((baseArgb >>> 8) & 0xFF) * inv + ((overlayArgb >>> 8) & 0xFF) * t);
        int b = Math.round((baseArgb & 0xFF) * inv + (overlayArgb & 0xFF) * t);
        return (baseA << 24)
                | (Math.min(255, Math.max(0, r)) << 16)
                | (Math.min(255, Math.max(0, g)) << 8)
                | Math.min(255, Math.max(0, b));
    }

    public static double relativeLuminance(int argb) {
        double r = srgbChannelToLinear(((argb >>> 16) & 0xFF) / 255.0);
        double g = srgbChannelToLinear(((argb >>> 8) & 0xFF) / 255.0);
        double b = srgbChannelToLinear((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double srgbChannelToLinear(double c) {
        return (c <= 0.03928) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static double contrastRatio(int fgArgb, int bgArgb) {
        double l1 = relativeLuminance(fgArgb);
        double l2 = relativeLuminance(bgArgb);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    // --- API 19 Native Material 3 Drawable & Component Styling ---

    public static int dp(Context ctx, float dpVal) {
        if (ctx == null || ctx.getResources() == null) {
            return Math.round(dpVal * 1.5f);
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float density = (dm != null && dm.density > 0f) ? dm.density : 1.5f;
        return Math.round(dpVal * density);
    }

    public static void setBackgroundKeepPadding(View v, Drawable bg) {
        if (v == null) return;
        int pl = v.getPaddingLeft();
        int pt = v.getPaddingTop();
        int pr = v.getPaddingRight();
        int pb = v.getPaddingBottom();
        v.setBackground(bg);
        v.setPadding(pl, pt, pr, pb);
    }

    private static GradientDrawable makeRoundedRect(int fillColor, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setColor(fillColor);
        if (strokePx > 0) {
            gd.setStroke(strokePx, strokeColor);
        }
        return gd;
    }

    private static ColorStateList makeButtonTextStateList(int normalTextColor) {
        int disabledTextColor = withAlpha(normalTextColor, 102);
        return new ColorStateList(
                new int[][] {
                    new int[] { -android.R.attr.state_enabled },
                    new int[] {}
                },
                new int[] {
                    disabledTextColor,
                    normalTextColor
                }
        );
    }

    public static void styleSurfaceCard(Context ctx, View view, int fillColor, float cornerDp,
                                        int strokePx, int strokeColor) {
        if (view == null) return;
        GradientDrawable gd = makeRoundedRect(fillColor, dp(ctx, cornerDp), strokePx, strokeColor);
        setBackgroundKeepPadding(view, gd);
    }

    public static void styleInteractiveCard(Context ctx, View view, int fillColor, int rippleTint,
                                            float cornerDp, int strokePx, int strokeColor) {
        if (view == null) return;
        float r = dp(ctx, cornerDp);
        GradientDrawable pressed = makeRoundedRect(blendArgb(fillColor, rippleTint, 0.14f), r, strokePx, strokeColor);
        GradientDrawable normal = makeRoundedRect(fillColor, r, strokePx, strokeColor);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed }, pressed);
        sld.addState(new int[] {}, normal);
        setBackgroundKeepPadding(view, sld);
    }

    public static void stylePillButton(Context ctx, Button btn, int fillColor, int textColor,
                                       int strokePx, int strokeColor, boolean bold) {
        if (btn == null) return;
        float pillRadius = dp(ctx, 999f);
        int disabledFill = (((fillColor >>> 24) & 0xFF) == 0)
                ? 0x00000000
                : withAlpha(fillColor, 92);
        int disabledStroke = (strokePx > 0) ? withAlpha(strokeColor, 76) : 0;
        int pressedFill = blendArgb(fillColor, textColor, 0.16f);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { -android.R.attr.state_enabled },
                makeRoundedRect(disabledFill, pillRadius, strokePx, disabledStroke));
        sld.addState(new int[] { android.R.attr.state_pressed },
                makeRoundedRect(pressedFill, pillRadius, strokePx, strokeColor));
        sld.addState(new int[] {},
                makeRoundedRect(fillColor, pillRadius, strokePx, strokeColor));

        setBackgroundKeepPadding(btn, sld);
        btn.setTextColor(makeButtonTextStateList(textColor));
        btn.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    public static void styleNavRailButton(Context ctx, Button btn, Palette p, String label, boolean selected) {
        if (btn == null || p == null) return;
        if (selected) {
            btn.setText("\u25CF  " + label);
            stylePillButton(ctx, btn, p.secondaryContainer, p.onSecondaryContainer, 0, 0, true);
        } else {
            btn.setText("    " + label);
            stylePillButton(ctx, btn, 0x00000000, p.onSurfaceVariant, 0, 0, false);
        }
    }

    public static void styleFilterChip(Context ctx, Button btn, Palette p, String baseLabel, boolean selected) {
        if (btn == null || p == null) return;
        float chipRadius = dp(ctx, 8f);
        int strokePx = selected ? 0 : Math.max(1, dp(ctx, 1f));
        int fill = selected ? p.secondaryContainer : 0x00000000;
        int text = selected ? p.onSecondaryContainer : p.onSurfaceVariant;
        int stroke = selected ? 0 : p.outline;
        int pressedFill = selected
                ? blendArgb(p.secondaryContainer, p.onSecondaryContainer, 0.15f)
                : blendArgb(p.surfaceContainerLow, p.onSurface, 0.12f);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed },
                makeRoundedRect(pressedFill, chipRadius, strokePx, stroke));
        sld.addState(new int[] {},
                makeRoundedRect(fill, chipRadius, strokePx, stroke));

        setBackgroundKeepPadding(btn, sld);
        btn.setTextColor(text);
        btn.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        btn.setText(selected ? ("\u2713 " + baseLabel) : baseLabel);
    }

    public static void styleClipRowCard(Context ctx, View rowView, Palette p, boolean isSelected) {
        if (rowView == null || p == null) return;
        float radius = dp(ctx, 12f);
        int baseFill = isSelected ? p.secondaryContainer : p.surfaceContainer;
        int pressTint = isSelected ? p.onSecondaryContainer : p.onSurface;
        int strokePx = isSelected ? Math.max(1, dp(ctx, 1f)) : 0;
        int strokeColor = isSelected ? withAlpha(p.primary, 140) : 0;

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed },
                makeRoundedRect(blendArgb(baseFill, pressTint, 0.14f), radius, strokePx, strokeColor));
        sld.addState(new int[] {},
                makeRoundedRect(baseFill, radius, strokePx, strokeColor));

        setBackgroundKeepPadding(rowView, sld);
    }

    public static CharSequence buildThemeButtonLabel(Palette p) {
        if (p == null) return "Theme";
        String prefix = "\u25CF ";
        String full = prefix + p.displayName;
        SpannableString ss = new SpannableString(full);
        ss.setSpan(new ForegroundColorSpan(p.primary), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    public static void styleSeekBar(Context ctx, SeekBar sb, int trackBgColor, int progressColor) {
        if (sb == null) return;
        float trackRadius = dp(ctx, 4f);

        GradientDrawable bgTrack = makeRoundedRect(trackBgColor, trackRadius, 0, 0);
        GradientDrawable fgTrack = makeRoundedRect(progressColor, trackRadius, 0, 0);
        ClipDrawable progressClip = new ClipDrawable(fgTrack, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[] { bgTrack, progressClip });
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        int curProgress = sb.getProgress();
        sb.setProgressDrawable(layers);

        int normalSize = dp(ctx, 16f);
        GradientDrawable thumbNormal = new GradientDrawable();
        thumbNormal.setShape(GradientDrawable.OVAL);
        thumbNormal.setSize(normalSize, normalSize);
        thumbNormal.setColor(progressColor);

        int pressedSize = dp(ctx, 20f);
        GradientDrawable thumbPressed = new GradientDrawable();
        thumbPressed.setShape(GradientDrawable.OVAL);
        thumbPressed.setSize(pressedSize, pressedSize);
        thumbPressed.setColor(progressColor);
        thumbPressed.setStroke(Math.max(2, dp(ctx, 2f)), 0xCCFFFFFF);

        int disabledSize = dp(ctx, 10f);
        GradientDrawable thumbDisabled = new GradientDrawable();
        thumbDisabled.setShape(GradientDrawable.OVAL);
        thumbDisabled.setSize(disabledSize, disabledSize);
        thumbDisabled.setColor(withAlpha(progressColor, 90));

        StateListDrawable thumbStates = new StateListDrawable();
        thumbStates.addState(new int[] { -android.R.attr.state_enabled }, thumbDisabled);
        thumbStates.addState(new int[] { android.R.attr.state_pressed }, thumbPressed);
        thumbStates.addState(new int[] {}, thumbNormal);

        sb.setThumb(thumbStates);
        sb.setThumbOffset(normalSize / 2);

        // Force Android 4.4 ProgressBar ClipDrawable level refresh
        sb.setProgress(0);
        sb.setProgress(curProgress);
    }

    public static void styleHorizontalProgressBar(Context ctx, ProgressBar pb, int trackBgColor, int progressColor) {
        if (pb == null) return;
        float trackRadius = dp(ctx, 4f);
        GradientDrawable bgTrack = makeRoundedRect(trackBgColor, trackRadius, 0, 0);
        GradientDrawable fgTrack = makeRoundedRect(progressColor, trackRadius, 0, 0);
        ClipDrawable progressClip = new ClipDrawable(fgTrack, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[] { bgTrack, progressClip });
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        int cur = pb.getProgress();
        pb.setProgressDrawable(layers);
        pb.setProgress(0);
        pb.setProgress(cur);
    }
}
