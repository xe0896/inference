package inference;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanim.server.Server;

import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

public class Gateway {
   private static SecureRandom random = new SecureRandom();

   private static final Logger logger = Logger.getLogger(Gateway.class.getName());
   private static final Server server = Server.create(InetAddress.getLoopbackAddress(), 8091);
   private static final ObjectMapper mapper = new ObjectMapper();
   private static InferenceServiceGrpc.InferenceServiceBlockingStub blockingStub;
   private static AuthenticationServiceGrpc.AuthenticationServiceBlockingStub authStub;
   private static long life = 604800;

   record Session(String hashedToken, long expiresAt, long createdAt) {
   }

   private static final Map<UUID, Session> sessions = new HashMap<>();

   public static void process(Request req, com.sanim.server.Response res) {
      Prompt request = Prompt.newBuilder().setPrompt(req.prompt())
            .setMaxTokens(req.maxTokens())
            .setTemperature((float) req.temperature())
            .setThinking(req.thinking())
            .build();
      Context.CancellableContext ctx = Context.current().withCancellation();
      try {
         res.onClose(() -> ctx.cancel(null));
         ctx.call(() -> {
            Iterator<Response> response = blockingStub.generate(request);
            boolean answer = false;
            while (response.hasNext()) {
               Response next = response.next();
               switch (next.getPayloadCase()) {
                  case CHUNK -> {
                     Chunk chunk = next.getChunk();
                     String event = (chunk.getKind() == Kind.REASONING) ? "reasoning" : "answer";
                     res.sendEvent(event, mapper.writeValueAsString(Map.of("text", chunk.getText())));
                  }

                  case DONE -> {
                     Done done = next.getDone();
                     res.sendEvent("done", mapper.writeValueAsString(
                           Map.of("finish_reason", done.getFinishReason(),
                                 "prompt_tokens", done.getPromptTokens(),
                                 "completion_tokens", done.getCompletionTokens(),
                                 "prefill_tps", done.getPrefillTps(),
                                 "decode_tps", done.getDecodeTps())));
                  }

                  case PAYLOAD_NOT_SET -> {
                     System.err.println("Payload not set");
                  }
               }
            }
            return null;
         });
      } catch (StatusRuntimeException e) {
         logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      } catch (Exception e) {
         e.printStackTrace();
      } finally {
         ctx.cancel(null);
      }
   }

   record Request(String prompt, @JsonProperty("max_tokens") int maxTokens, double temperature, boolean thinking,
         UUID id) {
   }

   record Auth(String username, String password) {
   }

   public static String jsonRequest(byte[] jsonBytes) {
      return new String(jsonBytes, StandardCharsets.UTF_8).trim();
   }

   public static String newToken() {
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
   }

   public static String sha256(String input) {
      try {
         // Get an instance of the MessageDigest for SHA-256
         MessageDigest digest = MessageDigest.getInstance("SHA-256");

         // Convert the string to bytes (required for hashing)
         byte[] encodedHash = digest.digest(
               input.getBytes(StandardCharsets.UTF_8));

         // Convert the byte array into a hexadecimal string for display
         StringBuilder hexString = new StringBuilder(2 * encodedHash.length);
         for (byte b : encodedHash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
               hexString.append('0');
            }
            hexString.append(hex);
         }
         return hexString.toString();

      } catch (NoSuchAlgorithmException e) {
         // This exception should rarely happen in standard Java environments
         throw new RuntimeException("SHA-256 algorithm not found", e);
      }
   }

   public static void main(String[] args) throws InterruptedException {
      String target = "localhost:8090";

      ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
      blockingStub = InferenceServiceGrpc.newBlockingStub(channel);
      authStub = AuthenticationServiceGrpc.newBlockingStub(channel);

      server.post("/chat", (req, res) -> {
         Request clientRequest = mapper.readValue(jsonRequest(req.getRequest()), Request.class);

         // session:prefix
         String token = req.get("cookie").substring(8);

         String hashedToken = sha256(token);
         Session session = sessions.get(clientRequest.id());
         if (session.hashedToken().equals(hashedToken)) {
            logger.info("Processing prompt: " + clientRequest.prompt());
            res.startStream();
            process(clientRequest, res);
         } else {
            res.sendResponseHeader(404);
            res.sendResponse(Optional.empty());
         }
      });

      server.post("/login", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         logger.info(String.format("Received username: '%s' and password: '%s", clientAuth.username(),
               clientAuth.password()));
         Credentials credentials = Credentials.newBuilder().setUsername(clientAuth.username())
               .setPassword(clientAuth.password()).build();

         Result result = authStub.login(credentials);
         switch (result.getPayloadCase()) {
            case STATUS -> {
               // Unsuccessful

               int code = result.getStatus();
               res.sendResponseHeader(code);
               res.sendResponse(Optional.empty());
            }

            case UUID -> {
               // Successful
               UUID uuid = UUID.fromString(result.getUuid());
               String token = newToken();
               res.addHeader("Set-Cookie",
                     "session=" + token + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + life);
               sessions.put(uuid,
                     new Session(sha256(token), Instant.now().getEpochSecond() + life,
                           Instant.now().getEpochSecond()));
               res.sendResponseHeader(200);
               res.sendResponse(Optional.of(uuid.toString().getBytes()));
            }

            default -> {
               System.err.println("Payload not set");
            }
         }
      });

      server.post("/signup", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         logger.info(String.format("Received username: '%s' and password: '%s", clientAuth.username(),
               clientAuth.password()));
         Credentials credentials = Credentials.newBuilder().setUsername(clientAuth.username())
               .setPassword(clientAuth.password()).build();

         Result result = authStub.sign(credentials);
         switch (result.getPayloadCase()) {
            case STATUS -> {
               // Unsuccessful
               int code = result.getStatus();
               res.sendResponseHeader(code);
               res.sendResponse(Optional.empty());
            }

            case UUID -> {
               // Successful
               UUID uuid = UUID.fromString(result.getUuid());
               String token = newToken();
               res.addHeader("Set-Cookie",
                     "session=" + token + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + life);
               sessions.put(uuid,
                     new Session(sha256(token), Instant.now().getEpochSecond() + life,
                           Instant.now().getEpochSecond()));
               res.sendResponseHeader(200);
               res.sendResponse(Optional.of(uuid.toString().getBytes()));
            }

            default -> {
               System.err.println("Payload not set");
            }
         }
      });

      server.start();

      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
   }
}
