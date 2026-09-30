import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls ONE MCP tool from the command line, without Copilot. Handy for checking the real instance:
 *
 *   java -cp "libs/*" scripts/CallTool.java sn_config
 *   java -cp "libs/*" scripts/CallTool.java sn_raw_get "{\"table\":\"change_request\",\"query\":\"number=CHG0012345\"}"
 *   java -cp "libs/*" scripts/CallTool.java lookup_service "{\"service\":\"Order Portal\",\"environment\":\"prod\"}"
 *
 * Anything after "--" is passed to the server as extra arguments, e.g.
 *   ... sn_config -- --spring.config.import=optional:file:C:/tmp/other.yml
 * Uses target/cr-agent.jar; set CR_AGENT_LAUNCH=offline to use run-offline.bat instead.
 */
public class CallTool {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.err.println("usage: CallTool <tool> [jsonArgs] [-- serverArgs...]"); System.exit(2); }
        ObjectMapper json = new ObjectMapper();
        String tool = a[0];
        String argsJson = "{}";
        List<String> extra = new ArrayList<>();
        boolean afterSep = false;
        for (int i = 1; i < a.length; i++) {
            if ("--".equals(a[i])) { afterSep = true; continue; }
            if (afterSep) extra.add(a[i]); else argsJson = a[i];
        }
        Path root = Path.of("").toAbsolutePath();
        List<String> cmd = new ArrayList<>();
        if ("offline".equalsIgnoreCase(System.getenv("CR_AGENT_LAUNCH")))
            cmd.addAll(List.of("cmd", "/c", root.resolve("run-offline.bat").toString()));
        else
            cmd.addAll(List.of("java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-jar", root.resolve("target/cr-agent.jar").toString()));
        cmd.addAll(extra);

        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BufferedWriter in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"calltool\",\"version\":\"0\"}}}\n");
        in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
        ObjectNode call = json.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
        ObjectNode params = json.createObjectNode().put("name", tool);
        params.set("arguments", json.readTree(argsJson));
        call.set("params", params);
        in.write(json.writeValueAsString(call)); in.write("\n"); in.flush();

        int exit = 1;
        String line;
        while ((line = out.readLine()) != null) {
            if (!line.startsWith("{")) continue;
            JsonNode n = json.readTree(line);
            if (n.path("id").asInt() != 2) continue;
            if (n.has("error")) { System.out.println("ERROR " + n.get("error")); break; }
            JsonNode res = n.path("result");
            String text = res.path("content").path(0).path("text").asText();
            if (res.path("isError").asBoolean()) { System.out.println("TOOL ERROR: " + text); break; }
            try { System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(text))); }
            catch (Exception e) { System.out.println(text); }
            exit = 0;
            break;
        }
        in.close();
        p.destroy();
        System.exit(exit);
    }
}
