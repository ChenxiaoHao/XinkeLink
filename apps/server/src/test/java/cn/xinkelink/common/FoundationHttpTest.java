package cn.xinkelink.common;

import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.common.api.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FoundationHttpTest.ProbeController.class)
class FoundationHttpTest {
    @Autowired MockMvc mvc;

    @Test
    @WithMockUser
    void returnsEnvelopeWithStringIdAndUtcMilliseconds() throws Exception {
        mvc.perform(get("/api/v1/test/foundation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-24T01:00:00.000Z"))
                .andExpect(jsonPath("$.timestamp", matchesPattern(".*\\.\\d{3}Z")))
                .andExpect(jsonPath("$.requestId", not(emptyString())))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void rejectsAnonymousApiAccessWithJson() throws Exception {
        mvc.perform(get("/api/v1/test/foundation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.requestId", not(emptyString())));
    }

    @Test
    @WithMockUser(roles = "USER")
    void rejectsUserFromAdministratorRoutes() throws Exception {
        mvc.perform(get("/api/v1/admin/test/foundation"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void permitsAdministratorBoundary() throws Exception {
        mvc.perform(get("/api/v1/admin/test/foundation"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void validatesBodyWithoutEchoingItsValue() throws Exception {
        mvc.perform(post("/api/v1/test/foundation").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test
    @WithMockUser
    void malformedBodyNeverReturnsParserDetails() throws Exception {
        mvc.perform(post("/api/v1/test/foundation").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"private-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test
    @WithMockUser
    void rejectsUnknownBodyFieldsInsteadOfSilentlyAcceptingThem() throws Exception {
        mvc.perform(post("/api/v1/test/foundation").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"同学\",\"studentId\":\"private-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test
    @WithMockUser
    void retainsCsrfProtectionUntilRealAuthenticationIsImplemented() throws Exception {
        mvc.perform(post("/api/v1/test/foundation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"同学\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser
    void unsupportedRequestsKeepTheErrorContract() throws Exception {
        mvc.perform(put("/api/v1/test/foundation").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/test/foundation").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @WithMockUser
    void validatesPaginationAndUsesDefaults() throws Exception {
        mvc.perform(get("/api/v1/test/page"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
        mvc.perform(get("/api/v1/test/page?page=0&pageSize=500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/test/page?pageSize=11"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void mapsBusinessConflictWithoutChangingItsStatus() throws Exception {
        mvc.perform(get("/api/v1/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    @WithMockUser
    void hidesUnexpectedExceptionDetails() throws Exception {
        mvc.perform(get("/api/v1/test/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("password=private-value"))));
    }

    @Test
    @WithMockUser
    void unknownApiReturnsTheSameErrorShape() throws Exception {
        mvc.perform(get("/api/v1/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser
    void replacesUntrustedRequestIds() throws Exception {
        mvc.perform(get("/api/v1/test/foundation").header("X-Request-Id", "password=private-value"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test
    void apiDocumentationIsDisabledInTheDefaultProfile() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void routesOutsideTheApiCannotBypassTheSecurityBoundary() throws Exception {
        mvc.perform(get("/internal-test/probe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 探测接口只存在于测试，不向正式产品增加业务入口。
    @RestController
    static class ProbeController {
        @GetMapping("/internal-test/probe")
        String outsideApi() { return "不应公开访问"; }

        @GetMapping({"/api/v1/test/foundation", "/api/v1/admin/test/foundation"})
        ApiResponse<Sample> success() {
            return ApiResponse.success(new Sample("9007199254740993", 2, Instant.parse("2026-10-24T01:00:00Z")));
        }

        @PostMapping("/api/v1/test/foundation")
        ApiResponse<Input> validate(@Valid @RequestBody Input input) { return ApiResponse.success(input); }

        @GetMapping("/api/v1/test/page")
        ApiResponse<PageQuery> page(@Valid @ModelAttribute PageQuery query) { return ApiResponse.success(query); }

        @GetMapping("/api/v1/test/conflict")
        void conflict() { throw new BusinessException(ErrorCode.VERSION_CONFLICT); }

        @GetMapping("/api/v1/test/crash")
        void crash() { throw new IllegalStateException("password=private-value"); }
    }

    record Sample(String id, int count, Instant createdAt) {}
    record Input(@NotBlank String nickname) {}
}
