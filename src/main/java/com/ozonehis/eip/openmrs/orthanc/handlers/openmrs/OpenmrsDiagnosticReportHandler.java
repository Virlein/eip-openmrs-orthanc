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
        return reportUUID;
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
