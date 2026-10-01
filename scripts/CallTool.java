import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls ONE MCP tool from the command line, without Copilot. No dependencies: run it straight from the JDK.
 *
 *   java scripts/CallTool.java sn_endpoints
 *   java scripts/CallTool.java get_change "{\"number\":\"CHG0012345\"}"
 *   java scripts/CallTool.java lookup_service "{\"service\":\"Order Portal\",\"environment\":\"prod\"}"
 *
 * Anything after "--" is passed to the server as extra arguments. Prints the tool's text result as returned.
 */
public class CallTool {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.err.println("usage: java scripts/CallTool.java <tool> [jsonArgs] [-- serverArgs...]"); System.exit(2); }
        String tool = a[0], argsJson = "{}";
        List<String> extra = new ArrayList<>();
        boolean afterSep = false;
        for (int i = 1; i < a.length; i++) {
            if ("--".equals(a[i])) { afterSep = true; continue; }
            if (afterSep) extra.add(a[i]); else argsJson = a[i];
        }
        Path root = Path.of("").toAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of("java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-jar", root.resolve("target/cr-agent.jar").toString()));
        cmd.addAll(extra);
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BufferedWriter in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"calltool\",\"version\":\"0\"}}}\n");
        in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
        in.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}\n");
        in.flush();

        int exit = 1;
        String line;
        while ((line = out.readLine()) != null) {
            if (!line.startsWith("{") || !line.contains("\"id\":2")) continue;
            // print the text content unescaped when the response has the usual shape, else the raw line
            int t = line.indexOf("\"text\":\"");
            if (t > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = t + 8; i < line.length(); i++) {
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
                System.out.println(sb);
                exit = line.contains("\"isError\":true") ? 1 : 0;
            } else {
                System.out.println(line);
                exit = line.contains("\"error\"") ? 1 : 0;
            }
            break;
        }
        in.close();
        p.destroy();
        System.exit(exit);
    }
}
