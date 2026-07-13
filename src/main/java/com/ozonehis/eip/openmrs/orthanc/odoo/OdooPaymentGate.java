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
            log.info("Authenticated to Odoo for payment gate");
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

    public boolean isOrderConfirmed(String patientUuid, String procedureDesc) {
        try {
            ArrayNode args = mapper.createArrayNode();
            ArrayNode domain = mapper.createArrayNode();

            ArrayNode stateCond = mapper.createArrayNode();
            stateCond.add("state"); stateCond.add("="); stateCond.add("sale");
            domain.add(stateCond);

            ArrayNode partnerCond = mapper.createArrayNode();
            partnerCond.add("partner_id.ref"); partnerCond.add("="); partnerCond.add(patientUuid);
            domain.add(partnerCond);

            args.add(domain);

            ObjectNode kwargs = mapper.createObjectNode();
            ArrayNode fields = mapper.createArrayNode();
            fields.add("id"); fields.add("name");
            kwargs.set("fields", fields);

            JsonNode orders = callKw("sale.order", "search_read", args, kwargs);
            if (orders == null || !orders.isArray() || orders.isEmpty()) {
                log.debug("No confirmed Odoo sale order for patient {}", patientUuid);
                return false;
            }

            log.info("Found confirmed Odoo order for patient {} procedure '{}'",
                patientUuid, procedureDesc);
            return true;

        } catch (Exception e) {
            log.warn("Odoo payment gate error: {} - failing open", e.getMessage());
            return true;
        }
    }
}
