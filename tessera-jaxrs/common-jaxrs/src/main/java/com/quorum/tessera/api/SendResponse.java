package com.quorum.tessera.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Model representation of a JSON body on incoming HTTP requests
 *
 * <p>A response to a {@link SendRequest} after the transaction has been distributed and saved
 */
public class SendResponse {

    @Schema(description = "encrypted payload hash", format = "base64")
    private String key;

    public SendResponse(final String key) {
        this.key = key;
    }

    public SendResponse() {}

    public String getKey() {
        return this.key;
    }

    public void setKey(final String key) {
        this.key = key;
    }
}
