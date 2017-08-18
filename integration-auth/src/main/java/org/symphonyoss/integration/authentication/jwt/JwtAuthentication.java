package org.symphonyoss.integration.authentication.jwt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.symphonyoss.integration.Integration;
import org.symphonyoss.integration.api.client.json.JsonUtils;
import org.symphonyoss.integration.authentication.AuthenticationProxy;
import org.symphonyoss.integration.authentication.api.AppAuthenticationProxy;
import org.symphonyoss.integration.authentication.api.model.AppToken;
import org.symphonyoss.integration.authentication.api.model.JwtPayload;
import org.symphonyoss.integration.authentication.api.model.PodCertificate;
import org.symphonyoss.integration.exception.RemoteApiException;
import org.symphonyoss.integration.exception.authentication.ExpirationException;
import org.symphonyoss.integration.exception.authentication.MalformedParameterException;
import org.symphonyoss.integration.exception.authentication.UnauthorizedUserException;
import org.symphonyoss.integration.exception.bootstrap.UnexpectedBootstrapException;
import org.symphonyoss.integration.logging.LogMessageSource;
import org.symphonyoss.integration.model.yaml.IntegrationProperties;
import org.symphonyoss.integration.pod.api.client.IntegrationAuthApiClient;
import org.symphonyoss.integration.pod.api.client.IntegrationHttpApiClient;
import org.symphonyoss.integration.service.IntegrationBridge;
import org.symphonyoss.integration.utils.RsaKeyUtils;
import org.symphonyoss.integration.utils.TokenUtils;

import java.security.PublicKey;
import java.util.Calendar;
import java.util.Date;

import javax.annotation.PostConstruct;

/**
 * Service class responsible for handling JWT authentication stuff.
 *
 * Created by rsanchez on 28/07/17.
 */
@Component
public class JwtAuthentication {

  public static final String AUTHORIZATION_HEADER_PREFIX = "Bearer ";

  private static final int CACHE_EXPIRATION_IN_MINUTES = 60;

  private static final String JWT_DESERIALIZE = "integration.auth.jwt.deserialize";
  private static final String JWT_DESERIALIZE_SOLUTION = JWT_DESERIALIZE + ".solution";

  private static final String JWT_EXPIRED = "integration.auth.jwt.expired";
  private static final String JWT_EXPIRED_SOLUTION = JWT_EXPIRED + ".solution";

  private static final String JWT_INVALID_ALGORITHM = "integration.auth.jwt.invalid.algorithm";
  private static final String JWT_INVALID_ALGORITHM_SOLUTION = JWT_INVALID_ALGORITHM + ".solution";

  private static final String JWT_TOKEN_EMPTY = "integration.auth.jwt.empty";
  private static final String JWT_TOKEN_EMPTY_SOLUTION = JWT_TOKEN_EMPTY + ".solution";

  private static final String INTEGRATION_UNAVAILABLE = "integration.auth.integration.unavailable";
  private static final String INTEGRATION_UNAVAILABLE_SOLUTION =
      INTEGRATION_UNAVAILABLE + ".solution";

  @Autowired
  private LogMessageSource logMessage;

  @Autowired
  private TokenUtils tokenUtils;

  @Autowired
  private AppAuthenticationProxy appAuthenticationService;

  @Autowired
  private AuthenticationProxy authenticationProxy;

  @Autowired
  private IntegrationBridge integrationBridge;

  @Autowired
  private IntegrationProperties properties;

  @Autowired
  private IntegrationHttpApiClient integrationHttpApiClient;

  @Autowired
  private RsaKeyUtils rsaKeyUtils;

  private IntegrationAuthApiClient apiClient;

  private PublicKey podPublicSignatureVerifier;

  private Date podPublicSignatureVerifierExpirationDate;

  /**
   * Initialize HTTP client.
   */
  @PostConstruct
  public void init() {
    this.apiClient = new IntegrationAuthApiClient(integrationHttpApiClient, logMessage);
  }

  /**
   * Retrieve an integration by a configuration ID.
   * @param configurationId Configuration ID.
   * @return Integration found or a runtime exception when it is missing.
   */
  private Integration getIntegrationAndCheckAvailability(String configurationId) {
    Integration integration = integrationBridge.getIntegrationById(configurationId);
    if (integration == null) {
      throw new UnexpectedBootstrapException(
          logMessage.getMessage(INTEGRATION_UNAVAILABLE, configurationId),
          logMessage.getMessage(INTEGRATION_UNAVAILABLE_SOLUTION));
    }
    return integration;
  }

  /**
   * Return user identifier from HTTP Authorization header.
   * @param authorizationHeader HTTP Authorization header
   * @return User identifier or null if the authorization header is not present or it's not a valid
   * JWT token
   */
  public Long getUserIdFromAuthorizationHeader(String authorizationHeader) {
    String token = getJwtToken(authorizationHeader);
    return getUserId(token);
  }

  /**
   * Retrieves JWT token from HTTP Authorization header.
   * @param authorizationHeader HTTP Authorization header
   * @return JWT token or null if the authorization header is not present or it's not a valid JWT
   * token
   */
  public String getJwtToken(String authorizationHeader) {
    if (StringUtils.isEmpty(authorizationHeader) || (!authorizationHeader.startsWith(
        AUTHORIZATION_HEADER_PREFIX))) {
      return null;
    }

    // TODO APP-1206 Validate JWT token

    return authorizationHeader.replaceFirst(AUTHORIZATION_HEADER_PREFIX, StringUtils.EMPTY);
  }

  /**
   * Return user identifier based on JWT token
   * @param token JWT token
   * @return User identifier
   */
  public Long getUserId(String token) {
    if (StringUtils.isEmpty(token)) {
      String message = logMessage.getMessage(JWT_TOKEN_EMPTY);
      String solution = logMessage.getMessage(JWT_TOKEN_EMPTY_SOLUTION);
      throw new UnauthorizedUserException(message, solution);
    }

    // TODO APP-1206 Need to be implemented
    return new Long(0);
  }

  /**
   * Start the JWT authentication between the App and the SBE.
   * @param configurationId Application identifier.
   * @return The generated Application Token (Ta).
   */
  public String authenticate(String configurationId) {
    Integration integration = getIntegrationAndCheckAvailability(configurationId);
    String appId = properties.getApplicationId(integration.getSettings().getType());

    String appToken = tokenUtils.generateToken();
    AppToken bothTokens = appAuthenticationService.authenticate(appId, appToken);

    String sessionToken = authenticationProxy.getSessionToken(integration.getSettings().getType());
    apiClient.saveAppAuthenticationToken(sessionToken, configurationId, bothTokens);

    return appToken;
  }

  /**
   * Validate if the Symphony previously generated token by the app token and the SBE token are
   * valid.
   * @param configurationId Application identifier.
   * @param applicationToken App token generated by the "authenticate" service.
   * @return <code>true</code> if the token pair is valid.
   */
  public boolean isValidTokenPair(String configurationId, String applicationToken,
      String symphonyToken) {
    Integration integration = getIntegrationAndCheckAvailability(configurationId);
    String sessionToken = authenticationProxy.getSessionToken(integration.getSettings().getType());

    AppToken bothTokens = apiClient.getAppAuthenticationToken(sessionToken, configurationId,
        applicationToken);
    if (bothTokens == null) {
      return false;
    }
    return symphonyToken.equals(bothTokens.getSymphonyToken());
  }

  /**
   * Validate if the sent JWT is valid by checking its signer and decodification and then return it.
   * @param jwt Json Web Token containing the user/app authentication data.
   * @return JwtPayload parsed;
   */
  public JwtPayload parseJwtPayload(String jwt) {
    PublicKey rsaVerifier = getPodPublicSignatureVerifier();
    Jws<Claims> jws = null;
    try {
      jws = Jwts.parser().setSigningKey(rsaVerifier).parseClaimsJws(jwt);
    } catch (ExpiredJwtException e) {
      Date expiration = e.getClaims().getExpiration();
      if (expiration.before(new Date())) {
        throw new ExpirationException(logMessage.getMessage(JWT_EXPIRED, expiration.toString()), e,
            logMessage.getMessage(JWT_EXPIRED_SOLUTION));
      }
    }

    String actualAlgorithm = jws.getHeader().getAlgorithm();
    String expectedAlgorithm = SignatureAlgorithm.RS512.name();
    if (!actualAlgorithm.equals(expectedAlgorithm)) {
      throw new MalformedParameterException(
          logMessage.getMessage(JWT_INVALID_ALGORITHM, actualAlgorithm),
          logMessage.getMessage(JWT_EXPIRED_SOLUTION, expectedAlgorithm));
    }

    try {
      String json = new ObjectMapper().writeValueAsString(jws.getBody());
      JsonUtils jsonUtils = new JsonUtils();
      return jsonUtils.deserialize(json, JwtPayload.class);
    } catch (JsonProcessingException | RemoteApiException e) {
      throw new MalformedParameterException(logMessage.getMessage(JWT_DESERIALIZE), e,
          logMessage.getMessage(JWT_DESERIALIZE_SOLUTION));
    }
  }

  /**
   * Gets the cached public pod certificate or a new one if the previous has expired.
   * @return Pod public certificate.
   */
  private PublicKey getPodPublicSignatureVerifier() {
    Calendar now = Calendar.getInstance();
    if (podPublicSignatureVerifier == null || now.after(podPublicSignatureVerifierExpirationDate)) {
      PodCertificate podPublicCertificate = appAuthenticationService.getPodPublicCertificate();
      podPublicSignatureVerifier = rsaKeyUtils.getPublicKeyFromCertificate(podPublicCertificate.getCertificate());

      now.add(Calendar.MINUTE, CACHE_EXPIRATION_IN_MINUTES);
      podPublicSignatureVerifierExpirationDate = now.getTime();
    }
    return podPublicSignatureVerifier;
  }
}
