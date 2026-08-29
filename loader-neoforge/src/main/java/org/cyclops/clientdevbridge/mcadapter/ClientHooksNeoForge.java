package org.cyclops.clientdevbridge.mcadapter;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.cyclops.clientdevbridge.Reference;

import java.util.List;

/**
 * NeoForge side of {@link IClientHooks}.
 *
 * @author rubensworks
 */
public class ClientHooksNeoForge implements IClientHooks {

    @Override
    public void registerClientTick(Runnable listener) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> listener.run());
    }

    @Override
    public String getLoaderName() {
        return "neoforge";
    }

    @Override
    public String getModVersion() {
        return ModList.get().getModContainerById(Reference.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public List<String> getLoadedModIds() {
        return ModList.get().getMods().stream()
                .map(info -> info.getModId())
                .sorted()
                .toList();
    }

}
