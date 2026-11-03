package de.lorenzo.chunkbots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verwaltet alle Bots, speichert sie in {@code config/chunkbots.json} und hält
 * ihre Chunks per Force-Load geladen. Die Chunks werden schrittweise (pro Tick
 * nur eine begrenzte Anzahl) geladen, damit der Server nicht einfriert.
 */
public final class BotManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chunkbots.json");

	/** Persistente Daten (Einstellungen + Bots). */
	private static final class Data {
		int chunksPerTick = 40;
		int maxViewDistance = 32;
		int maxBots = 1000;
		/** Laufgeschwindigkeit der Bots in Blöcken pro Sekunde (0 = stehen still). */
		double moveSpeed = 0;
		/** Alle wie viele Sekunden ein Stresstest-Bericht ins Server-Log geschrieben wird (0 = aus). */
		int logIntervalSeconds = 10;
		List<Bot> bots = new ArrayList<>();
	}

	private record ChunkOp(String dimension, long pos) {
	}

	private static Data data = new Data();
	private static final Map<String, Bot> BOTS = new LinkedHashMap<>();
	/** Dimension -> (ChunkPos als long -> Anzahl Bots, die den Chunk brauchen). */
	private static final Map<String, Map<Long, Integer>> REFS = new HashMap<>();
	private static final ArrayDeque<ChunkOp> QUEUE = new ArrayDeque<>();
	private static MinecraftServer server;
	private static final java.util.Random RANDOM = new java.util.Random();
	private static long tickCounter;

	private BotManager() {
	}

	// ------------------------------------------------------------------ Lifecycle

	static void onServerStarted(MinecraftServer srv) {
		server = srv;
		BOTS.clear();
		REFS.clear();
		QUEUE.clear();
		load();
		for (Bot bot : data.bots) {
			if (BOTS.containsKey(bot.name())) continue;
			BOTS.put(bot.name(), bot);
			claim(bot);
		}
		ChunkBots.LOGGER.info("ChunkBots: {} Bots geladen, {} Chunks werden geladen", BOTS.size(), QUEUE.size());
	}

	static void onServerStopping(MinecraftServer srv) {
		// Ausstehende Chunk-Änderungen noch durchführen, damit der Force-Load-Status stimmt.
		while (!QUEUE.isEmpty()) {
			apply(QUEUE.poll());
		}
		save();
		server = null;
	}

	static void onServerTick(MinecraftServer srv) {
		tickCounter++;
		if (data.moveSpeed > 0) {
			moveBots();
		}
		int budget = Math.max(1, data.chunksPerTick);
		while (budget-- > 0 && !QUEUE.isEmpty()) {
			apply(QUEUE.poll());
		}
		if (data.logIntervalSeconds > 0 && !BOTS.isEmpty() && tickCounter % (data.logIntervalSeconds * 20L) == 0) {
			ChunkBots.LOGGER.info("[Stresstest] {}", statusLine());
		}
	}

	/** Lässt alle Bots wie erkundende Spieler weiterlaufen. Neue Chunks werden geladen, alte freigegeben. */
	private static void moveBots() {
		double step = data.moveSpeed / 20.0;
		for (Map.Entry<String, Bot> entry : BOTS.entrySet()) {
			Bot old = entry.getValue();
			// Leicht schlangenförmiger Weg, damit die Bots nicht stur geradeaus laufen.
			double heading = old.heading() + (RANDOM.nextDouble() - 0.5) * 0.05;
			Bot moved = old.movedTo(old.x() + Math.cos(heading) * step, old.z() + Math.sin(heading) * step, heading);
			if (moved.chunkX() != old.chunkX() || moved.chunkZ() != old.chunkZ()) {
				claim(moved);
				release(old);
			}
			entry.setValue(moved);
		}
	}

	/** Eine Zeile mit den wichtigsten Werten für den Stresstest. */
	public static String statusLine() {
		double mspt = 0;
		int serverChunks = 0;
		if (server != null) {
			mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
			for (ServerLevel level : server.getAllLevels()) {
				serverChunks += level.getChunkSource().getLoadedChunksCount();
			}
		}
		double tps = mspt <= 50.0 ? 20.0 : 1000.0 / mspt;
		return String.format(java.util.Locale.ROOT,
				"%d Bots | %d Bot-Chunks | %d Chunks im Server geladen | Warteschlange %d | MSPT %.1f | TPS %.1f | Tempo %.1f Bl/s",
				BOTS.size(), loadedChunkCount(), serverChunks, QUEUE.size(), mspt, tps, data.moveSpeed);
	}

	public static double moveSpeed() {
		return data.moveSpeed;
	}

	public static void setMoveSpeed(double speed) {
		data.moveSpeed = speed;
		save();
	}

	// ------------------------------------------------------------------ API

	public static Collection<Bot> bots() {
		return Collections.unmodifiableCollection(BOTS.values());
	}

	public static Bot get(String name) {
		return BOTS.get(name);
	}

	public static int maxViewDistance() {
		return data.maxViewDistance;
	}

	public static int maxBots() {
		return data.maxBots;
	}

	public static int chunksPerTick() {
		return data.chunksPerTick;
	}

	public static void setChunksPerTick(int value) {
		data.chunksPerTick = value;
		save();
	}

	public static int pendingChunks() {
		return QUEUE.size();
	}

	public static int loadedChunkCount() {
		int sum = 0;
		for (Map<Long, Integer> m : REFS.values()) sum += m.size();
		return sum;
	}

	/** Liefert den nächsten freien Namen der Form Bot_N. */
	public static String nextName() {
		int i = 1;
		while (BOTS.containsKey("Bot_" + i)) i++;
		return "Bot_" + i;
	}

	/** Fügt einen Bot hinzu. Danach {@link #saveNow()} aufrufen (bei vielen Bots erst am Ende). */
	public static boolean add(Bot bot) {
		if (BOTS.containsKey(bot.name())) return false;
		BOTS.put(bot.name(), bot);
		claim(bot);
		return true;
	}

	public static void saveNow() {
		save();
	}

	public static boolean remove(String name) {
		Bot bot = BOTS.remove(name);
		if (bot == null) return false;
		release(bot);
		save();
		return true;
	}

	public static int clear() {
		int n = BOTS.size();
		for (Bot bot : BOTS.values()) release(bot);
		BOTS.clear();
		save();
		return n;
	}

	public static void setViewDistance(String name, int viewDistance) {
		Bot old = BOTS.get(name);
		if (old == null) return;
		Bot updated = old.withViewDistance(viewDistance);
		// Erst neue Chunks beanspruchen, dann alte freigeben -> gemeinsame Chunks bleiben geladen.
		claim(updated);
		release(old);
		BOTS.put(name, updated);
		save();
	}

	public static String dimensionId(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	// ------------------------------------------------------------------ Chunk-Logik

	private static void claim(Bot bot) {
		Map<Long, Integer> refs = REFS.computeIfAbsent(bot.dimension(), k -> new HashMap<>());
		int r = bot.viewDistance();
		for (int x = bot.chunkX() - r; x <= bot.chunkX() + r; x++) {
			for (int z = bot.chunkZ() - r; z <= bot.chunkZ() + r; z++) {
				long key = pack(x, z);
				if (refs.merge(key, 1, Integer::sum) == 1) {
					QUEUE.add(new ChunkOp(bot.dimension(), key));
				}
			}
		}
	}

	private static void release(Bot bot) {
		Map<Long, Integer> refs = REFS.get(bot.dimension());
		if (refs == null) return;
		int r = bot.viewDistance();
		for (int x = bot.chunkX() - r; x <= bot.chunkX() + r; x++) {
			for (int z = bot.chunkZ() - r; z <= bot.chunkZ() + r; z++) {
				long key = pack(x, z);
				Integer count = refs.get(key);
				if (count == null) continue;
				if (count <= 1) {
					refs.remove(key);
					QUEUE.add(new ChunkOp(bot.dimension(), key));
				} else {
					refs.put(key, count - 1);
				}
			}
		}
	}

	private static long pack(int x, int z) {
		return (x & 0xFFFFFFFFL) | ((long) z << 32);
	}

	/** Setzt den Force-Load-Status eines Chunks auf den aktuell gewünschten Zustand. */
	private static void apply(ChunkOp op) {
		if (server == null) return;
		ServerLevel level = findLevel(op.dimension());
		if (level == null) return;
		Map<Long, Integer> refs = REFS.get(op.dimension());
		boolean wanted = refs != null && refs.containsKey(op.pos());
		int x = (int) op.pos();
		int z = (int) (op.pos() >> 32);
		level.setChunkForced(x, z, wanted);
	}

	private static ServerLevel findLevel(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (dimensionId(level).equals(dimension)) return level;
		}
		return null;
	}

	// ------------------------------------------------------------------ Speichern / Laden

	private static void load() {
		if (!Files.exists(FILE)) {
			data = new Data();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			data = loaded != null ? loaded : new Data();
			if (data.bots == null) data.bots = new ArrayList<>();
		} catch (Exception e) {
			ChunkBots.LOGGER.error("ChunkBots: Konnte {} nicht lesen", FILE, e);
			data = new Data();
		}
	}

	private static void save() {
		data.bots = new ArrayList<>(BOTS.values());
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			ChunkBots.LOGGER.error("ChunkBots: Konnte {} nicht speichern", FILE, e);
		}
	}
}
