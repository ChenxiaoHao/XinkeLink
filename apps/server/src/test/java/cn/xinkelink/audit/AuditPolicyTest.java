package cn.xinkelink.audit;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AuditPolicyTest {
    @Test void onlyAllowlistedFieldNamesAreRecorded() {
        assertThat(AuditPolicy.fields(List.of("status", "password", "accessToken", "body", "version")))
                .containsExactly("status", "version");
    }
    @Test void csvNeutralizesFormulaAndEscapesQuotes() {
        assertThat(AuditPolicy.csv("=HYPERLINK(\"secret\")")).isEqualTo("\"'=HYPERLINK(\"\"secret\"\")\"");
    }
}
