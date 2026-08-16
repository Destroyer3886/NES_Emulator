import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class NESDisplayPanel extends JPanel {

    //dimensions of visible screen
    public static final int VIEW_WIDTH = 256;
    public static final int VIEW_HEIGHT = 224;

    //Screen buffer for swing to render
    private final BufferedImage screenImage;
    private final int[] screenPixels;

    public NESDisplayPanel() {
        //Actual size of the "pixels" in pixels
        setPreferredSize(new Dimension(VIEW_WIDTH * 3, VIEW_HEIGHT *3));

        //create screen image and obtain direct write access to it's integer pixel array
        screenImage = new BufferedImage(VIEW_WIDTH, VIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        screenPixels = ((DataBufferInt) screenImage.getRaster().getDataBuffer()).getData();
    }

    //Sets an RGB pixel directly in the final screen buffer. The PPU calls this live,
    //scanline by scanline, as it steps - so what lands here is exactly what real
    //hardware would have raster-scanned at that moment, not a reconstruction after
    //the fact.
    public void setPixel(int x, int y, int rgbColor) {
        if (x < 0 || x >= VIEW_WIDTH || y < 0 || y >= VIEW_HEIGHT) return;
        screenPixels[y * VIEW_WIDTH + x] = rgbColor;
    }

    //Screen buffer is already fully up to date pixel-by-pixel (the PPU wrote directly
    //into it during stepping) - this just triggers Swing to repaint with it.
    public void renderFrame() {
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        //Draw the 256x224 buffer scaled to fill the component dimensions
        g.drawImage(screenImage, 0, 0, getWidth(), getHeight(), null);
    }
}
