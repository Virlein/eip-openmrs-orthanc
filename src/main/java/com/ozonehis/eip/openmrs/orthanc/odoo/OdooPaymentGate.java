/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class OdooPaymentGate {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${odoo.baseUrl:http://odoo:8069}")
    private String odooBaseUrl;

    @Value("${odoo.database:odoo}")
    private String odooDatabase;

    @Value("${odoo.username:admin}")
    private String odooUsername;

    @Value("${odoo.password:admin}")
    private String odooPassword;

    private volatile String sessionCookie = null;

    private void authenticate() throws IOException {
        if (sessionCookie != null) return;

        ObjectNode params = mapper.createObjectNode();
        params.put("db", odooDatabase);
        params.put("login", odooUsername);
        params.put("password", odooPassword);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "call");
        payload.put("id", 1);
        payload.set("params", params);

        Request request = new Request.Builder()
            .url(odooBaseUrl + "/web/session/authenticate")
            .post(RequestBody.create(
                mapper.writeValueAsString(payload),
                MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String setCookie = response.header("Set-Cookie");
            if (setCookie != null) sessionCookie = setCookie.split(";")[0];
            JsonNode root = mapper.readTree(response.body().string());
            if (root.path("result").path("uid").isNull()) {
                sessionCookie = null;
                throw new IOException("Odoo auth failed");
            }
            log.debug("Payment gate: authenticated to Odoo");
        }
    }

    private JsonNode callKw(String model, String method, ArrayNode args, ObjectNode kwargs)
            throws IOException {
        authenticate();

        ObjectNode params = mapper.createObjectNode();
        params.put("model", model);
        params.put("method", method);
        params.set("args", args != null ? args : mapper.createArrayNode());
        params.set("kwargs", kwargs != null ? kwargs : mapper.createObjectNode());

        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "call");
        payload.put("id", 1);
        payload.set("params", params);

        Request request = new Request.Builder()
            .url(odooBaseUrl + "/web/dataset/call_kw")
            .header("Cookie", sessionCookie)
            .post(RequestBody.create(
                mapper.writeValueAsString(payload),
                MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode root = mapper.readTree(response.body().string());
            if (root.has("error")) {
                sessionCookie = null;
                throw new IOException("Odoo error: " + root.get("error"));
            }
            return root.get("result");
        }
    }

    /**
     * Check if the SPECIFIC procedure has been invoiced in Odoo (per-scan gating).
     * Queries sale.order.line directly via order_id.partner_id.ref, checking
     * qty_invoiced > 0, regardless of the parent order's current state.
     */
    public boolean isOrderConfirmed(String patientUuid, String procedureDesc) {
        log.debug("Payment gate: checking patient={} procedure={}", patientUuid, procedureDesc);
        try {
            ArrayNode lineArgs = mapper.createArrayNode();
            ArrayNode lineDomain = mapper.createArrayNode();

            ArrayNode partnerCond = mapper.createArrayNode();
            partnerCond.add("order_id.partner_id.ref");
            partnerCond.add("=");
            partnerCond.add(patientUuid);
            lineDomain.add(partnerCond);

            ArrayNode invoicedCond = mapper.createArrayNode();
            invoicedCond.add("qty_invoiced");
            invoicedCond.add(">");
            invoicedCond.add(0);
            lineDomain.add(invoicedCond);

            lineArgs.add(lineDomain);

            ObjectNode lineKwargs = mapper.createObjectNode();
            ArrayNode lineFields = mapper.createArrayNode();
            lineFields.add("name");
            lineFields.add("qty_invoiced");
            lineFields.add("order_id");
            lineKwargs.set("fields", lineFields);

            JsonNode lines = callKw("sale.order.line", "search_read", lineArgs, lineKwargs);
            log.debug("Payment gate: query result = {}", lines);

            if (lines == null || !lines.isArray() || lines.size() == 0) {
                log.info("Payment gate: no invoiced lines for patient {} - blocking", patientUuid);
                return false;
            }

            String matchKey = procedureDesc != null && procedureDesc.length() > 6
                ? procedureDesc.substring(0, 6).toLowerCase()
                : (procedureDesc != null ? procedureDesc.toLowerCase() : "");

            for (JsonNode line : lines) {
                String lineName = line.path("name").asText("").toLowerCase();
                if (!matchKey.isEmpty() && lineName.contains(matchKey)) {
                    log.info("Payment gate: patient {} procedure '{}' is invoiced - allowing",
                        patientUuid, procedureDesc);
                    return true;
                }
            }

            log.info("Payment gate: procedure '{}' not invoiced for patient {} - blocking",
                procedureDesc, patientUuid);
            return false;

        } catch (Exception e) {
            log.warn("Payment gate error: {} - failing open", e.getMessage());
            return true;
        }
    }
}
