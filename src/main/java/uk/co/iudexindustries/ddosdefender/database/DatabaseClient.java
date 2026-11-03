package uk.co.iudexindustries.ddosdefender.database;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Protocol v2 with the central database server (server/ddosdefender_db.py).
 *
 * Every install has its own Ed25519 key pair, made on first start and kept in
 * identity.json. Registering it costs a one-off proof of work, so mass-made
 * fake installs are expensive; after that every request is signed over
 * (method, path, time, nonce, body hash) and can be neither altered nor
 * replayed.
 *
 * Every response must carry the server's signature over (this request's
 * nonce, body hash), checked against SERVER_PUBLIC_KEY below. Only the public
 * half is in this jar, so a decompiled plugin can't forge server answers, and
 * a DNS hijack or MITM can't feed it a fake database or key.
 */
final class DatabaseClient {

    /** Public half of the server signing key: {@code ddosdefender_db.py pubkey}. */
    static final String SERVER_PUBLIC_KEY = "nTqQ81BmC6yfqNbB0Y3744kcqTcfqc9L2sCrYkShJcM=";

    private static final String IDENTITY_FILE = "identity.json";
    // DER header that turns a raw 32-byte Ed25519 public key into X.509 form.
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    // The server picks the proof-of-work difficulty; anything above this is
    // refused rather than letting a fake server pin our CPU.
    private static final int MAX_POW_BITS = 30;
    // Replies are read whole before their signature can be checked, so cap
    // them: real ones are a few KB, a full weekly update at most ~6 MB.
    private static final int MAX_REPLY_BYTES = 8 * 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String server;
    private final File folder;
    private final Logger logger;
    private final PublicKey serverKey;
    private final OkHttpClient http = new OkHttpClient.Builder()
            // Short enough that a final report flush can't hold up a server shutdown for long.
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    // No overall call timeout: a copy is hundreds of MB.
    private final OkHttpClient downloadHttp = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private KeyPair identity;
    private String installId;
    // Server time minus ours, learned from a "clock skew" refusal.
    private volatile long clockOffset;

    /** A refusal from the server, already checked to be genuinely from it. */
    static final class ApiException extends IOException {
        final int code;
        final JsonObject body;

        ApiException(int code, JsonObject body) {
            super(body.has("error") ? body.get("error").getAsString() : "HTTP " + code);
            this.code = code;
            this.body = body;
        }

        long retryAfterSeconds() {
            return body.has("retry_after") ? body.get("retry_after").getAsLong() : -1;
        }
    }

    DatabaseClient(String server, File folder, Logger logger) {
        this.server = server;
        this.folder = folder;
        this.logger = logger;
        PublicKey key;
        try {
            key = publicKey(Base64.getDecoder().decode(SERVER_PUBLIC_KEY));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            key = null;
        }
        this.serverKey = key;
    }

    boolean isConfigured() {
        return serverKey != null;
    }

    /** Loads identity.json, or makes and registers a new identity. Blocking. */
    synchronized void ensureRegistered() throws IOException {
        if (installId != null) return;
        if (!isConfigured()) throw new IOException("this build has no server key");
        File file = new File(folder, IDENTITY_FILE);
        if (file.exists()) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
                KeyFactory factory = KeyFactory.getInstance("Ed25519");
                PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.getDecoder().decode(saved.get("private").getAsString())));
                PublicKey pub = publicKey(Base64.getDecoder().decode(saved.get("public").getAsString()));
                identity = new KeyPair(pub, priv);
                installId = saved.get("install").getAsString();
                return;
            } catch (RuntimeException | GeneralSecurityException e) {
                logger.warning("identity.json is damaged — registering a new identity");
            }
        }
        register(file);
    }

    /** Forgets the identity, e.g. after the server says it no longer knows it. */
    synchronized void resetIdentity() {
        installId = null;
        identity = null;
        new File(folder, IDENTITY_FILE).delete();
    }

    private void register(File file) throws IOException {
        KeyPair pair;
        try {
            pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 unavailable: " + e.getMessage(), e);
        }
        byte[] rawPub = rawPublicKey(pair.getPublic());

        JsonObject challenge = call("GET", "/v2/challenge", null, false);
        byte[] chal = Base64.getDecoder().decode(challenge.get("challenge").getAsString());
        int bits = challenge.get("bits").getAsInt();
        if (bits < 0 || bits > MAX_POW_BITS) throw new IOException("unreasonable proof of work requested");

        logger.info("Registering this server with the DDoSDefender database (one-off, takes a few seconds)...");
        long start = System.currentTimeMillis();
        long pow = proofOfWork(chal, rawPub, bits);

        JsonObject body = new JsonObject();
        body.addProperty("pubkey", Base64.getEncoder().encodeToString(rawPub));
        body.addProperty("challenge", challenge.get("challenge").getAsString());
        body.addProperty("pow", pow);
        String install = call("POST", "/v2/register", body, false).get("install").getAsString();

        JsonObject saved = new JsonObject();
        saved.addProperty("install", install);
        saved.addProperty("public", Base64.getEncoder().encodeToString(rawPub));
        saved.addProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        File tmp = new File(folder, IDENTITY_FILE + ".tmp");
        Files.writeString(tmp.toPath(), saved.toString());
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        identity = pair;
        installId = install;
        logger.info("Registered (" + (System.currentTimeMillis() - start) / 1000 + "s)");
    }

    // Smallest n with sha256(challenge || pubkey || n) starting with `bits` zero bits.
    private static long proofOfWork(byte[] challenge, byte[] pubkey, int bits) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        ByteBuffer counter = ByteBuffer.allocate(8);
        for (long n = 0; ; n++) {
            sha.update(challenge);
            sha.update(pubkey);
            counter.putLong(0, n);
            sha.update(counter.array());
            if (leadingZeroBits(sha.digest()) >= bits) return n;
        }
    }

    private static int leadingZeroBits(byte[] digest) {
        int bits = 0;
        for (byte b : digest) {
            if (b == 0) {
                bits += 8;
                continue;
            }
            return bits + Integer.numberOfLeadingZeros(b & 0xFF) - 24;
        }
        return bits;
    }

    /** A signed JSON request; the reply is checked against the server key. */
    JsonObject call(String method, String path, JsonObject body) throws IOException {
        return call(method, path, body, true);
    }

    private JsonObject call(String method, String path, JsonObject body, boolean signed) throws IOException {
        try {
            return callOnce(method, path, body, signed);
        } catch (ApiException e) {
            // Our clock is off: callOnce learned the server's time, try once more.
            if (e.code == 401 && e.body.has("server_time")) return callOnce(method, path, body, signed);
            throw e;
        }
    }

    private JsonObject callOnce(String method, String path, JsonObject body, boolean signed) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        String nonce = newNonce();
        Request.Builder request = new Request.Builder().url(server + path).header("X-DD-Nonce", nonce);
        if (signed) sign(request, method, path, nonce, payload);
        request.method(method, method.equals("GET") ? null : RequestBody.create(payload, JSON));

        try (Response response = http.newCall(request.build()).execute()) {
            byte[] raw = response.body() == null ? new byte[0]
                    : response.body().byteStream().readNBytes(MAX_REPLY_BYTES + 1);
            if (raw.length > MAX_REPLY_BYTES) {
                throw new IOException("reply from " + server + " is too large — ignored");
            }
            if (!verify(response.header("X-DD-Sig"), nonce, raw)) {
                throw new IOException("reply from " + server + " is not signed by the DDoSDefender server (HTTP "
                        + response.code() + ")");
            }
            JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            JsonObject json = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            if (response.code() != 200) {
                if (json.has("server_time")) {
                    clockOffset = json.get("server_time").getAsLong() - System.currentTimeMillis() / 1000;
                }
                throw new ApiException(response.code(), json);
            }
            return json;
        } catch (RuntimeException e) {
            throw new IOException("unexpected reply from the database server: " + e.getMessage(), e);
        }
    }

    /**
     * Opens a signed GET for a large file (a database copy). The caller closes
     * the response and checks the bytes against the hash from the signed
     * /v2/download reply.
     */
    Response openFile(String path) throws IOException {
        String nonce = newNonce();
        Request.Builder request = new Request.Builder().url(server + path).header("X-DD-Nonce", nonce);
        sign(request, "GET", path, nonce, new byte[0]);
        Response response = downloadHttp.newCall(request.get().build()).execute();
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            response.close();
            throw new IOException("database server answered HTTP " + code);
        }
        return response;
    }

    private void sign(Request.Builder request, String method, String path, String nonce, byte[] payload)
            throws IOException {
        if (installId == null) throw new IOException("not registered");
        long time = System.currentTimeMillis() / 1000 + clockOffset;
        String message = "DDv2\n" + method + "\n" + path + "\n" + time + "\n" + nonce + "\n" + sha256Hex(payload);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(identity.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            request.header("X-DD-Install", installId)
                   .header("X-DD-Time", Long.toString(time))
                   .header("X-DD-Sig", Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign request: " + e.getMessage(), e);
        }
    }

    private boolean verify(String signature, String nonce, byte[] body) {
        if (signature == null || serverKey == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(serverKey);
            verifier.update(("DDv2-resp\n" + nonce + "\n" + sha256Hex(body)).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private static String newNonce() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) throw new GeneralSecurityException("not an Ed25519 key");
        byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + 32);
        System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] rawPublicKey(PublicKey key) {
        byte[] der = key.getEncoded();
        return Arrays.copyOfRange(der, der.length - 32, der.length);
    }
}
