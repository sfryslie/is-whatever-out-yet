import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import javax.imageio.ImageIO;

// Regenerates every "iwoy?" mark from the bundled IBM Plex Mono SemiBold, mirroring
// icons/icon.svg (font-weight 600, font-size 130/512, letter-spacing -2px, centered)
// but with the "?" in the accent purple (--other, #8f8cf0) instead of green.
public class IconGen {
    static final Color BG = new Color(0x0d0f12);
    static final Color TEXT = new Color(0xe6e9ee);
    static final Color ACCENT = new Color(0x8f8cf0);

    public static void main(String[] args) throws Exception {
        Font base = Font.createFont(Font.TRUETYPE_FONT, new File(args[0]));
        String root = args[1];

        // Site / PWA / favicon — same proportions as icon.svg (130px text on 512).
        gen(base, 512, 130f, false, root + "/icons/icon-512.png");
        gen(base, 192, 48.75f, false, root + "/icons/icon-192.png");
        gen(base, 180, 45.7f, false, root + "/icons/icon-180.png");
        gen(base, 32, 8.125f, false, root + "/icons/favicon-32.png");

        // Desktop window icon + legacy Android launcher icon (512, same as the site mark).
        gen(base, 512, 130f, false, root + "/app/composeApp/src/commonMain/composeResources/drawable/app_icon.png");
        gen(base, 512, 130f, false, root + "/app/composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png");

        // Android adaptive-icon foreground: 432px layer (108dp @ xxxhdpi), transparent bg,
        // text sized for the ~264px safe zone so launchers show it big instead of shrinking
        // the whole square inside their mask.
        gen(base, 432, 80f, true, root + "/app/composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png");

        // iOS app icon: 1024, opaque (iOS rejects alpha), same proportions as the site mark.
        gen(base, 1024, 260f, false, root + "/app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png");

        System.out.println("done");
    }

    static void gen(Font base, int size, float fontPx, boolean transparent, String outPath) throws Exception {
        BufferedImage img = new BufferedImage(
            size, size, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        if (!transparent) {
            g.setColor(BG);
            g.fillRect(0, 0, size, size);
        }
        HashMap<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, fontPx);
        attrs.put(TextAttribute.TRACKING, -2.0 / 130.0); // svg's -2px at 130px, em-relative
        g.setFont(base.deriveFont(attrs));
        FontMetrics fm = g.getFontMetrics();
        String word = "iwoy";
        String q = "?";
        int wordWidth = fm.stringWidth(word);
        int total = wordWidth + fm.stringWidth(q);
        float x = (size - total) / 2f;
        float y = size / 2f + (fm.getAscent() - fm.getDescent()) / 2f;
        g.setColor(TEXT);
        g.drawString(word, x, y);
        g.setColor(ACCENT);
        g.drawString(q, x + wordWidth, y);
        g.dispose();
        File out = new File(outPath);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + outPath);
    }
}
