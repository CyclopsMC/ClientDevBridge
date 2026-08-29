package org.cyclops.clientdevbridge.mcadapter;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.cyclops.clientdevbridge.Reference;

import java.util.List;

/**
 * Fabric side of {@link IClientHooks}.
 *
 * @author rubensworks
 */
public class ClientHooksFabric implements IClientHooks {

    @Override
    public void registerClientTick(Runnable listener) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> listener.run());
    }

    @Override
    public String getLoaderName() {
        return "fabric";
    }

    @Override
    public String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(Reference.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getId())
                .sorted()
                .toList();
    }

}
