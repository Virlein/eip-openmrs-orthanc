/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.routes;

import com.ozonehis.eip.openmrs.orthanc.Constants;
import com.ozonehis.eip.openmrs.orthanc.converters.ResourceConverter;
import com.ozonehis.eip.openmrs.orthanc.processors.ImagingStudyDeletionProcessor;
import com.ozonehis.eip.openmrs.orthanc.processors.ImagingSRProcessor;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancTokenProvider;
import com.ozonehis.eip.openmrs.orthanc.processors.ImagingStudyProcessor;
import lombok.Setter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Setter
@Component
public class ImagingStudyRouting extends RouteBuilder {

    @Autowired
    private ImagingStudyProcessor imagingStudyProcessor;

    @Autowired
    private ImagingStudyDeletionProcessor imagingStudyDeletionProcessor;
    @Autowired
    private ImagingSRProcessor imagingSRProcessor;
    @Autowired
    private OrthancTokenProvider orthancTokenProvider;

    @Autowired
    private ResourceConverter resourceConverter;

    @Value("${openmrs.baseUrl}")
    private String openmrsBaseUrl;

    @Override
    public void configure() {
        getContext().getTypeConverterRegistry().addTypeConverters(resourceConverter);

        // spotless:off

        // ── Orthanc polling ────────────────────────────────────────────────────
        from("scheduler:studyUpdate?initialDelay=10000&delay=10000")
            .routeId("poll-orthanc")
            .log(LoggingLevel.INFO, "Polling ImagingStudy started...")
            .to("direct:orthanc-get-studies-route")
            .process(imagingStudyProcessor)
            .log(LoggingLevel.INFO, "Polling ImagingStudy completed.")
            .end();

        from("scheduler:studyDeletion?initialDelay=15000&delay=10000")
            .routeId("poll-orthanc-changes")
            .log(LoggingLevel.INFO, "Polling Orthanc changes started...")
            .setHeader(Constants.HEADER_STUDIES_SINCE, simple("${exchangeProperty.orthanc.changes.cursor}?:0"))
            .to("direct:orthanc-get-changes-route")
            .process(imagingStudyDeletionProcessor)
            .log(LoggingLevel.INFO, "Polling Orthanc changes completed.")
            .end();

        from("scheduler:srDetection?initialDelay=20000&delay=10000")
            .routeId("poll-orthanc-sr")
            .log(LoggingLevel.INFO, "Polling Orthanc SR changes started...")
            .setHeader(Constants.HEADER_STUDIES_SINCE, simple("${exchangeProperty.orthanc.sr.changes.cursor}?:0"))
            .to("direct:orthanc-get-changes-route")
            .process(imagingSRProcessor)
            .log(LoggingLevel.INFO, "Polling Orthanc SR changes completed.")
            .end();
        // ── DiagnosticReport FHIR routes ───────────────────────────────────────
        from("direct:orthanc-get-diagnostic-reports-route")
            .routeId("openmrs-get-diagnostic-reports")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/DiagnosticReport?patient=${header."
                + Constants.HEADER_OPENMRS_PATIENT_UUID + "}")
            .end();

        from("direct:orthanc-create-diagnostic-report-route")
            .routeId("openmrs-create-diagnostic-report")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/DiagnosticReport")
            .end();

        from("direct:orthanc-delete-diagnostic-report-route")
            .routeId("openmrs-delete-diagnostic-report")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/DiagnosticReport/${header."
                + Constants.HEADER_DIAGNOSTIC_REPORT_UUID + "}")
            .end();

        from("direct:orthanc-search-diagnostic-reports-by-id-route")
            .routeId("openmrs-search-diagnostic-reports-by-id")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/DiagnosticReport?identifier=${header.orthanc.study.id}")
            .end();
        from("direct:openmrs-create-observation-route")
            .routeId("openmrs-create-observation")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/Observation")
            .end();
        from("direct:openmrs-update-diagnostic-report-route")
            .routeId("openmrs-update-diagnostic-report")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/DiagnosticReport/${header.openmrs.diagnostic.report.uuid}")
            .end();
        from("direct:openmrs-create-encounter-route")
            .routeId("openmrs-create-encounter")
            .toD(openmrsBaseUrl + "/ws/rest/v1/encounter")
            .end();
        from("direct:orthanc-get-instance-route")
            .routeId("orthanc-get-instance")
            .setHeader("token", method(orthancTokenProvider, "getToken"))
            .setHeader(Constants.CAMEL_HTTP_METHOD, constant(Constants.GET))
            .setHeader(Constants.CONTENT_TYPE, constant(Constants.APPLICATION_JSON))
            .toD("{{orthanc.baseUrl}}/instances/${header.orthanc.instance.id}")
            .end();
        from("direct:orthanc-get-series-by-id-route")
            .routeId("orthanc-get-series-by-id")
            .setHeader("token", method(orthancTokenProvider, "getToken"))
            .setHeader(Constants.CAMEL_HTTP_METHOD, constant(Constants.GET))
            .setHeader(Constants.CONTENT_TYPE, constant(Constants.APPLICATION_JSON))
            .toD("{{orthanc.baseUrl}}/series/${header.orthanc.series.id}")
            .end();
        from("direct:openmrs-update-observation-route")
            .routeId("openmrs-update-observation")
            .toD(openmrsBaseUrl + "/ws/fhir2/R4/Observation/${header." + Constants.HEADER_OBSERVATION_UUID + "}")
            .end();
        // spotless:on
    }
}