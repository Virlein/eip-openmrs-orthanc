/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.Constants;
import com.ozonehis.eip.openmrs.orthanc.config.OpenmrsConfig;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancTokenProvider;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsDiagnosticReportHandler;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedStudyRepository;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Setter
@Component
public class ImagingSRProcessor implements Processor {

    @Autowired
    private OpenmrsDiagnosticReportHandler openmrsDiagnosticReportHandler;

    @Autowired
    private ProcessedStudyRepository processedStudyRepository;

    @Autowired
    private OpenmrsConfig openmrsConfig;

    @Autowired
    private OrthancTokenProvider orthancTokenProvider;

    @Value("${orthanc.baseUrl:http://orthanc:8042}")
    private String orthancBaseUrl;

    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            String body = exchange.getMessage().getBody(String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode changes = root.get("Changes");
            long lastSeq = root.get("Last").asLong();

            if (changes == null || changes.isEmpty()) {
                exchange.getMessage().setHeader(Constants.HEADER_STUDIES_SINCE, lastSeq);
                exchange.setProperty("orthanc.sr.changes.cursor", lastSeq);
                return;
            }

            log.info("SR processor: processing {} changes, last={}", changes.size(), lastSeq);
            for (JsonNode change : changes) {
                String changeType = change.get("ChangeType").asText();
                String resourceType = change.get("ResourceType").asText();
                String instanceId = change.get("ID").asText();

                // Only process new SR instances
                if (!"Instance".equals(resourceType) || !"NewInstance".equals(changeType)) {
                    continue;
                }
                log.info("SR processor: checking instance {} for SR modality", instanceId);

                // Check if this instance is an SR
                handlePotentialSR(producerTemplate, mapper, instanceId);
            }

            exchange.getMessage().setHeader(Constants.HEADER_STUDIES_SINCE, lastSeq);
            exchange.setProperty("orthanc.sr.changes.cursor", lastSeq);
        } catch (Exception e) {
            throw new EIPException(
                    String.format("Error processing SR changes: %s", e.getMessage()));
        }
    }

    private void handlePotentialSR(ProducerTemplate producerTemplate, ObjectMapper mapper, String instanceId) {
        try {
            // Fetch instance metadata from Orthanc
            Map<String, Object> headers = new HashMap<>();
            headers.put(Constants.CAMEL_HTTP_METHOD, Constants.GET);
            headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
            headers.put("token", orthancTokenProvider.getToken());

            String instanceJson = producerTemplate.requestBodyAndHeaders(
                    "direct:orthanc-get-instance-route",
                    null,
                    Map.of(
                        Constants.CAMEL_HTTP_METHOD, Constants.GET,
                        Constants.CONTENT_TYPE, Constants.APPLICATION_JSON,
                        "token", orthancTokenProvider.getToken(),
                        "orthanc.instance.id", instanceId
                    ),
                    String.class);

            JsonNode instance = mapper.readTree(instanceJson);
            if (instance == null) return;

            // Modality is at series level, not instance level - get parent series
            String parentSeries = instance.has("ParentSeries") ? instance.get("ParentSeries").asText() : null;
            if (parentSeries == null) return;

            // Fetch series to check modality
            String seriesJson = producerTemplate.requestBodyAndHeaders(
                    "direct:orthanc-get-series-by-id-route",
                    null,
                    Map.of(
                        Constants.CAMEL_HTTP_METHOD, Constants.GET,
                        Constants.CONTENT_TYPE, Constants.APPLICATION_JSON,
                        "token", orthancTokenProvider.getToken(),
                        "orthanc.series.id", parentSeries
                    ),
                    String.class);

            JsonNode series = mapper.readTree(seriesJson);
            JsonNode seriesDicomTags = series.has("MainDicomTags") ? series.get("MainDicomTags") : null;
            String modality = seriesDicomTags != null && seriesDicomTags.has("Modality")
                ? seriesDicomTags.get("Modality").asText() : "";

            if (!"SR".equals(modality)) return;

            log.info("SR instance detected: {}", instanceId);

            // Get parent study ID from series
            String parentStudy = series.has("ParentStudy") ? series.get("ParentStudy").asText() : null;
            if (parentStudy == null) return;

            // Check if we have a DiagnosticReport for this study
            String[] patientAndReport = processedStudyRepository.findByOrthancStudyId(parentStudy);
            if (patientAndReport == null) {
                log.warn("No processed study found for SR parent study {}", parentStudy);
                return;
            }

            String patientUUID = patientAndReport[0];
            String reportUUID = patientAndReport[1];

            // Parse SR content
            String srText = parseSRContent(mapper, seriesDicomTags, series);

            // Update DiagnosticReport and Observation
            openmrsDiagnosticReportHandler.updateDiagnosticReportWithSR(
                    producerTemplate, patientUUID, reportUUID, srText);

        } catch (Exception e) {
            log.error("Error handling potential SR instance {}: {}", instanceId, e.getMessage());
        }
    }

    private String parseSRContent(ObjectMapper mapper, JsonNode mainDicomTags, JsonNode instance) {
        StringBuilder content = new StringBuilder();

        // Try to get study description
        if (mainDicomTags.has("StudyDescription")) {
            content.append("Study: ").append(mainDicomTags.get("StudyDescription").asText()).append("\n");
        }

        // Try to get series description
        if (mainDicomTags.has("SeriesDescription")) {
            content.append("Series: ").append(mainDicomTags.get("SeriesDescription").asText()).append("\n");
        }

        // Get content from TextValue if available
        if (instance.has("Content")) {
            JsonNode contentNode = instance.get("Content");
            if (contentNode.has("TextValue")) {
                content.append("Report: ").append(contentNode.get("TextValue").asText());
            }
        }

        return content.length() > 0 ? content.toString() : "Radiology report available (SR instance: " + instance.get("ID") + ")";
    }
}
