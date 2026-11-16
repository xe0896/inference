package inference;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

public class InferenceDatabase {
   private static final Logger logger = Logger.getLogger(InferenceDatabase.class.getName());
   private static final String DB_PASSWORD = "inference_platform";
   private static final String url = "jdbc:postgresql://inference.ct8ig6ycgivp.eu-north-1.rds.amazonaws.com:5432/postgres?sslmode=verify-full&sslrootcert=/home/ubuntu/.ssh/global-bundle.pem";

   private static final int NOT_FOUND = 404;
   private static final int UNAUTHORISED = 401;
   private static final int CONFLICT = 409;

   private Server server;

   record Auth(String username, String password) {
   }

   public static String jsonRequest(byte[] jsonBytes) {
      return new String(jsonBytes, StandardCharsets.UTF_8).trim();
   }

   public static void initialise(Connection conn) throws Exception {
      try (InputStream in = InferenceDatabase.class
            .getResourceAsStream("/schema.sql")) {

         if (in == null) {
            throw new FileNotFoundException("schema.sql not found");
         }

         String sql = new String(
               in.readAllBytes(),
               StandardCharsets.UTF_8);

         try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
         }
      }
   }

   public static byte[] generateSalt() {
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[16];
      random.nextBytes(salt);
      return salt;
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

   static class AuthenticationImpl extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {
      static String existenceQuery = "SELECT COUNT(*) FROM users WHERE username = ?";
      static String insertUserQuery = "INSERT INTO users (id, username, salt, hash) VALUES (?, ?, ?, ?)";
      static String getUserTuple = "SELECT * FROM users WHERE username = ?";

      @Override
      public void sign(Credentials credentials, StreamObserver<Result> responseObserver) {
         String username = credentials.getUsername();
         String password = credentials.getPassword();
         try (Connection conn = DriverManager.getConnection(url, "postgres", DB_PASSWORD)) {
            PreparedStatement existencePs = conn.prepareStatement(existenceQuery);
            existencePs.setString(1, username);

            try (ResultSet rs = existencePs.executeQuery()) {
               rs.next(); // Sits before the row so make it go ahead by one

               if (rs.getInt(1) > 0) {
                  // Already exists so send 409 conflict
                  Result result = Result.newBuilder().setStatus(CONFLICT).build();
                  responseObserver.onNext(result);
               } else {
                  // New user
                  PreparedStatement insertUserPs = conn.prepareStatement(insertUserQuery);
                  String salt = Base64.getEncoder().encodeToString(generateSalt());
                  UUID id = UUID.randomUUID();
                  insertUserPs.setObject(1, id);
                  insertUserPs.setString(2, username);
                  insertUserPs.setString(3, salt);
                  insertUserPs.setString(4, sha256(password.concat(salt)));
                  insertUserPs.executeUpdate();
                  LoginSuccess success = LoginSuccess.newBuilder().setUuid(id.toString()).build();
                  Result result = Result.newBuilder().setSuccess(success).build();
                  responseObserver.onNext(result);
               }
               responseObserver.onCompleted();
            }
         } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            throw new RuntimeException(e);
         }
      }

      @Override
      public void login(Credentials credentials, StreamObserver<Result> responseObserver) {
         String username = credentials.getUsername();
         String password = credentials.getPassword();

         try (Connection conn = DriverManager.getConnection(url, "postgres", DB_PASSWORD)) {
            PreparedStatement getUserTuplePs = conn.prepareStatement(getUserTuple);
            getUserTuplePs.setString(1, username);
            ResultSet rs = getUserTuplePs.executeQuery();
            if (rs.next()) {
               UUID id = rs.getObject("id", UUID.class);
               String salt = rs.getString("salt");
               String hash = rs.getString("hash");

               String currentHash = sha256(password.concat(salt));

               if (hash.equals(currentHash)) {
                  LoginSuccess success = LoginSuccess.newBuilder().setUuid(id.toString()).build();
                  Result result = Result.newBuilder().setSuccess(success).build();
                  responseObserver.onNext(result);
                  responseObserver.onCompleted();
               } else {
                  Result result = Result.newBuilder().setStatus(UNAUTHORISED).build();
                  responseObserver.onNext(result);
                  responseObserver.onCompleted();
               }
            } else {
               Result result = Result.newBuilder().setStatus(NOT_FOUND).build();
               responseObserver.onNext(result);
               responseObserver.onCompleted();
            }
         } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
         }

      }
   }

   public void start() throws IOException, Exception, UnknownHostException {
      int port = 8081;
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .executor(executor)
            .addService(new AuthenticationImpl())
            .build()
            .start();

      try (Connection initConn = DriverManager.getConnection(url, "postgres", DB_PASSWORD)) {
         initialise(initConn);
      }
      logger.info("Server started, listening on " + port);
      Runtime.getRuntime().addShutdownHook(new Thread() {
         @Override
         public void run() {
            // Use stderr here since the logger may have been reset by its JVM shutdown
            // hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
               InferenceDatabase.this.stop();
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
   public static void main(String[] args) throws Exception, InterruptedException {
      final InferenceDatabase server = new InferenceDatabase();
      server.start();
      server.blockUntilShutdown();
   }
}
