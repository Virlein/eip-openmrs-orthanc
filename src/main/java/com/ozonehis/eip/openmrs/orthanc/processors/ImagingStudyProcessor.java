/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsDiagnosticReportHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsPatientHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancImagingStudyHandler;
import com.ozonehis.eip.openmrs.orthanc.models.series.Series;
import com.ozonehis.eip.openmrs.orthanc.models.imagingStudy.Study;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedStudyRepository;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Patient;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Getter
@Component
public class ImagingStudyProcessor implements Processor {

    @Autowired
    private ProcessedStudyRepository processedStudyRepository;

    @Value("${orthanc.publicUrl:${orthanc.baseUrl}}")
    private String orthancPublicUrl;

    @Autowired
    private OpenmrsPatientHandler openmrsPatientHandler;

    @Autowired
    private OpenmrsDiagnosticReportHandler openmrsDiagnosticReportHandler;

    @Autowired
    private OrthancImagingStudyHandler orthancImagingStudyHandler;

    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            String body = exchange.getMessage().getBody(String.class);
            ObjectMapper mapper = new ObjectMapper();
            Study[] studies = mapper.readValue(body, Study[].class);

            for (Study study : studies) {
                if (study.getPatientMainDicomTags().getOtherPatientIDs() == null) {
                    continue;
                }

                Patient openmrsPatient = openmrsPatientHandler.getPatientByIdentifier(
                        study.getPatientMainDicomTags().getOtherPatientIDs());

                if (openmrsPatient == null || openmrsPatient.getIdentifier().isEmpty()) {
                    continue;
                }

                String patientUUID = openmrsPatient.getIdPart();
                String studyInstanceUID = study.getImagingStudyMainDicomTags().getStudyInstanceUID();

                if (processedStudyRepository.exists(study.id)) {
                    log.debug("DiagnosticReport already processed for study {}", study.id);
                    continue;
                }

                String modality = null;
                String studyDate = study.getImagingStudyMainDicomTags().getStudyDate();

                if (study.getSeries() != null && !study.getSeries().isEmpty()) {
                    try {
                        Series series = orthancImagingStudyHandler.getSeriesByID(
                                producerTemplate, study.getSeries().get(0));
                        if (series != null && series.getMainDicomTags() != null) {
                            modality = series.getMainDicomTags().getModality();
                        }
                    } catch (Exception e) {
                        log.warn("Could not fetch series for study {}: {}", study.id, e.getMessage());
                    }
                }

                String reportUUID = openmrsDiagnosticReportHandler.createDiagnosticReport(
                        producerTemplate,
                        patientUUID,
                        studyInstanceUID,
                        study.id,
                        orthancPublicUrl,
                        modality,
                        studyDate);
            }
        } catch (Exception e) {
            throw new EIPException(String.format("Error processing ImagingStudy: %s", e.getMessage()));
        }
    }
}
