package marionette.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a person sees when this jar is installed in a client that is not a bot.
 *
 * <p>A line in a log is the right place to say something to a program and the wrong
 * place to say it to a person. Someone who sets up an instance, launches it and waits
 * for a bot that never obeys is not going to open latest.log to find out why — they are
 * going to look at the screen in front of them, decide the mod is broken, and leave.
 *
 * <p>So it is said on the title screen, which in a per-instance launcher is exactly
 * where that person is standing. Two different things are said, because two different
 * things happened:
 *
 * <ul>
 *   <li><b>The flag is there and empty.</b> Someone meant to make a bot and did not
 *       finish. This is a problem, it reads like one, and it <b>cannot be dismissed</b>:
 *       silencing it is how you end up with the mute bot this notice exists to
 *       prevent.</li>
 *   <li><b>No flag at all.</b> Nothing is wrong: this is a player, and the mod is
 *       staying out of their way. It still says so, because a mod that is installed and
 *       silent is indistinguishable from a mod that is broken — but this one takes
 *       "do not show again", since being told twice is enough.</li>
 * </ul>
 *
 * <p>The wording and the rules live in {@link NoticeText}, which has no Minecraft in
 * it and therefore has tests. This class only draws them.
 *
 * <p>Only on the title screen, and only when this client is not a bot. A configured bot
 * never draws anything, and nothing is ever drawn over a world someone is playing in.
 */
public final class TitleNotice {

    private static final int WARNING = 0xFFFF6B6B;
    private static final int CALM = 0xFFAAAAAA;
    private static final int LINK = 0xFF7FB3FF;
    private static final int SHADE = 0x90000000;

    /** Where the box ended up last frame, so a click can be told whether it hit it. */
    private static int boxLeft, boxTop, boxRight, boxBottom;
    private static int manualTop, manualLeft, manualRight;
    private static int hideTop, hideLeft, hideRight;

    /** Loaded the first time it is needed, so a client that never shows it never looks. */
    private static Boolean dismissed;

    private TitleNotice() {
    }

    private static Path marker() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("marionette-notice-hidden");
    }

    private static boolean hidden() {
        if (dismissed == null) {
            boolean found;
            try {
                found = Files.exists(marker());
            } catch (RuntimeException e) {
                // A read-only or odd game directory is not a reason to fail a title
                // screen. Showing the notice one time too many is the harmless side.
                found = false;
            }
            dismissed = found;
        }
        return dismissed;
    }

    private static void hide() {
        dismissed = true;
        try {
            Path marker = marker();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "The Marionette title-screen notice was hidden "
                    + "from this instance. Delete this file to get it back.\n");
        } catch (IOException | RuntimeException e) {
            // It is hidden for this session either way; it will just come back next
            // time. Not worth interrupting anyone over.
        }
    }

    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        boolean misconfigured = Bot.misconfigured();
        String[] lines = NoticeText.linesFor(Bot.isBot(), misconfigured);
        if (lines == null) {
            return;
        }
        boolean canHide = NoticeText.dismissible(misconfigured);
        if (canHide && hidden()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int step = mc.font.lineHeight + 2;

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int buttons = mc.font.width(NoticeText.MANUAL) + (canHide ? mc.font.width(NoticeText.HIDE) + 12 : 0);
        width = Math.max(width, buttons);

        int rows = lines.length + 1;
        int x = (event.getScreen().width - width) / 2;
        int y = 8;

        boxLeft = x - 6;
        boxTop = y - 4;
        boxRight = x + width + 6;
        boxBottom = y + rows * step + 2;

        // A panorama is moving behind this: without something to sit on, the text is
        // legible in the dark half of the sky and gone in the bright half.
        g.fill(boxLeft, boxTop, boxRight, boxBottom, SHADE);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], x, y + i * step,
                    i == 0 && misconfigured ? WARNING : CALM);
        }

        int row = y + lines.length * step;
        manualTop = row;
        manualLeft = x;
        manualRight = x + mc.font.width(NoticeText.MANUAL);
        g.drawString(mc.font, NoticeText.MANUAL, manualLeft, row, LINK);

        if (canHide) {
            hideLeft = manualRight + 12;
            hideRight = hideLeft + mc.font.width(NoticeText.HIDE);
            hideTop = row;
            g.drawString(mc.font, NoticeText.HIDE, hideLeft, row, CALM);
        } else {
            hideLeft = hideRight = hideTop = -1;
        }
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }
        if (Bot.isBot()) {
            return;
        }
        boolean canHide = NoticeText.dismissible(Bot.misconfigured());
        if (canHide && hidden()) {
            return;
        }

        int h = Minecraft.getInstance().font.lineHeight;
        double mx = event.getMouseX();
        double my = event.getMouseY();

        if (canHide && NoticeText.inside(mx, my, hideLeft, hideRight, hideTop, h)) {
            hide();
            event.setCanceled(true);
            return;
        }
        if (NoticeText.inside(mx, my, manualLeft, manualRight, manualTop, h)) {
            // The vanilla "are you sure you want to open this link" screen, because a
            // mod opening a browser unannounced is not something anyone asked for.
            ConfirmLinkScreen.confirmLinkNow(screen, NoticeText.URL);
            event.setCanceled(true);
            return;
        }
        // A click anywhere else on the box is swallowed, not passed through: the box
        // sits over the title screen buttons and a misfire would start a world.
        if (NoticeText.inside(mx, my, boxLeft, boxRight, boxTop, boxBottom - boxTop)) {
            event.setCanceled(true);
        }
    }
}
