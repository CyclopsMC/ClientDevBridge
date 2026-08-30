package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.cyclops.clientdevbridge.ClientDevBridge;

/**
 * Getting past Minecraft's accessibility onboarding screen.
 *
 * That screen appears on a run directory Minecraft has never written an {@code options.txt} into,
 * and it waits for a human to click Continue. Nobody is going to: this client is driven by an
 * agent. Pinning {@code onboardAccessibility:false} before launch usually prevents it, but only
 * when the CLI guessed the run directory correctly, and it cannot always — a NeoForge dev launch
 * passes {@code --gameDir .}, which resolves against the Gradle task's working directory rather
 * than any of the conventional names.
 *
 * Leaving it up is not merely untidy. Its background is drawn through the blur post-process chain,
 * which is the single most expensive thing Minecraft draws under a software rasteriser; on a
 * two-core CI runner a frame of it takes long enough that the client looks hung, and that is
 * exactly how it presented — a render thread busy in {@code processBlurEffect} 869 seconds after
 * launch, having never reached the title screen.
 *
 * @author rubensworks
 */
public class Onboarding {

    private static boolean dismissed;

    /**
     * Dismisses the onboarding screen if it is showing, and stops it coming back.
     *
     * @return whether the screen was dismissed by this call
     */
    public static boolean dismissIfShowing() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(ClientState.screen() instanceof AccessibilityOnboardingScreen)) {
            return false;
        }
        // Written back to options.txt as well, so the next launch from this run directory does not
        // have to be rescued the same way -- whichever directory it actually turns out to be.
        minecraft.options.onboardAccessibility = false;
        minecraft.options.save();
        minecraft.setScreen(new TitleScreen(true));
        if (!dismissed) {
            dismissed = true;
            ClientDevBridge.LOGGER.info("{} dismissed the accessibility onboarding screen, which waits for a "
                    + "click nobody is going to make. Its blurred background is also ruinously slow to draw "
                    + "under software OpenGL.", org.cyclops.clientdevbridge.Reference.MOD_NAME);
        }
        return true;
    }

}
