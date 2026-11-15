import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Extracts packet ID tables from the Loom-generated Minecraft sources.
 * Packet IDs are implicit: they are the registration order of addPacket(...) / withBundlePacket(...)
 * calls inside each *_TEMPLATE builder (see ProtocolInfoBuilder / ProtocolCodecBuilder / IdDispatchCodec).
 *
 * Usage: java PacketTableExtractor.java <mc-src-root> <output.md>
 */
public class PacketTableExtractor {
    static final String[][] FILES = {
        {"handshake", "net/minecraft/network/protocol/handshake/HandshakeProtocols.java"},
        {"status", "net/minecraft/network/protocol/status/StatusProtocols.java"},
        {"login", "net/minecraft/network/protocol/login/LoginProtocols.java"},
        {"configuration", "net/minecraft/network/protocol/configuration/ConfigurationProtocols.java"},
        {"play", "net/minecraft/network/protocol/game/GameProtocols.java"},
    };

    static final Pattern TEMPLATE = Pattern.compile("(SERVERBOUND|CLIENTBOUND)_TEMPLATE\\s*=");
    static final Pattern ENTRY = Pattern.compile(
        "\\.addPacket\\(\\s*([A-Za-z]+PacketTypes)\\.([A-Z0-9_]+)\\s*,\\s*([A-Za-z0-9_.]+?)(?:\\.STREAM_CODEC|\\.CODEC|::|\\s*,|\\))"
        + "|\\.withBundlePacket\\(\\s*([A-Za-z]+PacketTypes)\\.([A-Z0-9_]+)\\s*,\\s*[A-Za-z0-9_]+::new\\s*,\\s*new\\s+([A-Za-z0-9_]+)\\(");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        StringBuilder out = new StringBuilder();
        out.append("# Paket-ID-Tabellen (Minecraft 26.3, Protokoll 777)\n\n");
        out.append("> Automatisch erzeugt von `analysis/tools/PacketTableExtractor.java` aus den Loom-Quellen.\n");
        out.append("> Die ID ist die Registrierungsreihenfolge im jeweiligen `*_TEMPLATE` (VarInt-kodiert, siehe `IdDispatchCodec.encode/decode`).\n");
        out.append("> `withBundlePacket` registriert nur das Delimiter-Paket (`ProtocolInfoBuilder.withBundlePacket`).\n");
        out.append(">\n> **Laufzeit-verifiziert** (docs/10-laufzeittest.md): alle IDs stimmen mit dem offiziellen Report\n");
        out.append("> `net.minecraft.data.Main --reports` → `packets.json` überein (260/260: 254 namensgleich + 6 nur namensverschieden, 0 ID-Abweichungen).\n");
        out.append("> Achtung: Die Spalte *PacketType* zeigt den Java-Konstantennamen, nicht immer die Ressourcen-ID auf dem Draht/im Log:\n");
        out.append("> `CLIENT_INTENTION` = `minecraft:intention`, `CLIENTBOUND_BUNDLE` = `minecraft:bundle_delimiter`,\n");
        out.append("> `CLIENTBOUND_DEBUG_*_VALUE`/`DEBUG_EVENT` = `minecraft:debug/*_value` bzw. `minecraft:debug/event`.\n\n");
        int total = 0;
        for (String[] f : FILES) {
            String src = Files.readString(root.resolve(f[1]));
            Matcher t = TEMPLATE.matcher(src);
            List<int[]> starts = new ArrayList<>();
            List<String> dirs = new ArrayList<>();
            while (t.find()) { starts.add(new int[]{t.start()}); dirs.add(t.group(1)); }
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i)[0];
                // template ends at the first ");\n\tpublic static" after start
                int e = src.indexOf("\n\tpublic static", s + 10);
                if (e < 0) e = src.length();
                String body = src.substring(s, e);
                Matcher m = ENTRY.matcher(body);
                out.append("## ").append(f[0]).append(" / ").append(dirs.get(i).toLowerCase()).append("\n\n");
                out.append("Quelle: `").append(f[1].replace(".java", "")).append(".").append(dirs.get(i)).append("_TEMPLATE`\n\n");
                out.append("| ID | Hex | PacketType | Klasse |\n|---:|---:|---|---|\n");
                int id = 0;
                while (m.find()) {
                    String types, name, cls;
                    if (m.group(1) != null) { types = m.group(1); name = m.group(2); cls = m.group(3); }
                    else { types = m.group(4); name = m.group(5); cls = m.group(6) + " (Bundle-Delimiter)"; }
                    out.append(String.format("| %d | 0x%02X | `%s.%s` | `%s` |%n", id, id, types, name, cls));
                    id++;
                }
                total += id;
                out.append("\n").append(id).append(" Pakete.\n\n");
            }
        }
        out.append("Gesamt: ").append(total).append(" Paketdefinitionen.\n");
        Files.writeString(Path.of(args[1]), out.toString());
        System.out.println("written, total=" + total);
    }
}
