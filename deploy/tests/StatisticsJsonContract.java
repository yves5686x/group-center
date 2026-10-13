import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.khm.group.center.config.JacksonConfig;
import com.khm.group.center.datatype.response.ClientResponse;
import com.khm.group.center.datatype.statistics.Report;
import com.khm.group.center.datatype.statistics.ReportType;
import com.khm.group.center.datatype.statistics.UserActivityTimeDistribution;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Verify the shipped statistics models through the real HTTP converter. */
public class StatisticsJsonContract {
    public static void main(String[] args) throws Exception {
        var date = LocalDate.of(2026, 10, 13);
        var time = LocalDateTime.of(2026, 10, 13, 16, 27, 14);
        var report = new Report(ReportType.TODAY, "JSON contract", date, date,
            time, time, time, time, 0, 0, 0, List.of(), List.of(), List.of(), null, time);
        var activity = new UserActivityTimeDistribution(List.of(), 0, time);
        var config = new JacksonConfig();
        var mapper = config.jacksonHttpMessageConverter(config.objectMapper()).getObjectMapper();

        if (args.length > 0 && args[0].equals("expect-unsupported-time")) {
            for (Object model : List.of(report, activity)) {
                try {
                    mapper.writeValueAsString(model);
                    throw new AssertionError("Previous version unexpectedly supports time types");
                } catch (InvalidDefinitionException expected) {
                    if (!expected.getMessage().contains("java.time.LocalDate")) throw expected;
                    System.out.println("Previous packaged version reproduces: " + expected.getOriginalMessage());
                }
            }
            return;
        }

        var envelope = new ClientResponse();
        envelope.setResult(report);
        JsonNode reportJson = mapper.readTree(mapper.writeValueAsString(envelope)).path("result");
        if (!reportJson.path("periodStartDate").isTextual()
                || !reportJson.path("periodStartDate").asText().equals("2026-10-13")
                || !reportJson.path("startTime").asText().equals("2026-10-13T16:27:14")
                || !reportJson.path("refreshTime").asText().equals("2026-10-13T16:27:14")) {
            throw new AssertionError("Report dates must be ISO strings: " + reportJson);
        }
        envelope.setResult(activity);
        JsonNode activityJson = mapper.readTree(mapper.writeValueAsString(envelope)).path("result");
        if (!activityJson.path("refreshTime").asText().equals("2026-10-13T16:27:14")) {
            throw new AssertionError("Activity refreshTime must be an ISO string: " + activityJson);
        }
        var restored = mapper.readValue(mapper.writeValueAsString(report), Report.class);
        if (!restored.getPeriodStartDate().equals(date) || !restored.getRefreshTime().equals(time)) {
            throw new AssertionError("Date/time JSON round trip failed");
        }
        System.out.println("Statistics JSON contract passed: LocalDate and LocalDateTime ISO serialization/deserialization");
    }
}
