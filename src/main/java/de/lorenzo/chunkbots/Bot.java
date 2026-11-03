package de.lorenzo.chunkbots;

/**
 * Ein Bot steht an einer Position einer Dimension und hält alle Chunks im Umkreis
 * von {@code viewDistance} Chunks geladen (wie ein Spieler mit dieser Sichtweite).
 * Im Stresstest-Modus läuft er in Richtung {@code heading} (Bogenmaß) weiter.
 */
public record Bot(String name, String dimension, double x, double z, int viewDistance, double heading) {

	public int chunkX() {
		return ((int) Math.floor(x)) >> 4;
	}

	public int chunkZ() {
		return ((int) Math.floor(z)) >> 4;
	}

	public int blockX() {
		return (int) Math.floor(x);
	}

	public int blockZ() {
		return (int) Math.floor(z);
	}

	public int chunkCount() {
		int d = viewDistance * 2 + 1;
		return d * d;
	}

	public Bot withViewDistance(int newViewDistance) {
		return new Bot(name, dimension, x, z, newViewDistance, heading);
	}

	public Bot movedTo(double newX, double newZ, double newHeading) {
		return new Bot(name, dimension, newX, newZ, viewDistance, newHeading);
	}

	/** Erzeugt einen Bot in der Mitte des angegebenen Chunks mit zufälliger Laufrichtung. */
	public static Bot atChunk(String name, String dimension, int chunkX, int chunkZ, int viewDistance) {
		return new Bot(name, dimension, (chunkX << 4) + 8.5, (chunkZ << 4) + 8.5, viewDistance,
				Math.random() * Math.PI * 2);
	}
}
