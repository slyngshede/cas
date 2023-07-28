package org.apereo.cas.oidc.web.controllers.introspection;

import org.apereo.cas.oidc.OidcConfigurationContext;
import org.apereo.cas.oidc.OidcConstants;
import org.apereo.cas.services.OidcRegisteredService;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.support.oauth.web.endpoints.OAuth20IntrospectionEndpointController;
import org.apereo.cas.support.oauth.web.response.introspection.success.OAuth20IntrospectionAccessTokenSuccessResponse;
import org.apereo.cas.ticket.OAuth20Token;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.LoggingUtils;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.util.serialization.JacksonObjectMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.pac4j.core.context.WebContext;
import org.pac4j.jee.context.JEEContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This is {@link OidcIntrospectionEndpointController}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
@Slf4j
public class OidcIntrospectionEndpointController extends OAuth20IntrospectionEndpointController<OidcConfigurationContext> {
    private static final ObjectMapper MAPPER = JacksonObjectMapperFactory.builder()
        .defaultTypingEnabled(false).build().toObjectMapper();

    public OidcIntrospectionEndpointController(final OidcConfigurationContext context) {
        super(context);
    }

    /**
     * Handle request.
     *
     * @param request  the request
     * @param response the response
     * @return the response entity
     */
    @GetMapping(consumes = {
        MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        MediaType.APPLICATION_JSON_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE,
        value = {
            '/' + OidcConstants.BASE_OIDC_URL + '/' + OidcConstants.INTROSPECTION_URL,
            "/**/" + OidcConstants.INTROSPECTION_URL
        })
    @Override
    public ResponseEntity handleRequest(
        final HttpServletRequest request,
        final HttpServletResponse response) {
        val webContext = new JEEContext(request, response);
        if (!getConfigurationContext().getIssuerService().validateIssuer(webContext, OidcConstants.INTROSPECTION_URL)) {
            val body = OAuth20Utils.toJson(OAuth20Utils.getErrorResponseBody(OAuth20Constants.INVALID_REQUEST, "Invalid issuer"));
            return new ResponseEntity(body, HttpStatus.BAD_REQUEST);
        }
        return super.handleRequest(request, response);
    }

    /**
     * Handle post request.
     *
     * @param request  the request
     * @param response the response
     * @return the response entity
     */
    @PostMapping(consumes = {
        MediaType.APPLICATION_JSON_VALUE,
        MediaType.APPLICATION_FORM_URLENCODED_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE,
        value = {
            '/' + OidcConstants.BASE_OIDC_URL + '/' + OidcConstants.INTROSPECTION_URL,
            "/**/" + OidcConstants.INTROSPECTION_URL
        })
    @Override
    public ResponseEntity handlePostRequest(final HttpServletRequest request, final HttpServletResponse response) {
        return super.handlePostRequest(request, response);
    }

    @Override
    protected OAuth20IntrospectionAccessTokenSuccessResponse createIntrospectionValidResponse(
        final String accessTokenId, final OAuth20Token ticket) {
        val response = super.createIntrospectionValidResponse(accessTokenId, ticket);
        if (ticket != null) {
            Optional.ofNullable(ticket.getService())
                .ifPresent(service -> {
                    val registeredService = getConfigurationContext().getServicesManager().findServiceBy(service, OidcRegisteredService.class);
                    response.setIss(getConfigurationContext().getIssuerService().determineIssuer(Optional.ofNullable(registeredService)));
                });
            FunctionUtils.doIf(response.isActive(), __ -> response.setScope(String.join(" ", ticket.getScopes()))).accept(response);
            CollectionUtils.firstElement(ticket.getAuthentication().getAttributes().get(OAuth20Constants.DPOP_CONFIRMATION))
                .ifPresent(dpop -> response.setDPopConfirmation(new OAuth20IntrospectionAccessTokenSuccessResponse.DPopConfirmation(dpop.toString())));
        }
        return response;
    }

    @Override
    protected ResponseEntity buildIntrospectionEntityResponse(final WebContext context,
                                                              final OAuth20IntrospectionAccessTokenSuccessResponse introspect) {
        val responseEntity = super.buildIntrospectionEntityResponse(context, introspect);
        return context.getRequestHeader("Accept")
            .filter(headerValue -> StringUtils.equalsAnyIgnoreCase(headerValue, OAuth20Constants.INTROSPECTION_JWT_HEADER))
            .map(headerValue -> {
                val registeredService = OAuth20Utils.getRegisteredOAuthServiceByClientId(
                    getConfigurationContext().getServicesManager(), introspect.getClientId());
                val signingAndEncryptionService = getConfigurationContext().getTokenIntrospectionSigningAndEncryptionService();
                return FunctionUtils.doAndHandle(() -> {
                    if (signingAndEncryptionService.shouldSignToken(registeredService)
                        || signingAndEncryptionService.shouldEncryptToken(registeredService)) {
                        return signAndEncryptIntrospection(context, introspect, registeredService);
                    }
                    return buildPlainIntrospectionClaims(context, introspect, registeredService);
                }, e -> ResponseEntity.badRequest().body("Unable to produce introspection JWT claims")).get();
            })
            .orElse(responseEntity);
    }

    protected ResponseEntity<String> buildPlainIntrospectionClaims(final WebContext context,
                                                                   final OAuth20IntrospectionAccessTokenSuccessResponse introspect,
                                                                   final OAuthRegisteredService registeredService) throws Exception {
        val claims = convertIntrospectionIntoClaims(introspect, registeredService);
        val jwt = new PlainJWT(JWTClaimsSet.parse(claims.getClaimsMap()));
        val jwtRequest = jwt.serialize();
        return buildResponseEntity(jwtRequest, registeredService);
    }

    private JwtClaims convertIntrospectionIntoClaims(final OAuth20IntrospectionAccessTokenSuccessResponse introspect,
                                                     final OAuthRegisteredService registeredService) throws Exception {
        val signingAndEncryptionService = getConfigurationContext().getTokenIntrospectionSigningAndEncryptionService();
        val claims = new JwtClaims();
        claims.setIssuer(signingAndEncryptionService.resolveIssuer(Optional.of(registeredService)));
        claims.setAudience(registeredService.getClientId());
        claims.setIssuedAt(NumericDate.now());
        claims.setJwtId(UUID.randomUUID().toString());
        claims.setClaim("token_introspection", MAPPER.readValue(MAPPER.writeValueAsString(introspect), Map.class));
        return claims;
    }

    protected ResponseEntity<String> signAndEncryptIntrospection(final WebContext context,
                                                                 final OAuth20IntrospectionAccessTokenSuccessResponse introspect,
                                                                 final OAuthRegisteredService registeredService) throws Exception {
        val claims = convertIntrospectionIntoClaims(introspect, registeredService);
        LOGGER.debug("Collected introspection claims, before cipher operations, are [{}]", claims);
        val signingAndEncryptionService = getConfigurationContext().getTokenIntrospectionSigningAndEncryptionService();
        val result = signingAndEncryptionService.encode(registeredService, claims);
        LOGGER.debug("Finalized introspection JWT is [{}]", result);
        return buildResponseEntity(result, registeredService);
    }

    private static ResponseEntity<String> buildResponseEntity(final String result,
                                                              final OAuthRegisteredService registeredService) {
        val context = CollectionUtils.<String, Object>wrap(
            "Content-Type", OAuth20Constants.INTROSPECTION_JWT_HEADER,
            "Client ID", registeredService.getClientId(),
            "Service", registeredService.getName());
        LoggingUtils.protocolMessage("OpenID Connect Introspection Response", context, result);
        val headers = new HttpHeaders();
        headers.put("Content-Type", CollectionUtils.wrapList(OAuth20Constants.INTROSPECTION_JWT_HEADER));
        return ResponseEntity.ok().headers(headers).body(result);
    }
}
