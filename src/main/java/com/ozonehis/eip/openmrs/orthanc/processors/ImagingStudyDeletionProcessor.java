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
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsAttachmentDeleteHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsObsHandler;
import com.ozonehis.eip.openmrs.orthanc.models.obs.Attachment;
import java.util.List;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class ImagingStudyDeletionProcessor implements Processor {

    @Value("${eip.attachment.concept}")
    private String attachmentConceptId;

    @Autowired
    private OpenmrsAttachmentDeleteHandler openmrsAttachmentDeleteHandler;

    @Autowired
    private OpenmrsObsHandler openmrsObsHandler;

    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            String body = exchange.getMessage().getBody(String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode changes = root.get("Changes");
            long lastSeq = root.get("Last").asLong();

            if (changes == null || changes.isEmpty()) {
                exchange.getMessage().setHeader(Constants.HEADER_CHANGES_SINCE, lastSeq);
                return;
            }

            for (JsonNode change : changes) {
                String changeType = change.get("ChangeType").asText();
                String resourceType = change.get("ResourceType").asText();
                String studyId = change.get("ID").asText();

                if (!"Study".equals(resourceType) || !"Deletion".equals(changeType)) {
                    continue;
                }

                log.info("Study deletion detected: {}", studyId);
                handleStudyDeletion(producerTemplate, studyId);
            }

            exchange.getMessage().setHeader(Constants.HEADER_CHANGES_SINCE, lastSeq);

        } catch (Exception e) {
            throw new EIPException(
                    String.format("Error processing ImagingStudy deletion: %s", e.getMessage()));
        }
    }

    private void handleStudyDeletion(ProducerTemplate producerTemplate, String orthancStudyId) {
        try {
            List<Attachment> attachments = openmrsObsHandler.getAllObsByConceptUUID(producerTemplate, attachmentConceptId);
            for (Attachment attachment : attachments) {
                String comment = attachment.getComment();
                if (comment != null && comment.contains(orthancStudyId)) {
                    log.info("Deleting attachment {} for study {}", attachment.getUuid(), orthancStudyId);
                    openmrsAttachmentDeleteHandler.deleteAttachment(attachment.getUuid());
                }
            }
        } catch (Exception e) {
            log.error("Error handling study deletion for {}: {}", orthancStudyId, e.getMessage());
        }
    }
}
