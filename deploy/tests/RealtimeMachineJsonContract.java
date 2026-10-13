import com.fasterxml.jackson.databind.JsonNode;
import com.khm.group.center.config.JacksonConfig;
import com.khm.group.center.datatype.realtime.RealtimeMachineBrief;
import com.khm.group.center.datatype.response.ClientResponse;
import java.util.List;

/** Run against the packaged JAR's actual HTTP converter without a database. */
public class RealtimeMachineJsonContract {
    public static void main(String[] args) throws Exception {
        String expectedField = args.length == 0 ? "gpu" : args[0];
        var gpu = new RealtimeMachineBrief();
        gpu.setServerNameEng("gpu-test");
        gpu.setGpu(true);
        var cpu = new RealtimeMachineBrief();
        cpu.setServerNameEng("cpu-test");
        cpu.setGpu(false);
        var envelope = new ClientResponse();
        envelope.setResult(List.of(gpu, cpu));

        var config = new JacksonConfig();
        var converter = config.jacksonHttpMessageConverter(config.objectMapper());
        var mapper = converter.getObjectMapper();
        String json = mapper.writeValueAsString(envelope);
        JsonNode response = mapper.readTree(json);
        JsonNode rows = response.get("result");
        System.out.println("Actual HTTP converter JSON: " + json);
        if (rows == null || rows.size() != 2) {
            throw new AssertionError("Expected two machine records");
        }
        for (int i = 0; i < 2; i++) {
            JsonNode value = rows.get(i).get(expectedField);
            if (value == null || !value.isBoolean() || value.booleanValue() != (i == 0)) {
                throw new AssertionError("Expected " + expectedField + "=" + (i == 0));
            }
            String otherField = expectedField.equals("gpu") ? "isGpu" : "gpu";
            if (rows.get(i).has(otherField)) {
                throw new AssertionError("Unexpected duplicate/legacy field " + otherField);
            }
        }
        if (!response.path("isSucceed").asBoolean()) {
            throw new AssertionError("Response envelope naming changed");
        }
        System.out.println("Realtime machine JSON contract passed: " + expectedField);
    }
}
