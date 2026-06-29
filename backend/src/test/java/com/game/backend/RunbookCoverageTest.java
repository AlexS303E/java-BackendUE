package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunbookCoverageTest {
    private static final Path RUNBOOK = Path.of("..", "docs", "runbooks.md");
    private static final List<String> REQUIRED_RUNBOOKS = List.of(
        "DB down",
        "Redis down",
        "Outbox stuck",
        "Catalog rollback",
        "DS revoke",
        "Overload"
    );
    private static final List<String> REQUIRED_SECTIONS = List.of(
        "### Symptoms",
        "### Immediate action",
        "### Verification",
        "### Follow-up"
    );

    @Test
    void operationalRunbooksCoverStageOneIncidentSet() throws IOException {
        String content = Files.readString(RUNBOOK);

        assertThat(content)
            .as("Stage 1 operational runbooks should exist in docs/runbooks.md")
            .isNotBlank();

        REQUIRED_RUNBOOKS.forEach(runbook -> assertThat(content)
            .as("Missing runbook heading for %s", runbook)
            .contains("## " + runbook));
    }

    @Test
    void eachRunbookHasActionableIncidentSections() throws IOException {
        String content = Files.readString(RUNBOOK);

        REQUIRED_RUNBOOKS.forEach(runbook -> {
            String section = sectionFor(content, runbook);

            REQUIRED_SECTIONS.forEach(requiredSection -> assertThat(section)
                .as("%s runbook must contain %s", runbook, requiredSection)
                .contains(requiredSection));
        });
    }

    @Test
    void incidentRunbooksMentionOperationalMetricSignals() throws IOException {
        String content = Files.readString(RUNBOOK);

        assertThat(sectionFor(content, "Outbox stuck"))
            .contains("outbox.pending.lag.seconds")
            .contains("outbox.events")
            .contains("outbox.circuit_breaker.open")
            .contains("outbox.circuit_breaker.opened");

        assertThat(sectionFor(content, "Overload"))
            .contains("backend.rate_limit.rejections");
    }

    private static String sectionFor(String content, String runbook) {
        String heading = "## " + runbook;
        int start = content.indexOf(heading);

        assertThat(start)
            .as("Missing runbook heading for %s", runbook)
            .isNotNegative();

        int next = content.indexOf("\n## ", start + heading.length());
        return next == -1 ? content.substring(start) : content.substring(start, next);
    }
}
