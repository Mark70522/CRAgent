import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calls MCP tools from the command line, without Copilot. No dependencies: run it straight from the JDK.
 *
 *   java scripts/CallTool.java status
 *   java scripts/CallTool.java get_change "{\"number\":\"CHG0012345\"}"
 *   java scripts/CallTool.java use_tools "{\"groups\":[\"cr\"]}" tools/list          several calls, one session
 *   java scripts/CallTool.java tools/list -- --tools.mode=groups                       what the client would see
 *
 * Arguments are `tool [jsonArgs]` pairs (a json argument starts with "{"); `tools/list` lists the registered
 * tools. Anything after "--" is passed to the server as extra arguments. Notifications from the server
 * (e.g. tools/list_changed) are printed as "<< notification ...". Exit code 1 if any call errored.
 */
public class CallTool {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.err.println("usage: java scripts/CallTool.java <tool> [jsonArgs] [<tool> [jsonArgs] ...] [-- serverArgs...]"); System.exit(2); }
        List<String[]> calls = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        boolean afterSep = false;
        for (String s : a) {
            if ("--".equals(s)) { afterSep = true; continue; }
            if (afterSep) { extra.add(s); continue; }
            if (s.startsWith("{") && !calls.isEmpty()) calls.get(calls.size() - 1)[1] = s;
            else calls.add(new String[]{s, "{}"});
        }
        Path root = Path.of("").toAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of("java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-jar", root.resolve("target/cr-agent.jar").toString()));
        cmd.addAll(extra);
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BufferedWriter in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"calltool\",\"version\":\"0\"}}}\n");
        in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
        in.flush();
        if (!waitFor(out, 1)) { System.err.println("server did not answer initialize"); p.destroy(); System.exit(1); }

        int exit = 0, id = 1;
        for (String[] call : calls) {
            id++;
            String tool = call[0], args = call[1];
            if ("tools/list".equals(tool)) in.write("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}\n");
            else in.write("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}\n");
            in.flush();
            String line = read(out, id);
            if (line == null) { System.err.println("no response for " + tool); exit = 1; break; }
            if (calls.size() > 1) System.out.println("== " + tool);
            if ("tools/list".equals(tool)) {
                List<String> names = new ArrayList<>();
                int i = 0;
                while ((i = line.indexOf("\"name\":\"", i)) >= 0) { int e = line.indexOf('"', i + 8); names.add(line.substring(i + 8, e)); i = e; }
                System.out.println(names.size() + " tools: " + names);
                // CALLTOOL_RAW=<file>: keep the raw tools/list response for a closer look
                if (System.getenv("CALLTOOL_RAW") != null) java.nio.file.Files.writeString(Path.of(System.getenv("CALLTOOL_RAW")), line, StandardCharsets.UTF_8);
                // what the client sends with every prompt: the whole definition list (~4 chars per token for this JSON)
                System.out.println("definitions: " + line.length() + " chars, ~" + line.length() / 4 + " tokens");
                List<int[]> starts = new ArrayList<>();
                int j = 0;
                while ((j = line.indexOf("{\"name\":\"", j)) >= 0) { starts.add(new int[]{j}); j++; }
                List<String> sizes = new ArrayList<>();
                for (int k = 0; k < starts.size(); k++) {
                    int from = starts.get(k)[0], to = k + 1 < starts.size() ? starts.get(k + 1)[0] : line.length();
                    int end = line.indexOf('"', from + 9);
                    sizes.add(String.format("%6d %s", to - from, line.substring(from + 9, end)));
                }
                sizes.sort(Comparator.reverseOrder());
                System.out.println("largest: " + sizes.subList(0, Math.min(8, sizes.size())).stream().map(String::trim).toList());
                continue;
            }
            int t = line.indexOf("\"text\":\"");
            if (t > 0) {
                System.out.println(unescape(line, t + 8));
                if (line.contains("\"isError\":true")) exit = 1;
            } else {
                System.out.println(line);
                if (line.contains("\"error\"")) exit = 1;
            }
        }
        in.close();
        p.destroy();
        System.exit(exit);
    }

    /** Reads until the response with this id; prints notifications seen on the way. */
    static String read(BufferedReader out, int id) throws Exception {
        String line;
        while ((line = out.readLine()) != null) {
            if (!line.startsWith("{")) continue;
            if (line.contains("\"method\":\"notifications/")) { System.out.println("<< notification " + line.replaceAll(".*\"method\":\"([^\"]+)\".*", "$1")); continue; }
            if (line.contains("\"id\":" + id + ",") || line.contains("\"id\":" + id + "}")) return line;
        }
        return null;
    }

    static boolean waitFor(BufferedReader out, int id) throws Exception { return read(out, id) != null; }

    static String unescape(String line, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case '"' -> sb.append('"'); case '\\' -> sb.append('\\'); case '/' -> sb.append('/');
                    case 'u' -> { sb.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16)); i += 4; }
                    default -> sb.append(n);
                }
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }
}
