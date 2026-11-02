import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class NESDisplayPanel extends JPanel {

    //Full 256x240 PPU output with no overscan crop: the PPU writes scanline y straight to row y, so all
    //240 lines must exist or the bottom two tile rows (e.g. the SMB floor) are silently dropped.
    public static final int VIEW_WIDTH = 256;
    public static final int VIEW_HEIGHT = 240;

    private final BufferedImage screenImage;
    private final int[] screenPixels;

    //Input overlay: dark grey letters that turn white for buttons held on the last
    //controller-1 state applied during the frame being displayed.
    private static final String[] OVERLAY_LETTERS = {"A", "B", "s", "S", "U", "D", "L", "R"};
    private static final int[] OVERLAY_BITS = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80};
    private static final Color OVERLAY_OFF_COLOR = new Color(0x555555);
    private boolean showInputOverlay = false;
    private int overlayBits = 0;
    private int overlayBits2 = 0;

    public NESDisplayPanel() {
        setPreferredSize(new Dimension(VIEW_WIDTH * 3, VIEW_HEIGHT *3));
        //Letterbox bars (full screen, or any size that isn't exactly 256:240) are plain black.
        setBackground(Color.BLACK);
        setOpaque(true);

        screenImage = new BufferedImage(VIEW_WIDTH, VIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        screenPixels = ((DataBufferInt) screenImage.getRaster().getDataBuffer()).getData();
    }

    //The PPU calls this live, scanline by scanline, so the buffer holds exactly what real hardware
    //would have raster-scanned at that moment, not an after-the-fact reconstruction.
    public void setPixel(int x, int y, int rgbColor) {
        if (x < 0 || x >= VIEW_WIDTH || y < 0 || y >= VIEW_HEIGHT) return;
        screenPixels[y * VIEW_WIDTH + x] = rgbColor;
    }

    //The PPU already wrote every pixel directly during stepping; this just asks Swing to repaint.
    public void renderFrame() {
        repaint();
    }

    //TAS Maker greenzone support: a restored checkpoint doesn't touch these pixels (they aren't in
    //Bus.EmulatorState), so landing exactly on a checkpoint would leave the previous frame on screen.
    //Checkpoints therefore capture/restore this buffer too.
    public int[] snapshotPixels() {
        return screenPixels.clone();
    }

    public void restorePixels(int[] pixels) {
        System.arraycopy(pixels, 0, screenPixels, 0, screenPixels.length);
    }

    //Precise Tracing mirrors the live framebuffer into its own window; read-only use.
    public BufferedImage getScreenImage() {
        return screenImage;
    }

    public void setShowInputOverlay(boolean show) {
        showInputOverlay = show;
        repaint();
    }

    //Called once per displayed frame with the LAST controller 1/2 bytes applied that frame
    //(mid-frame strobes, e.g. .r08 ACE mode, can apply several; only the final ones are shown).
    public void setOverlayBits(int bits1, int bits2) {
        overlayBits = bits1;
        overlayBits2 = bits2;
    }

    //Largest 256:240 rectangle that fits inside the component, centered. In the normal
    //window this is the whole panel; in full screen the leftover area becomes black bars.
    public Rectangle getImageBounds() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        int dw, dh;
        if ((long) w * VIEW_HEIGHT <= (long) h * VIEW_WIDTH) {
            //Width is the limiting dimension - bars top and bottom
            dw = w;
            dh = Math.max(1, (int) ((long) w * VIEW_HEIGHT / VIEW_WIDTH));
        } else {
            //Height is the limiting dimension - bars left and right
            dh = h;
            dw = Math.max(1, (int) ((long) h * VIEW_WIDTH / VIEW_HEIGHT));
        }
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    //Maps a component-space point (e.g. a mouse click) to NES pixel coordinates, taking the
    //letterbox offset into account. Returns null for a point on the black bars.
    public Point toNesPixel(int px, int py) {
        Rectangle r = getImageBounds();
        if (!r.contains(px, py)) return null;
        int nx = (px - r.x) * VIEW_WIDTH / r.width;
        int ny = (py - r.y) * VIEW_HEIGHT / r.height;
        return new Point(Math.min(nx, VIEW_WIDTH - 1), Math.min(ny, VIEW_HEIGHT - 1));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Rectangle r = getImageBounds();
        g.drawImage(screenImage, r.x, r.y, r.width, r.height, null);
        if (showInputOverlay) paintInputOverlay(g, r);
    }

    private void paintInputOverlay(Graphics g, Rectangle image) {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, 16);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int spaceWidth = fm.stringWidth(" ");
        int groupWidth = groupTextWidth(fm, spaceWidth);
        int pad = 3;
        //Anchored to the top-left of the game image, not the panel, so it stays on the
        //picture rather than on a letterbox bar.
        int x = image.x + 6;
        int y = image.y + fm.getAscent() + 4;
        int boxTop = y - fm.getAscent() - pad + 3;
        int boxHeight = fm.getAscent() + fm.getDescent() + pad * 2 - 3;

        g.setColor(Color.BLACK);
        g.fillRect(x - pad, boxTop, groupWidth + pad * 2, boxHeight);
        int x2 = x + groupWidth + spaceWidth; //gap separating the two controllers - no box here
        g.fillRect(x2 - pad, boxTop, groupWidth + pad * 2, boxHeight);

        drawOverlayLetters(g, fm, x, y, overlayBits, spaceWidth);
        drawOverlayLetters(g, fm, x2, y, overlayBits2, spaceWidth);
    }

    //Width of one controller's rendered letter row (letters plus the spaces between them, not
    //counting a trailing space).
    private int groupTextWidth(FontMetrics fm, int spaceWidth) {
        int width = 0;
        for (int i = 0; i < OVERLAY_LETTERS.length; i++) {
            width += fm.stringWidth(OVERLAY_LETTERS[i]);
            if (i < OVERLAY_LETTERS.length - 1) width += spaceWidth;
        }
        return width;
    }

    private void drawOverlayLetters(Graphics g, FontMetrics fm, int x, int y, int bits, int spaceWidth) {
        for (int i = 0; i < OVERLAY_LETTERS.length; i++) {
            boolean pressed = (bits & OVERLAY_BITS[i]) != 0;
            g.setColor(pressed ? Color.WHITE : OVERLAY_OFF_COLOR);
            g.drawString(OVERLAY_LETTERS[i], x, y);
            x += fm.stringWidth(OVERLAY_LETTERS[i]);
            if (i < OVERLAY_LETTERS.length - 1) x += spaceWidth;
        }
    }
}
