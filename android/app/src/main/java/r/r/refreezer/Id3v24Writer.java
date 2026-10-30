package r.r.refreezer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Writes MP3 tags in the form Android's media scanner reads correctly.
 *
 * The media scanner guesses one charset for title/artist/album/... together, mixing Latin-1 and
 * UTF-16 frames (what ID3v2.3 writers normally do) produces garbled text on many devices.
 * So every tag is written as:
 * - ID3v2.4, syncsafe frame sizes, no unsynchronisation, no extended header
 * - every text frame (T***, TXXX) and every other text carrying frame UTF-8 (0x03), no trailing NUL
 * - one value per frame (multiple values joined with ", ", "A/B" is never split)
 * - text in NFC, trimmed, without control characters, typographic characters kept as they are
 * - one frame per frame id (except frames told apart by description), genre as plain text
 * - a single front cover APIC with empty description
 * - no ID3v1, audio bytes after the tag untouched
 *
 * Existing tags (ID3v2.2/2.3/2.4) are read using the encoding each frame declares and rewritten
 * by these rules. No encoding is guessed or "repaired".
 *
 * Pure Java (no Android dependencies) so it can be unit tested on the JVM.
 */
public final class Id3v24Writer {

    private static final int PADDING = 1024;

    private Id3v24Writer() {
    }

    //=====================
    // Model
    //=====================

    public static final class Tag {
        //Frame id -> values, single value is written (joined)
        final LinkedHashMap<String, List<String>> text = new LinkedHashMap<>();
        //TXXX description -> value
        final LinkedHashMap<String, String> userText = new LinkedHashMap<>();
        //WXXX description -> url
        final LinkedHashMap<String, String> userUrl = new LinkedHashMap<>();
        //COMM / USLT: language + description -> text
        final LinkedHashMap<String, LangText> comments = new LinkedHashMap<>();
        final LinkedHashMap<String, LangText> lyrics = new LinkedHashMap<>();
        //Frames without text encoding (W***, PRIV, UFID, POPM, ...) copied as they are
        final List<RawFrame> raw = new ArrayList<>();
        Picture picture;

        public Tag setText(String id, String... values) {
            return setText(id, Arrays.asList(values));
        }

        public Tag setText(String id, List<String> values) {
            if (!id.startsWith("T") || id.equals("TXXX") || id.length() != 4)
                throw new IllegalArgumentException("Not a text frame: " + id);
            text.remove(id);
            text.put(id, new ArrayList<>(values));
            return this;
        }

        public Tag setUserText(String description, String value) {
            userText.remove(description);
            userText.put(description, value);
            return this;
        }

        public Tag setLyrics(String language, String description, String value) {
            lyrics.clear();
            lyrics.put(language + "\u0000" + description, new LangText(language, description, value));
            return this;
        }

        public Tag setCover(byte[] jpeg) {
            picture = new Picture("image/jpeg", jpeg);
            return this;
        }

        /** Value of a text frame as it would be written, null if absent. */
        public String getText(String id) {
            List<String> values = text.get(id);
            if (values == null) return null;
            String value = single(id, values);
            return value.isEmpty() ? null : value;
        }
    }

    static final class LangText {
        final String language;
        final String description;
        final String text;

        LangText(String language, String description, String text) {
            this.language = language;
            this.description = description;
            this.text = text;
        }
    }

    static final class Picture {
        final String mime;
        final byte[] data;

        Picture(String mime, byte[] data) {
            this.mime = mime;
            this.data = data;
        }
    }

    static final class RawFrame {
        final String id;
        final byte[] data;

        RawFrame(String id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }

    //=====================
    // Public API
    //=====================

    /** Reads existing ID3v2 tag(s) of the file, empty tag if there are none. */
    public static Tag read(File file) throws IOException {
        Tag tag = new Tag();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long offset = 0;
            byte[] header = new byte[10];
            while (offset + 10 <= raf.length()) {
                raf.seek(offset);
                raf.readFully(header);
                if (!isId3Header(header)) break;
                int size = syncsafe(header, 6);
                if (offset + 10 + size > raf.length()) break;
                byte[] body = new byte[size];
                raf.readFully(body);
                parseTag(header[3], header[5], body, tag);
                offset += 10 + size + ((header[3] == 4 && (header[5] & 0x10) != 0) ? 10 : 0);
            }
        }
        return tag;
    }

    /** Rewrites the file with the given tag (rules above), audio data is copied byte for byte. */
    public static void write(File file, Tag tag) throws IOException {
        byte[] tagBytes = render(tag);
        long[] audio = audioRange(file);
        File tmp = new File(file.getPath() + ".id3tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp);
                 RandomAccessFile in = new RandomAccessFile(file, "r")) {
                out.write(tagBytes);
                FileChannel inChannel = in.getChannel();
                FileChannel outChannel = out.getChannel();
                long position = audio[0];
                while (position < audio[1]) {
                    long n = inChannel.transferTo(position, audio[1] - position, outChannel);
                    if (n <= 0) throw new IOException("Copying audio data failed");
                    position += n;
                }
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                //Some file systems don't replace on rename
                if (!file.delete() || !tmp.renameTo(file))
                    throw new IOException("Unable to replace " + file.getPath());
            }
        } finally {
            tmp.delete();
        }
    }

    /** Reads the existing tag, sets the given text frames and writes it back. */
    public static void update(File file, Map<String, List<String>> textFrames) throws IOException {
        Tag tag = read(file);
        for (Map.Entry<String, List<String>> e : textFrames.entrySet())
            tag.setText(e.getKey(), e.getValue());
        write(file, tag);
    }

    /** [start, end) of audio data: after leading ID3v2 tag(s), before a trailing ID3v1 tag. */
    static long[] audioRange(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = 0;
            byte[] header = new byte[10];
            while (start + 10 <= length) {
                raf.seek(start);
                raf.readFully(header);
                if (!isId3Header(header)) break;
                long next = start + 10 + syncsafe(header, 6) + ((header[3] == 4 && (header[5] & 0x10) != 0) ? 10 : 0);
                if (next > length) break;
                start = next;
            }
            long end = length;
            if (end - start >= 128) {
                byte[] tagId = new byte[3];
                raf.seek(end - 128);
                raf.readFully(tagId);
                if (tagId[0] == 'T' && tagId[1] == 'A' && tagId[2] == 'G') end -= 128;
            }
            return new long[]{start, end};
        }
    }

    //=====================
    // Text rules
    //=====================

    /** NFC, no control characters (U+0000-U+001F, U+007F-U+009F), trimmed. */
    public static String clean(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) continue;
            out.append(c);
        }
        return out.toString().trim();
    }

    /** Joins multiple values into one ("A, B"), Android only shows the first value of a frame. */
    static String single(String id, List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String v : values) {
            if (id.equals("TCON")) {
                unique.addAll(genreNames(v));
            } else {
                String c = clean(v);
                if (!c.isEmpty()) unique.add(c);
            }
        }
        return join(unique);
    }

    /** Plain text genre names, ID3v1 numeric references ("(17)", "17", "(17)Rock") resolved. */
    static List<String> genreNames(String value) {
        List<String> out = new ArrayList<>();
        String v = clean(value);
        if (v.isEmpty()) return out;
        //Whole value is a number (ID3v2.4 style reference)
        if (v.matches("\\d{1,3}")) {
            out.add(genreName(Integer.parseInt(v), v));
            return out;
        }
        //ID3v2.3 style "(17)(RX)Refinement", "((" escapes a literal "("
        int i = 0;
        while (i < v.length() && v.charAt(i) == '(' && !v.startsWith("((", i)) {
            int close = v.indexOf(')', i);
            if (close < 0) break;
            String ref = v.substring(i + 1, close);
            if (ref.matches("\\d{1,3}")) out.add(genreName(Integer.parseInt(ref), ref));
            else if (ref.equals("RX")) out.add("Remix");
            else if (ref.equals("CR")) out.add("Cover");
            else break;
            i = close + 1;
        }
        String rest = clean(v.substring(i).replace("((", "("));
        if (!rest.isEmpty()) {
            //Refinement replaces the reference it refines ("(17)Rock" -> "Rock")
            if (!out.isEmpty()) out.remove(out.size() - 1);
            out.add(rest);
        }
        return out;
    }

    private static String genreName(int index, String fallback) {
        return index < GENRES.length ? GENRES[index] : fallback;
    }

    //=====================
    // Rendering
    //=====================

    static byte[] render(Tag tag) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();

        //Text frames
        for (Map.Entry<String, List<String>> e : tag.text.entrySet()) {
            String value = single(e.getKey(), e.getValue());
            if (value.isEmpty()) continue;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x03);
            data.write(utf8(value));
            writeFrame(frames, e.getKey(), data.toByteArray());
        }
        //TXXX
        for (Map.Entry<String, String> e : tag.userText.entrySet()) {
            String value = clean(e.getValue());
            if (value.isEmpty()) continue;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x03);
            data.write(utf8(clean(e.getKey())));
            data.write(0x00);
            data.write(utf8(value));
            writeFrame(frames, "TXXX", data.toByteArray());
        }
        //WXXX, url is ISO-8859-1 by spec
        for (Map.Entry<String, String> e : tag.userUrl.entrySet()) {
            String url = clean(e.getValue());
            if (url.isEmpty()) continue;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x03);
            data.write(utf8(clean(e.getKey())));
            data.write(0x00);
            data.write(url.getBytes(StandardCharsets.ISO_8859_1));
            writeFrame(frames, "WXXX", data.toByteArray());
        }
        //COMM / USLT
        writeLangTextFrames(frames, "COMM", tag.comments);
        writeLangTextFrames(frames, "USLT", tag.lyrics);
        //APIC: single front cover, empty description
        if (tag.picture != null && tag.picture.data != null && tag.picture.data.length > 0) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x03);
            data.write(tag.picture.mime.getBytes(StandardCharsets.ISO_8859_1));
            data.write(0x00);
            data.write(0x03);
            data.write(0x00);
            data.write(tag.picture.data);
            writeFrame(frames, "APIC", data.toByteArray());
        }
        //Frames without text encoding
        LinkedHashSet<String> rawIds = new LinkedHashSet<>();
        for (RawFrame f : tag.raw) {
            //One per id, except frames distinguished by their content
            boolean multiple = f.id.equals("PRIV") || f.id.equals("UFID") || f.id.equals("POPM");
            if (!multiple && !rawIds.add(f.id)) continue;
            writeFrame(frames, f.id, f.data);
        }

        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 0x04, 0x00, 0x00});
        out.write(syncsafeBytes(body.length + PADDING));
        out.write(body);
        out.write(new byte[PADDING]);
        return out.toByteArray();
    }

    private static void writeLangTextFrames(ByteArrayOutputStream frames, String id, Map<String, LangText> values) throws IOException {
        for (LangText t : values.values()) {
            String text = cleanMultiline(t.text);
            if (text.isEmpty()) continue;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x03);
            data.write(language(t.language));
            data.write(utf8(clean(t.description)));
            data.write(0x00);
            data.write(utf8(text));
            writeFrame(frames, id, data.toByteArray());
        }
    }

    /** Lyrics and comments keep line breaks, other control characters are removed. */
    static String cleanMultiline(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\n' && (c < 0x20 || (c >= 0x7F && c <= 0x9F))) continue;
            out.append(c);
        }
        return out.toString().trim();
    }

    private static byte[] language(String language) {
        String l = language == null ? "" : language;
        if (!l.matches("[A-Za-z]{3}")) l = "XXX";
        return l.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] data) throws IOException {
        out.write(id.getBytes(StandardCharsets.ISO_8859_1));
        out.write(syncsafeBytes(data.length));
        out.write(0x00);
        out.write(0x00);
        out.write(data);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    //=====================
    // Parsing
    //=====================

    private static boolean isId3Header(byte[] h) {
        return h[0] == 'I' && h[1] == 'D' && h[2] == '3' && h[3] >= 2 && h[3] <= 4 && h[4] != (byte) 0xFF
                && (h[6] & 0x80) == 0 && (h[7] & 0x80) == 0 && (h[8] & 0x80) == 0 && (h[9] & 0x80) == 0;
    }

    private static void parseTag(int version, int flags, byte[] body, Tag tag) {
        boolean tagUnsync = (flags & 0x80) != 0;
        //v2.2/v2.3 unsynchronise the whole tag, v2.4 every frame
        if (tagUnsync && version < 4) body = deunsync(body);
        int offset = 0;
        if ((flags & 0x40) != 0 && version >= 3) {
            if (body.length < 4) return;
            if (version == 3) offset = 4 + int32(body, 0);
            else offset = syncsafe(body, 0);
        }
        Map<String, String> dateParts = new LinkedHashMap<>();
        int headerLength = version == 2 ? 6 : 10;
        while (offset + headerLength <= body.length && body[offset] != 0) {
            String id;
            int size;
            int frameFlags = 0;
            if (version == 2) {
                id = new String(body, offset, 3, StandardCharsets.ISO_8859_1);
                size = ((body[offset + 3] & 0xFF) << 16) | ((body[offset + 4] & 0xFF) << 8) | (body[offset + 5] & 0xFF);
            } else {
                id = new String(body, offset, 4, StandardCharsets.ISO_8859_1);
                if (version == 4 && (body[offset + 4] & 0x80) == 0 && (body[offset + 5] & 0x80) == 0
                        && (body[offset + 6] & 0x80) == 0 && (body[offset + 7] & 0x80) == 0)
                    size = syncsafe(body, offset + 4);
                else
                    size = int32(body, offset + 4); //v2.3, or v2.4 written with plain sizes
                frameFlags = ((body[offset + 8] & 0xFF) << 8) | (body[offset + 9] & 0xFF);
            }
            if (!id.matches("[A-Z0-9]{3,4}") || size < 0 || offset + headerLength + size > body.length) break;
            byte[] data = Arrays.copyOfRange(body, offset + headerLength, offset + headerLength + size);
            offset += headerLength + size;

            data = frameData(version, frameFlags, tagUnsync, data);
            if (data == null) continue;
            if (version == 2) id = V22.get(id) != null ? V22.get(id) : null;
            if (id == null) continue;
            try {
                parseFrame(version, id, data, tag, dateParts);
            } catch (Exception ignored) {
                //Broken frame, skip it
            }
        }
        //ID3v2.3 TYER + TDAT (DDMM) + TIME (HHMM) -> TDRC
        String year = dateParts.get("TYER");
        if (year != null && !tag.text.containsKey("TDRC")) {
            String date = clean(year);
            String dm = dateParts.get("TDAT");
            if (date.matches("\\d{4}") && dm != null && clean(dm).matches("\\d{4}")) {
                dm = clean(dm);
                date += "-" + dm.substring(2, 4) + "-" + dm.substring(0, 2);
                String hm = dateParts.get("TIME");
                if (hm != null && clean(hm).matches("\\d{4}")) {
                    hm = clean(hm);
                    date += "T" + hm.substring(0, 2) + ":" + hm.substring(2, 4);
                }
            }
            tag.text.put("TDRC", new ArrayList<>(Collections.singletonList(date)));
        }
    }

    /** Undoes frame level compression / unsynchronisation, null if the frame can't be used. */
    private static byte[] frameData(int version, int flags, boolean tagUnsync, byte[] data) {
        if (version == 3) {
            if ((flags & 0x0040) != 0) return null; //encrypted
            int skip = 0;
            if ((flags & 0x0080) != 0) skip += 4; //decompressed size
            if ((flags & 0x0020) != 0) skip += 1; //group id
            if (skip > data.length) return null;
            data = Arrays.copyOfRange(data, skip, data.length);
            if ((flags & 0x0080) != 0) data = inflate(data);
        } else if (version == 4) {
            if ((flags & 0x0004) != 0) return null; //encrypted
            int skip = 0;
            if ((flags & 0x0040) != 0) skip += 1; //group id
            if ((flags & 0x0001) != 0) skip += 4; //data length indicator
            if (skip > data.length) return null;
            data = Arrays.copyOfRange(data, skip, data.length);
            if ((flags & 0x0002) != 0 || tagUnsync) data = deunsync(data);
            if ((flags & 0x0008) != 0) data = inflate(data);
        }
        return data;
    }

    private static void parseFrame(int version, String id, byte[] data, Tag tag, Map<String, String> dateParts) {
        if (id.equals("TXXX")) {
            List<String> parts = decodeStrings(data, 1, data[0]);
            if (parts.size() >= 2) {
                String description = clean(parts.get(0));
                if (!tag.userText.containsKey(description))
                    tag.userText.put(description, join(parts.subList(1, parts.size())));
            }
        } else if (id.startsWith("T")) {
            if (data.length == 0) return;
            List<String> values = decodeStrings(data, 1, data[0]);
            //v2.3 and older only have one value per frame, NUL is a terminator there
            if (version < 4 && values.size() > 1) values = values.subList(0, 1);
            switch (id) {
                case "TYER":
                case "TDAT":
                case "TIME":
                    if (!values.isEmpty()) dateParts.put(id, values.get(0));
                    return;
                case "TORY":
                    id = "TDOR";
                    break;
                case "TRDA":
                case "TSIZ":
                    return; //removed in ID3v2.4
                default:
                    break;
            }
            //One frame per id, merge values of duplicate frames
            List<String> existing = tag.text.get(id);
            if (existing == null) tag.text.put(id, new ArrayList<>(values));
            else existing.addAll(values);
        } else if (id.equals("IPLS")) {
            //v2.3 involved people list -> v2.4 TIPL
            List<String> values = decodeStrings(data, 1, data[0]);
            tag.text.put("TIPL", new ArrayList<>(Collections.singletonList(join(values))));
        } else if (id.equals("WXXX")) {
            List<String> parts = decodeStrings(data, 1, data[0]);
            if (parts.isEmpty()) return;
            int descriptionEnd = terminatorEnd(data, 1, data[0]);
            String url = new String(data, descriptionEnd, data.length - descriptionEnd, StandardCharsets.ISO_8859_1);
            tag.userUrl.put(clean(parts.get(0)), stripNuls(url));
        } else if (id.equals("COMM") || id.equals("USLT")) {
            if (data.length < 4) return;
            String language = new String(data, 1, 3, StandardCharsets.ISO_8859_1);
            int descriptionEnd = terminatorEnd(data, 4, data[0]);
            int descriptionStringEnd = descriptionEnd > data.length ? data.length : descriptionEnd - terminatorLength(data[0]);
            String description = decode(Arrays.copyOfRange(data, 4, Math.max(4, descriptionStringEnd)), data[0]);
            String text = descriptionEnd <= data.length
                    ? stripNuls(decode(Arrays.copyOfRange(data, descriptionEnd, data.length), data[0])) : "";
            Map<String, LangText> target = id.equals("COMM") ? tag.comments : tag.lyrics;
            String key = language + "\u0000" + description;
            if (!target.containsKey(key)) target.put(key, new LangText(language, description, text));
        } else if (id.equals("APIC")) {
            if (tag.picture != null || data.length < 4) return;
            int encoding = data[0];
            String mime;
            int o;
            if (version == 2) {
                //PIC: 3 character image format
                String format = new String(data, 1, 3, StandardCharsets.ISO_8859_1).toUpperCase();
                mime = format.equals("PNG") ? "image/png" : "image/jpeg";
                o = 4;
            } else {
                int mimeEnd = indexOf(data, 1, (byte) 0);
                if (mimeEnd < 0) return;
                mime = new String(data, 1, mimeEnd - 1, StandardCharsets.ISO_8859_1).toLowerCase();
                o = mimeEnd + 1;
            }
            o += 1; //picture type
            o = terminatorEnd(data, o, encoding); //description
            if (o > data.length) return;
            byte[] image = Arrays.copyOfRange(data, o, data.length);
            //Keep the real format of existing images, MIME must describe the data
            if (image.length > 2 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) mime = "image/jpeg";
            else if (image.length > 4 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') mime = "image/png";
            if (mime.equals("image/jpg")) mime = "image/jpeg";
            tag.picture = new Picture(mime, image);
        } else if (id.startsWith("W") || RAW_FRAMES.contains(id)) {
            tag.raw.add(new RawFrame(id, data));
        }
        //Other frames (with text encodings this writer doesn't convert) are dropped
    }

    //=====================
    // Decoding helpers
    //=====================

    private static Charset charset(int encoding) {
        switch (encoding) {
            case 1:
                return StandardCharsets.UTF_16; //BOM decides byte order
            case 2:
                return StandardCharsets.UTF_16BE;
            case 3:
                return StandardCharsets.UTF_8;
            default:
                return StandardCharsets.ISO_8859_1;
        }
    }

    private static String decode(byte[] bytes, int encoding) {
        return new String(bytes, charset(encoding));
    }

    private static int terminatorLength(int encoding) {
        return (encoding == 1 || encoding == 2) ? 2 : 1;
    }

    /** Offset after the terminated string starting at start (data.length + 1 if unterminated). */
    private static int terminatorEnd(byte[] data, int start, int encoding) {
        if (terminatorLength(encoding) == 1) {
            int i = indexOf(data, start, (byte) 0);
            return i < 0 ? data.length + 1 : i + 1;
        }
        for (int i = start; i + 1 < data.length; i += 2)
            if (data[i] == 0 && data[i + 1] == 0) return i + 2;
        return data.length + 1;
    }

    /** Strings separated / terminated by NUL in the declared encoding, trailing empty ones dropped. */
    private static List<String> decodeStrings(byte[] data, int start, int encoding) {
        List<String> out = new ArrayList<>();
        int o = start;
        int t = terminatorLength(encoding);
        while (o < data.length) {
            int end = terminatorEnd(data, o, encoding);
            //Last string doesn't need a terminator
            int stringEnd = end > data.length ? data.length : end - t;
            out.add(decode(Arrays.copyOfRange(data, o, Math.max(o, stringEnd)), encoding));
            o = end;
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    //Joins values with ", " (String.join needs API 26)
    private static String join(Iterable<String> values) {
        StringBuilder out = new StringBuilder();
        for (String v : values) {
            if (out.length() > 0) out.append(", ");
            out.append(v);
        }
        return out.toString();
    }

    private static String stripNuls(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end);
    }

    private static int indexOf(byte[] data, int start, byte value) {
        for (int i = start; i < data.length; i++) if (data[i] == value) return i;
        return -1;
    }

    private static byte[] deunsync(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            out.write(data[i]);
            if ((data[i] & 0xFF) == 0xFF && i + 1 < data.length && data[i + 1] == 0) i++;
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static byte[] syncsafeBytes(int n) {
        if (n < 0 || n > 0x0FFFFFFF) throw new IllegalArgumentException("Too large for ID3v2.4: " + n);
        return new byte[]{(byte) ((n >> 21) & 0x7F), (byte) ((n >> 14) & 0x7F), (byte) ((n >> 7) & 0x7F), (byte) (n & 0x7F)};
    }

    //=====================
    // Tables
    //=====================

    //Frames without a text encoding byte, copied unchanged
    private static final List<String> RAW_FRAMES = Arrays.asList("PRIV", "UFID", "POPM", "PCNT", "MCDI");

    //ID3v2.2 frame ids -> ID3v2.3/2.4
    private static final Map<String, String> V22 = new LinkedHashMap<>();

    static {
        String[] pairs = {
                "TT1", "TIT1", "TT2", "TIT2", "TT3", "TIT3", "TP1", "TPE1", "TP2", "TPE2", "TP3", "TPE3",
                "TP4", "TPE4", "TCM", "TCOM", "TXT", "TEXT", "TLA", "TLAN", "TCO", "TCON", "TAL", "TALB",
                "TPA", "TPOS", "TRK", "TRCK", "TRC", "TSRC", "TYE", "TYER", "TDA", "TDAT", "TIM", "TIME",
                "TOR", "TORY", "TBP", "TBPM", "TPB", "TPUB", "TEN", "TENC", "TSS", "TSSE", "TCR", "TCOP",
                "TOA", "TOPE", "TOT", "TOAL", "TOL", "TOLY", "TOF", "TOFN", "TKE", "TKEY", "TLE", "TLEN",
                "TMT", "TMED", "TFT", "TFLT", "TDY", "TDLY", "TXX", "TXXX", "COM", "COMM", "ULT", "USLT",
                "PIC", "APIC", "UFI", "UFID", "CNT", "PCNT", "POP", "POPM", "MCI", "MCDI", "WXX", "WXXX",
                "WAR", "WOAR", "WAF", "WOAF", "WAS", "WOAS", "WCM", "WCOM", "WCP", "WCOP", "WPB", "WPUB",
        };
        for (int i = 0; i < pairs.length; i += 2) V22.put(pairs[i], pairs[i + 1]);
    }

    //ID3v1 genres (including Winamp extensions)
    static final String[] GENRES = {
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
            "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
            "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
            "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
            "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka",
            "Retro", "Musical", "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing",
            "Fast Fusion", "Bebob", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock",
            "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus",
            "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata",
            "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba",
            "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock", "Drum Solo",
            "A capella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror",
            "Indie", "BritPop", "Afro-Punk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal",
            "Black Metal", "Crossover", "Contemporary Christian", "Christian Rock", "Merengue", "Salsa",
            "Thrash Metal", "Anime", "JPop", "Synthpop", "Abstract", "Art Rock", "Baroque", "Bhangra",
            "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic", "Electro",
            "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth",
            "Jam Band", "Krautrock", "Leftfield", "Lounge", "Math Rock", "New Romantic", "Nu-Breakz",
            "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze", "Space Rock", "Trop Rock", "World Music",
            "Neoclassical", "Audiobook", "Audio Theatre", "Neue Deutsche Welle", "Podcast", "Indie Rock",
            "G-Funk", "Dubstep", "Garage Rock", "Psybient",
    };
}
