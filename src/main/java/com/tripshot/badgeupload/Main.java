package com.tripshot.badgeupload;

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
import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
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

  private static void usage() {
    throw new IllegalArgumentException("usage : --config <config file> --badges <badge file>");
  }

  public static void main(String args[]) throws IOException, InvalidKeyException, NoSuchAlgorithmException {

    String configFilename = null;
    String badgeFilename = null;

    int k = 0;
    while ( k + 1 < args.length ) {
      String opt = args[k];
      switch ( opt ) {
        case "--config":
          configFilename = args[k + 1];
          break;
        case "--badges":
          badgeFilename = args[k + 1];
          break;
        default:
          usage();
      }

      k += 2;
    }

    if ( configFilename == null || badgeFilename == null )
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

    List<String> hashedBadges = hashBadges(badgingKey, badgeFilename);
    String accessToken = getAccessToken(requestFactory, baseUrl, appId, secret);
    sendBadges(requestFactory, baseUrl, accessToken, hashedBadges);
  }

  private final static String algo = "HmacSHA256";

  private static List<String> hashBadges(String badgingKey, String badgeFilename) throws NoSuchAlgorithmException, IOException {
    SecretKeySpec signingKey = new SecretKeySpec(badgingKey.getBytes(Charset.forName("UTF-8")), algo);
    Mac mac = Mac.getInstance(algo);

    List<String> hashedBadges;
    try ( FileInputStream bis = new FileInputStream(badgeFilename) ) {
      LineNumberReader reader = new LineNumberReader(new InputStreamReader(bis));

      hashedBadges = reader.lines().map(line -> {
        try {
          mac.init(signingKey);
          byte[] raw = mac.doFinal(line.getBytes(Charset.forName("UTF-8")));
          byte[] hexBytes = new Hex().encode(raw);
          return new String(hexBytes, "UTF-8");
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

  private static void sendBadges(HttpRequestFactory requestFactory, String baseUrl, String accessToken, List<String> hashedBadges) throws IOException {
    HttpRequest uploadRequest =
      requestFactory.buildPutRequest(new GenericUrl(baseUrl + "/v1/badgeData"), new JsonHttpContent(JSON_FACTORY, new BadgeData(hashedBadges)));
    uploadRequest.setHeaders(new HttpHeaders().setAuthorization("Bearer " + accessToken));
    uploadRequest.execute();
  }
}
