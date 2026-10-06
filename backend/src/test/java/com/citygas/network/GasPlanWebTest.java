package com.citygas.network;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GasPlanWebTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void context_only_returns_current_tenant_and_validation_endpoint_reports_capacity_errors() throws Exception {
        useTenant(A);
        var gate = newGate(A, "GS-WP1", "A门站");
        gate.setInletCapacityM3(new BigDecimal("100"));
        gateStationRepository.saveAndFlush(gate);
        var regulator = newRegulator(A, "RS-WP1");
        regulator.setOutletCapacityM3(new BigDecimal("50"));
        regulatorRepository.saveAndFlush(regulator);

        useTenant(B);
        var gateB = newGate(B, "GS-WPB", "B门站");
        gateB.setInletCapacityM3(new BigDecimal("999"));
        gateStationRepository.saveAndFlush(gateB);

        mockMvc.perform(get("/api/gas-supply-plans/context").header("X-Tenant", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gateStations[0].stationCode").value("GS-WP1"))
                .andExpect(jsonPath("$.data.regulatorStations[0].stationCode").value("RS-WP1"));

        String draft = """
                {"planNo":"GP-W1","planDate":"2026-10-06","areaDemandM3":100,"demandSource":"METER_SNAPSHOT",
                 "operator":"编制人甲","allocations":[
                 {"stationType":"GATE_STATION","stationId":%d,"quotaM3":120},
                 {"stationType":"REGULATOR_STATION","stationId":%d,"quotaM3":10}
                ]}
                """.formatted(gate.getId(), regulator.getId());
        MvcResult created = mockMvc.perform(post("/api/gas-supply-plans")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("plan").path("id").asLong();

        mockMvc.perform(get("/api/gas-supply-plans/" + id + "/validation").header("X-Tenant", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validForSubmit").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("进站能力")));

        mockMvc.perform(post("/api/gas-supply-plans/" + id + "/submit")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"operator":"提交人甲"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("提交被拦截")));
    }

    @Test
    void date_and_status_filter_works_and_cross_tenant_version_is_invisible() throws Exception {
        useTenant(A);
        var gate = newGate(A, "GS-F1", "过滤门站");
        gate.setInletCapacityM3(new BigDecimal("100000"));
        gate = gateStationRepository.saveAndFlush(gate);
        var regulator = newRegulator(A, "RS-F1");
        regulator.setOutletCapacityM3(new BigDecimal("50000"));
        regulator = regulatorRepository.saveAndFlush(regulator);

        String body = """
                {"planNo":"GP-F1","planDate":"%s","areaDemandM3":100,"demandSource":"MANUAL",
                 "operator":"编制人甲","allocations":[
                 {"stationType":"GATE_STATION","stationId":%d,"quotaM3":100},
                 {"stationType":"REGULATOR_STATION","stationId":%d,"quotaM3":50}
                ]}
                """.formatted(LocalDate.of(2026, 10, 6), gate.getId(), regulator.getId());
        MvcResult created = mockMvc.perform(post("/api/gas-supply-plans")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        long id = json.path("data").path("plan").path("id").asLong();

        mockMvc.perform(get("/api/gas-supply-plans")
                        .header("X-Tenant", A)
                        .param("planDate", "2026-10-06")
                        .param("status", "草稿"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].planNo").value("GP-F1"));

        mockMvc.perform(get("/api/gas-supply-plans")
                        .header("X-Tenant", A)
                        .param("planDate", "2026-10-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/gas-supply-plans/" + id).header("X-Tenant", B))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));
    }
}
