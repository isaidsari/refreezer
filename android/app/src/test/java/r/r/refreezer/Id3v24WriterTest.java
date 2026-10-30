package r.r.refreezer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Id3v24WriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    //=====================
    // Fixture table of the brief
    //=====================

    private static final String NFD_OLUM = "ölüm";

    //{frame, input values..., expected}
    private static final Object[][] TABLE = {
            {"TIT2", new String[]{"Gölge Haramileri"}, "Gölge Haramileri"},
            {"TALB", new String[]{"Kötü İnsanları Tanıma Senesi"}, "Kötü İnsanları Tanıma Senesi"},
            {"TPE1", new String[]{"Kült"}, "Kült"},
            {"TIT2", new String[]{"Denizcinin Son Şarkısı"}, "Denizcinin Son Şarkısı"},
            {"TPE1", new String[]{"Misþyrming"}, "Misþyrming"},
            {"TALB", new String[]{"Söngvar elds og óreiðu"}, "Söngvar elds og óreiðu"},
            {"TALB", new String[]{"Écailles de lune"}, "Écailles de lune"},
            {"TALB", new String[]{"The Sick, The Dying… And The Dead!"}, "The Sick, The Dying… And The Dead!"},
            {"TIT2", new String[]{"We’ll Be Back"}, "We’ll Be Back"},
            {"TPE1", new String[]{"罗志祥"}, "罗志祥"},
            {"TIT2", new String[]{NFD_OLUM}, "ölüm"},
            {"TPE1", new String[]{"A", "B"}, "A, B"},
    };

    @Test
    public void fixtureTable() throws Exception {
        for (Object[] row : TABLE) {
            String id = (String) row[0];
            String[] input = (String[]) row[1];
            String expected = (String) row[2];

            File f = mp3(null, false);
            String sha = Id3Validator.audioSha256(f);
            Map<String, List<String>> fields = new LinkedHashMap<>();
            fields.put(id, Arrays.asList(input));
            Id3v24Writer.update(f, fields);

            List<String> errors = Id3Validator.validate(f, Collections.singletonMap(id, expected), sha);
            System.out.println((errors.isEmpty() ? "PASS " : "FAIL ") + id + " " + Arrays.toString(input) + " -> \"" + expected + "\" " + errors);
            assertTrue(id + " " + Arrays.toString(input) + ": " + errors, errors.isEmpty());
            //Encoded bytes are the UTF-8 bytes of the NFC string, nothing else
            byte[] frame = frame(f, id).data;
            byte[] want = concat(new byte[]{0x03}, expected.getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(id + " raw bytes", want, frame);
        }
        //The NFD input really was decomposed
        assertNotEquals("ölüm", NFD_OLUM);
        assertEquals("ölüm", Normalizer.normalize(NFD_OLUM, Normalizer.Form.NFC));
    }

    @Test
    public void allFieldsInOneTag() throws Exception {
        File f = mp3(null, false);
        String sha = Id3Validator.audioSha256(f);
        Id3v24Writer.Tag tag = Id3v24Writer.read(f);
        tag.setText("TIT2", "Denizcinin Son Şarkısı");
        tag.setText("TALB", "Kötü İnsanları Tanıma Senesi");
        tag.setText("TPE1", "Kült");
        tag.setText("TPE2", "AC/DC");
        tag.setText("TCON", "Rock");
        tag.setText("TRCK", "05/12");
        tag.setText("TDRC", "1995");
        tag.setUserText("BARCODE", "0123456789");
        tag.setLyrics("eng", "", "Satır bir\nSatır iki");
        tag.setCover(jpeg());
        Id3v24Writer.write(f, tag);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIT2", "Denizcinin Son Şarkısı");
        expected.put("TALB", "Kötü İnsanları Tanıma Senesi");
        expected.put("TPE1", "Kült");
        expected.put("TPE2", "AC/DC");
        expected.put("TCON", "Rock");
        expected.put("TRCK", "05/12");
        expected.put("TDRC", "1995");
        expected.put("TXXX:BARCODE", "0123456789");
        assertValid(f, expected, sha);
        assertCover(f, jpeg());
        Id3Validator.Frame uslt = frame(f, "USLT");
        assertEquals(0x03, uslt.data[0]);
        assertEquals("eng", new String(uslt.data, 1, 3, StandardCharsets.ISO_8859_1));
        assertEquals("\u0000Satır bir\nSatır iki", new String(uslt.data, 4, uslt.data.length - 4, StandardCharsets.UTF_8));
    }

    //=====================
    // Round trip of existing tags
    //=====================

    @Test
    public void roundTripId3v23MixedEncodingsWithId3v1() throws Exception {
        //ID3v2.3 like typical writers produce: Latin-1 where it fits, UTF-16 otherwise
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v23Frame("TIT2", latin1Text("Gölge Haramileri")));
        frames.write(v23Frame("TALB", utf16Text("Kötü İnsanları Tanıma Senesi")));
        frames.write(v23Frame("TPE1", latin1Text("Misþyrming")));
        frames.write(v23Frame("TPE2", latin1Text("AC/DC")));
        frames.write(v23Frame("TCOM", utf16Text("We’ll / Somebody")));
        frames.write(v23Frame("TCON", latin1Text("(17)")));
        frames.write(v23Frame("TYER", latin1Text("1995")));
        frames.write(v23Frame("TDAT", latin1Text("0703")));
        frames.write(v23Frame("TXXX", concat(new byte[]{0x01}, utf16("BARCODE"), new byte[]{0, 0}, utf16("123"))));
        frames.write(v23Frame("COMM", concat(new byte[]{0x01}, "eng".getBytes(StandardCharsets.ISO_8859_1),
                utf16(""), new byte[]{0, 0}, utf16("Yorum: ü ş"))));
        frames.write(v23Frame("APIC", concat(new byte[]{0x00}, "image/jpeg".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0, 0x03}, "Front".getBytes(StandardCharsets.ISO_8859_1), new byte[]{0}, jpeg())));
        File f = mp3(id3v23(frames.toByteArray(), false), true);
        String sha = Id3Validator.audioSha256(f);

        //Mixed encodings before, as in the bug report
        assertFalse(Id3Validator.validate(f, null, null).isEmpty());

        //What the downloader does: read existing tag, write it back by the rules
        Id3v24Writer.write(f, Id3v24Writer.read(f));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIT2", "Gölge Haramileri");
        expected.put("TALB", "Kötü İnsanları Tanıma Senesi");
        expected.put("TPE1", "Misþyrming"); //þ stays þ, no "repair"
        expected.put("TPE2", "AC/DC"); //not split
        expected.put("TCOM", "We’ll / Somebody"); //typography kept
        expected.put("TCON", "Rock");
        expected.put("TDRC", "1995-03-07");
        expected.put("TXXX:BARCODE", "123");
        assertValid(f, expected, sha);
        assertCover(f, jpeg());

        Id3v24Writer.Tag back = Id3v24Writer.read(f);
        Id3v24Writer.LangText comment = back.comments.values().iterator().next();
        assertEquals("eng", comment.language);
        assertEquals("Yorum: ü ş", comment.text);
        Id3Validator.Frame comm = frame(f, "COMM");
        assertEquals("COMM written as UTF-8", 0x03, comm.data[0]);
    }

    @Test
    public void downloaderFieldsOverwriteExistingTag() throws Exception {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v23Frame("TIT2", latin1Text("Old title")));
        frames.write(v23Frame("TIT2", latin1Text("Second TIT2")));
        frames.write(v23Frame("TPE1", utf16Text("Kült")));
        File f = mp3(id3v23(frames.toByteArray(), false), true);
        String sha = Id3Validator.audioSha256(f);

        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("TIT2", Collections.singletonList("Gölge Haramileri"));
        Id3v24Writer.update(f, fields);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIT2", "Gölge Haramileri");
        expected.put("TPE1", "Kült");
        assertValid(f, expected, sha);
    }

    @Test
    public void mutagenStyleId3v24WithTrailingNulsAndMultipleValues() throws Exception {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v24Frame("TIT2", concat(new byte[]{0x03}, utf8("Kült"), new byte[]{0})));
        frames.write(v24Frame("TPE1", concat(new byte[]{0x03}, utf8("A"), new byte[]{0}, utf8("B"), new byte[]{0})));
        frames.write(v24Frame("TALB", concat(new byte[]{0x01}, utf16("ölüm"), new byte[]{0, 0})));
        File f = mp3(id3v24(frames.toByteArray()), true);
        String sha = Id3Validator.audioSha256(f);
        assertFalse(Id3Validator.validate(f, null, null).isEmpty());

        Id3v24Writer.write(f, Id3v24Writer.read(f));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIT2", "Kült");
        expected.put("TPE1", "A, B");
        expected.put("TALB", "ölüm");
        assertValid(f, expected, sha);
    }

    @Test
    public void unsynchronisedId3v23TagIsDecoded() throws Exception {
        //Picture bytes containing FF E0 / FF 00 must survive unsynchronisation
        byte[] picture = concat(jpeg(), new byte[]{(byte) 0xFF, (byte) 0xE0, 0x01, (byte) 0xFF, 0x00, (byte) 0xFF});
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v23Frame("TIT2", utf16Text("Söngvar elds og óreiðu")));
        frames.write(v23Frame("APIC", concat(new byte[]{0x00}, "image/jpeg".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0, 0x03, 0}, picture)));
        File f = mp3(id3v23(frames.toByteArray(), true), false);
        String sha = Id3Validator.audioSha256(f);

        Id3v24Writer.write(f, Id3v24Writer.read(f));

        assertValid(f, Collections.singletonMap("TIT2", "Söngvar elds og óreiðu"), sha);
        assertCover(f, picture);
    }

    @Test
    public void noEncodingGuessOrRepair() throws Exception {
        //Latin-1 Þ Ð Ý are real letters (Icelandic, Czech), they must not become ş ğ ı
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v23Frame("TPE1", latin1Text("Þórir Ðorsteinn Ýr")));
        File f = mp3(id3v23(frames.toByteArray(), false), false);
        String sha = Id3Validator.audioSha256(f);

        Id3v24Writer.write(f, Id3v24Writer.read(f));

        assertValid(f, Collections.singletonMap("TPE1", "Þórir Ðorsteinn Ýr"), sha);
    }

    @Test
    public void genreCodesBecomePlainText() {
        assertEquals("Rock", Id3v24Writer.single("TCON", Collections.singletonList("(17)")));
        assertEquals("Rock", Id3v24Writer.single("TCON", Collections.singletonList("17")));
        assertEquals("Hard Rock", Id3v24Writer.single("TCON", Collections.singletonList("(17)Hard Rock")));
        assertEquals("Blues, Remix", Id3v24Writer.single("TCON", Collections.singletonList("(0)(RX)")));
        assertEquals("(Acoustic) Rock", Id3v24Writer.single("TCON", Collections.singletonList("((Acoustic) Rock")));
        assertEquals("Türkçe Rock", Id3v24Writer.single("TCON", Collections.singletonList("Türkçe Rock")));
    }

    @Test
    public void textCleaning() {
        assertEquals("ölüm", Id3v24Writer.clean("  " + NFD_OLUM + "\t\u0000 "));
        assertEquals("ab", Id3v24Writer.clean("a\u0085b\u001F"));
        //Typography untouched
        assertEquals("… ’ “ ” – —", Id3v24Writer.clean("… ’ “ ” – —"));
        //A/B is never split
        assertEquals("AC/DC", Id3v24Writer.single("TPE1", Collections.singletonList("AC/DC")));
        assertEquals("A, B", Id3v24Writer.single("TPE1", Arrays.asList("A", " ", "B", "A")));
    }

    @Test
    public void id3v1OnlyFileGetsV24AndLosesV1() throws Exception {
        File f = mp3(null, true);
        String sha = Id3Validator.audioSha256(f);
        Id3v24Writer.update(f, Collections.singletonMap("TIT2", Collections.singletonList("Écailles de lune")));
        assertValid(f, Collections.singletonMap("TIT2", "Écailles de lune"), sha);
    }

    @Test
    public void rewriteIsIdempotent() throws Exception {
        File f = mp3(null, false);
        Id3v24Writer.Tag tag = Id3v24Writer.read(f);
        tag.setText("TIT2", "We’ll Be Back");
        tag.setText("TPE1", "罗志祥");
        tag.setCover(jpeg());
        Id3v24Writer.write(f, tag);
        byte[] first = Files.readAllBytes(f.toPath());
        Id3v24Writer.write(f, Id3v24Writer.read(f));
        assertArrayEquals(first, Files.readAllBytes(f.toPath()));
    }

    //=====================
    // The validator catches what it should
    //=====================

    @Test
    public void validatorRejectsBrokenFiles() throws Exception {
        //What jaudiotagger 2.2.5 wrote before: v2.3, Latin-1 + UTF-16, (17), ID3v1 kept
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(v23Frame("TIT2", latin1Text("Gölge Haramileri")));
        frames.write(v23Frame("TALB", utf16Text("Kötü İnsanları Tanıma Senesi")));
        File old = mp3(id3v23(frames.toByteArray(), false), true);
        List<String> errors = Id3Validator.validate(old, null, null);
        System.out.println("old style tag -> " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Version byte")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ID3v1")));

        //v2.4 with Latin-1 frame, trailing NUL, duplicate frame
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        bad.write(v24Frame("TIT2", latin1Text("Kült")));
        bad.write(v24Frame("TPE1", concat(new byte[]{0x03}, utf8("A"), new byte[]{0})));
        bad.write(v24Frame("TPE1", concat(new byte[]{0x03}, utf8("B"))));
        File v24 = mp3(id3v24(bad.toByteArray()), false);
        errors = Id3Validator.validate(v24, Collections.singletonMap("TIT2", "Kült"), null);
        System.out.println("bad v2.4 tag -> " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("TIT2: encoding byte 0x00")));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("TPE1: ends with 00")));
        assertTrue(errors.stream().anyMatch(e -> e.equals("Duplicate frame TPE1")));

        //Changed audio is detected
        File f = mp3(null, false);
        String sha = Id3Validator.audioSha256(f);
        Id3v24Writer.update(f, Collections.singletonMap("TIT2", Collections.singletonList("x")));
        byte[] bytes = Files.readAllBytes(f.toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(f.toPath(), bytes);
        errors = Id3Validator.validate(f, null, sha);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Audio data changed")));
    }

    //=====================
    // Helpers
    //=====================

    private void assertValid(File f, Map<String, String> expected, String sha) throws IOException {
        List<String> errors = Id3Validator.validate(f, expected, sha);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private void assertCover(File f, byte[] image) throws IOException {
        Id3Validator.Frame apic = frame(f, "APIC");
        byte[] header = concat(new byte[]{0x03}, "image/jpeg".getBytes(StandardCharsets.ISO_8859_1), new byte[]{0, 0x03, 0});
        assertArrayEquals("APIC header (UTF-8, image/jpeg, front cover, empty description)",
                header, Arrays.copyOf(apic.data, header.length));
        assertArrayEquals("APIC image data", image, Arrays.copyOfRange(apic.data, header.length, apic.data.length));
    }

    private static Id3Validator.Frame frame(File f, String id) throws IOException {
        Id3Validator.Frame found = null;
        for (Id3Validator.Frame fr : Id3Validator.frames(Files.readAllBytes(f.toPath()))) {
            if (fr.id.equals(id)) {
                assertTrue("single " + id, found == null);
                found = fr;
            }
        }
        assertTrue("frame " + id + " present", found != null);
        return found;
    }

    /** Fixture file: optional tag + silent audio + optional ID3v1 block. */
    private File mp3(byte[] tag, boolean id3v1) throws IOException {
        File f = tmp.newFile();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tag != null) out.write(tag);
        out.write(Id3Validator.silentMpeg(40));
        if (id3v1) {
            byte[] v1 = new byte[128];
            v1[0] = 'T';
            v1[1] = 'A';
            v1[2] = 'G';
            byte[] title = "Gölge Haramileri".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(title, 0, v1, 3, title.length);
            v1[127] = 17;
            out.write(v1);
        }
        Files.write(f.toPath(), out.toByteArray());
        return f;
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};
    }

    private static byte[] id3v23(byte[] frames, boolean unsync) throws IOException {
        byte[] body = frames;
        if (unsync) {
            ByteArrayOutputStream u = new ByteArrayOutputStream();
            for (int i = 0; i < frames.length; i++) {
                u.write(frames[i]);
                if ((frames[i] & 0xFF) == 0xFF && (i + 1 == frames.length || (frames[i + 1] & 0xE0) == 0xE0 || frames[i + 1] == 0))
                    u.write(0);
            }
            body = u.toByteArray();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 3, 0, (byte) (unsync ? 0x80 : 0)});
        out.write(Id3v24Writer.syncsafeBytes(body.length + 64));
        out.write(body);
        out.write(new byte[64]);
        return out.toByteArray();
    }

    private static byte[] id3v24(byte[] frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 4, 0, 0});
        out.write(Id3v24Writer.syncsafeBytes(frames.length));
        out.write(frames);
        return out.toByteArray();
    }

    private static byte[] v23Frame(String id, byte[] data) {
        int n = data.length;
        return concat(id.getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n, 0, 0}, data);
    }

    private static byte[] v24Frame(String id, byte[] data) {
        return concat(id.getBytes(StandardCharsets.ISO_8859_1), Id3v24Writer.syncsafeBytes(data.length), new byte[]{0, 0}, data);
    }

    private static byte[] latin1Text(String s) {
        return concat(new byte[]{0x00}, s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] utf16Text(String s) {
        return concat(new byte[]{0x01}, utf16(s), new byte[]{0, 0});
    }

    /** UTF-16 with little endian BOM, like most v2.3 writers. */
    private static byte[] utf16(String s) {
        return concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, s.getBytes(Charset.forName("UTF-16LE")));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
