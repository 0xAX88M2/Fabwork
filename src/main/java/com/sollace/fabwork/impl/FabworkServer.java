package com.sollace.fabwork.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Streams;
import com.sollace.fabwork.api.Fabwork;
import com.sollace.fabwork.impl.PlayPingSynchroniser.ResponseType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.ClientConnection;
import net.minecraft.util.Identifier;

public class FabworkServer implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("Fabwork::SERVER");
    public static final Identifier CONSENT_ID = id("synchronize");
    public static final int PROTOCOL_VERSION = 1;

    public static final Fabwork FABWORK = FabworkImpl.INSTANCE;

    @Override
    public void onInitialize() {
        final FabworkConfig config = FabworkConfig.INSTANCE.get();
        final Map<ClientConnection, SynchronisationState> clientLoginStates = new HashMap<>();
        final SynchronisationState emptyState = new SynchronisationState(Stream.empty(),
                makeDistinct(Streams.concat(FabworkImpl.INSTANCE.getInstalledMods().filter(ModEntryImpl::requiredOnEither), config.getCustomRequiredMods()))
        );

        ServerPlayNetworking.registerGlobalReceiver(CONSENT_ID, (server, player, handler, buffer, response) -> {
            LOGGER.info("Received synchronize response from client " + handler.getConnection().getAddress().toString());
            clientLoginStates.put(handler.getConnection(), new SynchronisationState(ModEntryImpl.read(buffer), emptyState.installedOnServer().stream()));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("Sending synchronize packet to {}", handler.getConnection().getAddress());
            sender.sendPacket(CONSENT_ID, ModEntryImpl.write(
                    emptyState.installedOnServer().stream(),
                    PacketByteBufs.create())
            );

            PlayPingSynchroniser.waitForClientResponse(handler.getConnection(), responseType -> {
                if (responseType == ResponseType.COMPLETED) {
                    LOGGER.info("Performing verify of client's installed mods {}", handler.getConnection().getAddress());
                    if (clientLoginStates.containsKey(handler.getConnection())) {
                        clientLoginStates.remove(handler.getConnection()).verify(handler.getConnection(), LOGGER, true);
                    } else {
                        LOGGER.warn("Client failed to respond to challenge. Assuming vanilla client {}", handler.getConnection().getAddress());
                        emptyState.verify(handler.getConnection(), LOGGER, false);
                    }
                } else {
                    LOGGER.warn("Failed to receive response from client. {} ConnectionState: {}",
                            handler.getConnection().getAddress(),
                            handler.getConnection().isOpen() ? " OPEN" : " CLOSED"
                    );
                }
            });
        });
        LoaderUtil.invokeEntryPoints("fabwork:main", ModInitializer.class, ModInitializer::onInitialize);

        LOGGER.info("Loaded Fabwork " + FabricLoader.getInstance().getModContainer("fabwork").get().getMetadata().getVersion().getFriendlyString());
    }

    private static Stream<ModEntryImpl> makeDistinct(Stream<ModEntryImpl> entries) {
        Map<String, ModEntryImpl> map = new HashMap<>();
        entries.forEach(entry -> {
            map.compute(entry.modId(), (id, value) -> value == null || entry.requirement().supercedes(value.requirement()) ? entry : value);
        });
        return map.values().stream();
    }

    private static Identifier id(String name) {
        return new Identifier("fabwork", name);
    }
}
