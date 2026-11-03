package de.lorenzo.chunkbots;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkBots implements ModInitializer {
	public static final String MOD_ID = "chunkbots";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				BotCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(BotManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(BotManager::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(BotManager::onServerTick);

		LOGGER.info("ChunkBots geladen");
	}
}
