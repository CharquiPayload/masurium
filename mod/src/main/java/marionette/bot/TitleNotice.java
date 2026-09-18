package marionette.bot;

import marionette.bot.NoticeText.Notice;
import marionette.bot.NoticeText.Severity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a person sees on the title screen about how this instance was configured.
 *
 * <p>A line in a log is the right place to say something to a program and the wrong
 * place to say it to a person. Someone who sets up an instance, launches it and waits
 * for a bot that never obeys is not going to open latest.log to find out why — they are
 * going to look at the screen in front of them, decide the mod is broken, and leave.
 *
 * <p>More than one thing can be wrong at once, so they stack, each with its own link to
 * the manual and its own "do not show again". What is said and to whom lives in
 * {@link NoticeText}, which has no Minecraft in it and therefore has tests; this class
 * only draws it and reads the clicks.
 *
 * <p>Only on the title screen. Nothing is ever drawn over a world someone is playing in,
 * and a bot with somewhere to go replaces this screen before anyone reads it anyway.
 */
public final class TitleNotice {

    private static final int ERROR = 0xFFFF6B6B;
    private static final int WARNING = 0xFFFFC96B;
    private static final int INFO = 0xFFAAAAAA;
    private static final int HINT = 0xFF999999;
    private static final int LINK = 0xFF7FB3FF;
    private static final int SHADE = 0x90000000;

    /** Where each row of buttons ended up, so a click can be matched to its notice. */
    private record Hit(Notice notice, int manualLeft, int manualRight,
                       int hideLeft, int hideRight, int top) {
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static int boxLeft, boxTop, boxRight, boxBottom;

    /** Loaded the first time it is needed, so a client that never shows one never looks. */
    private static List<String> hidden;

    private TitleNotice() {
    }

    private static Path marker() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("marionette-hidden-notices.txt");
    }

    private static List<String> hidden() {
        if (hidden == null) {
            List<String> found = new ArrayList<>();
            try {
                Path m = marker();
                if (Files.exists(m)) {
                    for (String line : Files.readAllLines(m)) {
                        String id = line.trim();
                        if (!id.isEmpty() && !id.startsWith("#")) {
                            found.add(id);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A read-only or odd game directory is not a reason to fail a title
                // screen. Showing a notice one time too many is the harmless side.
            }
            hidden = found;
        }
        return hidden;
    }

    private static void hide(String id) {
        if (hidden().contains(id)) {
            return;
        }
        hidden().add(id);
        try {
            Path m = marker();
            Files.createDirectories(m.getParent());
            List<String> out = new ArrayList<>();
            out.add("# Marionette notices hidden from this instance.");
            out.add("# Delete a line to get that one back, or delete the file for all.");
            out.addAll(hidden());
            Files.write(m, out);
        } catch (IOException | RuntimeException e) {
            // Hidden for this session either way; it will just come back next time.
        }
    }

    private static List<Notice> showing() {
        return NoticeText.notSilenced(
                NoticeText.noticesFor(Bot.isBot(), Bot.misconfigured(),
                        Bot.server() != null, Bot.portSpecified()),
                hidden());
    }

    private static int colour(Severity severity) {
        return switch (severity) {
            case ERROR -> ERROR;
            case WARNING -> WARNING;
            case INFO -> INFO;
        };
    }

    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        List<Notice> notices = showing();
        HITS.clear();
        if (notices.isEmpty()) {
            boxLeft = boxRight = -1;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int step = mc.font.lineHeight + 2;

        int width = 0;
        for (Notice n : notices) {
            width = Math.max(width, mc.font.width(n.text()));
            width = Math.max(width, mc.font.width(n.hint()));
        }
        width = Math.max(width, mc.font.width(NoticeText.MANUAL)
                + mc.font.width(NoticeText.HIDE) + 12);

        int x = (event.getScreen().width - width) / 2;
        int y = 8;
        // Three rows each (text, hint, buttons) plus a blank line between notices.
        int rows = notices.size() * 3 + (notices.size() - 1);

        boxLeft = x - 6;
        boxTop = y - 4;
        boxRight = x + width + 6;
        boxBottom = y + rows * step + 2;

        // A panorama is moving behind this: without something to sit on, the text is
        // legible in the dark half of the sky and gone in the bright half.
        g.fill(boxLeft, boxTop, boxRight, boxBottom, SHADE);

        int row = y;
        for (Notice n : notices) {
            g.drawString(mc.font, n.text(), x, row, colour(n.severity()));
            row += step;
            g.drawString(mc.font, n.hint(), x, row, HINT);
            row += step;

            int manualLeft = x;
            int manualRight = x + mc.font.width(NoticeText.MANUAL);
            g.drawString(mc.font, NoticeText.MANUAL, manualLeft, row, LINK);

            int hideLeft = -1, hideRight = -1;
            if (n.dismissible()) {
                hideLeft = manualRight + 12;
                hideRight = hideLeft + mc.font.width(NoticeText.HIDE);
                g.drawString(mc.font, NoticeText.HIDE, hideLeft, row, INFO);
            }
            HITS.add(new Hit(n, manualLeft, manualRight, hideLeft, hideRight, row));
            row += step * 2;
        }
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof TitleScreen screen) || HITS.isEmpty()) {
            return;
        }
        int h = Minecraft.getInstance().font.lineHeight;
        double mx = event.getMouseX();
        double my = event.getMouseY();

        for (Hit hit : HITS) {
            if (NoticeText.inside(mx, my, hit.hideLeft(), hit.hideRight(), hit.top(), h)) {
                hide(hit.notice().id());
                event.setCanceled(true);
                return;
            }
            if (NoticeText.inside(mx, my, hit.manualLeft(), hit.manualRight(),
                    hit.top(), h)) {
                // The vanilla "are you sure you want to open this link" screen, because
                // a mod opening a browser unannounced is not something anyone asked for.
                ConfirmLinkScreen.confirmLinkNow(screen, NoticeText.URL);
                event.setCanceled(true);
                return;
            }
        }
        // A click anywhere else on the box is swallowed, not passed through: the box
        // sits over the title screen and a misfire would start a world.
        if (NoticeText.inside(mx, my, boxLeft, boxRight, boxTop, boxBottom - boxTop)) {
            event.setCanceled(true);
        }
    }
}
