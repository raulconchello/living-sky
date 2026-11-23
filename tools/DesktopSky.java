import dev.livingsky.render.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Desktop harness for the pure-Java shaders (no Android needed): renders the card sky and the column band for the
 * sketch's mock scenario or a synthetic weather scenario, so the port can be compared with sky8.js in Chromium.
 *
 * usage: java -cp build/desktop DesktopSky out_dir [grain]
 */
public class DesktopSky {
    static final double DPR = 2.625;

    static void save(int[] px, int w, int h, String path) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        im.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(im, "png", new File(path));
    }

    static void sky(SkyShader.Params p, String out, boolean grain) throws Exception {
        int pw = (int) Math.round(379.4 * DPR * 0.5), ph = (int) Math.round(192.4 * DPR * 0.5);
        int[] lo = new SkyShader(p).render(pw, ph, DPR * 0.5);
        int W = (int) Math.round(379.4 * DPR), H = (int) Math.round(192.4 * DPR);
        int[] full = Px.upscale(lo, pw, ph, W, H);
        if (grain) Px.grain(full, 7, 1);
        save(full, W, H, out);
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        boolean grain = a.length > 1 && a[1].equals("grain");
        for (boolean night : new boolean[]{false, true}) {
            Timeline w = MockData.timeline(night, false);
            double now = MockData.now(night);
            SkyShader.Params p = Scene.sky(w, now, MockData.LAT, MockData.LON, true, Scene.MODE_AUTO, 1);
            System.out.println((night ? "night" : "day") + " el=" + p.el + " sun=" + p.sunX + "," + p.sunY + " night=" + p.night);
            sky(p, dir + "/sky_" + (night ? "night" : "day") + ".png", grain);
            // band: 102 dp wide, 24 h at 22.3 dp/h, painted at 0.6 x and upscaled
            double pxh = 178.4 / 8, CW = 102, CH = 24 * pxh;
            int pw = (int) Math.round(CW * DPR * 0.6), ph = (int) Math.round(CH * DPR * 0.6);
            int[] lo = new BandShader(w, now, pxh, MockData.LAT, MockData.LON, 20261121).render(pw, ph, DPR * 0.6);
            int Wc = (int) Math.round(CW * DPR), Hc = (int) Math.round(CH * DPR);
            int[] full = Px.upscale(lo, pw, ph, Wc, Hc);
            save(full, Wc, Hc, dir + "/band_" + (night ? "night" : "day") + ".png");
        }
        { // sky9.js sunny reference (make_sunny.py): cloud .12, approach off
            Timeline w = MockData.timeline(false, true);
            double now = MockData.now(false);
            SkyShader.Params p = Scene.sky(w, now, MockData.LAT, MockData.LON, true, Scene.MODE_AUTO, 1);
            sky(p, dir + "/sky_sunny.png", grain);
            double pxh = 178.4 / 8, CW = 102, CH = 24 * pxh;
            int pw = (int) Math.round(CW * DPR * 0.6), ph = (int) Math.round(CH * DPR * 0.6);
            int[] lo = new BandShader(w, now, pxh, MockData.LAT, MockData.LON, 20261121).render(pw, ph, DPR * 0.6);
            save(Px.upscale(lo, pw, ph, (int) Math.round(CW * DPR), (int) Math.round(CH * DPR)), (int) Math.round(CW * DPR), (int) Math.round(CH * DPR), dir + "/band_sunny.png");
        }
        // synthetic scenarios for the new sky types
        String[] names = {"clear_noon", "partly_noon", "cloudy70", "partly_sunset", "clear_sunset", "overcast_sunset", "clear_night", "partly_night", "blue_hour", "rain_now", "snow_now", "fog"};
        for (String n : names) {
            SkyShader.Params p = new SkyShader.Params();
            p.el = 25; p.sunX = 195; p.cover = 1; p.night = false;
            switch (n) {
                case "clear_noon": p.cover = 0.05; break;
                case "partly_noon": p.cover = 0.45; break;
                case "cloudy70": p.cover = 0.72; break;
                case "partly_sunset": p.cover = 0.5; p.el = 1.5; p.sunX = 245; break;
                case "clear_sunset": p.cover = 0.08; p.el = 0.5; p.sunX = 248; break;
                case "overcast_sunset": p.cover = 1; p.el = 1; p.sunX = 246; break;
                case "clear_night": p.cover = 0.05; p.el = -40; p.night = true; break;
                case "partly_night": p.cover = 0.5; p.el = -40; p.night = true; break;
                case "blue_hour": p.cover = 0.3; p.el = -6.5; p.night = true; p.sunX = 255; break;
                case "rain_now": p.cover = 1; p.precip = 1.2; p.ptype = Wx.RAIN; p.el = 15; break;
                case "snow_now": p.cover = 1; p.precip = 0.8; p.ptype = Wx.SNOW; p.el = 10; break;
                case "fog": p.cover = 1; p.fog = 0.7; p.el = 12; break;
            }
            p.sunY = p.HY - Math.max(-3, p.el) * 4.2;
            sky(p, dir + "/sc_" + n + ".png", grain);
        }
        System.out.println("ok");
    }
}
