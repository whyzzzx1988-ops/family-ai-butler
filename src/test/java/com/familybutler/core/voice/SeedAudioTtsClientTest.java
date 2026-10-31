package com.familybutler.core.voice;

import com.familybutler.core.account.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedAudioTtsClientTest {

    @Test
    void parseResponseDecodesAudioAndDuration() throws Exception {
        byte[] fakeMp3 = new byte[512];
        fakeMp3[0] = 'I';
        fakeMp3[1] = 'D';
        fakeMp3[2] = '3';
        String json = "{\"audio\":\"" + Base64.getEncoder().encodeToString(fakeMp3)
                + "\",\"duration\":12.5}";

        TtsClient.AudioResult result = SeedAudioTtsClient.parseResponse(json);

        assertEquals(512, result.audio().length);
        assertEquals("mp3", result.format());
        assertEquals(12.5, result.durationSeconds());
    }

    @Test
    void parseResponseRejectsMissingAudio() {
        assertThrows(ApiException.class, () -> SeedAudioTtsClient.parseResponse("{\"message\":\"bad key\"}"));
    }
}
