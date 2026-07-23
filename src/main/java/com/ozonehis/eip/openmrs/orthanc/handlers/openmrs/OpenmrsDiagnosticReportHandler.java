/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.openmrs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.Constants;
import com.ozonehis.eip.openmrs.orthanc.config.OpenmrsConfig;
import com.ozonehis.eip.openmrs.orthanc.models.diagnosticreport.DiagnosticReportBundle;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedStudyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.ozonehis.eip.openmrs.orthanc.models.diagnosticreport.DiagnosticReportResource;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenmrsDiagnosticReportHandler {

    private static final String HEADER_ORTHANC_STUDY_ID = "orthanc.study.id";

    // General visit type UUID — present in all Ozone distros
    private static final String GENERAL_VISIT_TYPE_UUID = "32176576-1652-4835-8736-826eb0237482";

    @Autowired
    private OpenmrsConfig openmrsConfig;

    @Autowired
    private ProcessedStudyRepository processedStudyRepository;

    @Value("${openmrs.baseUrl}")
    private String openmrsBaseUrl;

    /**
     * Create an encounter via REST API and return its UUID.
     */
    private String createEncounter(ProducerTemplate producerTemplate, String patientUUID, String encounterDate)
            throws JsonProcessingException {
        String date = (encounterDate != null && !encounterDate.isEmpty())
                ? encounterDate.substring(0, 8) // YYYYMMDD → keep as-is for REST
                : java.time.LocalDate.now().toString().replace("-", "");

        // Format date for REST API: YYYYMMDD → YYYY-MM-DD HH:MM:SS
        String formattedDate = date.length() == 8
                ? date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8) + " 00:00:00"
                : java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19);

        String encounterJson = String.format(
                "{\"encounterType\":\"%s\",\"patient\":\"%s\",\"encounterDatetime\":\"%s\"}",
                "dd528487-82a5-4082-9c72-ed246bd49591", // Consultation
                patientUUID,
                formattedDate);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.POST);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-create-encounter-route",
                encounterJson,
                headers,
                String.class);

        Map<?, ?> result = new ObjectMapper().readValue(response, Map.class);
        String encounterUUID = (String) result.get("uuid");
        log.info("Created encounter {} for patient {}", encounterUUID, patientUUID);
        return encounterUUID;
    }

    /**
     * Search DiagnosticReports for a patient by their UUID.
     */
    public List<DiagnosticReportResource> getDiagnosticReportsByPatient(
            ProducerTemplate producerTemplate, String patientUUID) throws JsonProcessingException {
        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.HEADER_OPENMRS_PATIENT_UUID, patientUUID);
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.GET);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:orthanc-get-diagnostic-reports-route", null, headers, String.class);
        DiagnosticReportBundle bundle = new ObjectMapper().readValue(response, DiagnosticReportBundle.class);
        return bundle.getEntries();
    }

    /**
     * Search DiagnosticReports by Orthanc study ID.
     */
    public List<DiagnosticReportResource> getDiagnosticReportsByOrthancId(
            ProducerTemplate producerTemplate, String orthancStudyId) throws JsonProcessingException {
        Map<String, Object> headers = new HashMap<>();
        headers.put(HEADER_ORTHANC_STUDY_ID, orthancStudyId);
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.GET);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:orthanc-search-diagnostic-reports-by-id-route", null, headers, String.class);
        DiagnosticReportBundle bundle = new ObjectMapper().readValue(response, DiagnosticReportBundle.class);
        return bundle.getEntries();
    }

    /**
     * Create a DiagnosticReport in OpenMRS for a DICOM study.
     * Creates an encounter first as required by OpenMRS FHIR2.
     */
    public String createDiagnosticReport(
            ProducerTemplate producerTemplate,
            String patientUUID,
            String studyInstanceUID,
            String orthancId,
            String orthancPublicUrl,
            String modality,
            String studyDate) throws JsonProcessingException {

        // Step 1: create encounter (required by OpenMRS FHIR2)
        String encounterUUID = createEncounter(producerTemplate, patientUUID, studyDate);

        // Step 2: build viewer URL
        String viewerUrl = orthancPublicUrl
                + "/stone-webviewer/index.html?study=" + studyInstanceUID
                + "&orthancId=" + orthancId;

        // Step 3: build and POST DiagnosticReport
        DiagnosticReportResource report = DiagnosticReportResource.builder()
                .resourceType("DiagnosticReport")
                .status("final")
                .identifier(DiagnosticReportResource.buildIdentifier(orthancId))
                .category(DiagnosticReportResource.buildRadiologyCategory())
                .code(DiagnosticReportResource.buildCode(studyInstanceUID, modality))
                .subject(DiagnosticReportResource.buildReference("Patient", patientUUID))
                .encounter(DiagnosticReportResource.buildReference("Encounter", encounterUUID))
                .effectiveDateTime(studyDate != null && studyDate.length() == 8 ? studyDate.substring(0, 4) + "-" + studyDate.substring(4, 6) + "-" + studyDate.substring(6, 8) : studyDate)
                .conclusion("DICOM study available. View at: " + viewerUrl)
                .presentedForm(DiagnosticReportResource.buildAttachment(viewerUrl, studyInstanceUID))
                .build();

        String reportJson = new ObjectMapper().writeValueAsString(report);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.POST);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:orthanc-create-diagnostic-report-route", reportJson, headers, String.class);

        String reportUUID = null;
        try {
            Map<?, ?> result = new ObjectMapper().readValue(response, Map.class);
            reportUUID = (String) result.get("id");
        } catch (Exception e) {
            log.warn("Could not parse DiagnosticReport UUID from response");
        }

        processedStudyRepository.save(orthancId, patientUUID, reportUUID);
        log.info("Created DiagnosticReport for patient {} study {} encounter {}",
                patientUUID, studyInstanceUID, encounterUUID);

        // Step 4: create linked Observation with viewer URL (makes report visible in Results tab)
        if (reportUUID != null) {
            createObservationAndLinkToReport(
                    producerTemplate, patientUUID, encounterUUID, reportUUID, viewerUrl, studyDate);
        }

        return reportUUID;
    }


    /**
     * Create a text Observation with the Stone viewer URL and link it to the DiagnosticReport.
     * This makes the study visible in the OpenMRS O3 Results tab.
     */
    public String createObservationAndLinkToReport(
            ProducerTemplate producerTemplate,
            String patientUUID,
            String encounterUUID,
            String reportUUID,
            String viewerUrl,
            String studyDate) throws JsonProcessingException {

        // Step 1: create Observation with viewer URL as valueString
        String effectiveDate = studyDate != null && studyDate.length() == 8
                ? studyDate.substring(0, 4) + "-" + studyDate.substring(4, 6) + "-" + studyDate.substring(6, 8)
                : java.time.LocalDate.now().toString();

        String observationJson = String.format(
                "{\"resourceType\":\"Observation\"," +
                "\"status\":\"final\"," +
                "\"code\":{\"coding\":[{\"code\":\"%s\"}]}," +
                "\"subject\":{\"reference\":\"Patient/%s\"}," +
                "\"encounter\":{\"reference\":\"Encounter/%s\"}," +
                "\"effectiveDateTime\":\"%s\"," +
                "\"valueString\":\"%s\"}",
                Constants.GENERAL_PATIENT_NOTE_CONCEPT_UUID,
                patientUUID,
                encounterUUID,
                effectiveDate,
                "DICOM study available. View at: " + viewerUrl);

        Map<String, Object> obsHeaders = new HashMap<>();
        obsHeaders.put(Constants.CAMEL_HTTP_METHOD, Constants.POST);
        obsHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        obsHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String obsResponse = producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-create-observation-route", observationJson, obsHeaders, String.class);

        String observationUUID = null;
        try {
            Map<?, ?> obsResult = new ObjectMapper().readValue(obsResponse, Map.class);
            observationUUID = (String) obsResult.get("id");
            log.info("Created Observation {} for patient {}", observationUUID, patientUUID);
        } catch (Exception e) {
            log.warn("Could not parse Observation UUID from response: {}", e.getMessage());
            return null;
        }

        // Step 2: update DiagnosticReport to link the Observation as result
        String updateJson = String.format(
                "{\"resourceType\":\"DiagnosticReport\"," +
                "\"id\":\"%s\"," +
                "\"status\":\"final\"," +
                "\"code\":{\"coding\":[{\"code\":\"%s\"}]}," +
                "\"subject\":{\"reference\":\"Patient/%s\"}," +
                "\"encounter\":{\"reference\":\"Encounter/%s\"}," +
                "\"result\":[{\"reference\":\"Observation/%s\"}]}",
                reportUUID,
                "27fe6714-0bc6-4435-adb0-818538abe42c",
                patientUUID,
                encounterUUID,
                observationUUID);

        Map<String, Object> updateHeaders = new HashMap<>();
        updateHeaders.put(Constants.CAMEL_HTTP_METHOD, "PUT");
        updateHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        updateHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());
        updateHeaders.put(Constants.HEADER_DIAGNOSTIC_REPORT_UUID, reportUUID);

        producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-update-diagnostic-report-route", updateJson, updateHeaders, String.class);

        log.info("Linked Observation {} to DiagnosticReport {}", observationUUID, reportUUID);
        return observationUUID;
    }


    /**
     * Update a DiagnosticReport and its linked Observation with SR report content.
     */
    public void updateDiagnosticReportWithSR(
            ProducerTemplate producerTemplate,
            String patientUUID,
            String reportUUID,
            String srText,
            String procedureConceptUuid) throws JsonProcessingException {
        // Use the exact ordered procedure's concept when known (resolved via
        // AccessionNumber), so Results shows e.g. "RX01 - Chest X-ray"
        // instead of the generic "General patient note" - falls back to the
        // generic concept if the specific order could not be resolved.
        String observationConceptUuid = (procedureConceptUuid != null && !procedureConceptUuid.isEmpty())
                ? procedureConceptUuid : Constants.GENERAL_PATIENT_NOTE_CONCEPT_UUID;

        // Step 1: get existing DiagnosticReport to find linked observation and encounter
        Map<String, Object> getHeaders = new HashMap<>();
        getHeaders.put(Constants.CAMEL_HTTP_METHOD, Constants.GET);
        getHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        getHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());
        getHeaders.put(Constants.HEADER_DIAGNOSTIC_REPORT_UUID, reportUUID);

        String reportJson = producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-update-diagnostic-report-route", null, getHeaders, String.class);

        Map<?, ?> reportMap = new ObjectMapper().readValue(reportJson, Map.class);
        String encounterRef = reportMap.containsKey("encounter")
                ? ((Map<?, ?>) reportMap.get("encounter")).get("reference").toString().replace("Encounter/", "")
                : null;

        // Step 2: find existing linked observation UUID
        String observationUUID = null;
        if (reportMap.containsKey("result")) {
            List<?> results = (List<?>) reportMap.get("result");
            if (!results.isEmpty()) {
                Map<?, ?> firstResult = (Map<?, ?>) results.get(0);
                observationUUID = firstResult.get("reference").toString().replace("Observation/", "");
            }
        }

        // Step 3: update or create observation with SR text
        if (observationUUID != null) {
            // Update existing observation
            String updateObsJson = String.format(
                    "{\"resourceType\":\"Observation\"," +
                    "\"id\":\"%s\"," +
                    "\"status\":\"final\"," +
                    "\"code\":{\"coding\":[{\"code\":\"%s\"}]}," +
                    "\"subject\":{\"reference\":\"Patient/%s\"}," +
                    "\"encounter\":{\"reference\":\"Encounter/%s\"}," +
                    "\"effectiveDateTime\":\"%s\"," +
                    "\"valueString\":\"%s\"}",
                    observationUUID,
                    observationConceptUuid,
                    patientUUID,
                    encounterRef,
                    java.time.LocalDate.now().toString(),
                    srText.replace("\"", "\\\"").replace("\n", "\\n"));

            Map<String, Object> updateObsHeaders = new HashMap<>();
            updateObsHeaders.put(Constants.CAMEL_HTTP_METHOD, "PUT");
            updateObsHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
            updateObsHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());
            updateObsHeaders.put(Constants.HEADER_OBSERVATION_UUID, observationUUID);

            producerTemplate.requestBodyAndHeaders(
                    "direct:openmrs-update-observation-route", updateObsJson, updateObsHeaders, String.class);
            log.info("Updated Observation {} with SR content for DiagnosticReport {}", observationUUID, reportUUID);
        } else {
            // No Observation is linked to this DiagnosticReport yet (its
            // "result" reference never persisted when the report was first
            // created - a known OpenMRS FHIR2 write-support gap). Create a
            // fresh Observation carrying the real SR text so the report
            // content is not silently discarded.
            String createObsJson = String.format(
                    "{\"resourceType\":\"Observation\"," +
                    "\"status\":\"final\"," +
                    "\"code\":{\"coding\":[{\"code\":\"%s\"}]}," +
                    "\"subject\":{\"reference\":\"Patient/%s\"}," +
                    "\"encounter\":{\"reference\":\"Encounter/%s\"}," +
                    "\"effectiveDateTime\":\"%s\"," +
                    "\"valueString\":\"%s\"}",
                    observationConceptUuid,
                    patientUUID,
                    encounterRef,
                    java.time.LocalDate.now().toString(),
                    srText.replace("\"", "\\\"").replace("\n", "\\n"));
            Map<String, Object> createObsHeaders = new HashMap<>();
            createObsHeaders.put(Constants.CAMEL_HTTP_METHOD, "POST");
            createObsHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
            createObsHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());
            producerTemplate.requestBodyAndHeaders(
                    "direct:openmrs-create-observation-route", createObsJson, createObsHeaders, String.class);
            log.info("Created new Observation with SR content for DiagnosticReport {} (no prior link existed)", reportUUID);
        }

        // Step 4: update DiagnosticReport status to final with conclusion
        String updateReportJson = String.format(
                "{\"resourceType\":\"DiagnosticReport\"," +
                "\"id\":\"%s\"," +
                "\"status\":\"final\"," +
                "\"code\":{\"coding\":[{\"code\":\"%s\"}]}," +
                "\"subject\":{\"reference\":\"Patient/%s\"}," +
                "\"encounter\":{\"reference\":\"Encounter/%s\"}," +
                "\"conclusion\":\"%s\"}",
                reportUUID,
                "27fe6714-0bc6-4435-adb0-818538abe42c",
                patientUUID,
                encounterRef,
                srText.replace("\"", "\\\"").replace("\n", "\\n"));

        Map<String, Object> updateReportHeaders = new HashMap<>();
        updateReportHeaders.put(Constants.CAMEL_HTTP_METHOD, "PUT");
        updateReportHeaders.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        updateReportHeaders.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());
        updateReportHeaders.put(Constants.HEADER_DIAGNOSTIC_REPORT_UUID, reportUUID);

        producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-update-diagnostic-report-route", updateReportJson, updateReportHeaders, String.class);
        log.info("Updated DiagnosticReport {} with SR conclusion", reportUUID);
    }

    /**
     * Delete a DiagnosticReport by its UUID.
     */
    public void deleteDiagnosticReport(ProducerTemplate producerTemplate, String reportUUID) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.HEADER_DIAGNOSTIC_REPORT_UUID, reportUUID);
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.DELETE);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        producerTemplate.requestBodyAndHeaders(
                "direct:orthanc-delete-diagnostic-report-route", null, headers, String.class);

        log.info("Deleted DiagnosticReport {}", reportUUID);
    }

    /**
     * Check if a DiagnosticReport already exists for a given study UID.
     */
    public boolean doesDiagnosticReportExist(
            ProducerTemplate producerTemplate, String patientUUID, String orthancStudyId)
            throws JsonProcessingException {
        List<DiagnosticReportResource> reports = getDiagnosticReportsByPatient(producerTemplate, patientUUID);
        return reports.stream().anyMatch(r ->
                r.getIdentifier() != null && r.getIdentifier().stream()
                        .anyMatch(id -> orthancStudyId.equals(id.getValue())));
    }
}
