package de.lorenzo.chunkbots;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Befehle:
 * <pre>
 * /chunkbots spawn &lt;anzahl&gt; &lt;sichtweite&gt; [abstand]     Bots im Raster um dich herum verteilen
 * /chunkbots random &lt;anzahl&gt; &lt;sichtweite&gt; &lt;radius&gt;    Bots zufällig im Radius (Blöcke) verteilen
 * /chunkbots add &lt;name&gt; &lt;sichtweite&gt; [x z]              Einen Bot an deiner Position / bei x z setzen
 * /chunkbots remove &lt;name&gt;                               Bot entfernen
 * /chunkbots clear                                       Alle Bots entfernen
 * /chunkbots list                                        Alle Bots anzeigen
 * /chunkbots status                                      Übersicht
 * /chunkbots sichtweite &lt;name|alle&gt; &lt;sichtweite&gt;         Sichtweite ändern
 * /chunkbots laufen &lt;blockProSekunde&gt;                   Bots laufen lassen (Stresstest, 0 = stehen)
 * /chunkbots stop                                        Stresstest beenden: anhalten und alle Bots entfernen
 * /chunkbots speed &lt;chunksProTick&gt;                       Ladegeschwindigkeit
 * </pre>
 */
public final class BotCommand {
	private static final Random RANDOM = new Random();

	private static final SuggestionProvider<CommandSourceStack> BOT_NAMES = (ctx, builder) ->
			SharedSuggestionProvider.suggest(BotManager.bots().stream().map(Bot::name), builder);

	private static final SuggestionProvider<CommandSourceStack> BOT_NAMES_AND_ALL = (ctx, builder) -> {
		List<String> names = new ArrayList<>();
		names.add("alle");
		BotManager.bots().forEach(b -> names.add(b.name()));
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private BotCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("chunkbots")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

				.then(Commands.literal("spawn")
						.then(Commands.argument("anzahl", IntegerArgumentType.integer(1))
								.then(Commands.argument("sichtweite", IntegerArgumentType.integer(0))
										.executes(ctx -> spawnGrid(ctx, -1))
										.then(Commands.argument("abstand", IntegerArgumentType.integer(1))
												.executes(ctx -> spawnGrid(ctx, IntegerArgumentType.getInteger(ctx, "abstand")))))))

				.then(Commands.literal("random")
						.then(Commands.argument("anzahl", IntegerArgumentType.integer(1))
								.then(Commands.argument("sichtweite", IntegerArgumentType.integer(0))
										.then(Commands.argument("radius", IntegerArgumentType.integer(16))
												.executes(BotCommand::spawnRandom)))))

				.then(Commands.literal("add")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("sichtweite", IntegerArgumentType.integer(0))
										.executes(ctx -> addSingle(ctx, false))
										.then(Commands.argument("x", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(ctx -> addSingle(ctx, true)))))))

				.then(Commands.literal("remove")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(BOT_NAMES)
								.executes(BotCommand::remove)))

				.then(Commands.literal("clear").executes(BotCommand::clear))
				.then(Commands.literal("list").executes(BotCommand::list))
				.then(Commands.literal("status").executes(BotCommand::status))

				.then(Commands.literal("sichtweite")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(BOT_NAMES_AND_ALL)
								.then(Commands.argument("sichtweite", IntegerArgumentType.integer(0))
										.executes(BotCommand::setViewDistance))))

				.then(Commands.literal("laufen")
						.then(Commands.argument("blockProSekunde", DoubleArgumentType.doubleArg(0, 200))
								.executes(BotCommand::setMoveSpeed)))

				.then(Commands.literal("stop").executes(BotCommand::stop))

				.then(Commands.literal("speed")
						.then(Commands.argument("chunksProTick", IntegerArgumentType.integer(1, 1000))
								.executes(BotCommand::setSpeed))));

		// Kurzform /bots
		dispatcher.register(Commands.literal("bots")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.redirect(root));
	}

	// ------------------------------------------------------------------ Spawnen

	/** Verteilt Bots spiralförmig in einem Raster um die Position des Ausführenden. */
	private static int spawnGrid(CommandContext<CommandSourceStack> ctx, int spacing) {
		CommandSourceStack src = ctx.getSource();
		int count = IntegerArgumentType.getInteger(ctx, "anzahl");
		int view = IntegerArgumentType.getInteger(ctx, "sichtweite");
		if (!checkLimits(src, count, view)) return 0;

		// Standard-Abstand: Bereiche grenzen genau aneinander, ohne sich zu überlappen.
		int step = spacing > 0 ? spacing : view * 2 + 1;
		ServerLevel level = src.getLevel();
		String dim = BotManager.dimensionId(level);
		Vec3 pos = src.getPosition();
		int cx = ((int) Math.floor(pos.x)) >> 4;
		int cz = ((int) Math.floor(pos.z)) >> 4;

		Set<Long> occupied = occupiedChunks(dim);
		List<Bot> created = new ArrayList<>();
		// Quadratische Spirale: (0,0), dann Ring 1, Ring 2, ...
		for (int ring = 0; created.size() < count && ring < 100_000; ring++) {
			for (int[] o : ringOffsets(ring)) {
				if (created.size() >= count) break;
				int x = cx + o[0] * step;
				int z = cz + o[1] * step;
				if (!occupied.add(key(x, z))) continue;
				Bot bot = Bot.atChunk(BotManager.nextName(), dim, x, z, view);
				BotManager.add(bot);
				created.add(bot);
			}
		}
		BotManager.saveNow();
		reportCreated(src, created, view);
		return created.size();
	}

	/** Verteilt Bots zufällig in einem Kreis mit {@code radius} Blöcken um den Ausführenden. */
	private static int spawnRandom(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int count = IntegerArgumentType.getInteger(ctx, "anzahl");
		int view = IntegerArgumentType.getInteger(ctx, "sichtweite");
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		if (!checkLimits(src, count, view)) return 0;

		ServerLevel level = src.getLevel();
		String dim = BotManager.dimensionId(level);
		Vec3 pos = src.getPosition();
		Set<Long> occupied = occupiedChunks(dim);
		List<Bot> placedNow = new ArrayList<>();
		int minDist = view * 2 + 1; // in Chunks, damit sich die Bereiche möglichst nicht überlappen

		for (int i = 0; i < count; i++) {
			int bestX = 0, bestZ = 0;
			boolean found = false;
			for (int attempt = 0; attempt < 200; attempt++) {
				double angle = RANDOM.nextDouble() * Math.PI * 2;
				double dist = Math.sqrt(RANDOM.nextDouble()) * radius;
				int x = ((int) Math.floor(pos.x + Math.cos(angle) * dist)) >> 4;
				int z = ((int) Math.floor(pos.z + Math.sin(angle) * dist)) >> 4;
				if (occupied.contains(key(x, z))) continue;
				bestX = x;
				bestZ = z;
				found = true;
				if (farEnough(placedNow, x, z, minDist)) break;
			}
			if (!found) break;
			occupied.add(key(bestX, bestZ));
			Bot bot = Bot.atChunk(BotManager.nextName(), dim, bestX, bestZ, view);
			BotManager.add(bot);
			placedNow.add(bot);
		}
		BotManager.saveNow();
		reportCreated(src, placedNow, view);
		return placedNow.size();
	}

	private static int addSingle(CommandContext<CommandSourceStack> ctx, boolean withCoords) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		int view = IntegerArgumentType.getInteger(ctx, "sichtweite");
		if (!checkLimits(src, 1, view)) return 0;
		if (BotManager.get(name) != null) {
			src.sendFailure(Component.literal("Es gibt schon einen Bot mit dem Namen " + name));
			return 0;
		}
		int bx, bz;
		if (withCoords) {
			bx = IntegerArgumentType.getInteger(ctx, "x");
			bz = IntegerArgumentType.getInteger(ctx, "z");
		} else {
			Vec3 pos = src.getPosition();
			bx = (int) Math.floor(pos.x);
			bz = (int) Math.floor(pos.z);
		}
		Bot bot = new Bot(name, BotManager.dimensionId(src.getLevel()), bx + 0.5, bz + 0.5, view, Math.random() * Math.PI * 2);
		BotManager.add(bot);
		BotManager.saveNow();
		src.sendSuccess(() -> Component.literal("Bot " + name + " gesetzt bei X " + bot.blockX() + " Z " + bot.blockZ()
				+ " (Sichtweite " + view + ", " + bot.chunkCount() + " Chunks)"), true);
		return 1;
	}

	// ------------------------------------------------------------------ Verwalten

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		if (!BotManager.remove(name)) {
			ctx.getSource().sendFailure(Component.literal("Kein Bot mit dem Namen " + name));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Bot " + name + " entfernt"), true);
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> ctx) {
		int n = BotManager.clear();
		ctx.getSource().sendSuccess(() -> Component.literal(n + " Bots entfernt, Chunks werden freigegeben"), true);
		return n;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (BotManager.bots().isEmpty()) {
			src.sendSuccess(() -> Component.literal("Keine Bots vorhanden"), false);
			return 0;
		}
		src.sendSuccess(() -> Component.literal("Bots (" + BotManager.bots().size() + "):"), false);
		for (Bot b : BotManager.bots()) {
			src.sendSuccess(() -> Component.literal(" - " + b.name() + "  " + b.dimension()
					+ "  X " + b.blockX() + " Z " + b.blockZ() + "  Sichtweite " + b.viewDistance()), false);
		}
		return BotManager.bots().size();
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal("ChunkBots: " + BotManager.statusLine()
				+ " | " + BotManager.chunksPerTick() + " Chunks/Tick"), false);
		return BotManager.bots().size();
	}

	private static int setMoveSpeed(CommandContext<CommandSourceStack> ctx) {
		double speed = DoubleArgumentType.getDouble(ctx, "blockProSekunde");
		BotManager.setMoveSpeed(speed);
		if (speed <= 0) {
			ctx.getSource().sendSuccess(() -> Component.literal("Bots bleiben jetzt stehen"), true);
		} else {
			ctx.getSource().sendSuccess(() -> Component.literal("Bots laufen jetzt mit " + speed
					+ " Blöcken/s (Gehen 4.3, Sprinten 5.6, Elytra ca. 30)"), true);
		}
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> ctx) {
		BotManager.setMoveSpeed(0);
		int n = BotManager.clear();
		ctx.getSource().sendSuccess(() -> Component.literal("Stresstest beendet: " + n
				+ " Bots entfernt, Chunks werden freigegeben"), true);
		return n;
	}

	private static int setViewDistance(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		int view = IntegerArgumentType.getInteger(ctx, "sichtweite");
		if (!checkLimits(src, 0, view)) return 0;

		List<String> targets = new ArrayList<>();
		if (name.equalsIgnoreCase("alle")) {
			BotManager.bots().forEach(b -> targets.add(b.name()));
		} else if (BotManager.get(name) != null) {
			targets.add(name);
		} else {
			src.sendFailure(Component.literal("Kein Bot mit dem Namen " + name));
			return 0;
		}
		for (String t : targets) BotManager.setViewDistance(t, view);
		src.sendSuccess(() -> Component.literal("Sichtweite von " + targets.size() + " Bot(s) auf " + view + " gesetzt"), true);
		return targets.size();
	}

	private static int setSpeed(CommandContext<CommandSourceStack> ctx) {
		int speed = IntegerArgumentType.getInteger(ctx, "chunksProTick");
		BotManager.setChunksPerTick(speed);
		ctx.getSource().sendSuccess(() -> Component.literal("Es werden jetzt " + speed + " Chunks pro Tick geladen"), true);
		return speed;
	}

	// ------------------------------------------------------------------ Hilfsmethoden

	private static boolean checkLimits(CommandSourceStack src, int newBots, int view) {
		if (view > BotManager.maxViewDistance()) {
			src.sendFailure(Component.literal("Sichtweite maximal " + BotManager.maxViewDistance()
					+ " (änderbar in config/chunkbots.json)"));
			return false;
		}
		if (BotManager.bots().size() + newBots > BotManager.maxBots()) {
			src.sendFailure(Component.literal("Maximal " + BotManager.maxBots()
					+ " Bots erlaubt (änderbar in config/chunkbots.json)"));
			return false;
		}
		return true;
	}

	private static void reportCreated(CommandSourceStack src, List<Bot> created, int view) {
		if (created.isEmpty()) {
			src.sendFailure(Component.literal("Es konnten keine Bots platziert werden"));
			return;
		}
		int chunks = created.size() * (view * 2 + 1) * (view * 2 + 1);
		src.sendSuccess(() -> Component.literal(created.size() + " Bots gesetzt (Sichtweite " + view + ", bis zu "
				+ chunks + " Chunks). Mit /chunkbots list siehst du die Positionen."), true);
	}

	private static Set<Long> occupiedChunks(String dim) {
		Set<Long> set = new HashSet<>();
		for (Bot b : BotManager.bots()) {
			if (b.dimension().equals(dim)) set.add(key(b.chunkX(), b.chunkZ()));
		}
		return set;
	}

	private static boolean farEnough(List<Bot> bots, int x, int z, int minDist) {
		for (Bot b : bots) {
			if (Math.abs(b.chunkX() - x) < minDist && Math.abs(b.chunkZ() - z) < minDist) return false;
		}
		return true;
	}

	/** Alle Rasterpunkte auf dem Rand des Quadrats mit Abstand {@code ring} vom Mittelpunkt. */
	private static List<int[]> ringOffsets(int ring) {
		List<int[]> list = new ArrayList<>();
		if (ring == 0) {
			list.add(new int[]{0, 0});
			return list;
		}
		for (int i = -ring; i <= ring; i++) {
			list.add(new int[]{i, -ring});
			list.add(new int[]{i, ring});
		}
		for (int i = -ring + 1; i <= ring - 1; i++) {
			list.add(new int[]{-ring, i});
			list.add(new int[]{ring, i});
		}
		return list;
	}

	private static long key(int x, int z) {
		return (x & 0xFFFFFFFFL) | ((long) z << 32);
	}
}
