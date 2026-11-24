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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
   static class InferenceImpl extends InferenceServiceGrpc.InferenceServiceImplBase {
      private static final String MODEL_ID = "ggml-org/gemma-4-E2B-it-GGUF:Q80";

      record Message(String role, String content) {
      }

      record ChatTemplateKwargs(@JsonProperty("enable_thinking") boolean enableThinking) {
      }

      record TemplateRequest(List<Message> messages) {
      }

      // JsonProperty allowing it to be defined as 'max_tokens' as that is what the
      // API would expect
      record ChatRequest(String model, List<Message> messages, boolean stream,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature, @JsonProperty("chat_template_kwargs") ChatTemplateKwargs chatTemplateKwargs) {
      }

      public static Response processResponse(String text, Kind kind) {
         Chunk chunk = Chunk.newBuilder().setKind(kind).setText(text).build();
         Response response = Response.newBuilder().setChunk(chunk).build();
         return response;
      }

      private static Response llamaResponse(HttpResponse<Stream<String>> httpResponse, Consumer<Response> stream)
            throws JsonProcessingException, HttpConnectTimeoutException {

         if (httpResponse.statusCode() != 200) {
            throw new HttpConnectTimeoutException("Status code is: " + httpResponse.statusCode());
         }

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
                  stream.accept(response);
               } else if (delta.hasNonNull("reasoning_content")) {
                  String text = delta.get("reasoning_content").asText();
                  Response response = processResponse(text, Kind.REASONING);
                  stream.accept(response);
               } else if (json.hasNonNull("timings")) {
                  JsonNode timings = json.get("timings");
                  String reason = choices.get("finish_reason").asText();
                  int promptTokens = timings.get("cache_n").asInt() + timings.get("prompt_n").asInt();
                  int completionTokens = timings.get("predicted_n").asInt();
                  double prefillTps = timings.get("prompt_per_second").asDouble();
                  double decodeTps = timings.get("predicted_per_second").asDouble();
                  Done done = Done.newBuilder().setFinishReason(reason)
                        .setPromptTokens(promptTokens)
                        .setCompletionTokens(completionTokens)
                        .setPrefillTps(prefillTps)
                        .setDecodeTps(decodeTps)
                        .build();

                  Response response = Response.newBuilder().setDone(done).build();
                  return response;
               }
            }
         }

         return Response.newBuilder().build();
      }

      public ChatRequest summaryCheck(Conversation conv, StreamObserver<Response> responseObserver) {
         float temperature = conv.getTemperature();
         boolean thinking = conv.getThinking();
         int maxTokens = conv.getMaxTokens();
         inference.Messages messages = conv.getMessages();

         int currentToken = tokensConversation(messages);
         System.out.println("Current token count: " + currentToken);

         if (currentToken > CONTEXT_LIMIT * 0.1) {
            responseObserver
                  .onNext(Response.newBuilder().setChunk(Chunk.newBuilder().setKind(Kind.COMPACT).setText("")).build());
            List<inference.Message> m = messages.getMessageList();
            String summary = codexSummary(m.subList(0, m.size() - 1));
            responseObserver
                  .onNext(Response.newBuilder()
                        .setChunk(Chunk.newBuilder().setKind(Kind.FINISHED_COMPACT).setText(summary))
                        .build());

            return new ChatRequest(MODEL_ID,
                  List.of(new Message("assistant", summary), new Message("user", m.getLast().getContent())), true,
                  maxTokens, temperature,
                  new ChatTemplateKwargs(thinking));
         }

         return new ChatRequest(MODEL_ID, protoToHttpMessage(messages), true, maxTokens, temperature,
               new ChatTemplateKwargs(thinking));
      }

      @Override
      public void generate(Conversation conv, StreamObserver<Response> responseObserver) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            // Request in record form which would turn into JSON
            ChatRequest chatRequest = summaryCheck(conv, responseObserver);

            // An event stream as we want SSE between our server and the llama-server
            HttpRequest request = post(URL, chatRequest, "text/event-stream", "/v1/chat/completions", "secret");

            // BodyHandlers.ofLines() is going to return a stream of lines which
            // in the llama-server API would be a singular token followed by a blank
            HttpResponse<Stream<String>> httpResponse = client.send(request, BodyHandlers.ofLines());

            // Calls the llama server to get a stream of tokens to construct
            // reasoning/answer
            // returns the statistics that the llama server produces at the end
            Response response = llamaResponse(httpResponse, (in) -> {
               responseObserver.onNext(in);
            });

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            // Previewing to the console
            String recentMessage = chatRequest.messages().getLast().content();
            String preview = recentMessage.length() > 20
                  ? recentMessage.substring(0, 20) + "..."
                  : recentMessage;

            if (Context.current().isCancelled()) {
               logger.info("Client cancelled: '" + preview + "'");
            } else {
               logger.info("Generation for prompt: '" + preview + "' has been completed");
            }

         } catch (InterruptedException | IOException e) {
            e.printStackTrace();
         }
      }

      private static String formatMessages(List<inference.Message> messages) {
         StringBuilder sb = new StringBuilder();
         for (inference.Message message : messages) {
            if (message.getRole().equals("user")) {
               sb.append(String.format("U: %s\n", message.getContent()));
            } else if (message.getRole().equals("assistant")) {
               sb.append(String.format("A: %s\n", message.getContent()));
            }
         }

         return sb.toString();
      }

      public static List<Message> protoToHttpMessage(Messages messages) {
         return (messages.getMessageList().stream()
               .map(m -> new Message(m.getRole(), m.getContent()))
               .toList());
      }

      private static String codexSummary(List<inference.Message> messages) {
         String formattedConversation = formatMessages(messages);
         try {
            Path emptyDir = Files.createTempDirectory("codex-");

            Process process = new ProcessBuilder(
                  "/home/ubuntu/.local/bin/codex", "exec",
                  "--ephemeral",
                  "--skip-git-repo-check",
                  "--ignore-user-config",
                  "-s", "read-only",
                  "-C", emptyDir.toString(),
                  "-c", "model_reasoning_effort=\"low\"",
                  "-").redirectError(ProcessBuilder.Redirect.INHERIT)
                  .start();

            try (var stdin = process.getOutputStream()) {
               stdin.write(SUMMARY_QUERY.formatted(formattedConversation).getBytes(StandardCharsets.UTF_8));
            }

            String output = new String(
                  process.getInputStream().readAllBytes(),
                  StandardCharsets.UTF_8);

            process.waitFor();

            return output;
         } catch (IOException | InterruptedException e) {
            e.printStackTrace();
         }

         return "";
      }

      private static String llamaSummary(List<inference.Message> messages) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            String formattedConversation = formatMessages(messages);

            ChatRequest chatRequest = new ChatRequest(MODEL_ID,
                  List.of(new Message("user", SUMMARY_QUERY.formatted(formattedConversation))),
                  STREAM, SUMMARY_TOKENS,
                  0.0, new ChatTemplateKwargs(false));

            HttpRequest httpRequest = post(URL, chatRequest, "text/event-stream", "/v1/chat/completions", "secret");

            HttpResponse<Stream<String>> httpResponse = client.send(httpRequest, BodyHandlers.ofLines());
            StringBuilder sb = new StringBuilder();

            llamaResponse(httpResponse, (in) -> {
               sb.append(in.getChunk().getText());
            });
            return sb.toString();
         } catch (InterruptedException | IOException e) {
            e.printStackTrace();
         }

         return "";
      }

      public int tokensConversation(List<Message> messages) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            TemplateRequest content = new TemplateRequest(messages);

            HttpRequest templateRequest = post(URL, content, "application/json", "/apply-template", "secret");

            HttpResponse<String> templateResponse = client.send(templateRequest, BodyHandlers.ofString());

            JsonNode node = mapper.readTree(templateResponse.body());
            String template = node.get("prompt").asText();

            return amountOfTokens(template);

         } catch (InterruptedException | IOException e) {
            e.printStackTrace();
         }
         return 0;
      }

      public int tokensConversation(Messages messages) {
         return tokensConversation(protoToHttpMessage(messages));
      }

      public TokenOutput tokens(String contentString) {
         try (HttpClient client = HttpClient.newHttpClient()) {
            Content content = new Content(contentString);
            HttpRequest httpRequest = post(URL, content, "application/json", "/tokenize", "secret");
            HttpResponse<String> httpResponse = client.send(httpRequest, BodyHandlers.ofString());
            TokenOutput tokens = mapper.readValue(httpResponse.body(), TokenOutput.class);
            return tokens;
         } catch (InterruptedException | IOException e) {
            e.printStackTrace();
         }
         return null;
      }

      public int amountOfTokens(String contentString) {
         return tokens(contentString).tokenIds().size();
      }

      @Override
      public void tokenCount(Messages request, StreamObserver<TokenCountResponse> responseObserver) {
         int normalCount = tokensConversation(request);
         String summary = codexSummary(request.getMessageList());
         System.out.println("SUMMARY ".concat(summary.toUpperCase()));
         int summaryCount = amountOfTokens(summary);
         responseObserver.onNext(
               TokenCountResponse.newBuilder().setNormalCount(normalCount).setSummaryCount(summaryCount).build());
         responseObserver.onCompleted();
      }

      record Content(String content) {
      }

      record TokenOutput(@JsonProperty("tokens") List<Integer> tokenIds) {
      }

      private static final String SUMMARY_QUERY = """
            Compress the following conversation into a detailed memory that will replace the original transcript.

            Preserve information rather than prioritising brevity.

            For each user message and corresponding assistant response:
            - Preserve the user's complete intent, questions and clarifications.
            - Preserve all distinct facts, explanations and technical details.
            - Keep exact names, numbers, commands, paths, errors and configuration values.
            - Keep relevant code and examples.
            - Preserve decisions, corrections, rejected approaches and their reasons.
            - Preserve context needed to understand follow-up questions.
            - Remove filler, greetings and redundant wording only.

            Process messages chronologically. Do not skip or merge messages.
            Give earlier and later messages equal attention.
            Use as many sentences as necessary to preserve information.
            Do not invent information.

            Format:
            U1: ...
            A1: ...
            U2: ...
            A2: ...

            Continue until the end of the transcript.

            Output only the compressed conversation in plain text.

            <transcript>
            %s
            </transcript>
            """;

      private final int SUMMARY_COUNT = tokens(SUMMARY_QUERY).tokenIds().size();

      private static final int SUMMARY_TOKENS = 500;

      private static final int CONTEXT_LIMIT = 4096;
   }

   private static final Logger logger = Logger.getLogger(InferenceServer.class.getName());
   private static final String URL = "http://100.108.55.41:9931";

   // private static final String DATABASE_URL = "http://100.115.30.63:8081";
   // User receiving the stream of reasoning back, almost always required
   // so we can utilise the gRPC idea of server sending us a stream back
   private static final boolean STREAM = true;

   private static final ObjectMapper mapper = new ObjectMapper();

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

   private Server server;

   /**
    * Await termination on the main thread since the grpc library uses daemon
    * threads.
    */
   private void blockUntilShutdown() throws InterruptedException {
      if (server != null) {
         server.awaitTermination();
      }
   }

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
}
