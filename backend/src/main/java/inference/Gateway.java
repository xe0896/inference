package inference;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
   private static final ObjectMapper mapper = new ObjectMapper();
   private static InferenceServiceGrpc.InferenceServiceBlockingStub inferenceStub;
   private static AuthenticationServiceGrpc.AuthenticationServiceBlockingStub verifyStub;
   private static StatServiceGrpc.StatServiceBlockingStub statStub;

   private static long life = 604800;

   record Session(String hashedToken, long expiresAt, long createdAt) {
   }

   private static final Map<UUID, Session> sessions = new HashMap<>();

   public static Messages httpMessageToProto(List<Message> messages) {
      return Messages.newBuilder().addAllMessage(messages.stream()
            .map(m -> inference.Message.newBuilder()
                  .setRole(m.role())
                  .setContent(m.content())
                  .build())
            .toList()).build();
   }

   public static Done processModel(Request req, com.sanim.server.Response res) {
      Messages messages = httpMessageToProto(req.messages());

      Conversation conv = Conversation.newBuilder()
            .setMessages(messages)
            .setMaxTokens(req.maxTokens())
            .setTemperature((float) req.temperature())
            .setThinking(req.thinking())
            .setId(inference.UUID.newBuilder().setUuid(req.id().toString()).build())
            .build();
      Context.CancellableContext ctx = Context.current().withCancellation();

      try {
         AtomicReference<Done> done = new AtomicReference<>();
         res.onClose(() -> ctx.cancel(null));
         ctx.call(() -> {
            Iterator<inference.Response> response = inferenceStub.generate(conv);
            while (response.hasNext()) {
               inference.Response next = response.next();
               switch (next.getPayloadCase()) {
                  case CHUNK -> {
                     Chunk chunk = next.getChunk();
                     String event;
                     if (chunk.getKind() == Kind.REASONING) {
                        event = "reasoning";
                     } else if (chunk.getKind() == Kind.ANSWER) {
                        event = "answer";
                     } else if (chunk.getKind() == Kind.COMPACT) {
                        event = "compact";
                     } else {
                        event = "finished_compact";
                     }
                     res.sendEvent(event, mapper.writeValueAsString(Map.of("text", chunk.getText())));
                  }

                  case DONE -> {

                     done.set(next.getDone());
                     Done innerDone = done.get();
                     res.sendEvent("done", mapper.writeValueAsString(
                           Map.of("finish_reason", innerDone.getFinishReason(),
                                 "prompt_tokens", innerDone.getPromptTokens(),
                                 "completion_tokens", innerDone.getCompletionTokens(),
                                 "prefill_tps", innerDone.getPrefillTps(),
                                 "decode_tps", innerDone.getDecodeTps())));
                  }
                  case PAYLOAD_NOT_SET -> {
                     System.err.println("Payload not set");
                  }
               }
            }
            return null;
         });
         return done.get();
      } catch (StatusRuntimeException e) {
         logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      } catch (Exception e) {
         e.printStackTrace();
      } finally {
         ctx.cancel(null);
      }
      return null;
   }

   record Message(String role, String content) {
   }

   record Request(List<Message> messages, @JsonProperty("max_tokens") int maxTokens, double temperature,
         boolean thinking,
         UUID id) {
   }

   record Convo(List<Message> messages, UUID id) {
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

   public static void handleResult(LoginResult result, com.sanim.server.Response res) throws IOException {
      switch (result.getPayloadCase()) {
         case STATUS -> {
            // Unsuccessful
            int code = result.getStatus();
            res.sendResponseHeader(code);
            res.sendResponse(Optional.empty());
         }

         case SUCCESS -> {
            // Successful login may mean that there is a saved conversation we can get,
            // meaning the gRPC should return the UUID as well as the list of messages,
            // so far each user has only one conversation
            UUID uuid = UUID.fromString(result.getSuccess().getUuid().getUuid());
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
   }

   public static void main(String[] args) throws InterruptedException, UnknownHostException {
      Server server = Server.create(InetAddress.getByName("100.115.30.63"), 8091);
      String databaseTarget = "localhost:8081";
      String inferenceTarget = "localhost:8090";

      ManagedChannel databaseChannel = Grpc.newChannelBuilder(databaseTarget, InsecureChannelCredentials.create())
            .build();
      ManagedChannel inferenceChannel = Grpc.newChannelBuilder(inferenceTarget, InsecureChannelCredentials.create())
            .build();

      inferenceStub = InferenceServiceGrpc.newBlockingStub(inferenceChannel);
      verifyStub = AuthenticationServiceGrpc.newBlockingStub(databaseChannel);
      statStub = StatServiceGrpc.newBlockingStub(databaseChannel);

      server.post("/chat", (req, res) -> {
         Request clientRequest = mapper.readValue(jsonRequest(req.getRequest()), Request.class);

         // session:prefix
         String token = req.get("cookie").substring(8);
         String hashedToken = sha256(token);
         Session session = sessions.get(clientRequest.id());
         if (session.hashedToken().equals(hashedToken)) {
            res.startStream();

            Done done = processModel(clientRequest, res);
            Tokens tokens = Tokens.newBuilder().setCompletionTokens(done.getCompletionTokens())
                  .setPromptTokens(done.getPromptTokens()).build();
            Status status = statStub.updateTokens(Statistic.newBuilder().setTokens(tokens)
                  .setId(inference.UUID.newBuilder().setUuid(clientRequest.id().toString()).build()).build());
            res.sendResponseHeader(status.getStatus());
         } else {
            res.sendResponseHeader(404);
         }
         res.sendResponse(Optional.empty());
      });

      server.post("/usage", (req, res) -> {
         JsonNode node = mapper.readTree(req.getRequest());
         String uuid = node.get("id").asText();

         StatsResult result = statStub.getStats(inference.UUID.newBuilder().setUuid(uuid).build());

         switch (result.getPayloadCase()) {
            case STATUS -> {
               int code = result.getStatus();
               res.sendResponseHeader(code);
               res.sendResponse(Optional.empty());
            }

            case SUCCESS -> {
               StatsSuccess stats = result.getSuccess();
               int promptTokens = stats.getPromptTokens();
               int completionTokens = stats.getCompletionTokens();
               int requests = stats.getRequests();
               int compaction = stats.getCompaction();
               int contextLength = stats.getContextLength();

               String json = mapper
                     .writeValueAsString(Map.of("prompt_tokens", promptTokens,
                           "completion_tokens", completionTokens,
                           "requests", requests,
                           "compaction", compaction,
                           "context_length", contextLength));

               res.sendResponseHeader(200);
               res.sendResponse(Optional.of(json.getBytes()));
            }

            default -> {
               System.err.println("Payload not set");
            }
         }
      });

      server.post("/tokens", (req, res) -> {
         Convo convo = mapper.readValue(jsonRequest(req.getRequest()), Convo.class);
         String token = req.get("cookie").substring(8);

         String hashedToken = sha256(token);
         Session session = sessions.get(convo.id());
         if (session.hashedToken().equals(hashedToken)) {
            Messages messages = httpMessageToProto(convo.messages());
            TokenCountResponse countResponse = inferenceStub.tokenCount(messages);
            res.sendResponseHeader(200);

            Map<String, Object> body = Map.of(
                  "normal", countResponse.getNormalCount(),
                  "summary", countResponse.getSummaryCount());

            res.sendResponse(Optional.of(mapper.writeValueAsBytes(body)));
         } else {
            res.sendResponseHeader(404);
            res.sendResponse(Optional.empty());
         }
      });

      server.post("/login", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         Credentials credentials = Credentials.newBuilder().setUsername(clientAuth.username())
               .setPassword(clientAuth.password()).build();
         LoginResult result = verifyStub.login(credentials);
         handleResult(result, res);
      });

      server.post("/signup", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         Credentials credentials = Credentials.newBuilder().setUsername(clientAuth.username())
               .setPassword(clientAuth.password()).build();

         LoginResult result = verifyStub.sign(credentials);
         handleResult(result, res);
      });

      server.start();

      databaseChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      inferenceChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
   }
}
