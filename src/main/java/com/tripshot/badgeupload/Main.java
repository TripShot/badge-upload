package com.tripshot.badgeupload;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Key;
import com.google.api.client.util.Lists;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;


public class Main {

  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();

  // Tolerate long respones from server for extremely large badge files.
  private static final int READ_TIMEOUT_MS = 2 * 60 * 1000;

  public static class AccessTokenRequest {
    @SuppressWarnings("unused")
    @Key
    private final String appId;

    @SuppressWarnings("unused")
    @Key
    private final String secret;

    public AccessTokenRequest(String appId, String secret) {
      this.appId = appId;
      this.secret = secret;
    }
  }

  public static class AccessTokenResponse {
    @SuppressWarnings("unused")
    @Key
    private String accessToken;
  }

  private static class Row {
    private final String badge;
    private final String riderId;
    private final boolean delete;

    public Row(String badge, String riderId, boolean delete) {
      this.badge = badge;
      this.riderId = riderId;
      this.delete = delete;
    }
  }

  public static class BadgeData {
    @SuppressWarnings("unused")
    @Key
    private final List<String> hashedCardIds;

    public BadgeData(List<String> hashedCardIds) {
      this.hashedCardIds = hashedCardIds;
    }
  }

  private static void usage() {
    throw new IllegalArgumentException(
      "usage : --config <config file> { --badgesCsv <badge file> [ --dumpFile <dumpfile> ] [ --namespace <namespace> ] [ --incremental ] | --badges <badge file> } ");
  }

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

    String configFilename = null;
    String inputFileNameV1 = null;
    String inputFileNameV2 = null;
    String namespace = null;
    String dumpFile = null;
    boolean incremental = false;

    Function<Integer, String> safeArg = k -> {
      if ( k < args.length ) {
        return args[k];
      } else {
        usage();
        // unreachable
        return null;
      }
    };

    int k = 0;
    while ( k < args.length ) {
      String opt = args[k];
      switch ( opt ) {
        case "--config":
          configFilename = safeArg.apply(++k);
          break;
        case "--badgesCsv":
          inputFileNameV2 = safeArg.apply(++k);
          break;
        case "--badges":
          inputFileNameV1 = safeArg.apply(++k);
          break;
        case "--namespace":
          namespace = safeArg.apply(++k);
          break;
        case "--dumpFile":
          dumpFile = safeArg.apply(++k);
          break;
        case "--incremental":
          incremental = true;
          break;
        default:
          usage();
      }

      ++k;
    }

    if ( configFilename == null ) {
      usage();
    }

    // exactly one of V1 or V2 filename must be specified
    if ( (inputFileNameV1 == null) == (inputFileNameV2 == null) ) {
      usage();
    }

    // dumpFile, namespace, incremental only available for V2 uploads
    if ( inputFileNameV2 == null && (dumpFile != null || namespace != null || incremental) ) {
      usage();
    }

    Properties prop = new Properties();
    try ( FileInputStream fis = new FileInputStream(configFilename) ) {
      prop.load(fis);
    }

    String baseUrl = prop.getProperty("baseUrl");
    String appId = prop.getProperty("appId");
    String secret = prop.getProperty("secret");
    String badgingKey = prop.getProperty("badgingKey");

    if ( baseUrl == null || appId == null || secret == null || badgingKey == null )
      throw new RuntimeException("config file is missing a required property : baseUrl, appId, secret, or badgingKey");

    HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(request -> request.setParser(new JsonObjectParser(JSON_FACTORY)));

    if ( inputFileNameV2 != null ) {
      List<Row> hashedRows = hashRows(badgingKey, readV2Input(inputFileNameV2, incremental));
      String outputCsv = generateV2Output(hashedRows, incremental);

      if ( dumpFile != null ) {
        try ( OutputStream os = new FileOutputStream(dumpFile) ) {
          os.write(outputCsv.getBytes(StandardCharsets.UTF_8));
        }
      } else {
        String accessToken = getAccessToken(requestFactory, baseUrl, appId, secret);
        sendV2Badges(requestFactory, baseUrl, namespace, incremental, accessToken, outputCsv);
      }
    } else {
      List<String> hashedBadges = hashBadges(badgingKey, inputFileNameV1);
      String accessToken = getAccessToken(requestFactory, baseUrl, appId, secret);
      sendV1Badges(requestFactory, baseUrl, accessToken, hashedBadges);
    }
  }

  private static List<Row> readV2Input(String inputFilename, boolean incrementally) throws IOException {
    File csvFile = new File(inputFilename);

    List<Row> rows = Lists.newArrayList();

    try ( FileInputStream rawFileInput = new FileInputStream(csvFile);
          BOMInputStream bomInput = new BOMInputStream(rawFileInput);
          CSVParser parser = CSVParser.parse(bomInput, StandardCharsets.UTF_8, CSVFormat.RFC4180.withHeader())
    ) {
      for ( CSVRecord record : parser ) {
        String badge = record.get("badge");
        String riderId = record.get("riderId");

        boolean delete;

        if ( incrementally ) {
          String deleteStr = record.get("delete");
          switch ( deleteStr ) {
            case "":
            case "F":
              delete = false;
              break;
            case "T":
              delete = true;
              break;
            default:
              throw new RuntimeException("Unsupported value for delete : " + deleteStr);
          }
        } else {
          delete = false;
        }

        rows.add(new Row(badge, riderId, delete));
      }
    }

    return rows;
  }

  private static String generateV2Output(List<Row> rows, boolean incrementally) throws IOException {
    StringBuilder out = new StringBuilder();

    CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180);

    List<String> headers = new ArrayList<>(List.of("badge", "riderId"));
    if ( incrementally ) {
      headers.add("delete");
    }
    printer.printRecord(headers);

    for ( Row row : rows ) {
      List<String> entry = new ArrayList<>(List.of(row.badge, row.riderId));
      if ( incrementally ) {
        entry.add(row.delete ? "T" : "F");
      }
      printer.printRecord(entry);
    }

    printer.close();

    return out.toString();
  }

  private final static String algo = "HmacSHA256";

  private static List<Row> hashRows(String badgingKey, List<Row> rawRows) throws NoSuchAlgorithmException {
    SecretKeySpec signingKey = new SecretKeySpec(badgingKey.getBytes(StandardCharsets.UTF_8), algo);
    Mac mac = Mac.getInstance(algo);

    return rawRows.stream().map(rawRow -> {
      try {
        mac.init(signingKey);
        byte[] raw = mac.doFinal(rawRow.badge.getBytes(StandardCharsets.UTF_8));
        byte[] hexBytes = new Hex().encode(raw);
        String hashed = new String(hexBytes, StandardCharsets.UTF_8);
        return new Row(hashed, rawRow.riderId, rawRow.delete);
      } catch ( Exception e ) {
        throw new RuntimeException(e);
      }
    }).collect(Collectors.toList());
  }

  private static List<String> hashBadges(String badgingKey, String badgeFilename) throws NoSuchAlgorithmException, IOException {
    SecretKeySpec signingKey = new SecretKeySpec(badgingKey.getBytes(StandardCharsets.UTF_8), algo);
    Mac mac = Mac.getInstance(algo);

    List<String> hashedBadges;
    try ( FileInputStream bis = new FileInputStream(badgeFilename) ) {
      LineNumberReader reader = new LineNumberReader(new InputStreamReader(bis));

      hashedBadges = reader.lines().map(line -> {
        try {
          mac.init(signingKey);
          byte[] raw = mac.doFinal(line.getBytes(StandardCharsets.UTF_8));
          byte[] hexBytes = new Hex().encode(raw);
          return new String(hexBytes, StandardCharsets.UTF_8);
        } catch ( Exception e ) {
          throw new RuntimeException(e);
        }
      }).collect(Collectors.toList());
    }

    return hashedBadges;
  }

  private static String getAccessToken(HttpRequestFactory requestFactory, String baseUrl, String appId, String secret) throws IOException {

    HttpRequest accessTokenRequest =
      requestFactory.buildPostRequest(new GenericUrl(baseUrl + "/v1/accessToken"), new JsonHttpContent(JSON_FACTORY, new AccessTokenRequest(appId, secret)));
    AccessTokenResponse accessTokenResponse = accessTokenRequest.execute().parseAs(AccessTokenResponse.class);

    return accessTokenResponse.accessToken;
  }

  private static void sendV2Badges(HttpRequestFactory requestFactory, String baseUrl, String namespace, boolean incremental, String accessToken,
                                   String outputCsv)
    throws IOException {
    GenericUrl url = new GenericUrl(baseUrl + "/v2/badgeData");
    if ( namespace != null ) {
      url.set("namespace", namespace);
    }
    if ( incremental ) {
      url.set("incremental", "true");
    }

    HttpRequest uploadRequest =
      requestFactory.buildPutRequest(url, ByteArrayContent.fromString("text/csv; charset=utf-8", outputCsv));
    uploadRequest.setHeaders(new HttpHeaders().setAuthorization("Bearer " + accessToken));
    uploadRequest.setReadTimeout(READ_TIMEOUT_MS);
    uploadRequest.execute();
  }

  private static void sendV1Badges(HttpRequestFactory requestFactory, String baseUrl, String accessToken, List<String> hashedBadges) throws IOException {
    HttpRequest uploadRequest =
      requestFactory.buildPutRequest(new GenericUrl(baseUrl + "/v1/badgeData"), new JsonHttpContent(JSON_FACTORY, new BadgeData(hashedBadges)));
    uploadRequest.setHeaders(new HttpHeaders().setAuthorization("Bearer " + accessToken));
    uploadRequest.execute();
  }

}
