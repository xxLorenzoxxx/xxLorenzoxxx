package de.lorenzo.chunkbots;

/**
 * Ein Bot steht in einem Chunk einer Dimension und hält alle Chunks im Umkreis
 * von {@code viewDistance} Chunks geladen (wie ein Spieler mit dieser Sichtweite).
 */
public record Bot(String name, String dimension, int chunkX, int chunkZ, int viewDistance) {

	public int blockX() {
		return (chunkX << 4) + 8;
	}

	public int blockZ() {
		return (chunkZ << 4) + 8;
	}

	public int chunkCount() {
		int d = viewDistance * 2 + 1;
		return d * d;
	}

	public Bot withViewDistance(int newViewDistance) {
		return new Bot(name, dimension, chunkX, chunkZ, newViewDistance);
	}
}
