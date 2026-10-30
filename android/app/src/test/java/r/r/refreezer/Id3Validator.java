package r.r.refreezer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acceptance checks of the ID3 brief on the raw bytes of a file.
 * Independent of Id3v24Writer: parses the tag on its own, doesn't trust any library.
 */
public final class Id3Validator {

    private Id3Validator() {
    }

    /** A frame as found in the file. */
    public static final class Frame {
        public final String id;
        public final int flags;
        public final byte[] data;

        Frame(String id, int flags, byte[] data) {
            this.id = id;
            this.flags = flags;
            this.data = data;
        }
    }

    /**
     * Validates the file, empty list = passed.
     *
     * @param expected  frame id (or "TXXX:description") -> value that was asked to be written
     *                  (compared to its NFC form)
     * @param audioSha256Before SHA-256 of the audio data before writing, null to skip
     */
    public static List<String> validate(File file, Map<String, String> expected, String audioSha256Before) throws IOException {
        List<String> errors = new ArrayList<>();
        byte[] b = Files.readAllBytes(file.toPath());

        if (b.length < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3') {
            errors.add("File doesn't start with ID3");
            return errors;
        }
        if (b[3] != 4) errors.add("Version byte is " + b[3] + ", expected 4");
        if (b[4] != 0) errors.add("Revision byte is " + b[4] + ", expected 0");
        if ((b[5] & 0x80) != 0) errors.add("Unsynchronisation flag set");
        if ((b[5] & 0x40) != 0) errors.add("Extended header flag set");

        List<Frame> frames;
        try {
            frames = frames(b);
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
            return errors;
        }

        Set<String> seen = new HashSet<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (Frame f : frames) {
            if (!f.id.equals("TXXX") && !seen.add(f.id)) errors.add("Duplicate frame " + f.id);
            if (f.flags != 0) errors.add(f.id + ": frame flags " + Integer.toHexString(f.flags) + " (expected none)");
            if (!f.id.startsWith("T")) continue;
            if (f.data.length == 0) {
                errors.add(f.id + ": empty frame");
                continue;
            }
            if (f.data[0] != 0x03) errors.add(f.id + ": encoding byte 0x" + String.format("%02x", f.data[0]) + ", expected 0x03 (UTF-8)");
            if (f.data[f.data.length - 1] == 0) errors.add(f.id + ": ends with 00");
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(f.data, 1, f.data.length - 1)).toString();
            } catch (CharacterCodingException e) {
                errors.add(f.id + ": not valid UTF-8");
                continue;
            }
            if (f.id.equals("TXXX")) {
                int sep = text.indexOf('\u0000');
                if (sep < 0) {
                    errors.add("TXXX without description separator");
                    continue;
                }
                values.put("TXXX:" + text.substring(0, sep), text.substring(sep + 1));
            } else {
                if (text.indexOf('\u0000') >= 0) errors.add(f.id + ": multiple values (NUL separated)");
                values.put(f.id, text);
            }
        }

        if (b.length >= 128 && b[b.length - 128] == 'T' && b[b.length - 127] == 'A' && b[b.length - 126] == 'G')
            errors.add("ID3v1 tag present (last 128 bytes start with TAG)");

        if (expected != null) {
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String want = Normalizer.normalize(e.getValue(), Normalizer.Form.NFC);
                String got = values.get(e.getKey());
                if (got == null) errors.add(e.getKey() + ": missing (expected \"" + want + "\")");
                else if (!got.equals(want)) errors.add(e.getKey() + ": \"" + got + "\" != expected \"" + want + "\"");
            }
        }

        if (audioSha256Before != null) {
            String after = audioSha256(file);
            if (!audioSha256Before.equals(after)) errors.add("Audio data changed (SHA-256 " + after + " != " + audioSha256Before + ")");
        }
        return errors;
    }

    /** Frames of the ID3v2.4 tag at the start of the file. */
    public static List<Frame> frames(byte[] b) {
        int size = syncsafe(b, 6);
        if (10 + size > b.length) throw new IllegalStateException("Tag size larger than file");
        List<Frame> out = new ArrayList<>();
        int o = 10;
        int end = 10 + size;
        while (o + 10 <= end && b[o] != 0) {
            String id = new String(b, o, 4, StandardCharsets.ISO_8859_1);
            if (!id.matches("[A-Z0-9]{4}")) throw new IllegalStateException("Invalid frame id at " + o);
            for (int i = 4; i < 8; i++)
                if ((b[o + i] & 0x80) != 0) throw new IllegalStateException(id + ": frame size is not syncsafe");
            int fs = syncsafe(b, o + 4);
            if (o + 10 + fs > end) throw new IllegalStateException(id + ": frame larger than tag");
            int flags = ((b[o + 8] & 0xFF) << 8) | (b[o + 9] & 0xFF);
            out.add(new Frame(id, flags, Arrays.copyOfRange(b, o + 10, o + 10 + fs)));
            o += 10 + fs;
        }
        //Rest must be padding
        for (int i = o; i < end; i++)
            if (b[i] != 0) throw new IllegalStateException("Garbage after last frame at " + i);
        return out;
    }

    /** SHA-256 of the audio data: after leading ID3v2 tag(s), without a trailing ID3v1 tag. */
    public static String audioSha256(File file) throws IOException {
        byte[] b = Files.readAllBytes(file.toPath());
        int start = 0;
        while (start + 10 <= b.length && b[start] == 'I' && b[start + 1] == 'D' && b[start + 2] == '3') {
            boolean footer = b[start + 3] == 4 && (b[start + 5] & 0x10) != 0;
            start += 10 + syncsafe(b, start + 6) + (footer ? 10 : 0);
        }
        int end = b.length;
        if (end - start >= 128 && b[end - 128] == 'T' && b[end - 127] == 'A' && b[end - 126] == 'G') end -= 128;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(b, start, end - start);
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format("%02x", x));
            return hex.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    //=====================
    // Fixtures
    //=====================

    /** Silent MPEG-1 Layer III audio: 128 kbps, 44.1 kHz frames with empty side info / main data. */
    public static byte[] silentMpeg(int frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            byte[] frame = new byte[417]; //144 * 128000 / 44100
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            frame[3] = (byte) 0x00;
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }
}
