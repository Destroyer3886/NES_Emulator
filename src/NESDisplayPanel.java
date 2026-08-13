import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class NESDisplayPanel extends JPanel {

    //dimensions of visible screen
    public static final int VIEW_WIDTH = 256;
    public static final int VIEW_HEIGHT = 224;

    //Size of nametables in V-RAM
    public static final int WORLD_WIDTH = 512;
    public static final int WORLD_HEIGHT = 480;

    //Virtual VRAM/Nametable full pixel array (512x480)
    private final int[] worldPixels = new int[WORLD_WIDTH * WORLD_HEIGHT];

    //Screen buffer for swing to render
    private final BufferedImage screenImage;
    private final int[] screenPixels;

    private int scrollX = 0;
    private int scrollY = 0;

    public NESDisplayPanel() {
        //Actual size of the "pixels" in pixels
        setPreferredSize(new Dimension(VIEW_WIDTH * 3, VIEW_HEIGHT *3));

        //create screen image and obtain direct write access to it's integer pixel array
        screenImage = new BufferedImage(VIEW_WIDTH, VIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        screenPixels = ((DataBufferInt) screenImage.getRaster().getDataBuffer()).getData();

    }

    public void setScroll(int x, int y) {
        this.scrollX = Math.floorMod(x, WORLD_WIDTH);
        this.scrollY = Math.floorMod(y, WORLD_HEIGHT);
    }

    //Sets an RGB pixel color directly in the 512x480 virtual space

    public void setWorldPixel(int x, int y, int rgbColor) {
        int wx = Math.floorMod(x, WORLD_WIDTH);
        int wy = Math.floorMod(y, WORLD_HEIGHT);
        worldPixels[wy * WORLD_WIDTH + wx] = rgbColor;
    }

    //copies pixels from the 512x480 virtual world into the 256x224 buffer, wrapping where necessary

    public void renderFrame() {
        for (int vy = 0; vy < VIEW_HEIGHT; vy++) {
            //Compute wrapped Y coordinate in the world space
            int worldY = (scrollY + vy) % WORLD_HEIGHT;
            int worldYOffset = worldY * WORLD_WIDTH;
            int viewYOffset = vy * VIEW_WIDTH;

            for (int vx = 0; vx < VIEW_WIDTH; vx++) {
                //compute wrapped X coordinate in the world space
                int worldX = (scrollX + vx) % WORLD_WIDTH;

                //Copy pixel from virtual buffer to viewport buffer
                screenPixels[viewYOffset + vx] = worldPixels[worldYOffset + worldX];
            }
        }

        //tell Swing to redraw the panel
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        //Draw the 256x224 buffer scaled to fill the component dimensions
        g.drawImage(screenImage, 0, 0, getWidth(), getHeight(), null);
    }
}