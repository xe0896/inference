/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package inference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class InferenceServer {
   private static final Logger logger = Logger.getLogger(InferenceServer.class.getName());

   private static final String URL = "http://127.0.0.1:9931";

   private static final String DATABASE_URL = "http://100.115.30.63:8081";
   // User receiving the stream of reasoning back, almost always required
   // so we can utilise the gRPC idea of server sending us a stream back
   private static final boolean STREAM = true;
   private static final ObjectMapper mapper = new ObjectMapper();

   private Server server;

   private void start() throws IOException {
      /* The port on which the server should run */
      int port = 8090;
      /*
       * By default gRPC uses a global, shared Executor.newCachedThreadPool() for gRPC
       * callbacks into
       * your application. This is convenient, but can cause an excessive number of
       * threads to be
       * created if there are many RPCs. It is often better to limit the number of
       * threads your
       * application uses for processing and let RPCs queue when the CPU is saturated.
       * The appropriate number of threads varies heavily between applications.
       * Async application code generally does not need more threads than CPU cores.
       */
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .executor(executor)
            .addService(new InferenceImpl())
            .addService(new AuthenticationImpl())
            .build()
            .start();
      logger.info("Server started, listening on " + port);
      Runtime.getRuntime().addShutdownHook(new Thread() {
         @Override
         public void run() {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
               InferenceServer.this.stop();
            } catch (InterruptedException e) {
               if (server != null) {
                  server.shutdownNow();
               }
               e.printStackTrace(System.err);
            } finally {
               executor.shutdown();
            }
            System.err.println("*** server shut down");
         }
      });
   }

   private void stop() throws InterruptedException {
      if (server != null) {
         server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
      }
   }

   /**
    * Await termination on the main thread since the grpc library uses daemon
    * threads.
    */
   private void blockUntilShutdown() throws InterruptedException {
      if (server != null) {
         server.awaitTermination();
      }
   }

   /**
    * Main launches the server from the command line.
    */
   public static void main(String[] args) throws IOException, InterruptedException {
      final InferenceServer server = new InferenceServer();
      server.start();
      server.blockUntilShutdown();
   }

   public static HttpRequest post(String url, Object obj, String type, String endpoint, String secret)
         throws JsonProcessingException {
      String req = mapper.writeValueAsString(obj);
      System.out.println(prettyPrint(obj));
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url + endpoint))
            .header("Authorization", "Bearer " + secret)
            .header("Content-Type", type)
            .POST(BodyPublishers.ofString(req)).build();
      return request;
   }

   public static String prettyPrint(Object obj) throws JsonProcessingException {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
   }

   public static String prettyPrintString(String str) throws JsonProcessingException {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(str));
   }

   static class AuthenticationImpl extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {
      record Version(String version) {
      }

      record CredentialsRecord(String username, String password) {
      }

      @Override
      public void sign(Credentials credentials, StreamObserver<Result> responseObserver) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            CredentialsRecord cred = new CredentialsRecord(credentials.getUsername(), credentials.getPassword());

            HttpRequest request = post(DATABASE_URL, cred, "application/json", "/signup", "secret");
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int code = response.statusCode();
            String id = response.body();
            Result result;
            if (code == 200) {
               result = Result.newBuilder().setUuid(id).build();
            } else {
               result = Result.newBuilder().setStatus(code).build();
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
         } catch (IOException | InterruptedException e) {
            e.printStackTrace();
         }
      }

      @Override
      public void login(Credentials credentials, StreamObserver<Result> responseObserver) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            CredentialsRecord cred = new CredentialsRecord(credentials.getUsername(), credentials.getPassword());

            HttpRequest request = post(DATABASE_URL, cred, "application/json", "/login", "secret");
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            int code = response.statusCode();
            String id = response.body();
            Result result;
            if (code == 200) {
               result = Result.newBuilder().setUuid(id).build();
            } else {
               result = Result.newBuilder().setStatus(code).build();
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
         } catch (IOException | InterruptedException e) {
            e.printStackTrace();
         }
      }
   }

   static class InferenceImpl extends InferenceServiceGrpc.InferenceServiceImplBase {
      record Message(String role, String content) {
      }

      record ChatTemplateKwargs(@JsonProperty("enable_thinking") boolean enableThinking) {
      }

      // JsonProperty allowing it to be defined as 'max_tokens' as that is what the
      // API would expect
      record ChatRequest(List<Message> messages, boolean stream, @JsonProperty("max_tokens") int maxTokens,
            double temperature, @JsonProperty("chat_template_kwargs") ChatTemplateKwargs chatTemplateKwargs) {
      }

      @Override
      public void generate(Prompt prompt, StreamObserver<Response> responseObserver) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            // Given the prompt from the client which includes some attributes as well
            String userPrompt = prompt.getPrompt();
            int maxTokens = prompt.getMaxTokens();
            double temperature = prompt.getTemperature();
            boolean thinking = prompt.getThinking();

            // Request in record form which would turn into JSON
            var chatRequest = new ChatRequest(List.of(new Message("user", userPrompt)), STREAM, maxTokens,
                  temperature, new ChatTemplateKwargs(thinking));

            // An event stream as we want SSE between our server and the llama-server
            HttpRequest request = post(URL, chatRequest, "text/event-stream", "/v1/chat/completions", "secret");

            // BodyHandlers.ofLines() is going to return a stream of lines which
            // in the llama-server API would be a singular token followed by a blank
            HttpResponse<Stream<String>> httpResponse = client.send(request, BodyHandlers.ofLines());

            if (httpResponse.statusCode() != 200)
               throw new HttpConnectTimeoutException("Status code is: " + httpResponse.statusCode());

            try (Stream<String> lines = httpResponse.body()) {
               Iterator<String> it = lines.iterator();
               while (it.hasNext()) {
                  // This threads context points to a piece of context that
                  // Netty also points to it (indirectly), if the user leaves then
                  // Netty would write to cancel which we read and see its cancelled
                  // and can leave and stop
                  if (Context.current().isCancelled())
                     break;

                  // Skip the blank lines that llama-server would give after every token line
                  String line = it.next();
                  if (line.isBlank())
                     continue;

                  //
                  if (line.equals("data: [DONE]"))
                     break;

                  line = line.substring(6); // Removes 'data: ' prefix

                  // A response from llama could either be a piece of content of
                  // the final response, a reasoning content that would reason the
                  // steps of the final answer or the final output which is the
                  // timings/summary of the prompt
                  JsonNode json = mapper.readTree(line);
                  JsonNode choices = json.get("choices").get(0);
                  JsonNode delta = choices.get("delta");
                  if (delta.hasNonNull("content")) {
                     String text = delta.get("content").asText();
                     Response response = processResponse(text, Kind.ANSWER);
                     responseObserver.onNext(response);
                  } else if (delta.hasNonNull("reasoning_content")) {
                     String text = delta.get("reasoning_content").asText();
                     Response response = processResponse(text, Kind.REASONING);
                     responseObserver.onNext(response);
                  } else if (json.hasNonNull("timings")) {
                     JsonNode timings = json.get("timings");
                     String reason = choices.get("finish_reason").asText();
                     int promptTokens = timings.get("cache_n").asInt() + timings.get("prompt_n").asInt();
                     int completionTokens = timings.get("predicted_n").asInt();
                     double prefillTps = timings.get("prompt_per_second").asDouble();
                     double decodeTps = timings.get("predicted_per_second").asDouble();

                     System.out.println("Summary has been sent");

                     Done done = Done.newBuilder().setFinishReason(reason)
                           .setPromptTokens(promptTokens)
                           .setCompletionTokens(completionTokens)
                           .setPrefillTps(prefillTps)
                           .setDecodeTps(decodeTps)
                           .build();

                     Response response = Response.newBuilder().setDone(done).build();
                     responseObserver.onNext(response);
                     responseObserver.onCompleted();
                  }
               }
            }

            if (Context.current().isCancelled()) {
               logger.info("Client cancelled: '" + userPrompt + "'");
            } else {
               logger.info("\nGeneration for prompt: '" + userPrompt + "' has been completed");
            }

         } catch (InterruptedException | IOException e) {
            e.printStackTrace();
         }
      }

      public static Response processResponse(String text, Kind kind) {
         Chunk chunk = Chunk.newBuilder().setKind(kind).setText(text).build();
         Response response = Response.newBuilder().setChunk(chunk).build();
         return response;
      }
   }
}
