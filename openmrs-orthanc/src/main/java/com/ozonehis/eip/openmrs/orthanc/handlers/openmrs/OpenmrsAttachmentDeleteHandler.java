/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.openmrs;

import com.ozonehis.eip.openmrs.orthanc.config.OpenmrsConfig;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenmrsAttachmentDeleteHandler {

    private static final String ATTACHMENT_DELETE_ENDPOINT = "%s/ws/rest/v1/attachment/%s";

    @Autowired
    private OpenmrsConfig openmrsConfig;

    public void deleteAttachment(String attachmentUuid) throws IOException {
        String url = String.format(ATTACHMENT_DELETE_ENDPOINT,
                openmrsConfig.getOpenmrsBaseUrl(), attachmentUuid);

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", openmrsConfig.authHeader())
                .delete()
                .build();

        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("Attachment {} deleted successfully", attachmentUuid);
            } else {
                log.error("Failed to delete attachment {}: {} {}",
                        attachmentUuid, response.code(),
                        response.body() != null ? response.body().string() : "");
            }
        }
    }
}
