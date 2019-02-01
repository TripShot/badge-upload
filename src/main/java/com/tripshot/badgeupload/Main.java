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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;


public class Main {

  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();


  public static class AccessTokenRequest {
    @SuppressWarnings("unused")
    @Key
    private String appId;

    @SuppressWarnings("unused")
    @Key
    private String secret;

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

  public static class BadgeData {
    @SuppressWarnings("unused")
    @Key
    private List<String> hashedCardIds;

    public BadgeData(List<String> hashedCardIds) {
      this.hashedCardIds = hashedCardIds;
    }
  }

  private static class Row {
    private String badge;
    private String riderId;

    public Row(String badge, String riderId) {
      this.badge = badge;
      this.riderId = riderId;
    }
  }

  private static void usage() {
    throw new IllegalArgumentException("usage : --config <config file> --badgesCsv <badge file> [ --namespace <namespace> ]");
  }

  public static void main(String args[]) throws IOException, InvalidKeyException, NoSuchAlgorithmException {

    String configFilename = null;
    String inputFilename = null;
    String namespace = null;

    int k = 0;
    while ( k + 1 < args.length ) {
      String opt = args[k];
      switch ( opt ) {
        case "--config":
          configFilename = args[k + 1];
          break;
        case "--badgesCsv":
          inputFilename = args[k + 1];
          break;
        case "--namespace":
          namespace = args[k + 1];
          break;
        default:
          usage();
      }

      k += 2;
    }

    if ( configFilename == null || inputFilename == null )
      usage();

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

    List<Row> hashedRows = hashRows(badgingKey, readInput(inputFilename));
    String outputCsv = generateOutput(hashedRows);

    String accessToken = getAccessToken(requestFactory, baseUrl, appId, secret);
    sendBadges(requestFactory, baseUrl, namespace, accessToken, outputCsv);
  }

  private static List<Row> readInput(String inputFilename) throws IOException {
    File csvFile = new File(inputFilename);

    List<Row> rows = Lists.newArrayList();

    try ( CSVParser parser = CSVParser.parse(csvFile, Charset.forName("UTF-8"), CSVFormat.RFC4180.withHeader()) ) {
      for ( CSVRecord record : parser ) {
        String badge = record.get("badge");
        String riderId = record.get("riderId");

        rows.add(new Row(badge, riderId));
      }
    }

    return rows;
  }

  private static String generateOutput(List<Row> rows) throws IOException {
    StringBuilder out = new StringBuilder();

    CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180);

    printer.printRecord("badge", "riderId");
    for ( Row row : rows ) {
      printer.printRecord(row.badge, row.riderId);
    }

    printer.close();

    return out.toString();
  }

  private final static String algo = "HmacSHA256";

  private static List<Row> hashRows(String badgingKey, List<Row> rawRows) throws NoSuchAlgorithmException {
    SecretKeySpec signingKey = new SecretKeySpec(badgingKey.getBytes(Charset.forName("UTF-8")), algo);
    Mac mac = Mac.getInstance(algo);

    return rawRows.stream().map(rawRow -> {
      try {
        mac.init(signingKey);
        byte[] raw = mac.doFinal(rawRow.badge.getBytes(Charset.forName("UTF-8")));
        byte[] hexBytes = new Hex().encode(raw);
        String hashed = new String(hexBytes, "UTF-8");
        return new Row(hashed, rawRow.riderId);
      } catch ( Exception e ) {
        throw new RuntimeException(e);
      }
    }).collect(Collectors.toList());
  }

  private static String getAccessToken(HttpRequestFactory requestFactory, String baseUrl, String appId, String secret) throws IOException {

    HttpRequest accessTokenRequest =
      requestFactory.buildPostRequest(new GenericUrl(baseUrl + "/v1/accessToken"), new JsonHttpContent(JSON_FACTORY, new AccessTokenRequest(appId, secret)));
    AccessTokenResponse accessTokenResponse = accessTokenRequest.execute().parseAs(AccessTokenResponse.class);

    return accessTokenResponse.accessToken;
  }

  private static void sendBadges(HttpRequestFactory requestFactory, String baseUrl, String namespace, String accessToken, String outputCsv) throws IOException {
    GenericUrl url = new GenericUrl(baseUrl + "/v2/badgeData");
    if ( namespace != null ) {
      url.set("namespace", namespace);
    }

    HttpRequest uploadRequest =
      requestFactory.buildPutRequest(url, ByteArrayContent.fromString("text/csv; charset=utf-8", outputCsv));
    uploadRequest.setHeaders(new HttpHeaders().setAuthorization("Bearer " + accessToken));
    uploadRequest.execute();
  }
}
