package inference;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
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

public class Client {
   private static final Logger logger = Logger.getLogger(Client.class.getName());
   private static final Server server = Server.create(InetAddress.getLoopbackAddress(), 8091);
   private static final ObjectMapper mapper = new ObjectMapper();
   private static InferenceServiceGrpc.InferenceServiceBlockingStub blockingStub;
   private static AuthenticationServiceGrpc.AuthenticationServiceBlockingStub authStub;

   private static final String password = "inference_platform";
   private static final String url = "jdbc:postgresql://inference.ct8ig6ycgivp.eu-north-1.rds.amazonaws.com:5432/postgres?sslmode=verify-full&sslrootcert=./global-bundle.pem";

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

   record Request(String prompt, @JsonProperty("max_tokens") int maxTokens, double temperature, boolean thinking) {
   }

   record Auth(String username, String password) {
   }

   public static String jsonRequest(byte[] jsonBytes) {
      return new String(jsonBytes, StandardCharsets.UTF_8).trim();
   }

   public byte[] generateSalt() {
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[16];
      random.nextBytes(salt);
      return salt;
   }

   public static void main(String[] args) throws InterruptedException {
      String target = "localhost:8090";

      ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
      blockingStub = InferenceServiceGrpc.newBlockingStub(channel);
      authStub = AuthenticationServiceGrpc.newBlockingStub(channel);

      server.post("/chat", (req, res) -> {
         Request clientRequest = mapper.readValue(jsonRequest(req.getRequest()), Request.class);

         logger.info("Processing prompt: " + clientRequest.prompt());
         res.startStream();
         process(clientRequest, res);
      });

      server.post("/login", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         logger.info(String.format("Received username: '%s' and password: '%s", clientAuth.username(),
               clientAuth.password()));
      });

      server.post("/signup", (req, res) -> {
         Auth clientAuth = mapper.readValue(jsonRequest(req.getRequest()), Auth.class);
         logger.info(String.format("Received username: '%s' and password: '%s", clientAuth.username(),
               clientAuth.password()));
         Credentials credentials = Credentials.newBuilder().setUsername(clientAuth.username())
               .setPassword(clientAuth.password()).build();

         CredentialsResult result = authStub.sign(credentials).getResult();
      });

      server.start();

      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
   }
}
