# ChunkBots – Stresstest-Mod für Minecraft 26.2 (Fabric)

Mit dieser Mod testest du, wie viel dein Server aushält. Sie **Bots an verschiedenen Orten auf der Welt verteilt**. Jeder Bot hält die
Chunks in seiner **Sichtweite** geladen, wie ein Spieler, der dort steht. Du legst fest,
**wie viele Bots** es gibt und **welche Sichtweite** sie haben.

- **Stresstest:** Mit `/chunkbots laufen <tempo>` laufen alle Bots los wie Spieler, die die Welt
  erkunden. Dabei werden ständig neue Chunks geladen und generiert und alte freigegeben.
- Alle 10 Sekunden schreibt die Mod einen Bericht ins Server-Log (Bots, Chunks, MSPT, TPS).
- Läuft nur auf dem Server. Spieler brauchen die Mod nicht.
- Die Bots werden in `config/chunkbots.json` gespeichert und nach einem Neustart wieder geladen.
- Chunks werden nach und nach geladen (Standard: 40 pro Tick), damit der Server nicht hängt.
- Wenn sich Bots überlappen, bleibt ein gemeinsamer Chunk geladen, bis der letzte Bot weg ist.

## Installation

1. Auf dem Server [Fabric Loader](https://fabricmc.net/use/server/) für **26.2** installieren.
2. **Fabric API** (für 26.2) in den Ordner `mods/` legen.
3. `chunkbots-1.0.0.jar` in den Ordner `mods/` legen.
4. Server starten. Du brauchst Java 25.

## Bauen

```bash
./gradlew build
```

Die Jar liegt danach in `build/libs/chunkbots-1.0.0.jar`. Wenn das Repo auf GitHub liegt,
baut die GitHub Action unter `.github/workflows/build.yml` die Jar bei jedem Push und
stellt sie als Download („chunkbots-mod“) bereit.

## Befehle

Du brauchst OP (Level 2). `/bots` ist eine Kurzform für `/chunkbots`.

| Befehl | Was er macht |
|---|---|
| `/chunkbots spawn <anzahl> <sichtweite> [abstand]` | Verteilt Bots spiralförmig im Raster um dich herum. Ohne `abstand` grenzen die Bereiche genau aneinander. `abstand` ist in Chunks. |
| `/chunkbots random <anzahl> <sichtweite> <radius>` | Verteilt Bots zufällig in einem Kreis um dich herum (`radius` in Blöcken). |
| `/chunkbots add <name> <sichtweite> [x z]` | Setzt einen einzelnen Bot an deine Position oder bei den Koordinaten x/z. |
| `/chunkbots remove <name>` | Entfernt einen Bot. |
| `/chunkbots clear` | Entfernt alle Bots. |
| `/chunkbots list` | Zeigt alle Bots mit Dimension und Position. |
| `/chunkbots status` | Zeigt Bots, geladene Chunks, Warteschlange, MSPT und TPS. |
| `/chunkbots laufen <blöckeProSekunde>` | Alle Bots laufen los (Gehen 4.3, Sprinten 5.6, Elytra ca. 30). `0` = stehen bleiben. |
| `/chunkbots stop` | Beendet den Stresstest: Bots anhalten und alle entfernen. |
| `/chunkbots sichtweite <name\|alle> <sichtweite>` | Ändert die Sichtweite eines Bots oder aller Bots. |
| `/chunkbots speed <chunksProTick>` | Legt fest, wie viele Chunks pro Tick geladen werden. |

Bots landen in der Dimension, in der du den Befehl ausführst (Overworld, Nether, End).

### Beispiele

```
/chunkbots spawn 10 8          -> 10 Bots mit Sichtweite 8, direkt nebeneinander
/chunkbots spawn 5 4 50        -> 5 Bots mit Sichtweite 4, je 50 Chunks Abstand
/chunkbots random 20 6 5000    -> 20 Bots, zufällig im Umkreis von 5000 Blöcken
/chunkbots add Farm 3 1200 -800
```

### Typischer Stresstest

```
/chunkbots random 20 10 3000   -> 20 Bots mit Sichtweite 10 verteilen
/chunkbots status              -> warten, bis die Warteschlange bei 0 ist
/chunkbots laufen 10           -> alle Bots laufen mit 10 Blöcken/s los
/chunkbots status              -> MSPT und TPS beobachten
/chunkbots stop                -> Test beenden
```

**So liest du die Werte:** Unter 50 MSPT hält der Server 20 TPS und läuft flüssig. Steigt
die Warteschlange immer weiter an, laufen die Bots schneller, als Chunks geladen werden.
Dann mit `/chunkbots speed <zahl>` mehr Chunks pro Tick laden lassen. Das belastet den
Server zusätzlich.

Tipp: Teste in einer Kopie deiner Welt. Laufende Bots generieren viel neues Gelände, und
dadurch wächst die Welt-Datei.

Ein Bot mit Sichtweite `s` lädt `(2s+1)²` Chunks. Bei Sichtweite 10 sind das 441 Chunks.
Viele Bots mit großer Sichtweite brauchen viel RAM und CPU.

## Einstellungen (`config/chunkbots.json`)

```json
{
  "chunksPerTick": 40,
  "maxViewDistance": 32,
  "maxBots": 1000,
  "moveSpeed": 0,
  "logIntervalSeconds": 10,
  "bots": [ ... ]
}
```

Ändere die Datei nur, wenn der Server aus ist. Beim Stoppen wird sie überschrieben.

## Wie es funktioniert und was es nicht kann

Die Bots sind **keine echten Spieler-Entities**. Sie stehen nicht in der Tab-Liste und
belegen keine Spieler-Slots. Für jeden Bot werden die Chunks in seinem Umkreis per
Force-Load geladen, wie mit `/forceload`. Redstone, Öfen, Trichter, Entities usw.
laufen dort weiter. Zufalls-Ticks (z. B. Pflanzenwachstum) und Mob-Spawning hängen in
Vanilla davon ab, ob ein echter Spieler in der Nähe ist. Ob das in Force-Load-Chunks
passiert, bestimmt Minecraft, nicht diese Mod.

Wenn du einen Bot entfernst, gibt die Mod seine Chunks wieder frei. Hast du einen
dieser Chunks vorher selbst mit `/forceload` geladen, wird er dabei ebenfalls freigegeben.
