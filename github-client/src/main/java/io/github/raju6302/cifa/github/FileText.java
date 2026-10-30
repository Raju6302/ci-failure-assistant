package io.github.raju6302.cifa.github;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Decodes the base64 content the contents API returns, keeping only real text: an agent
 * can't do anything useful with an image or a jar, and binary noise wastes its context.
 */
final class FileText {

    private FileText() {
    }

    /** @return the UTF-8 text, or null if the content is binary (has NUL bytes or isn't valid UTF-8) */
    static String decode(String base64) {
        // GitHub wraps the base64 at 60 characters; the MIME decoder skips the line breaks.
        byte[] bytes = Base64.getMimeDecoder().decode(base64);
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }
}
