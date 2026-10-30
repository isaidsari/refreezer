package r.r.refreezer;

import android.util.Log;
import android.util.Pair;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.valuepair.ImageFormats;
import org.jaudiotagger.tag.reference.PictureTypes;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import r.r.refreezer.models.Lyrics;
import r.r.refreezer.models.LyricsNew;
import r.r.refreezer.models.SynchronizedLyric;

public class Deezer {

    static String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36";
    DownloadLog logger;
    String token;
    String arl;
    String sid;
    String licenseToken;
    String contentLanguage = "en";
    volatile boolean authorized = false;
    volatile boolean authorizing = false;

    Deezer() {}

    //Initialize for logging
    void init(DownloadLog logger, String arl) {
        //Load native
        //System.loadLibrary("decryptor-jni");

        this.logger = logger;
        this.arl = arl;
    }

    // Method for when using c libraries for decryption
    //public native void decryptFile(String trackId, String inputFilename, String outputFilename);

    //Authorize GWLight API
    public synchronized void authorize() {
        if (!authorized || sid == null || token == null || token.equals("null")) {
            authorizing = true;
            try {
                //Fresh session
                token = null;
                sid = null;
                callGWAPI("deezer.getUserData", "{}");
                authorized = token != null && !token.equals("null");
            } catch (Exception e) {
                //Retried on next authorize() call (e.g. network was down)
                token = null;
                authorized = false;
                logger.warn("Error authorizing to Deezer API! " + e);
            }
        }
        authorizing = false;
    }

    //Force new session on next authorize(), e.g. after the API token expired
    public synchronized void invalidateSession() {
        authorized = false;
        token = null;
        sid = null;
    }

    //Make POST request
    private String POST(String _url, String data,  Map<String, String> additionalHeaders) {
        String result = null;

        try {
            URL url = new URL(_url);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Language", contentLanguage + ",*");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "*/*");

            // Add additional headers if provided
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            //Write body
            if (data != null) {
                try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                    wr.writeBytes(data);
                }
            }

            //Get response
            try (Scanner scanner = new Scanner(connection.getInputStream())) {
                StringBuilder output = new StringBuilder();
                while (scanner.hasNext()) {
                    output.append(scanner.nextLine());
                }
                result = output.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Error posting Deezer request: " + e);
        }

        return result;
    }

    public JSONObject callGWAPI(String method, String body) throws Exception {
        //Get token
        if (token == null) {
            token = "null";
            callGWAPI("deezer.getUserData", "{}");
        }

        // Construct cookie header
        Map<String, String> cookies = new HashMap<>();
        cookies.put("Cookie", "arl=" + arl + (sid == null ? "" : "; sid=" + sid));

        String data = POST(
                "https://www.deezer.com/ajax/gw-light.php?method=" + method + "&input=3&api_version=1.0&api_token=" + token,
                body,
                cookies
        );
        if (data == null)
            throw new java.io.IOException("No response from Deezer (" + method + ")");

        //Parse JSON
        JSONObject out = new JSONObject(data);

        //API token expired (long running downloads), get a new session and retry once
        JSONObject error = out.optJSONObject("error");
        if (error != null && error.has("VALID_TOKEN_REQUIRED") && !method.equals("deezer.getUserData")) {
            logger.warn("Deezer API token expired, refreshing session");
            invalidateSession();
            authorize();
            cookies.put("Cookie", "arl=" + arl + (sid == null ? "" : "; sid=" + sid));
            data = POST(
                    "https://www.deezer.com/ajax/gw-light.php?method=" + method + "&input=3&api_version=1.0&api_token=" + token,
                    body,
                    cookies
            );
            if (data == null)
                throw new java.io.IOException("No response from Deezer (" + method + ")");
            out = new JSONObject(data);
        }

        //Save token
        if ((token == null || token.equals("null")) && method.equals("deezer.getUserData")) {
            token = out.getJSONObject("results").getString("checkForm");
            sid = out.getJSONObject("results").getString("SESSION_ID");

            // Get User license code
            try {
                JSONObject userData = out.getJSONObject("results").getJSONObject("USER");
                licenseToken = userData.getJSONObject("OPTIONS").getString("license_token");
            } catch (JSONException e) {
                e.printStackTrace();
                logger.warn("Error getting user License Token - FLAC not available! " + e);
            }
        }

        return out;
    }

    //api.deezer.com/$method/$param
    public JSONObject callPublicAPI(String method, String param) throws Exception {
        //Public API is rate limited (50 requests / 5s), returns error code 4 when exceeded
        for (int attempt = 1; ; attempt++) {
            JSONObject result = callPublicAPIOnce(method, param);
            JSONObject error = result.optJSONObject("error");
            if (error == null || error.optInt("code") != 4 || attempt >= 5) return result;
            Thread.sleep(1500L * attempt);
        }
    }

    private JSONObject callPublicAPIOnce(String method, String param) throws Exception {
        URL url = new URL("https://api.deezer.com/" + method + "/" + param);
        HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept-Language", contentLanguage + ",*");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.connect();

        //Get string data
        StringBuilder data = new StringBuilder();
        InputStream inputStream = connection.getInputStream();
        try (Scanner scanner = new Scanner(new InputStreamReader(inputStream))) {
            while (scanner.hasNext()) {
                data.append(scanner.nextLine());
            }
        } finally {
            connection.disconnect();
        }

        //Parse JSON & return
        return new JSONObject(data.toString());
    }

    // Method to call the Pipe API
    public JSONObject callPipeApi(Map<String, Object> params) throws Exception {
        String jwtToken;
        try {
            jwtToken = getJsonWebToken();
        } catch (Exception e) {
            logger.error("Error getting JsonWebToken: " + e);
            throw e;
        }

        // Headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "arl=" + arl + (sid == null ? "" : "; sid=" + sid));
        headers.put("Authorization", "Bearer " + jwtToken);

        // Convert params to JSON string
        String paramsJsonString = null;
        if (params != null && !params.isEmpty()) {
            JSONObject paramsJsonObject = new JSONObject(params);
            paramsJsonString = paramsJsonObject.toString();
        }

        String response = POST("https://pipe.deezer.com/api/", paramsJsonString, headers);
        // Return response as JSONObject
        return new JSONObject(response);
    }

    // Method to get JSON Web Token
    public String getJsonWebToken() throws Exception{
        String urlString = "https://auth.deezer.com/login/arl?jo=p&rto=c&i=c";
        Map<String, String> cookies = new HashMap<>();
        cookies.put("Cookie", "arl=" + arl + (sid == null ? "" : "; sid=" + sid));
        String response = POST(urlString, "", cookies);

        // Parse JSON and return JWT
        JSONObject body = new JSONObject(response);
        return body.has("jwt") ? body.getString("jwt") : "";
    }

    public Lyrics getlyricsNew(String trackId) {
        try {
            // Create the GraphQL query string
            String queryStringGraphQL =
                    "query SynchronizedTrackLyrics($trackId: String!) {" +
                            "  track(trackId: $trackId) {" +
                            "    id" +
                            "    isExplicit" +
                            "    lyrics {" +
                            "      id" +
                            "      copyright" +
                            "      text" +
                            "      writers" +
                            "      synchronizedLines {" +
                            "        lrcTimestamp" +
                            "        line" +
                            "        milliseconds" +
                            "        duration" +
                            "      }" +
                            "    }" +
                            "  }" +
                            "}";

            // Create the request parameters
            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("operationName", "SynchronizedTrackLyrics");
            Map<String, String> variables = new HashMap<>();
            variables.put("trackId", trackId);
            requestParams.put("variables", variables);
            requestParams.put("query", queryStringGraphQL);

            // Call the API
            JSONObject data = callPipeApi(requestParams);

            // Parse the response into a LyricsFull object
            return new LyricsNew(data);

        } catch (Exception e) {
            e.printStackTrace();
            Lyrics errorLyrics = new LyricsNew();
            errorLyrics.setErrorMessage("An error occurred: " + e.getMessage());
            return errorLyrics;
        }
    }

    //Generate track download URL
    public String generateTrackUrl(String trackId, String md5origin, String mediaVersion, int quality) {
        try {
            int magic = 164;

            ByteArrayOutputStream step1 = new ByteArrayOutputStream();
            step1.write(md5origin.getBytes());
            step1.write(magic);
            step1.write(Integer.toString(quality).getBytes());
            step1.write(magic);
            step1.write(trackId.getBytes());
            step1.write(magic);
            step1.write(mediaVersion.getBytes());
            //Get MD5
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(step1.toByteArray());
            byte[] digest = md5.digest();
            String md5hex = DeezerDecryptor.bytesToHex(digest).toLowerCase();

            //Step 2
            ByteArrayOutputStream step2 = new ByteArrayOutputStream();
            step2.write(md5hex.getBytes());
            step2.write(magic);
            step2.write(step1.toByteArray());
            step2.write(magic);

            //Pad step2 with dots, to get correct length
            while(step2.size()%16 > 0) step2.write(46);

            //Prepare AES encryption
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec key = new SecretKeySpec("jo6aey6haid2Teih".getBytes(), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            //Encrypt
            StringBuilder step3 = new StringBuilder();
            for (int i=0; i<step2.size()/16; i++) {
                byte[] b = Arrays.copyOfRange(step2.toByteArray(), i*16, (i+1)*16);
                step3.append(DeezerDecryptor.bytesToHex(cipher.doFinal(b)).toLowerCase());
            }
            //Return joined to URL
            return "https://e-cdns-proxy-" + md5origin.charAt(0) + ".dzcdn.net/mobile/1/" + step3;

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Error generating track URL! ID: " + trackId + " " + e);
        }
        return null;
    }

    // Returns URL and whether encrypted
    public Pair<String, Boolean> getTrackUrl(String trackId, String trackToken, String md5origin, String mediaVersion,
            int quality, int refreshAttempt) {
        // Legit url gen
        if (this.licenseToken != null && (quality > 0)) {
            String url = null;
            String format = "FLAC";

            if (quality == 3) {
                format = "MP3_320";
            } else if (quality == 1) {
                format = "MP3_128";
            }

            try {
                //arl cookie
                Map<String, String> cookies = new HashMap<>();
                cookies.put("Cookie", "arl=" + arl);
                // Create track_url payload
                String payload = "{\n" +
                        "\"license_token\": \"" + licenseToken + "\",\n" +
                        "\"media\": [{ \"type\": \"FULL\", \"formats\": [{ \"cipher\": \"BF_CBC_STRIPE\", \"format\": \"" + format + "\"}]}],\n" +
                        "\"track_tokens\": [\"" + trackToken + "\"]\n" +
                        "}";
                String output = POST("https://media.deezer.com/v1/get_url", payload, cookies);

                JSONObject result = new JSONObject(output);

                if (result.has("data")){
                    for (int i = 0; i < result.getJSONArray("data").length(); i++){
                        JSONObject data = result.getJSONArray("data").getJSONObject(i);
                        if (data.has("errors")){
                            JSONArray errors = data.getJSONArray("errors");
                            for (int j = 0; j < errors.length(); j++) {
                                JSONObject error = errors.getJSONObject(j);
                                if (error.getInt("code") == 2001 && refreshAttempt < 1) {
                                    // Track token is expired, attempt 1 track data refresh
                                    JSONObject privateJson = callGWAPI("song.getListData", "{\"sng_ids\": [" + trackId + "]}");
                                    JSONObject trackData = privateJson.getJSONObject("results").getJSONArray("data").getJSONObject(0);
                                    trackId = trackData.getString("SNG_ID");
                                    trackToken = trackData.getString("TRACK_TOKEN");
                                    md5origin = trackData.getString("MD5_ORIGIN");
                                    mediaVersion = trackData.getString("MEDIA_VERSION");

                                    // Retry getTrackUrl with refreshed track data and increment retry count
                                    return getTrackUrl(trackId, trackToken, md5origin, mediaVersion, quality, refreshAttempt + 1);
                                }
                            }
                            logger.warn("Failed in getting streaming URL: " + data.get("errors"));
                        }
                        if (data.has("media") && data.getJSONArray("media").length() > 0){
                            url = data.getJSONArray("media").getJSONObject(0).getJSONArray("sources").getJSONObject(0).getString("url");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.warn("Error getting streaming URL: " + e);
            }
            return new Pair<String, Boolean>(url,true);
        }
        // Deprecated Legacy url generation. Seems unavailable now, will probably remove it at some point.
        return new Pair<String, Boolean>(generateTrackUrl(trackId, md5origin, mediaVersion, quality), true);
    }

    public static String sanitize(String input) {
        return input.replaceAll("[\\\\/?*:%<>|\"]", "").replace("$", "\\$");
    }

    public static String generateFilename(String original, JSONObject publicTrack, JSONObject publicAlbum, int newQuality) throws Exception {
        original = original.replaceAll("%title%", sanitize(publicTrack.getString("title")));
        original = original.replaceAll("%album%", sanitize(publicTrack.getJSONObject("album").getString("title")));
        original = original.replaceAll("%artist%", sanitize(publicTrack.getJSONObject("artist").getString("name")));
        // Album might not be available
        try {
            original = original.replaceAll("%albumArtist%", sanitize(publicAlbum.getJSONObject("artist").getString("name")));
        } catch (Exception e) {
            original = original.replaceAll("%albumArtist%", sanitize(publicTrack.getJSONObject("artist").getString("name")));
        }

        //Artists
        StringBuilder artists = new StringBuilder();
        StringBuilder feats = new StringBuilder();
        for (int i=0; i<publicTrack.getJSONArray("contributors").length(); i++) {
            String artist = publicTrack.getJSONArray("contributors").getJSONObject(i).getString("name");
            if (!artists.toString().contains(artist))
                artists.append(", ").append(artist);
            if (i > 0 && !artists.toString().contains(artist) && !feats.toString().contains(artist))
                feats.append(", ").append(artist);
        }
        original = original.replaceAll("%artists%", sanitize(artists.toString()).substring(2));
        if (feats.length() >= 2)
            original = original.replaceAll("%feats%", sanitize(feats.toString()).substring(2));
        //Track number
        int trackNumber = publicTrack.getInt("track_position");
        original = original.replaceAll("%trackNumber%", Integer.toString(trackNumber));
        original = original.replaceAll("%0trackNumber%", String.format("%02d", trackNumber));
        //Year
        original = original.replaceAll("%year%", publicTrack.getString("release_date").substring(0, 4));
        original = original.replaceAll("%date%", publicTrack.getString("release_date"));

        //Remove leading dots
        original = original.replaceAll("/\\.+", "/");

        if (newQuality == 9) return original + ".flac";
        return original + ".mp3";
    }

    //Deezer patched something so getting metadata of user uploaded MP3s is not working anymore
    public static String generateUserUploadedMP3Filename(String original, String title) {
        String[] ignored = {"%feats%", "%trackNumber%", "%0trackNumber%", "%year%", "%date%", "%album%", "%artist%", "%artists%", "%albumArtist%"};
        for (String i : ignored) {
            original = original.replaceAll(i, "");
        }

        original = original.replace("%title%", sanitize(title));
        return original;
    }

    //Tag track with data from API
    public void tagTrack(String path, JSONObject publicTrack, JSONObject publicAlbum, String cover, Lyrics lyricsData, JSONObject privateJson, DownloadService.DownloadSettings settings) throws Exception {
        boolean albumAvailable = !publicAlbum.has("error");
        String separator = settings.artistSeparator;

        //Values to write, null = not written
        String title = settings.tags.title ? publicTrack.getString("title") : null;
        String album = settings.tags.album ? publicTrack.getJSONObject("album").getString("title") : null;
        String artist = null;
        if (settings.tags.artist) {
            StringBuilder artists = new StringBuilder();
            for (int i=0; i<publicTrack.getJSONArray("contributors").length(); i++) {
                String name = publicTrack.getJSONArray("contributors").getJSONObject(i).getString("name");
                if (!artists.toString().contains(name))
                    artists.append(separator).append(name);
            }
            if (artists.length() > separator.length()) artist = artists.substring(separator.length());
        }
        String trackNumber = settings.tags.track ? String.format("%02d", publicTrack.getInt("track_position")) : null;
        String discNumber = settings.tags.disc ? Integer.toString(publicTrack.getInt("disk_number")) : null;
        String albumArtist = (settings.tags.albumArtist && albumAvailable) ? publicAlbum.getJSONObject("artist").getString("name") : null;
        String year = settings.tags.date ? publicTrack.getString("release_date").substring(0, 4) : null;
        String label = (settings.tags.label && albumAvailable) ? publicAlbum.getString("label") : null;
        String isrc = settings.tags.isrc ? publicTrack.getString("isrc") : null;
        String upc = (settings.tags.upc && albumAvailable) ? publicAlbum.getString("upc") : null;
        String trackTotal = (settings.tags.trackTotal && albumAvailable) ? Integer.toString(publicAlbum.getInt("nb_tracks")) : null;
        String bpm = (settings.tags.bpm && publicTrack.has("bpm") && (int)publicTrack.getDouble("bpm") > 0)
                ? Integer.toString((int)publicTrack.getDouble("bpm")) : null;
        String lyrics = (settings.tags.lyrics && lyricsData != null && lyricsData.getUnsyncedLyrics() != null
                && !lyricsData.getUnsyncedLyrics().isEmpty()) ? lyricsData.getUnsyncedLyrics() : null;

        //Genres
        String genre = null;
        if (albumAvailable && settings.tags.genre) {
            StringBuilder genres = new StringBuilder();
            for (int i=0; i<publicAlbum.getJSONObject("genres").getJSONArray("data").length(); i++) {
                String name = publicAlbum.getJSONObject("genres").getJSONArray("data").getJSONObject(0).getString("name");
                if (!genres.toString().contains(name)) {
                    genres.append(", ").append(name);
                }
            }
            if (genres.length() > 2) genre = genres.substring(2);
        }

        //Additional tags from private api
        String composer = null, engineer = null, mixer = null, producer = null, author = null, writer = null;
        if (settings.tags.contributors) {
            try {
                if (privateJson != null && privateJson.has("SNG_CONTRIBUTORS")) {
                    JSONObject contrib = privateJson.getJSONObject("SNG_CONTRIBUTORS");
                    composer = joinContributors(contrib, "composer", separator);
                    engineer = joinContributors(contrib, "engineer", separator);
                    mixer = joinContributors(contrib, "mixer", separator);
                    producer = joinContributors(contrib, "producer", separator);
                    author = joinContributors(contrib, "author", separator);
                    writer = joinContributors(contrib, "writer", separator);
                }
            } catch (Exception e) {
                logger.warn("Error writing contributors data: " + e);
            }
        }

        File coverFile = new File(cover);
        boolean addCover = settings.tags.albumArt && coverFile.exists() && coverFile.length() > 0;

        //MP3: ID3v2.4, all text UTF-8, see Id3v24Writer (Android's media scanner garbles mixed encodings)
        if (path.toLowerCase().endsWith(".mp3")) {
            File file = new File(path);
            Id3v24Writer.Tag tag = Id3v24Writer.read(file);
            if (title != null) tag.setText("TIT2", title);
            if (album != null) tag.setText("TALB", album);
            if (artist != null) tag.setText("TPE1", artist);
            if (trackNumber != null) tag.setText("TRCK", trackTotal != null ? trackNumber + "/" + trackTotal : trackNumber);
            if (discNumber != null) tag.setText("TPOS", discNumber);
            if (albumArtist != null) tag.setText("TPE2", albumArtist);
            if (year != null) tag.setText("TDRC", year);
            if (label != null) tag.setText("TPUB", label);
            if (isrc != null) tag.setText("TSRC", isrc);
            if (upc != null) tag.setUserText("BARCODE", upc);
            if (bpm != null) tag.setText("TBPM", bpm);
            if (genre != null) tag.setText("TCON", genre);
            if (composer != null) tag.setText("TCOM", composer);
            if (engineer != null) tag.setUserText("ENGINEER", engineer);
            if (mixer != null) tag.setUserText("MIXER", mixer);
            if (producer != null) tag.setUserText("PRODUCER", producer);
            if (lyrics != null) tag.setLyrics("eng", "", lyrics);
            if (addCover) {
                try (RandomAccessFile cf = new RandomAccessFile(coverFile, "r")) {
                    byte[] coverData = new byte[(int) cf.length()];
                    cf.readFully(coverData);
                    tag.setCover(coverData);
                } catch (Exception e) {
                    logger.warn("Error reading cover for tagging: " + e);
                }
            }
            Id3v24Writer.write(file, tag);
            return;
        }

        //FLAC (Vorbis comments, always UTF-8)
        TagOptionSingleton.getInstance().setAndroid(true);
        AudioFile f = AudioFileIO.read(new File(path));
        Tag tag = f.getTag();
        if (title != null) tag.setField(FieldKey.TITLE, title);
        if (album != null) tag.setField(FieldKey.ALBUM, album);
        if (artist != null) tag.addField(FieldKey.ARTIST, artist);
        if (trackNumber != null) tag.setField(FieldKey.TRACK, trackNumber);
        if (discNumber != null) tag.setField(FieldKey.DISC_NO, discNumber);
        if (albumArtist != null) tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
        if (year != null) tag.setField(FieldKey.YEAR, year);
        if (label != null) tag.setField(FieldKey.RECORD_LABEL, label);
        if (isrc != null) tag.setField(FieldKey.ISRC, isrc);
        if (upc != null) tag.setField(FieldKey.BARCODE, upc);
        if (trackTotal != null) tag.setField(FieldKey.TRACK_TOTAL, trackTotal);
        if (bpm != null) tag.setField(FieldKey.BPM, bpm);
        if (lyrics != null) {
            try {
                tag.setField(FieldKey.LYRICS, lyrics);
            } catch (Exception e) {
                Log.w("WARN", "Error adding unsynced lyrics!");
            }
        }
        if (genre != null) tag.setField(FieldKey.GENRE, genre);
        try {
            if (composer != null) tag.setField(FieldKey.COMPOSER, composer);
            if (engineer != null) tag.setField(FieldKey.ENGINEER, engineer);
            if (mixer != null) tag.setField(FieldKey.MIXER, mixer);
            if (producer != null) tag.setField(FieldKey.MIXER, producer);
            if (author != null) ((FlacTag) tag).setField("AUTHOR", author);
            if (writer != null) ((FlacTag) tag).setField("WRITER", writer);
        } catch (Exception e) {
            logger.warn("Error writing contributors data: " + e);
        }

        //FLAC Specific tags
        if (settings.tags.date) ((FlacTag)tag).setField("DATE", publicTrack.getString("release_date"));
        //Cover
        if (addCover) {
            try (RandomAccessFile cf = new RandomAccessFile(coverFile, "r")) {
                byte[] coverData = new byte[(int) cf.length()];
                cf.read(coverData);
                tag.setField(((FlacTag) tag).createArtworkField(
                        coverData,
                        PictureTypes.DEFAULT_ID,
                        ImageFormats.MIME_TYPE_JPEG,
                        "cover",
                        settings.albumArtResolution,
                        settings.albumArtResolution,
                        24,
                        0
                ));
            } catch (Exception e) {
                logger.warn("Error writing coverFile artwork: " + e);
            }
        }

        //Save
        AudioFileIO.write(f);
    }

    //Contributors of a role joined with separator, null if none
    private static String joinContributors(JSONObject contrib, String role, String separator) throws JSONException {
        if (!contrib.has(role)) return null;
        JSONArray names = contrib.getJSONArray(role);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.length(); i++)
            out.append(separator).append(names.getString(i));
        return out.length() > 2 ? out.substring(separator.length()) : null;
    }

    //Create JSON file, privateJsonData = `song.getLyrics`
    public static String generateLRC(Lyrics lyricsData, JSONObject publicTrack) throws Exception {
        StringBuilder output = new StringBuilder();

        //Create metadata
        String title = publicTrack.getString("title");
        String album = publicTrack.getJSONObject("album").getString("title");
        StringBuilder artists = new StringBuilder();
        for (int i=0; i<publicTrack.getJSONArray("contributors").length(); i++) {
            artists.append(", ").append(publicTrack.getJSONArray("contributors").getJSONObject(i).getString("name"));
        }
        //Write metadata
        output.append("[ar:").append(artists.substring(2)).append("]\r\n[al:").append(album).append("]\r\n[ti:").append(title).append("]\r\n");

        //Get lyrics
        int counter = 0;
        if (lyricsData.getSyncedLyrics() != null){
            for (int i=0; i<lyricsData.getSyncedLyrics().size(); i++) {
                SynchronizedLyric lyric = lyricsData.getSyncedLyrics().get(i);
                if (lyric.getLrcTimestamp() != null && lyric.getText() != null) {
                    output.append(lyric.getLrcTimestamp()).append(lyric.getText()).append("\r\n");
                    counter += 1;
                }
            }
        }
        
        return output.toString();
    }

    static class QualityInfo {
        int quality;
        String md5origin;
        String mediaVersion;
        String trackId;
        String trackToken;
        int initialQuality;
        DownloadLog logger;
        boolean encrypted;

        QualityInfo(int quality, String trackId, String trackToken, String md5origin, String mediaVersion, DownloadLog logger) {
            this.quality = quality;
            this.initialQuality = quality;
            this.trackId = trackId;
            this.trackToken = trackToken;
            this.mediaVersion = mediaVersion;
            this.md5origin = md5origin;
            this.logger = logger;
        }

        String fallback(Deezer deezer) {
            //Quality fallback
            try {
                String url = qualityFallback(deezer);
                //No quality
                if (quality == -1)
                    throw new Exception("No quality to fallback to!");

                //Success
                return url;
            } catch (Exception e) {
                logger.warn("Quality fallback failed! ID: " + trackId + " " + e);
                quality = initialQuality;
            }

            //Track ID Fallback
            JSONObject privateJson = null;
            try {
                //Fetch meta
                JSONObject privateRaw = deezer.callGWAPI("deezer.pageTrack", "{\"sng_id\": \"" + trackId + "\"}");
                privateJson = privateRaw.getJSONObject("results").getJSONObject("DATA");
                if (privateJson.has("FALLBACK")) {
                    //Fetch new track
                    String fallbackId = privateJson.getJSONObject("FALLBACK").getString("SNG_ID");
                    if (!fallbackId.equals(trackId)) {
                        JSONObject newPrivate = deezer.callGWAPI("song.getListData", "{\"sng_ids\": [" + fallbackId + "]}");
                        JSONObject trackData = newPrivate.getJSONObject("results").getJSONArray("data").getJSONObject(0);
                        trackId = trackData.getString("SNG_ID");
                        trackToken = trackData.getString("TRACK_TOKEN");
                        md5origin = trackData.getString("MD5_ORIGIN");
                        mediaVersion = trackData.getString("MEDIA_VERSION");
                        return fallback(deezer);
                    }
                }
            } catch (Exception e) {
                logger.error("ID fallback failed! ID: " + trackId + " " + e);
            }

            return null;
        }

        private String qualityFallback(Deezer deezer) throws Exception {
            Pair<String,Boolean> urlGen = deezer.getTrackUrl(trackId, trackToken, md5origin, mediaVersion, quality, 0);
            this.encrypted = urlGen.second;

            // initialise as "404 Not Found"
            int urlResponseCode = 404;

            if (urlGen.first != null) {
                //Create HEAD requests to check if exists
                URL url = new URL(urlGen.first);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "*");
                connection.setRequestProperty("Accept", "*/*");
                try {
                    urlResponseCode = connection.getResponseCode();
                } finally {
                    connection.disconnect();
                }
            }
            //Track not available
            if (urlResponseCode > 400) {
                logger.warn("Quality fallback, response code: " + urlResponseCode + ", current: " + quality);
                //-1 if no quality available
                if (quality == 1) {
                    quality = -1;
                    return null;
                }
                if (quality == 3) quality = 1;
                if (quality == 9) quality = 3;
                return qualityFallback(deezer);
            }
            return urlGen.first;
        }

    }
}
